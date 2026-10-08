"""Codex ``ThreadItem`` → Archie tool calls, shared by the live stream and history.

The same item reaches us in two spellings:

* **live** (app-server notifications ``item/started`` / ``item/completed``):
  ``type`` in camelCase (``commandExecution``) with camelCase fields
  (``aggregatedOutput``, ``exitCode``);
* **on disk** (rollout ``event_msg`` / ``item_completed``): ``type`` in
  PascalCase (``CommandExecution``) with snake_case fields
  (``aggregated_output``, ``exit_code``).

Everything here accepts both, so a reopened conversation renders exactly
like the live one.  Tool names follow Claude Code's so the clients' tool
cards apply: a shell command becomes ``Bash`` ``{"command"}``, a one-file
patch ``Edit`` ``{"file_path", "old_string", "new_string"}`` (or ``Write``
for a new file), Codex's plan becomes ``TodoWrite`` ``{"todos"}``.
"""

from __future__ import annotations

import json
import shlex
from typing import Any

# Cap for tool output we forward (web search results, MCP structured content).
_MAX_OUTPUT = 20_000


def item_kind(item: dict[str, Any]) -> str:
    """``commandExecution`` for both ``commandExecution`` and ``CommandExecution``."""
    t = item.get("type")
    if not isinstance(t, str) or not t:
        return ""
    return t[0].lower() + t[1:]


def _get(item: dict[str, Any], *keys: str, default: Any = None) -> Any:
    for k in keys:
        if k in item and item[k] is not None:
            return item[k]
    return default


def display_command(command: Any) -> str:
    """The command the model asked for, without Codex's shell wrapper.

    Codex runs ``/bin/bash -lc '<cmd>'``; show ``<cmd>``.  Accepts the live
    string form and the rollout's argv list.
    """
    if isinstance(command, list):
        argv = [str(a) for a in command]
    elif isinstance(command, str):
        try:
            argv = shlex.split(command)
        except ValueError:
            return command
    else:
        return ""
    if len(argv) == 3 and argv[0].rsplit("/", 1)[-1] in ("bash", "sh", "zsh") and argv[1] in ("-lc", "-c"):
        return argv[2]
    return command if isinstance(command, str) else shlex.join(argv)


def _change_kind(change: dict[str, Any]) -> str:
    kind = change.get("kind")
    if isinstance(kind, dict):
        kind = kind.get("type")
    return str(kind or "update").lower()


def _iter_changes(changes: Any) -> list[dict[str, Any]]:
    """Normalize ``changes`` (live: list of ``{path, kind, diff}``; older
    rollouts: ``{path: {...}}``) to a list of dicts with a ``path``."""
    out: list[dict[str, Any]] = []
    if isinstance(changes, list):
        for c in changes:
            if isinstance(c, dict) and c.get("path"):
                out.append(c)
    elif isinstance(changes, dict):
        for path, c in changes.items():
            if isinstance(c, dict):
                entry = dict(c)
                entry.setdefault("path", path)
                if "kind" not in entry and "type" in entry:
                    entry["kind"] = entry["type"]
                if "diff" not in entry:
                    entry["diff"] = entry.get("unified_diff") or entry.get("content") or ""
                out.append(entry)
    return out


def diff_sides(diff: str) -> tuple[str, str]:
    """Split a unified diff into (old text, new text) for an Edit card."""
    old: list[str] = []
    new: list[str] = []
    for line in diff.splitlines():
        if line.startswith(("--- ", "+++ ", "diff ", "index ", "\\ No newline")):
            continue
        if line.startswith("@@"):
            if old or new:
                old.append("…")
                new.append("…")
            continue
        if line.startswith("-"):
            old.append(line[1:])
        elif line.startswith("+"):
            new.append(line[1:])
        else:
            text = line[1:] if line.startswith(" ") else line
            old.append(text)
            new.append(text)
    return "\n".join(old), "\n".join(new)


def _added_content(diff: str) -> str:
    lines = diff.splitlines()
    body = [ln for ln in lines if not ln.startswith(("--- ", "+++ ", "@@", "diff ", "index "))]
    if body and all(ln.startswith("+") for ln in body):
        return "\n".join(ln[1:] for ln in body)
    return diff


def file_change_tool(changes: Any) -> tuple[str, dict[str, Any]]:
    entries = _iter_changes(changes)
    if len(entries) == 1:
        c = entries[0]
        path = str(c.get("path"))
        diff = str(c.get("diff") or "")
        kind = _change_kind(c)
        if kind == "add":
            return "Write", {"file_path": path, "content": _added_content(diff)}
        if kind == "delete":
            return "Delete", {"file_path": path}
        old, new = diff_sides(diff)
        tool_input: dict[str, Any] = {"file_path": path, "old_string": old, "new_string": new}
        move = c.get("move_path")
        if not move and isinstance(c.get("kind"), dict):
            move = c["kind"].get("move_path")
        if move:
            tool_input["move_path"] = move
        return "Edit", tool_input
    return "apply_patch", {
        "changes": [
            {"path": str(c.get("path")), "kind": _change_kind(c), "diff": str(c.get("diff") or "")}
            for c in entries
        ],
    }


