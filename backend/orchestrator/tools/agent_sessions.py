"""Agent session tools — control Claude Code instances from the orchestrator."""

from __future__ import annotations

import asyncio
import json
import logging
from typing import Any

from orchestrator.providers.voice_base import VOICE_TOOL_OUTPUT_MAX_CHARS
from orchestrator.tools import registry
from utils.mcp_config import get_mcp_configs, load_available_mcps

logger = logging.getLogger(__name__)


@registry.register(
    name="list_agent_sessions",
    description=(
        "List all currently active Claude Code agent sessions with their status. "
        "Each session has a session_id (use with send_to_agent_session/close_agent_session) "
        "and a sdk_session_id (use with open_agent_session to resume after closing)."
    ),
    input_schema={
        "type": "object",
        "properties": {},
    },
)
async def list_agent_sessions(context: dict[str, Any]) -> str:
    pool = context["pool"]
    store = context["store"]
    sessions = pool.list_sessions()

    # Enrich with history data from the store (message count, title).
    # The store uses SDK session IDs (JSONL filenames).
    for s in sessions:
        sdk_id = s.get("sdk_session_id")
        if sdk_id:
            info = store.get_session_info(sdk_id)
            if info:
                s["message_count"] = info.message_count
                s["title"] = info.title

    return json.dumps({"sessions": sessions, "count": len(sessions)})


def _open_agent_session_schema(static: dict[str, Any]) -> dict[str, Any]:
    """Inject the live MCP list as an ``enum`` on the ``mcp_servers`` items.

    Both Anthropic and Gemini (via ``functionDeclarations``) honour
    ``enum``: passing an unknown value is rejected before the call lands,
    which prevents the hallucinated-MCP-name failure mode where the model
    confidently invokes ``open_agent_session({"mcp_servers": ["google-banana"]})``
    against a project that has no such server. We also rewrite the human
    description to list what's actually available rather than hardcoding
    examples from other deployments.
    """
    import copy

    available = sorted(load_available_mcps().keys())
    schema = copy.deepcopy(static)
    mcp_prop = schema["properties"]["mcp_servers"]
    if available:
        mcp_prop["items"] = {"type": "string", "enum": available}
        mcp_prop["description"] = (
            "Names of MCP servers to enable for this session. "
            f"Valid names: {available}. "
            "Omit or pass [] to start with default Claude Code tools only."
        )
    else:
        # No MCPs configured — keep the field but make the constraint
        # explicit so the model doesn't invent values.
        mcp_prop["items"] = {"type": "string", "enum": []}
        mcp_prop["description"] = (
            "No MCP servers are configured for this project. "
            "Omit this field or pass []."
        )
    return schema


