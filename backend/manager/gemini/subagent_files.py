"""Gemini CLI subagents (``invoke_agent``): outcome parsing and history (spec 12 §4.8).

What the CLI gives us (checked with 0.63):

* **stream-json** has no inner events for a subagent: only the parent's
  ``tool_use`` ``invoke_agent {agent_name, prompt}`` and, at the end, its
  ``tool_result``.  That result carries ``output`` only when the tool's
  ``resultDisplay`` is a string — for a local subagent it is a
  ``SubagentProgress`` object, so ``output`` is absent.
* the parent chat file (``context/chats/session-*.jsonl``) records the call
  in a ``gemini`` record's ``toolCalls`` entry: ``status``, ``agentId``, the
  ``SubagentProgress`` as ``resultDisplay`` (``{isSubagentProgress,
  agentName, recentActivity: [{id, type: "tool_call"|"thought", content,
  args, status}], state: "completed"|"error"|"cancelled", result,
  terminateReason: "GOAL"|"TIMEOUT"|"MAX_TURNS"|"ERROR"|…}``) and the text
  the model saw (``Subagent 'x' finished.\\nTermination Reason: R\\nResult:\\n…``
  or ``Subagent 'x' failed. Error: …``) as the function response;
* the subagent's own chat: ``chats/<parent session id>/<agentId>.jsonl``
  (header ``kind: "subagent"``, same record format as a session), written
  while it runs.

So subagents are reported post-hoc: started at the ``tool_use``, finished
at the ``tool_result`` from the outcome read back from the parent file.
"""

from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any

from ..subagents import usage_dict

SPAWN_TOOL = "invoke_agent"

_FINISHED_RE = re.compile(
    r"^Subagent '([^']*)' finished\.\s*\nTermination Reason:\s*(\S+)\s*\nResult:\s*\n?(.*)$", re.S,
)
_FAILED_RE = re.compile(r"^Subagent '([^']*)' failed\.\s*(?:Error:\s*)?(.*)$", re.S)


def spawn_description(prompt: Any) -> str:
    line = next((ln.strip() for ln in str(prompt or "").splitlines() if ln.strip()), "")
    return line if len(line) <= 80 else line[:79].rstrip() + "…"


def readable_result(result: Any) -> str:
    """The subagent's answer as text.  ``complete_task`` results are JSON
    (default schema ``{"response": "…"}``): a single string field is shown
    as is, anything else pretty-printed."""
    if result is None:
        return ""
    if not isinstance(result, str):
        value: Any = result
    else:
        try:
            value = json.loads(result)
        except ValueError:
            return result.strip()
    if isinstance(value, str):
        return value.strip()
    if isinstance(value, dict) and len(value) == 1:
        only = next(iter(value.values()))
        if isinstance(only, str):
            return only.strip()
    return json.dumps(value, ensure_ascii=False, indent=2)


def _reason_status(reason: str | None) -> str:
    if reason in (None, "", "GOAL"):
        return "done"
    if reason == "ABORTED":
        return "stopped"
    return "failed"


def progress_outcome(progress: dict[str, Any]) -> tuple[str, str]:
    """``(status, summary)`` from a ``SubagentProgress``."""
    state = progress.get("state")
    if state == "cancelled":
        return "stopped", "Cancelled"
    if state == "error":
        errors = [
            a.get("content") for a in progress.get("recentActivity") or []
            if isinstance(a, dict) and a.get("status") == "error" and a.get("content")
        ]
        return "failed", str(errors[-1]) if errors else "Subagent failed"
    reason = progress.get("terminateReason")
    summary = readable_result(progress.get("result"))
    status = _reason_status(reason)
    if status != "done" and not summary:
        summary = f"Ended: {reason}"
    return status, summary


def text_outcome(text: str) -> tuple[str, str] | None:
    """``(status, summary)`` from the text the model saw, else None."""
    text = (text or "").strip()
    m = _FINISHED_RE.match(text)
    if m:
        status = _reason_status(m.group(2))
        summary = readable_result(m.group(3))
        return status, summary or (f"Ended: {m.group(2)}" if status != "done" else "")
    m = _FAILED_RE.match(text)
    if m:
        return "failed", m.group(2).strip() or "Subagent failed"
    return None