def tool_call(item: dict[str, Any]) -> tuple[str, dict[str, Any]] | None:
    """``(tool_name, tool_input)`` for a tool-like item, ``None`` otherwise."""
    kind = item_kind(item)
    if kind == "commandExecution":
        return "Bash", {"command": display_command(item.get("command"))}
    if kind == "fileChange":
        return file_change_tool(item.get("changes"))
    if kind == "mcpToolCall":
        server = item.get("server") or "mcp"
        tool = item.get("tool") or "tool"
        args = item.get("arguments")
        if isinstance(args, str):
            try:
                args = json.loads(args)
            except json.JSONDecodeError:
                args = {"arguments": args}
        return f"mcp__{server}__{tool}", args if isinstance(args, dict) else {"arguments": args}
    if kind == "dynamicToolCall":
        args = item.get("arguments")
        return str(item.get("tool") or "tool"), args if isinstance(args, dict) else {"arguments": args}
    if kind == "webSearch":
        query = item.get("query") or ""
        action = item.get("action")
        if not query and isinstance(action, dict):
            query = action.get("query") or action.get("url") or action.get("pattern") or ""
        return "WebSearch", {"query": query}
    if kind == "collabAgentToolCall":
        return "Task", {
            "description": str(item.get("tool") or "agent"),
            "prompt": item.get("prompt") or "",
            **({"model": item["model"]} if item.get("model") else {}),
        }
    if kind == "imageView":
        return "Read", {"file_path": str(item.get("path") or "")}
    if kind == "imageGeneration":
        return "ImageGeneration", {"prompt": _get(item, "revisedPrompt", "revised_prompt", default="")}
    return None


def _truncate(text: str) -> str:
    return text if len(text) <= _MAX_OUTPUT else text[:_MAX_OUTPUT] + "\n… (truncated)"


def _content_text(content: Any) -> str:
    parts: list[str] = []
    if isinstance(content, list):
        for c in content:
            if isinstance(c, dict):
                if isinstance(c.get("text"), str):
                    parts.append(c["text"])
                elif c.get("type") == "image":
                    parts.append("[image]")
            elif isinstance(c, str):
                parts.append(c)
    elif isinstance(content, str):
        parts.append(content)
    return "\n".join(parts)


def tool_result(item: dict[str, Any]) -> tuple[str, bool]:
    """``(output, is_error)`` for a completed tool-like item."""
    kind = item_kind(item)
    status = str(item.get("status") or "").lower()
    failed = status in ("failed", "declined")
    if kind == "commandExecution":
        output = _get(item, "aggregatedOutput", "aggregated_output", "formatted_output")
        if output is None:
            output = (item.get("stdout") or "") + (item.get("stderr") or "")
        code = _get(item, "exitCode", "exit_code")
        is_error = failed or (isinstance(code, int) and code != 0)
        text = str(output or "")
        if not text and isinstance(code, int) and code != 0:
            text = f"Exit code {code}"
        if status == "declined" and not text:
            text = "Command declined"
        return text, is_error
    if kind == "fileChange":
        paths = [str(c.get("path")) for c in _iter_changes(item.get("changes"))]
        if failed:
            return f"Patch {status}: {', '.join(paths)}", True
        return "Applied patch: " + ", ".join(paths) if paths else "Applied patch", False
    if kind == "mcpToolCall":
        err = item.get("error")
        if isinstance(err, dict) and err.get("message"):
            return str(err["message"]), True
        result = item.get("result")
        if isinstance(result, dict):
            text = _content_text(result.get("content"))
            if not text and result.get("structuredContent") is not None:
                text = json.dumps(result["structuredContent"], ensure_ascii=False)
            return _truncate(text), failed
        return "", failed
    if kind == "dynamicToolCall":
        text = _content_text(_get(item, "contentItems", "content_items"))
        success = item.get("success")
        return _truncate(text), failed or success is False
    if kind == "webSearch":
        results = item.get("results")
        if results:
            return _truncate(json.dumps(results, ensure_ascii=False, indent=1)), False
        query = item.get("query") or ""
        return (f"Searched: {query}" if query else "Search done"), False
    if kind == "collabAgentToolCall":
        states = _get(item, "agentsStates", "agents_states")
        return (_truncate(json.dumps(states, ensure_ascii=False)) if states else status or "done"), failed
    if kind == "imageGeneration":
        saved = _get(item, "savedPath", "saved_path")
        return (str(saved) if saved else status or "done"), bool(_get(item, "failure"))
    return "", failed


def plan_todos(plan: Any) -> dict[str, Any]:
    """``turn/plan/updated`` steps → a ``TodoWrite`` input."""
    status_map = {"inprogress": "in_progress", "in_progress": "in_progress", "completed": "completed"}
    todos = []
    for step in plan or []:
        if not isinstance(step, dict):
            continue
        text = str(step.get("step") or "")
        status = status_map.get(str(step.get("status") or "").lower().replace("-", "_"), "pending")
        todos.append({"content": text, "status": status, "activeForm": text})
    return {"todos": todos}


def message_text(item: dict[str, Any]) -> str:
    """Text of a userMessage / agentMessage item in either spelling."""
    if isinstance(item.get("text"), str):
        return item["text"]
    return _content_text(item.get("content"))


def reasoning_text(item: dict[str, Any]) -> str:
    summary = _get(item, "summary", "summary_text", default=[])
    parts = [s for s in summary if isinstance(s, str) and s] if isinstance(summary, list) else []
    if not parts:
        raw = _get(item, "content", "raw_content", default=[])
        parts = [s for s in raw if isinstance(s, str) and s] if isinstance(raw, list) else []
    return "\n\n".join(parts)