@registry.register(
    name="open_agent_session",
    description=(
        "Start a new Claude Code agent session OR re-open a past one from history "
        "(to continue a conversation found with search_history, prefer resume_conversation). "
        "This is the single entry point for both: omit all parameters to start fresh, "
        "or pass resume_sdk_id with any sdk_session_id from list_history / "
        "list_agent_sessions to resume that exact conversation (full context restored). "
        "You can specify which MCP servers to load for extended capabilities — "
        "valid names are listed in the system prompt's 'Available MCPs' section. "
        "Returns the session_id; you MUST use that exact session_id in the very "
        "next send_to_agent_session call rather than reusing an older one."
    ),
    input_schema={
        "type": "object",
        "properties": {
            "resume_sdk_id": {
                "type": "string",
                "description": (
                    "The sdk_session_id of a past session to resume from history. "
                    "This is the Claude SDK session ID, NOT the session_id returned by "
                    "open_agent_session. Get it from list_agent_sessions or list_history."
                ),
            },
            "mcp_servers": {
                "type": "array",
                "items": {"type": "string"},
                "description": (
                    "List of MCP server names to enable for this session. "
                    "See the 'Available MCPs' section of the system prompt for valid names. "
                    "Omit or pass [] for default Claude Code tools only."
                ),
            },
        },
    },
    schema_builder=_open_agent_session_schema,
)
async def open_agent_session(
    context: dict[str, Any],
    resume_sdk_id: str = "",
    mcp_servers: list[str] | None = None,
) -> str:
    """Spawn an agent session honouring the live global config.

    Working directory (local or SSH), session-harness, harness model, and
    chrome flag all come from ``assistant_config.json`` via
    :func:`api.session_factory.build_session_config` — the same path the
    UI's "+" button uses.  ``mcp_servers`` is the only per-call override:
    when ``None``, the factory falls back to the global ``enabled_mcps``;
    when a list (possibly empty), it replaces the inherited value
    verbatim, matching the per-session config the UI exposes via the gear
    panel.
    """
    from api.session_factory import build_session_config

    pool = context["pool"]
    store = context["store"]
    sdk_id = resume_sdk_id if resume_sdk_id else None

    # Validate that the session actually exists before trying to resume.
    if sdk_id:
        session_info = store.get_session_info(sdk_id)
        if session_info is None:
            return json.dumps({
                "error": f"Session {sdk_id!r} not found in history. "
                "Use list_history to see available sessions."
            })
        if session_info.is_orchestrator:
            return json.dumps({
                "error": f"Session {sdk_id!r} is an orchestrator session and cannot "
                "be resumed as an agent session. Only agent sessions (type='agent') "
                "from list_history can be resumed."
            })

    # Eagerly validate any explicit MCP names against the live catalogue
    # so the model gets a precise error before pool.create is invoked.
    # The factory drops unknown names silently; here we *want* the loud
    # signal because the user-visible failure mode is "the model invented
    # a name and the tool reported success on an empty MCP set".
    if mcp_servers:
        resolved = get_mcp_configs(mcp_servers)
        missing = [n for n in mcp_servers if n not in resolved]
        if missing:
            available = sorted(load_available_mcps().keys())
            return json.dumps({
                "error": (
                    f"Unknown MCP servers: {missing}. "
                    f"Available: {available or '(none configured)'}."
                )
            })

    try:
        config, resolved_mcps, info = build_session_config(
            resume_sdk_id=sdk_id,
            mcp_override=mcp_servers,
        )
    except Exception as e:
        return json.dumps({"error": f"Failed to build session config: {e}"})

    try:
        local_id = await pool.create(
            config, resume_sdk_id=sdk_id, mcp_servers=resolved_mcps,
        )
    except Exception as e:
        return json.dumps({"error": f"Failed to start session: {e}"})

    # Mirror chat.py's persist-on-detect behaviour so a legacy resumed
    # session gets its provider pinned for future deterministic resumes.
    if sdk_id and info.get("persist_provider"):
        from api.routes.session_config import save_session_config
        try:
            save_session_config(sdk_id, {"provider": info["persist_provider"]})
        except Exception as e:
            logger.warning(
                "Failed to persist resolved provider for session %s: %s",
                sdk_id, e,
            )

    # Surface the resolved config back to the model so it can see what
    # global settings landed (working dir, provider, MCPs, ssh target).
    # Without this the model has no way to verify that its expectations
    # match reality without calling get_assistant_config separately.
    return json.dumps({
        "session_id": local_id,
        "status": "started",
        "resolved_config": {
            "working_directory": info["working_directory"],
            "project_dir": info["project_dir"],
            "ssh_host": info["ssh_host"],
            "provider": info["provider"],
            "model": info["model"],
            "chrome_extension": info["chrome_extension"],
            "mcp_servers": info["mcp_servers"],
        },
    })


