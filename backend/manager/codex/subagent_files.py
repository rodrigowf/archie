"""Codex multi-agent history on disk (spec 12 SA-8).

Each sub-agent is a thread of its own with its own rollout, next to the
parent's (checked with CLI 0.161)::

    sessions/YYYY/MM/DD/rollout-<ts>-<parent thread>.jsonl
        event_msg item_completed CollabAgentToolCall {id, tool: "spawn_agent",
            receiver_thread_ids: [child], receiver_agents: [{thread_id, agent_nickname}],
            prompt, model, agents_states}
        … CollabAgentToolCall {tool: "wait" | "close_agent" | …, agents_states:
            {child: "pending_init" | {"completed": msg} | {"errored": err} | "shutdown"}}
    sessions/YYYY/MM/DD/rollout-<ts>-<child thread>.jsonl
        session_meta {id, parent_thread_id, source: {subagent: {thread_spawn:
            {parent_thread_id, depth, agent_nickname, agent_role}}}, …}
        event_msg task_complete {last_agent_message, duration_ms} | turn_aborted
        event_msg token_count {info: {total_token_usage: {total_tokens}}}

The subagent key is the spawning item's id (the parent's ``Task`` card).  A
child's own spawns make nested subagents (``parent_tool_use_id`` = the
child's key).  Child rollouts are excluded from session discovery.
"""

from __future__ import annotations

from pathlib import Path
from typing import Any

from ..subagents import usage_dict
from . import items

_MAX_DEPTH = 6
_STATE_STATUS = {"completed": "done", "errored": "failed", "shut down": "stopped",
                 "interrupted": "stopped", "not found": "stopped"}


def _lines(path: Path):
    from .adapter import _iter_lines
    return _iter_lines(path)


def _find_child(parent_path: Path, thread_id: str) -> Path | None:
    """The child's rollout: first under the parent's sessions root, then
    every root the harness knows."""
    try:
        root = parent_path.parents[3]
        for p in root.glob(f"*/*/*/rollout-*-{thread_id}.jsonl"):
            if p.is_file():
                return p
    except (IndexError, OSError):
        pass
    from .adapter import _codex_jsonl_candidates
    found = _codex_jsonl_candidates(thread_id)
    return found[0] if found else None


def _spawns(path: Path) -> tuple[list[dict[str, Any]], dict[str, tuple[str, str | None]]]:
    """``(spawn items, latest reported state per child thread)`` of one rollout."""
    spawns: list[dict[str, Any]] = []
    states: dict[str, tuple[str, str | None]] = {}
    for obj in _lines(path):
        if obj.get("type") != "event_msg":
            continue
        payload = obj.get("payload") if isinstance(obj.get("payload"), dict) else {}
        if payload.get("type") != "item_completed":
            continue
        item = payload.get("item")
        if not isinstance(item, dict) or items.item_kind(item) != "collabAgentToolCall":
            continue
        if items.collab_tool(item) == "spawnAgent" and item.get("id"):
            spawns.append({"item": item, "ts": obj.get("timestamp")})
        states.update(items.agent_states(item))
    return spawns, states


def _child_stats(path: Path) -> dict[str, Any]:
    out: dict[str, Any] = {"role": None, "nickname": None, "started_at": None, "terminal": None,
                           "report": None, "ended_at": None, "tokens": None, "tools": 0, "duration": 0}
    for i, obj in enumerate(_lines(path)):
        payload = obj.get("payload") if isinstance(obj.get("payload"), dict) else {}
        if i == 0 and obj.get("type") == "session_meta":
            out["started_at"] = payload.get("timestamp") or obj.get("timestamp")
            source = payload.get("source")
            sub = (source.get("subagent") or source.get("subAgent")) if isinstance(source, dict) else None
            spawn = sub.get("thread_spawn") if isinstance(sub, dict) else None
            if isinstance(spawn, dict):
                out["role"] = spawn.get("agent_role")
                out["nickname"] = spawn.get("agent_nickname")
            out["nickname"] = out["nickname"] or payload.get("agent_nickname")
            continue
        if obj.get("type") != "event_msg":
            continue
        et = payload.get("type")
        if et == "task_complete":
            out["terminal"] = "done"
            out["report"] = payload.get("last_agent_message") or out["report"]
            out["ended_at"] = obj.get("timestamp")
            if isinstance(payload.get("duration_ms"), int):
                out["duration"] += payload["duration_ms"]
        elif et == "turn_aborted":
            out["terminal"] = "stopped"
            out["ended_at"] = obj.get("timestamp")
        elif et == "token_count":
            info = payload.get("info") if isinstance(payload.get("info"), dict) else {}
            total = info.get("total_token_usage") if isinstance(info.get("total_token_usage"), dict) else {}
            if total.get("total_tokens") is not None:
                out["tokens"] = total.get("total_tokens")
        elif et == "item_completed":
            item = payload.get("item")
            kind = items.item_kind(item) if isinstance(item, dict) else ""
            if kind and kind not in ("userMessage", "agentMessage", "reasoning", "plan", "contextCompaction"):
                out["tools"] += 1
    return out


def scan(parent_jsonl: Path) -> tuple[list[dict[str, Any]], dict[str, Path]]:
    """``(SubagentSummary rows, tool_use_id → child rollout)`` in spawn
    order, depth first (a nested agent right after the one that spawned it)."""
    rows: list[dict[str, Any]] = []
    files: dict[str, Path] = {}
    seen_threads: set[str] = set()

    def walk(path: Path, owner: str | None, depth: int) -> None:
        if depth > _MAX_DEPTH:
            return
        spawns, states = _spawns(path)
        for sp in spawns:
            item = sp["item"]
            tid = str(item["id"])
            spawn = items.spawn_input(item)
            receivers = items.receiver_ids(item)
            row = {
                "tool_use_id": tid, "parent_tool_use_id": owner,
                "agent_type": spawn["subagent_type"], "description": spawn["description"],
                "background": False, "status": None, "activity": None, "usage": None,
                "summary": None, "started_at": sp.get("ts"), "ended_at": None,
            }
            rows.append(row)
            if str(item.get("status") or "").lower() == "failed" or not receivers:
                row["status"], row["summary"], row["ended_at"] = "failed", "Could not start the agent", sp.get("ts")
                continue
            thread = receivers[0]
            if thread in seen_threads:
                continue
            seen_threads.add(thread)
            child = _find_child(path, thread)
            state = states.get(thread)
            if child is not None:
                files[tid] = child
                st = _child_stats(child)
                if st["role"]:
                    row["agent_type"] = str(st["role"])
                row["started_at"] = st["started_at"] or row["started_at"]
                row["status"] = st["terminal"]
                row["summary"] = st["report"]
                row["ended_at"] = st["ended_at"]
                row["usage"] = usage_dict(st["tokens"], st["tools"], st["duration"] or None)
            if state is not None:
                final = _STATE_STATUS.get(state[0])
                if final and (row["status"] is None or final == "failed"):
                    row["status"] = final
                if not row["summary"] and state[1]:
                    row["summary"] = state[1]
            if row["status"] is None:
                row["status"] = "done" if row["summary"] else "stopped"
            if child is not None:
                walk(child, tid, depth + 1)

    walk(parent_jsonl, None, 0)
    return rows, files
