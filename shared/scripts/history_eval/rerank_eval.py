"""Offline experiment: does an LLM re-rank of the top candidates improve history search?

For each dev question: search (as production would), give an LLM the request and the top K
sessions (title, dates, summary, best excerpts), ask for the ids that match in order, or none.
Scores hit@1/@5/MRR of the re-ranked list and, for negatives, how often the LLM says none.

Usage: context/scripts/run.sh shared/scripts/history_eval/rerank_eval.py --db index/history_pml12_sum.sqlite3 [--model gpt-4.1-mini] [--k 8]
"""
from __future__ import annotations

import argparse
import asyncio
import json
import time

from agent import _load_env
from common import RUNS_DIR, accepted, load_questions, load_sessions
from retrieval import TODAY, encoder
from utils import history_index as hi
from utils.timewords import find_time_expression

PROMPT = """Rodrigo asked his assistant to find a past conversation. Candidates found by search are below.
Decide which candidates are the conversation he means, best first. Only include candidates that
really match what he describes (topic, details, time); if none does, return an empty list.
Request: {request}

Candidates:
{candidates}

Answer JSON only: {{"matches": ["<id>", ...]}}"""


def describe(i: int, s: dict) -> str:
    lines = [f"[{i}] id={s['session_id'][:8]} | {s.get('title') or s.get('first_message') or '(untitled)'} | "
             f"{(s.get('started_at') or '')[:10]} | {s.get('kind')}"]
    if s.get("summary"):
        lines.append(f"    summary: {s['summary'][:500]}")
    for h in s.get("hits", [])[:2]:
        lines.append(f"    excerpt ({h['role']}): {h['text'][:300]}")
    return "\n".join(lines)


async def rerank(client, model: str, request: str, sessions: list[dict]) -> tuple[list[str], float]:
    if not sessions:
        return [], 0.0
    cands = "\n".join(describe(i, s) for i, s in enumerate(sessions))
    t0 = time.perf_counter()
    r = await client.chat.completions.create(
        model=model, temperature=0, response_format={"type": "json_object"},
        messages=[{"role": "user", "content": PROMPT.format(request=request, candidates=cands)}],
    )
    dt = time.perf_counter() - t0
    short = {s["session_id"][:8]: s["session_id"] for s in sessions}
    try:
        ids = json.loads(r.choices[0].message.content or "{}").get("matches", [])
    except json.JSONDecodeError:
        ids = []
    return [short[x[:8]] for x in ids if isinstance(x, str) and x[:8] in short], dt


async def main_async(args) -> None:
    _load_env()
    import openai

    client = openai.AsyncOpenAI()
    sessions_meta = load_sessions()
    conn = hi.connect(args.db, readonly=True)
    enc = encoder(hi.index_model(conn))
    conn.close()
    searcher = hi.HistorySearcher(args.db)
    qs = load_questions(args.split)
    sem = asyncio.Semaphore(6)

    async def one(q):
        query = q["utterance"]
        kw = {}
        w, cleaned = find_time_expression(query, TODAY)
        if w:
            query, kw = (cleaned or query), {"window": w.iso(), "window_mode": "prefer"}
        res = searcher.search(query, enc(query), max_sessions=args.k, **kw)
        before = [s["session_id"] for s in res["sessions"]]
        async with sem:
            after, dt = await rerank(client, args.model, q["utterance"], res["sessions"])
        ok = accepted(q, sessions_meta["copies"])

        def rank(ids):
            for i, sid in enumerate(ids):
                if sid in ok:
                    return i + 1
            return None
        return {"id": q["id"], "type": q["type"], "lang": q["lang"], "before": rank(before), "after": rank(after),
                "n_after": len(after), "seconds": round(dt, 2)}

    rows = await asyncio.gather(*(one(q) for q in qs))
    pos = [r for r in rows if r["type"] != "negative"]
    neg = [r for r in rows if r["type"] == "negative"]

    def m(key):
        return {
            "hit1": round(sum(r[key] == 1 for r in pos) / len(pos), 3),
            "hit3": round(sum(bool(r[key] and r[key] <= 3) for r in pos) / len(pos), 3),
            "hit5": round(sum(bool(r[key] and r[key] <= 5) for r in pos) / len(pos), 3),
            "mrr": round(sum(1 / r[key] for r in pos if r[key]) / len(pos), 3),
        }
    summary = {"model": args.model, "k": args.k, "before": m("before"), "after": m("after"),
               "neg_says_none": round(sum(r["n_after"] == 0 for r in neg) / len(neg), 3) if neg else None,
               "pos_says_none": round(sum(r["n_after"] == 0 for r in pos) / len(pos), 3),
               "latency_p50": sorted(r["seconds"] for r in rows)[len(rows) // 2]}
    for lang in ("en", "pt"):
        sub = [r for r in pos if r["lang"] == lang]
        summary[f"after_hit1_{lang}"] = round(sum(r["after"] == 1 for r in sub) / len(sub), 3)
    (RUNS_DIR / f"rerank_{args.split}_{args.model}_{args.name}.json").write_text(json.dumps({"summary": summary, "rows": rows}, indent=1))
    print(json.dumps(summary, indent=1))


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", required=True)
    ap.add_argument("--model", default="gpt-4.1-mini")
    ap.add_argument("--k", type=int, default=8)
    ap.add_argument("--split", default="dev")
    ap.add_argument("--name", default="x")
    asyncio.run(main_async(ap.parse_args()))


if __name__ == "__main__":
    main()