@registry.register(
    name="resume_conversation",
    description=(
        "Reopen a PAST agent conversation (Claude Code, Qwen, Gemini) with its full context so work "
        "can continue in it — pass the session_id from a search_history / list_conversations result. "
        "Only when the user wants to continue or open it, not merely to find or summarise it. "
        "For a past orchestrator conversation (yours) use switch_conversation instead. Returns the "
        "live session_id to use with send_to_agent_session."
    ),
    input_schema={
        "type": "object",
        "properties": {
            "session_id": {
                "type": "string",
                "description": "session_id of the past conversation (from search_history / list_conversations).",
            },
        },
        "required": ["session_id"],
    },
)
async def resume_conversation(context: dict[str, Any], session_id: str = "") -> str:
    """Single-purpose resume: the voice model dropped the optional resume_sdk_id of
    open_agent_session and opened blank sessions instead (agent eval, 2026-10-06)."""
    sid = (session_id or "").strip()
    if not sid:
        return json.dumps({"error": "session_id is required: take it from a search_history or list_conversations result."})
    out = json.loads(await open_agent_session(context, resume_sdk_id=sid))
    if "error" in out:
        if "orchestrator session" in out["error"]:
            out["hint"] = (f"That is one of your own past conversations: switch_conversation(session_id='{sid}') "
                           f"to continue in it, or read_conversation(session_id='{sid}') to just read it.")
    else:
        out["resumed"] = sid
    return json.dumps(out)


# Pause before acting so the tool result reaches the model and the device first.
SWITCH_SETTLE_S = 0.5
# Text mode: the switch waits for the current turn (the one that called the tool) to end.
SWITCH_BUSY_WAIT_S = 60.0
_switch_tasks: set[asyncio.Task] = set()


@registry.register(
    name="switch_conversation",
    description=(
        "Leave this conversation and continue in one of YOUR OWN past (orchestrator) conversations, "
        "with its full context — pass the session_id from a search_history / list_conversations result "
        "(kind 'orchestrator'). The current conversation is saved and closed, the app reopens the past "
        "one and, in a voice call, the call reconnects inside it. Only when the user wants to go back "
        "into that conversation, not to find or summarise it (read_conversation does that). In a voice "
        "call, say a short line like 'switching now' BEFORE calling it: nothing said after it is heard. "
        "For Claude/Qwen/Gemini conversations use resume_conversation."
    ),
    input_schema={
        "type": "object",
        "properties": {
            "session_id": {
                "type": "string",
                "description": "session_id of the past orchestrator conversation.",
            },
        },
        "required": ["session_id"],
    },
)
async def switch_conversation(context: dict[str, Any], session_id: str = "") -> str:
    sid = (session_id or "").strip()
    if not sid:
        return json.dumps({"error": "session_id is required: take it from a search_history or list_conversations result."})
    pool = context.get("pool")
    session = pool.get_orchestrator() if pool is not None else None
    if session is None:
        return json.dumps({"error": "No active orchestrator conversation to switch from."})
    if sid == session.jsonl_id:
        return json.dumps({"error": "That is the current conversation already."})
    info = context["store"].get_session_info(sid)
    if info is None:
        return json.dumps({"error": f"Conversation {sid!r} not found. Use list_conversations or search_history."})
    if not info.is_orchestrator:
        return json.dumps({"error": f"{sid!r} is an agent conversation, not one of yours.",
                           "hint": f"resume_conversation(session_id='{sid}') opens it as an agent session."})
    voice = session.is_voice
    target_ws = (session.voice_owner_ws if voice else None) or session.last_input_ws
    if target_ws is None:
        return json.dumps({"error": "No connected app to reopen the conversation on."})

    frame = {"type": "orchestrator_switch", "sdk_session_id": sid, "title": info.title,
             "voice": voice, "from_session_id": session.local_id}
    task = asyncio.create_task(_run_switch(pool, session, target_ws, frame))
    _switch_tasks.add(task)
    task.add_done_callback(_switch_tasks.discard)
    return json.dumps({"status": "switching", "session_id": sid, "title": info.title,
                       "note": "This conversation is closing now; do not say anything else."})