def as_progress(value: Any) -> dict[str, Any] | None:
    if isinstance(value, str):
        try:
            value = json.loads(value)
        except ValueError:
            return None
    return value if isinstance(value, dict) and value.get("isSubagentProgress") else None


def _response_text(call: dict[str, Any]) -> str:
    for entry in call.get("result") or []:
        resp = entry.get("functionResponse") if isinstance(entry, dict) else None
        inner = resp.get("response") if isinstance(resp, dict) else None
        if isinstance(inner, dict):
            for key in ("output", "error"):
                if isinstance(inner.get(key), str):
                    return inner[key]
    return ""


def call_outcome(call: dict[str, Any]) -> tuple[str, str] | None:
    """``(status, summary)`` of a finished ``toolCalls`` entry, None while it runs."""
    if call.get("status") is None and "result" not in call:
        return None
    progress = as_progress(call.get("resultDisplay"))
    if progress is not None and progress.get("state") in ("completed", "error", "cancelled"):
        return progress_outcome(progress)
    parsed = text_outcome(_response_text(call))
    if parsed is not None:
        return parsed
    if call.get("status") == "error":
        return "failed", _response_text(call) or "Subagent failed"
    if call.get("status") == "cancelled":
        return "stopped", "Cancelled"
    return "done", readable_result(_response_text(call))


# ---------------------------------------------------------------------------
# Files
# ---------------------------------------------------------------------------

def _conversation(path: Path):
    from .adapter import _load_conversation_file
    return _load_conversation_file(path)


def find_call(parent_jsonl: Path, tool_id: str) -> dict[str, Any] | None:
    """The latest ``toolCalls`` entry for *tool_id* in the parent chat file."""
    conv = _conversation(parent_jsonl)
    if conv is None:
        return None
    for rec in reversed(conv.records):
        for call in rec.get("toolCalls") or []:
            if isinstance(call, dict) and call.get("id") == tool_id:
                return call
    return None


def _spawn_calls(parent_jsonl: Path) -> tuple[str | None, list[tuple[dict[str, Any], str | None]]]:
    conv = _conversation(parent_jsonl)
    if conv is None:
        return None, []
    calls = []
    for rec in conv.records:
        if rec.get("type") != "gemini":
            continue
        for call in rec.get("toolCalls") or []:
            if isinstance(call, dict) and call.get("name") == SPAWN_TOOL and call.get("id"):
                calls.append((call, rec.get("timestamp")))
    return conv.session_id, calls


def _child_files(parent_jsonl: Path, session_id: str | None) -> list[Path]:
    if not session_id:
        return []
    d = parent_jsonl.parent / session_id
    try:
        return sorted(p for p in d.glob("*.jsonl") if p.is_file())
    except OSError:
        return []


def _first_prompt(path: Path) -> str | None:
    conv = _conversation(path)
    if conv is None:
        return None
    for rec in conv.records:
        if rec.get("type") == "user":
            parts = rec.get("content")
            if isinstance(parts, str):
                return parts.strip()
            if isinstance(parts, list):
                return "".join(p.get("text", "") for p in parts if isinstance(p, dict)).strip()
    return None


def _child_usage(path: Path) -> dict[str, Any]:
    conv = _conversation(path)
    tokens, tools = 0, 0
    for rec in conv.records if conv is not None else []:
        t = rec.get("tokens")
        if isinstance(t, dict) and isinstance(t.get("total"), int):
            tokens += t["total"]
        tools += sum(1 for c in rec.get("toolCalls") or [] if isinstance(c, dict) and c.get("name") != "complete_task")
    return usage_dict(tokens or None, tools, None)


