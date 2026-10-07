"""Agent eval: a model plays Archie's voice orchestrator and tries to find the conversation the
user asks about, using the real system prompt and the real tool definitions.

- The history tools run for real against the local index (search through an in-process model
  instead of the warm server); `read_file`/`search_memory`/`list_history` are real too.
- `open_agent_session` is recorded (the agent "jumps into" a session) but not executed;
  every other tool with side effects returns a stub error.
- Models: `gpt-realtime-*` over the Realtime WebSocket in text mode (the actual voice model),
  or any chat-completions model (e.g. gpt-4o-mini as a cheap stand-in, gpt-4o as the text
  orchestrator).

Scores per question:
  surfaced    best rank of the right session (or a copy) in any search/listing result it got
  opened      it read / grepped / reopened the right session
  wrong_open  it read or reopened only other sessions
  claims      a small judge (gpt-4o-mini) on the final reply: did it say it found the conversation?
  located     surfaced ≤ 3 and claims            (positives' primary metric)
  rejected    not claims                          (negatives' primary metric)

Usage:
  context/scripts/run.sh shared/scripts/history_eval/agent.py --model gpt-realtime-1.5 --split dev --name baseline [--limit 40]
"""
from __future__ import annotations

import argparse
import asyncio
import json
import os
import random
import sys
import time
from pathlib import Path

from common import PROJECT_DIR, RUNS_DIR, accepted, load_questions, load_sessions

sys.path.insert(0, str(PROJECT_DIR / "shared" / "scripts"))

from utils import history_index as hi  # noqa: E402

READ_ONLY = {
    "search_history", "read_conversation", "search_memory", "read_file", "list_history",
    "list_conversations", "grep_conversation", "list_agent_sessions",
}
OPENING = {"read_conversation", "grep_conversation"}
MAX_RESPONSES = 8


def _load_env() -> None:
    env = PROJECT_DIR / "context" / ".env"
    for line in env.read_text().splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            k, _, v = line.partition("=")
            os.environ.setdefault(k.strip(), v.strip().strip('"').strip("'"))


# ── tool execution ───────────────────────────────────────────────────────────

class Env:
    """Runs tool calls for one question and records what the agent did."""

    _model = None

    def __init__(self, db: Path | None, memory_db: Path | None = None):
        import orchestrator.tools.search as search_tools

        self.search_tools = search_tools
        if db:  # every tool opens the index by path
            hi.get_history_db_path = lambda: Path(db)
        if memory_db:
            from utils import memory_index
            memory_index.get_memory_db_path = lambda: Path(memory_db)
        self.calls: list[dict] = []

    _service = None

    async def server_request(self, request: dict, label: str) -> dict | None:
        """Stand-in for the warm search-server: the same SearchService it runs."""
        from utils import search_service

        if Env._service is None:
            Env._service = search_service.SearchService(search_service.sentence_transformer_loader())
        if request.get("command") == "history_search":
            return await asyncio.to_thread(Env._service.history_search, request)
        if request.get("command") == "memory_search":
            return await asyncio.to_thread(Env._service.memory_search, request)
        return None

    async def execute(self, name: str, args: dict) -> str:
        from orchestrator.tools import registry

        if name in ("open_agent_session", "resume_conversation"):
            target = args.get("resume_sdk_id") or args.get("session_id")
            if name == "resume_conversation" and not target:
                out = json.dumps({"error": "session_id is required"})
            else:
                out = json.dumps({"session_id": "eval-session", "resumed": target or None})
        elif name in READ_ONLY:
            context: dict = {}
            if name == "list_history":
                from manager.store import SessionStore
                context["store"] = SessionStore(PROJECT_DIR)
            orig = self.search_tools._server_request
            self.search_tools._server_request = self.server_request
            try:
                out = await registry.execute(name, args, context)
            finally:
                self.search_tools._server_request = orig
        else:
            out = json.dumps({"error": f"{name} is not available in this evaluation"})
        self.calls.append({"name": name, "args": args, "output": out})
        return out


# ── model loops ──────────────────────────────────────────────────────────────