async def _run_switch(pool: Any, session: Any, ws: Any, frame: dict) -> None:
    """End voice, stop the current orchestrator, then tell the requesting app which one to open.

    The frame goes out after the stop so the app's resume start never meets the old session
    (``orchestrator_active``). Other devices follow through the pool watcher events."""
    try:
        await asyncio.sleep(SWITCH_SETTLE_S)
        if not frame["voice"]:
            loop = asyncio.get_running_loop()
            deadline = loop.time() + SWITCH_BUSY_WAIT_S
            while session.is_busy and loop.time() < deadline:
                await asyncio.sleep(0.2)
        if pool.get_orchestrator() is not session:
            logger.info("switch_conversation: orchestrator changed meanwhile; not switching")
            return
        if session.is_voice:
            await session.end_voice("switch")
        await pool.stop_orchestrator()
        await ws.send_bytes(json.dumps(frame).encode())
        logger.info("switch_conversation: %s -> %s (voice=%s)", frame["from_session_id"], frame["sdk_session_id"], frame["voice"])
    except Exception:
        logger.exception("switch_conversation failed")


@registry.register(
    name="close_agent_session",
    description="Close an active Claude Code agent session.",
    input_schema={
        "type": "object",
        "properties": {
            "session_id": {
                "type": "string",
                "description": "The session ID to close.",
            },
        },
        "required": ["session_id"],
    },
)
async def close_agent_session(context: dict[str, Any], session_id: str) -> str:
    pool = context["pool"]

    if not pool.is_open(session_id):
        return json.dumps({"error": f"No active session with ID {session_id}"})

    try:
        await pool.close(session_id)
    except Exception as e:
        logger.warning("Error closing session %s: %s", session_id, e)

    return json.dumps({"session_id": session_id, "status": "closed"})


# How much one read_agent_session call returns, in chars of JSON.  Voice: the
# realtime providers reject tool output above ~16 KB and
# truncate_voice_tool_output() clips anything over VOICE_TOOL_OUTPUT_MAX_CHARS
# by shortening every string and dropping the END of the biggest list — for a
# transcript that is the newest message, i.e. the agent's final report.  So a
# read sizes itself under that cap and hands back cursors instead.  Text: a
# default the model can raise with max_chars; nothing is ever lost, the rest is
# one cursor away.
_READ_BUDGET_VOICE = VOICE_TOOL_OUTPUT_MAX_CHARS - 500
_READ_BUDGET_TEXT = 40_000
_READ_BUDGET_MIN = 2_000
# Share of the budget the live block may use; the rest is for messages.
_LIVE_SHARE = 0.4
# Room kept free for the "earlier" / "continue" cursors and their hint.
_CURSOR_RESERVE = 400


def _dumps(obj: Any) -> str:
    return json.dumps(obj, ensure_ascii=False)


def _read_budget(context: dict[str, Any], max_chars: int | None) -> int:
    session = context.get("session")
    if bool(getattr(session, "is_voice", False)):
        cap = _READ_BUDGET_VOICE
        budget = min(max_chars, cap) if max_chars else cap
    else:
        budget = max_chars or _READ_BUDGET_TEXT
    return max(budget, _READ_BUDGET_MIN)


def _fit_text(text: str, room: int) -> int:
    """Length of the longest prefix of ``text`` whose JSON string encoding
    fits in ``room`` chars (escapes like ``\\n`` count double)."""
    n = min(len(text), max(room - 2, 0))
    while n > 0:
        size = len(_dumps(text[:n]))
        if size <= room:
            return n
        n -= size - room  # dropping k chars shrinks the encoding by >= k
    return 0


def _fit_live(live: dict[str, Any], room: int) -> dict[str, Any]:
    """``live`` within ``room`` chars: drop the oldest events first, then
    shorten the in-flight text from the front (its end is the newest)."""
    if len(_dumps(live)) <= room:
        return live
    live = dict(live)
    events = list(live.get("events") or [])
    omitted = 0
    while events and len(_dumps(live)) > room:
        events.pop(0)
        omitted += 1
        live["events"] = events
        live["events_omitted"] = omitted
    text = live.get("last_assistant_text") or ""
    over = len(_dumps(live)) - room
    if text and over > 0:
        live["last_assistant_text"] = "…" + text[min(len(text), over + 1):]
    return live


def _message_entry(index: int, p: Any) -> dict[str, Any]:
    return {
        "index": index,
        "role": p.role,
        "timestamp": p.timestamp.isoformat() if p.timestamp else None,
        "text": p.text,
    }