def call_usage(parent_jsonl: Path, session_id: str, call: dict[str, Any]) -> dict[str, Any] | None:
    """Tokens / tool calls of a finished call's subagent, from its chat file."""
    agent_id = call.get("agentId")
    if not isinstance(agent_id, str) or not agent_id:
        return None
    child = parent_jsonl.parent / session_id / f"{agent_id}.jsonl"
    return _child_usage(child) if child.is_file() else None


def scan(parent_jsonl: Path) -> tuple[list[dict[str, Any]], dict[str, Path], dict[str, dict[str, Any]]]:
    """``(SubagentSummary rows, tool_id → child chat file, tool_id → call)``."""
    session_id, calls = _spawn_calls(parent_jsonl)
    children = _child_files(parent_jsonl, session_id)
    by_stem = {p.stem: p for p in children}
    rows: list[dict[str, Any]] = []
    files: dict[str, Path] = {}
    by_id: dict[str, dict[str, Any]] = {}
    claimed: set[Path] = set()
    for call, ts in calls:
        tid = str(call["id"])
        args = call.get("args") if isinstance(call.get("args"), dict) else {}
        by_id[tid] = call
        child = by_stem.get(str(call.get("agentId") or ""))
        if child is None:
            # Still running (agentId is recorded with the result): match its
            # chat by the prompt it was given.
            prompt = str(args.get("prompt") or "").strip()
            for p in children:
                if p not in claimed and prompt and _first_prompt(p) == prompt:
                    child = p
                    break
        if child is not None:
            claimed.add(child)
            files[tid] = child
        outcome = call_outcome(call)
        rows.append({
            "tool_use_id": tid, "parent_tool_use_id": None,
            "agent_type": str(args.get("agent_name") or ""),
            "description": spawn_description(args.get("prompt")),
            "background": False,
            # Unfinished on disk: live rows win while the turn runs; after it
            # the CLI process that ran it is gone.
            "status": outcome[0] if outcome else "stopped",
            "activity": None,
            "usage": _child_usage(child) if child is not None else None,
            "summary": (outcome[1] if outcome else None) or None,
            "started_at": ts,
            "ended_at": call.get("timestamp") if outcome else None,
        })
    return rows, files, by_id


def synthetic_transcript(call: dict[str, Any]) -> list[dict[str, Any]]:
    """Normalized messages for a subagent without a chat file: its prompt,
    the activity the CLI kept (``recentActivity``, the last few items) and
    its answer."""
    args = call.get("args") if isinstance(call.get("args"), dict) else {}
    ts = call.get("timestamp")
    out: list[dict[str, Any]] = []
    prompt = str(args.get("prompt") or "")
    if prompt:
        out.append({"type": "user", "timestamp": ts, "message": {"role": "user", "content": prompt}})
    progress = as_progress(call.get("resultDisplay")) or {}
    blocks: list[dict[str, Any]] = []
    results: list[dict[str, Any]] = []
    for act in progress.get("recentActivity") or []:
        if not isinstance(act, dict):
            continue
        if act.get("type") == "thought" and act.get("content"):
            blocks.append({"type": "thinking", "text": str(act["content"])})
        elif act.get("type") == "tool_call" and act.get("id"):
            try:
                tool_input = json.loads(act.get("args") or "{}")
            except (TypeError, ValueError):
                tool_input = {"args": act.get("args")}
            blocks.append({"type": "tool_use", "id": act["id"], "name": str(act.get("content") or "tool"),
                           "input": tool_input if isinstance(tool_input, dict) else {"args": tool_input}})
            st = act.get("status")
            if st in ("completed", "error", "cancelled"):
                results.append({"type": "tool_result", "tool_use_id": act["id"],
                                "content": str(act.get("description") or st), "is_error": st != "completed"})
    if blocks:
        out.append({"type": "assistant", "timestamp": ts, "message": {"role": "assistant", "content": blocks}})
    if results:
        out.append({"type": "user", "timestamp": ts, "message": {"role": "user", "content": results}})
    outcome = call_outcome(call)
    if outcome and outcome[1]:
        out.append({"type": "assistant", "timestamp": ts,
                    "message": {"role": "assistant", "content": [{"type": "text", "text": outcome[1]}]}})
    return out