async def run_realtime(model: str, instructions: str, tools: list, utterance: str, env: Env) -> tuple[str, dict]:
    import websockets

    url = f"wss://api.openai.com/v1/realtime?model={model}"
    headers = {"Authorization": f"Bearer {os.environ['OPENAI_API_KEY']}"}
    usage = {"input_tokens": 0, "output_tokens": 0, "responses": 0}
    async with websockets.connect(url, additional_headers=headers, max_size=None) as ws:
        await ws.send(json.dumps({"type": "session.update", "session": {
            "type": "realtime", "model": model, "instructions": instructions,
            "tools": tools, "tool_choice": "auto", "output_modalities": ["text"],
        }}))
        await ws.send(json.dumps({"type": "conversation.item.create", "item": {
            "type": "message", "role": "user", "content": [{"type": "input_text", "text": utterance}],
        }}))
        await ws.send(json.dumps({"type": "response.create"}))
        final = ""
        while usage["responses"] < MAX_RESPONSES:
            ev = json.loads(await asyncio.wait_for(ws.recv(), timeout=120))
            if ev["type"] == "error":
                raise RuntimeError(ev["error"].get("message"))
            if ev["type"] != "response.done":
                continue
            usage["responses"] += 1
            resp = ev["response"]
            u = resp.get("usage") or {}
            usage["input_tokens"] += u.get("input_tokens", 0)
            usage["output_tokens"] += u.get("output_tokens", 0)
            calls = [o for o in resp.get("output", []) if o.get("type") == "function_call"]
            texts = [c.get("text", "") for o in resp.get("output", []) if o.get("type") == "message"
                     for c in o.get("content", []) if c.get("type") in ("output_text", "text")]
            if texts:
                final = "\n".join(t for t in texts if t)
            if not calls:
                break
            for c in calls:
                try:
                    args = json.loads(c.get("arguments") or "{}")
                except json.JSONDecodeError:
                    args = {}
                out = await env.execute(c["name"], args)
                await ws.send(json.dumps({"type": "conversation.item.create", "item": {
                    "type": "function_call_output", "call_id": c["call_id"], "output": out,
                }}))
            await ws.send(json.dumps({"type": "response.create"}))
    return final, usage


async def run_chat(model: str, instructions: str, tools: list, utterance: str, env: Env) -> tuple[str, dict]:
    import openai

    client = openai.AsyncOpenAI()
    chat_tools = [{"type": "function", "function": {k: t[k] for k in ("name", "description", "parameters")}} for t in tools]
    messages = [{"role": "system", "content": instructions}, {"role": "user", "content": utterance}]
    usage = {"input_tokens": 0, "output_tokens": 0, "responses": 0}
    final = ""
    while usage["responses"] < MAX_RESPONSES:
        r = await client.chat.completions.create(model=model, messages=messages, tools=chat_tools)
        usage["responses"] += 1
        usage["input_tokens"] += r.usage.prompt_tokens
        usage["output_tokens"] += r.usage.completion_tokens
        msg = r.choices[0].message
        messages.append(msg.model_dump(exclude_none=True))
        if msg.content:
            final = msg.content
        if not msg.tool_calls:
            break
        for tc in msg.tool_calls:
            try:
                args = json.loads(tc.function.arguments or "{}")
            except json.JSONDecodeError:
                args = {}
            out = await env.execute(tc.function.name, args)
            messages.append({"role": "tool", "tool_call_id": tc.id, "content": out})
    return final, usage


async def judge_claims_found(reply: str) -> bool:
    import openai

    client = openai.AsyncOpenAI()
    r = await client.chat.completions.create(model="gpt-4o-mini", temperature=0, messages=[{
        "role": "user",
        "content": "An assistant was asked to find a past conversation. Its reply:\n\n"
                   f"<<<{reply}>>>\n\nDoes the reply claim it found a matching past conversation "
                   "(even if it adds caveats)? Answer only yes or no.",
    }])
    return (r.choices[0].message.content or "").strip().lower().startswith("y")


# ── scoring ──────────────────────────────────────────────────────────────────

def score(q: dict, calls: list[dict], claims: bool, copies: dict) -> dict:
    ok = accepted(q, copies)
    surfaced = None
    opened, opened_other = False, False
    for c in calls:
        try:
            out = json.loads(c["output"])
        except (json.JSONDecodeError, TypeError):
            out = {}
        for i, s in enumerate(out.get("sessions", []) if isinstance(out, dict) else []):
            if s.get("session_id") in ok or ok & set(s.get("copies", [])):
                surfaced = i + 1 if surfaced is None else min(surfaced, i + 1)
        target = c["args"].get("session_id") or c["args"].get("resume_sdk_id")
        if c["name"] in OPENING | {"open_agent_session", "resume_conversation"} and target:
            if target in ok:
                opened = True
            else:
                opened_other = True
    return {
        "surfaced": surfaced, "opened": opened, "wrong_open": opened_other and not opened,
        "claims": claims, "located": bool(surfaced and surfaced <= 3 and claims),
        "rejected": not claims,
    }