def _read_page(
    previews: list[Any],
    body: dict[str, Any],
    budget: int,
    max_messages: int | None,
    before_index: int | None,
) -> None:
    """Fill ``body["messages"]`` newest first with the text messages that fit.

    Tool-only entries (no text) are skipped: they would otherwise eat the
    "last N" window — in long sessions most entries are tool calls/results.
    """
    with_text = [
        i for i, p in enumerate(previews)
        if p.text.strip() and (before_index is None or i < before_index)
    ]
    candidates = with_text if max_messages is None else with_text[-max_messages:] if max_messages > 0 else []
    room = budget - len(_dumps(body)) - _CURSOR_RESERVE
    picked: list[dict[str, Any]] = []
    for i in reversed(candidates):
        entry = _message_entry(i, previews[i])
        cost = len(_dumps(entry)) + 2
        if cost <= room:
            picked.insert(0, entry)
            room -= cost
            continue
        if not picked:
            # The newest message alone is too big: return its head and a
            # cursor to the rest, never a lossy cut.
            text = entry["text"]
            entry.update(text="", truncated=True, chars_total=len(text))
            n = _fit_text(text, room - len(_dumps(entry)))
            entry["text"] = text[:n]
            picked.append(entry)
            body["continue"] = {"message_index": i, "char_offset": n}
        break
    body["messages"] = picked

    earliest = picked[0]["index"] if picked else before_index
    if earliest is not None:
        earlier = sum(1 for i in with_text if i < earliest)
        if earlier:
            body["earlier"] = {"before_index": earliest, "text_messages": earlier}


def _read_chunk(
    previews: list[Any],
    body: dict[str, Any],
    budget: int,
    message_index: int,
    char_offset: int,
) -> str | None:
    """Fill ``body["messages"]`` with one slice of one message.  Returns an
    error string when the index is out of range."""
    if not 0 <= message_index < len(previews):
        return f"message_index {message_index} is out of range (0..{len(previews) - 1})"
    text = previews[message_index].text
    offset = min(max(char_offset, 0), len(text))
    entry = _message_entry(message_index, previews[message_index])
    entry.update(text="", char_offset=offset, chars_total=len(text))
    room = budget - len(_dumps(body)) - len(_dumps(entry)) - _CURSOR_RESERVE
    n = _fit_text(text[offset:], room)
    entry["text"] = text[offset:offset + n]
    body["messages"] = [entry]
    if offset + n < len(text):
        body["continue"] = {"message_index": message_index, "char_offset": offset + n}
    return None


@registry.register(
    name="read_agent_session",
    description=(
        "Read a Claude Code session's conversation. By default returns its last 5 "
        "messages that contain text (tool-only steps are skipped), oldest first, "
        "each with its 'index' in the transcript; 'total_messages' counts every "
        "entry. Nothing is cut silently: when the output would be too large, the "
        "newest messages come first and the response carries cursors — "
        "'earlier': {before_index} to page back to older messages, and "
        "'continue': {message_index, char_offset} to read the rest of a long "
        "message (pass both back as arguments). Follow the cursors until you "
        "have what you need; a long final report may take several calls. "
        "The response also tells you whether a turn is actively running on this "
        "session: 'live.status' is 'running' (a turn is in flight — events shows "
        "the live tail of text/tool_use/permission events) or 'idle' (no turn "
        "running — messages is the canonical record). A delegated turn stays "
        "'running' while subagents it started are still working: 'live.phase' is "
        "then 'waiting_for_subagents' (with 'live.waiting_for_subagents' = how "
        "many) or 'notification_turn' (the agent is reporting on a finished "
        "subagent), and 'live.subagents' lists them with status and current "
        "activity. Use this single tool whether you want the finished output of a "
        "past turn or a progress check on an in-flight one."
    ),
    input_schema={
        "type": "object",
        "properties": {
            "session_id": {
                "type": "string",
                "description": "The session ID to read.",
            },
            "max_messages": {
                "type": ["integer", "null"],
                "description": (
                    "How many of the most recent text messages to return "
                    "(default: 5). Use null for as many as fit; the rest is "
                    "reachable through the 'earlier' cursor."
                ),
            },
            "before_index": {
                "type": "integer",
                "description": (
                    "Page back: return only messages with an index below this "
                    "(pass 'earlier.before_index' from a previous read)."
                ),
            },
            "message_index": {
                "type": "integer",
                "description": (
                    "Read one message by its index, in chunks (pass "
                    "'continue.message_index' from a previous read)."
                ),
            },
            "char_offset": {
                "type": "integer",
                "description": (
                    "With message_index: where in that message's text to start "
                    "(pass 'continue.char_offset'; default 0)."
                ),
            },
            "max_chars": {
                "type": "integer",
                "description": (
                    "Size of this response in characters (default 40000 in text "
                    "mode; voice mode is always capped near 7500). Raise it to "
                    "read a long report in fewer calls."
                ),
            },
        },
        "required": ["session_id"],
    },
)
async def read_agent_session(
    context: dict[str, Any],
    session_id: str,
    max_messages: int | None = 5,
    before_index: int | None = None,
    message_index: int | None = None,
    char_offset: int = 0,
    max_chars: int | None = None,
) -> str:
    pool = context["pool"]
    store = context["store"]
    runner = context.get("runner")

    # session_id is the local_id; look up the SDK session ID for JSONL store
    sm = pool.get(session_id)
    sdk_id = sm.sdk_session_id if sm else (pool.restored_sdk_id(session_id) or session_id)

    previews = store.get_preview(sdk_id, max_messages=None)
    budget = _read_budget(context, max_chars)

    # Live status: is a background turn currently driving this session?
    # The runner's ring buffer is the single source of truth for in-flight
    # state — pool membership alone only means the session is open.
    live: dict[str, Any] = {"status": "idle"}
    if runner is not None:
        snapshot = runner.peek(session_id)
        if snapshot.get("status") == "running" and not snapshot.get("finished", True):
            live = {
                "status": "running",
                "turn_id": snapshot.get("turn_id"),
                "phase": snapshot.get("phase", "agent"),
                "last_assistant_text": snapshot.get("last_assistant_text", ""),
                "events": snapshot.get("events", []),
            }
            if snapshot.get("waiting_for_subagents"):
                live["waiting_for_subagents"] = snapshot["waiting_for_subagents"]
    # Subagents the session spawned (spec 12 §4.8): one line each, so the
    # orchestrator can tell "waiting on its agents" from "stuck".
    rows = pool.subagents_for(session_id) if hasattr(pool, "subagents_for") else []
    subagents = [
        {k: row.get(k) for k in ("tool_use_id", "agent_type", "description", "status", "activity")}
        for row in (rows if isinstance(rows, list) else [])[-10:]
        if isinstance(row, dict)
    ]
    if subagents:
        live["subagents"] = subagents
    live = _fit_live(live, int(budget * _LIVE_SHARE))

    if not previews and live["status"] == "idle":
        return _dumps({
            "error": f"No messages found for session {session_id}",
            "live": live,
        })

    body: dict[str, Any] = {
        "session_id": session_id,
        "total_messages": len(previews),
        "messages": [],
        "live": live,
    }
    if message_index is not None:
        error = _read_chunk(previews, body, budget, message_index, char_offset)
        if error:
            return _dumps({"error": error, "session_id": session_id, "total_messages": len(previews)})
    else:
        _read_page(previews, body, budget, max_messages, before_index)

    hints = []
    if "continue" in body:
        c = body["continue"]
        hints.append(
            f"message {c['message_index']} continues: call read_agent_session with "
            f"message_index={c['message_index']}, char_offset={c['char_offset']}"
        )
    if "earlier" in body:
        e = body["earlier"]
        hints.append(
            f"{e['text_messages']} earlier text messages: call read_agent_session "
            f"with before_index={e['before_index']}"
        )
    if hints:
        body["more"] = "; ".join(hints)
    return _dumps(body)