async def main_async(args) -> None:
    _load_env()
    from orchestrator.config import OrchestratorConfig
    from orchestrator.prompt import build_system_prompt
    from orchestrator.tools import registry
    import orchestrator.tools.agent_sessions  # noqa: F401
    import orchestrator.tools.assistant_config  # noqa: F401
    import orchestrator.tools.files  # noqa: F401
    import orchestrator.tools.run_script  # noqa: F401
    import orchestrator.tools.search  # noqa: F401
    import orchestrator.tools.voice_control  # noqa: F401

    instructions = build_system_prompt(OrchestratorConfig(), {}, voice_provider_id="openai")
    tools = registry.get_openai_definitions()
    sessions = load_sessions()
    qs = load_questions(None if args.split == "all" else args.split)
    if args.only:
        wanted = set(args.only.split(","))
        qs = [q for q in qs if q["id"] in wanted]
    elif args.limit and len(qs) > args.limit:
        qs = random.Random(args.seed).sample(qs, args.limit)
        qs.sort(key=lambda q: q["id"])
    runner = run_realtime if args.model.startswith("gpt-realtime") else run_chat
    sem = asyncio.Semaphore(args.concurrency)

    async def one(q):
        async with sem:
            env = Env(Path(args.db) if args.db else None, Path(args.memory_db) if args.memory_db else None)
            t0 = time.perf_counter()
            try:
                final, usage = await runner(args.model, instructions, tools, q["utterance"], env)
                err = None
            except Exception as e:  # noqa: BLE001
                final, usage, err = "", {}, f"{type(e).__name__}: {e}"
            claims = await judge_claims_found(final) if final else False
            row = {"id": q["id"], "type": q["type"], "lang": q["lang"], "utterance": q["utterance"],
                   "final": final, "error": err, "usage": usage, "seconds": round(time.perf_counter() - t0, 1),
                   "calls": [{"name": c["name"], "args": c["args"]} for c in env.calls],
                   **score(q, env.calls, claims, sessions["copies"])}
            print(f"  {q['id']} {q['type']:<9} located={row['located']!s:<5} rejected={row['rejected']!s:<5} "
                  f"calls={len(env.calls)} {row['seconds']}s{' ERR ' + err if err else ''}", flush=True)
            return row

    rows = await asyncio.gather(*(one(q) for q in qs))
    name = args.name
    if args.merge_into:
        prev = json.loads((RUNS_DIR / f"agent_{args.split}_{args.model}_{args.merge_into}.json").read_text())
        fresh = {r["id"]: r for r in rows}
        known = {r["id"] for r in prev["rows"]}
        rows = [fresh.get(r["id"], r) for r in prev["rows"]] + [r for r in rows if r["id"] not in known]
        name = args.merge_into
    summary = summarize(rows, args.model)
    RUNS_DIR.mkdir(parents=True, exist_ok=True)
    out = RUNS_DIR / f"agent_{args.split}_{args.model}_{name}.json"
    out.write_text(json.dumps({"summary": summary, "rows": rows}, indent=1, ensure_ascii=False))
    print(json.dumps(summary, indent=1))
    print(f"saved {out}")


def summarize(rows: list[dict], model: str) -> dict:
    pos = [r for r in rows if r["type"] != "negative"]
    neg = [r for r in rows if r["type"] == "negative"]

    def rate(rs, k):
        return round(sum(1 for r in rs if r[k]) / len(rs), 3) if rs else None

    summary = {
        "model": model, "n_pos": len(pos), "n_neg": len(neg),
        "located": rate(pos, "located"), "surfaced@3": rate([{"x": r["surfaced"] and r["surfaced"] <= 3} for r in pos], "x"),
        "opened": rate(pos, "opened"), "wrong_open": rate(pos, "wrong_open"), "neg_rejected": rate(neg, "rejected"),
        "avg_calls": round(sum(len(r["calls"]) for r in rows) / len(rows), 2) if rows else None,
        "avg_seconds": round(sum(r["seconds"] for r in rows) / len(rows), 1) if rows else None,
        "errors": sum(1 for r in rows if r["error"]),
        "input_tokens": sum(r["usage"].get("input_tokens", 0) for r in rows),
    }
    for t in sorted({r["type"] for r in pos}):
        summary[f"located[{t}]"] = rate([r for r in pos if r["type"] == t], "located")
    for lang in sorted({r["lang"] for r in pos}):
        summary[f"located[{lang}]"] = rate([r for r in pos if r["lang"] == lang], "located")
    return summary


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="gpt-realtime-1.5")
    ap.add_argument("--split", default="dev", choices=["dev", "test", "all"])
    ap.add_argument("--name", required=True)
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--concurrency", type=int, default=4)
    ap.add_argument("--db", default=None)
    ap.add_argument("--memory-db", default=None)
    ap.add_argument("--only", default="", help="comma-separated question ids (re-run a subset)")
    ap.add_argument("--merge-into", default="", help="replace these rows in an existing run name, recompute its summary")
    asyncio.run(main_async(ap.parse_args()))


if __name__ == "__main__":
    main()