@registry.register(
    name="send_to_agent_session",
    description=(
        "Send a message to an active Claude Code agent session. RETURNS IMMEDIATELY "
        "with a turn_id; the agent runs in the background. The result will arrive "
        "as a structured background event ('[SESSION xxx event: turn finished]') at "
        "the start of your next turn — do NOT loop calling this tool waiting for a "
        "response. If the agent starts subagents, the turn finishes only when they "
        "are all done and the agent has reported on them, so the event carries its "
        "final answer, not 'I started N agents'. While the turn is in flight you "
        "can: spawn other agents, check progress or read persisted output via "
        "read_agent_session, answer permission requests via "
        "respond_to_agent_permission, or simply respond to the user. Use "
        "interrupt_agent_session to stop a turn early. If the agent is already "
        "working on a turn (yours or the user's), the message goes INTO that turn: "
        "the agent reads it at its next step and adjusts course without stopping "
        "(status 'steered'). Use that to correct or redirect a running agent."
    ),
    input_schema={
        "type": "object",
        "properties": {
            "session_id": {
                "type": "string",
                "description": (
                    "The session ID to send to. When you just called "
                    "open_agent_session, use the exact session_id it returned — "
                    "do NOT substitute a session_id from earlier in the conversation."
                ),
            },
            "message": {
                "type": "string",
                "description": "The message to send to the agent.",
            },
        },
        "required": ["session_id", "message"],
    },
)
async def send_to_agent_session(
    context: dict[str, Any], session_id: str, message: str
) -> str:
    pool = context["pool"]
    runner = context.get("runner")
    if runner is None:
        # Fallback safety: shouldn't happen — OrchestratorSession injects runner
        # into context at __init__.  If it's missing we have a wiring bug.
        return json.dumps({
            "error": "BackgroundAgentRunner not available in context — orchestrator init bug",
        })

    if not pool.is_open(session_id):
        return json.dumps({"error": f"No active session with ID {session_id}"})

    # A turn is running: hand the message to it (spec 12 I-16) rather than
    # waiting behind it.  Harnesses that cannot steer fall through.
    if pool.turn_in_flight(session_id) and await pool.steer(session_id, message):
        turn_id = runner.in_flight_turn(session_id)
        return json.dumps({
            "turn_id": turn_id,
            "session_id": session_id,
            "status": "steered",
            "hint": (
                "Delivered into the turn the agent is running; it reads it at its next step. "
                + ("That turn's background event carries the result."
                   if turn_id else
                   "The user started that turn, so no background event will come: "
                   "check the outcome with read_agent_session(session_id).")
            ),
        })

    try:
        handle = await runner.spawn(session_id, message)
    except ValueError as e:
        return json.dumps({"error": str(e)})

    return json.dumps({
        "turn_id": handle.turn_id,
        "session_id": session_id,
        "session_title": handle.session_title,
        "status": "running",
        "started_at": handle.started_at,
        "hint": (
            "Result arrives as a background event next turn. "
            "Call read_agent_session(session_id) at any time — "
            "it returns persisted messages plus 'live.status' (running/idle) for in-flight progress."
        ),
    })


@registry.register(
    name="interrupt_agent_session",
    description=(
        "Interrupt an actively executing Claude Code agent session. "
        "Use this to stop an agent that is running undesired actions or taking "
        "too long.  The agent will stop processing immediately, the subagents "
        "started by a fire-and-forget turn (send_to_agent_session) are stopped "
        "too, and that turn will produce a 'cancelled' background event next turn."
    ),
    input_schema={
        "type": "object",
        "properties": {
            "session_id": {
                "type": "string",
                "description": "The session ID to interrupt.",
            },
        },
        "required": ["session_id"],
    },
)
async def interrupt_agent_session(context: dict[str, Any], session_id: str) -> str:
    pool = context["pool"]
    runner = context.get("runner")

    if not pool.has(session_id):
        return json.dumps({"error": f"No active session with ID {session_id}"})

    # A delegated turn goes through the runner: it interrupts the SDK while the
    # session works on the turn, stops the turn's running subagents and ends it
    # as 'cancelled' — also while it only waits for those subagents (no SDK
    # turn running then, so a bare pool.interrupt would stop nothing).
    try:
        cancelled = await runner.cancel_session(session_id) if runner is not None else []
    except Exception as e:  # noqa: BLE001
        logger.warning("Error cancelling runner turns of %s: %s", session_id, e)
        cancelled = []
    if cancelled:
        stopped = sum(runner.stopped_subagents(t) for t in cancelled)
        out: dict[str, Any] = {"session_id": session_id, "status": "interrupted", "cancelled_turns": cancelled}
        if stopped:
            out["stopped_subagents"] = stopped
        return json.dumps(out)

    try:
        await pool.interrupt(session_id)
    except Exception as e:
        logger.warning("Error interrupting session %s: %s", session_id, e)
        return json.dumps({"error": f"Failed to interrupt session: {e}"})

    return json.dumps({"session_id": session_id, "status": "interrupted"})


@registry.register(
    name="respond_to_agent_permission",
    description=(
        "Approve or deny a pending permission request from an agent session. "
        "When an agent calls a gated tool (currently only ExitPlanMode), the SDK "
        "pauses; the user gets a per-tab modal in the agent's tab and the "
        "orchestrator gets a 'permission_request' nested event with the request_id. "
        "Typically the user answers in their tab — only intervene if the user is "
        "unavailable, you have specific reason to overrule, or the agent has "
        "asked you in chat to decide.  First answer wins; the loser's call is a "
        "no-op (this includes when the user clicks the modal before you respond)."
    ),
    input_schema={
        "type": "object",
        "properties": {
            "session_id": {
                "type": "string",
                "description": "The agent session that issued the permission request.",
            },
            "request_id": {
                "type": "string",
                "description": "The request_id from the permission_request event.",
            },
            "decision": {
                "type": "string",
                "enum": ["allow", "deny"],
                "description": "'allow' to let the tool run, 'deny' to block it.",
            },
            "message": {
                "type": "string",
                "description": (
                    "Optional message returned to the agent. For 'deny' it "
                    "becomes the rejection reason; for 'allow' it's purely "
                    "informational and recorded in the conversation."
                ),
            },
        },
        "required": ["session_id", "request_id", "decision"],
    },
)
async def respond_to_agent_permission(
    context: dict[str, Any],
    session_id: str,
    request_id: str,
    decision: str,
    message: str | None = None,
) -> str:
    pool = context["pool"]
    if decision not in ("allow", "deny"):
        return json.dumps({"error": "decision must be 'allow' or 'deny'"})
    won = await pool.resolve_session_permission(
        session_id,
        request_id,
        decision,
        message=message,
        responder="orchestrator",
    )
    if not won:
        return json.dumps({
            "session_id": session_id,
            "request_id": request_id,
            "result": "no_op",
            "detail": "request was already answered or no longer exists",
        })
    return json.dumps({
        "session_id": session_id,
        "request_id": request_id,
        "decision": decision,
        "result": "ok",
    })


@registry.register(
    name="list_history",
    description=(
        "List all past conversation sessions (closed agents from history). "
        "Each entry has a 'session_id' (the sdk_session_id) and a 'type': "
        "pass any 'agent' entry's session_id to resume_conversation(session_id=...) "
        "to re-open and continue that exact conversation with full context. "
        "'orchestrator' sessions CANNOT be re-opened as agent sessions."
    ),
    input_schema={
        "type": "object",
        "properties": {
            "limit": {
                "type": "integer",
                "description": "Maximum number of sessions to return (default: 20).",
            },
        },
    },
)
async def list_history(context: dict[str, Any], limit: int = 20) -> str:
    store = context["store"]
    sessions = store.list_sessions()[:limit]
    result = []
    for s in sessions:
        result.append({
            "session_id": s.session_id,
            "title": s.title,
            "message_count": s.message_count,
            "last_activity": s.last_activity.isoformat(),
            "type": "orchestrator" if s.is_orchestrator else "agent",
        })
    return json.dumps({"sessions": result, "total": len(sessions)})
