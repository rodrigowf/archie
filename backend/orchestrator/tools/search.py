"""Search tools — past conversations and memory notes, with navigation for both.

- ``search_history`` / ``list_conversations`` / ``grep_conversation`` / ``read_conversation``:
  every harness's transcripts (index/history.sqlite3, utils/history_index.py + history_nav.py).
  Results are grouped by session, LLM re-ranked, say how to open each session, which memory
  notes were written from it, and which memory notes match the same query.
- ``search_memory`` / ``browse_memory`` / ``grep_memory``: the memory wiki
  (index/memory.sqlite3, utils/memory_index.py); read notes with ``read_file``.

Searches go through a persistent search-server subprocess that keeps the embedding model loaded
(stdin/stdout JSON lines) — ~100 s cold start on the Jetson otherwise — and run the same
utils/search_service.py code as the one-shot fallback (search.py), used only when the server
can't start.
"""

from __future__ import annotations

import asyncio
import json
import logging
import re
import sys
from pathlib import Path
from utils.paths import PROJECT_ROOT
from typing import Any

from orchestrator.tools import registry

logger = logging.getLogger(__name__)

# Paths (defined upfront so enrichment helpers below can reference them).
_PROJECT_DIR = PROJECT_ROOT
_SEARCH_SERVER = _PROJECT_DIR / "shared" / "scripts" / "search-server.py"
_SEARCH_SCRIPT = _PROJECT_DIR / "shared" / "scripts" / "search.py"
_RUN_SH = _PROJECT_DIR / "context" / "scripts" / "run.sh"

# Singleton warm server process
_server_proc: asyncio.subprocess.Process | None = None
_server_lock = asyncio.Lock()  # guards lifecycle (start/stop)
_query_lock = asyncio.Lock()   # serializes stdin/stdout pairing across concurrent queries
_server_ready = False


async def _ensure_server() -> asyncio.subprocess.Process | None:
    """Start the search server if not already running. Returns the process or None."""
    global _server_proc, _server_ready

    async with _server_lock:
        # Check if existing process is still alive
        if _server_proc is not None and _server_proc.returncode is None:
            return _server_proc

        # Need to (re)start
        _server_ready = False
        _server_proc = None

        logger.info("Starting search server subprocess...")
        try:
            # `--socket` also opens a Unix domain socket so other processes
            # (the indexers, search.py) can borrow the warm model.
            proc = await asyncio.create_subprocess_exec(
                str(_RUN_SH), str(_SEARCH_SERVER), "--socket",
                stdin=asyncio.subprocess.PIPE,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
            )
        except Exception as e:
            logger.error("Failed to start search server: %s", e)
            return None

        # Forward the server's stderr to our logger so its ready/error
        # messages surface in journalctl.
        # We start this BEFORE waiting on `ready` so any messages
        # emitted during the model-load window aren't lost.
        asyncio.create_task(_forward_stderr(proc))

        # Wait for the "ready" signal (model loaded)
        try:
            ready_line = await asyncio.wait_for(
                proc.stdout.readline(), timeout=180,  # Model load can be slow on Jetson
            )
            ready_data = json.loads(ready_line.decode().strip())
            if ready_data.get("status") == "ready":
                _server_proc = proc
                _server_ready = True
                socket_path = ready_data.get("socket")
                if socket_path:
                    logger.info("Search server ready (PID %d, socket=%s)", proc.pid, socket_path)
                else:
                    logger.info("Search server ready (PID %d)", proc.pid)
                return proc
            else:
                logger.error("Unexpected ready response: %s", ready_data)
                _kill_quietly(proc)
                return None
        except asyncio.TimeoutError:
            logger.error("Search server startup timed out (180s)")
            _kill_quietly(proc)
            return None
        except Exception as e:
            logger.error("Search server startup error: %s", e)
            _kill_quietly(proc)
            return None


def _kill_quietly(proc: asyncio.subprocess.Process) -> None:
    """Kill a server that failed to start; it may already have exited (e.g. another server holds the lock)."""
    try:
        proc.kill()
    except ProcessLookupError:
        pass


async def _forward_stderr(proc: asyncio.subprocess.Process) -> None:
    """Pipe the search server's stderr into our logger one line at a time.

    Lines that look like our own `[search-server]` markers (ready, request
    failures) are logged at WARNING — they're once-per-boot
    events worth surfacing under the default log config without
    needing to flip the orchestrator logger to INFO. Lines from chatty
    libraries we trust (sentence-transformers' "Loading weights:"
    progress bar, huggingface_hub's auth warning) are dropped.
    Anything else also goes to WARNING — that's the bucket for genuine
    surprises like tracebacks."""
    if proc.stderr is None:
        return
    # Patterns we silence outright — high-volume noise that's not
    # actionable. Anything not matched falls through to WARNING.
    NOISE_PREFIXES = (
        "Loading weights",
        "BertModel LOAD REPORT",
        "Warning: You are sending unauthenticated requests to the HF Hub",
        "Notes:",
        "- UNEXPECTED",
        "Key",
        "embeddings.position_ids",
        "------------------------",
    )
    try:
        while True:
            raw = await proc.stderr.readline()
            if not raw:
                return
            line = raw.decode(errors="replace").rstrip()
            if not line:
                continue
            if line.startswith("[search-server]"):
                logger.warning("search-server: %s", line[len("[search-server] "):])
                continue
            if any(line.startswith(p) for p in NOISE_PREFIXES):
                continue
            logger.warning("search-server stderr: %s", line)
    except Exception as e:
        logger.warning("search-server stderr reader stopped: %s", e)


async def _query_server(
    proc: asyncio.subprocess.Process,
    request: dict,
    *,
    timeout: float = 30.0,
) -> dict | None:
    """Send a query to the warm server and read the response."""
    try:
        line = json.dumps(request) + "\n"
        proc.stdin.write(line.encode())
        await proc.stdin.drain()

        response_line = await asyncio.wait_for(
            proc.stdout.readline(), timeout=timeout,  # Warm queries should be fast
        )

        if not response_line:
            logger.warning("Search server returned empty response (process died?)")
            return None

        return json.loads(response_line.decode().strip())

    except asyncio.TimeoutError:
        logger.error("Search server query timed out (30s)")
        return None
    except Exception as e:
        logger.error("Search server query error: %s", e)
        return None


async def _server_request(request: dict, label: str) -> dict | None:
    """Send one request to the warm server, restarting it once if it's unresponsive.

    Returns the reply dict, or None when the warm server can't be brought up at all (the
    caller then uses its cold fallback, which loads the model in a subprocess).
    """
    # Serialize concurrent queries — the warm server is single-threaded and
    # request/response pairing on its stdio is positional.
    async with _query_lock:
        proc = await _ensure_server()
        if proc is None:
            return None
        response = await _query_server(proc, request)
        if response is not None:
            return response

        # Warm path failed — restart and retry once.
        msg = (
            f"[search warm-server UNRESPONSIVE] pid={proc.pid} returncode={proc.returncode} "
            f"{label} — restarting and retrying"
        )
        logger.warning(msg)
        print(msg, file=sys.stderr, flush=True)
        await _restart_server()

        proc = await _ensure_server()
        if proc is not None:
            response = await _query_server(proc, request)
            if response is not None:
                return response
            msg = f"[search warm-server FAILED AFTER RESTART] {label}"
            logger.error(msg)
            print(msg, file=sys.stderr, flush=True)
    return None


async def _history_search_cold(request: dict) -> dict:
    """Fallback: search.py --collection history as a one-shot subprocess. The history index is
    plain SQLite, so this is safe even next to a live warm server."""
    args = [
        str(_RUN_SH), str(_SEARCH_SCRIPT), request["query"],
        "--collection", "history", "--n", str(request.get("max_sessions") or 5), "--json",
    ]
    for sid in request.get("exclude_sessions") or ():
        args += ["--exclude", sid]
    for key in ("after", "before"):
        if request.get(key):
            args += [f"--{key}", request[key]]
    if request.get("session_id"):
        args += ["--session", request["session_id"]]
    if request.get("kind"):
        args += ["--kind", request["kind"]]
    if request.get("window"):
        args += ["--window", *request["window"]]
    for q in request.get("queries") or ():
        args += ["--also", q]
    if request.get("rerank"):
        args += ["--rerank"]
    try:
        proc = await asyncio.create_subprocess_exec(
            *args, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE,
        )
        stdout, stderr = await asyncio.wait_for(proc.communicate(), timeout=180)
    except asyncio.TimeoutError:
        _kill_quietly(proc)
        return {"sessions": [], "error": "Search timed out"}
    if proc.returncode != 0:
        tail = stderr.decode(errors="replace").strip()[-800:]
        logger.error("Cold history search failed (rc=%s): %s", proc.returncode, tail)
        return {"sessions": [], "error": f"Search failed: {tail}"}
    try:
        return json.loads(stdout.decode())
    except json.JSONDecodeError:
        return {"sessions": [], "error": "Search returned no parseable output"}


async def _restart_server() -> None:
    """Tear down the warm server so the next _ensure_server starts fresh."""
    global _server_proc, _server_ready
    async with _server_lock:
        proc = _server_proc
        _server_ready = False
        _server_proc = None
        if proc is not None and proc.returncode is None:
            try:
                proc.kill()
            except ProcessLookupError:
                pass
            try:
                await asyncio.wait_for(proc.wait(), timeout=5)
            except (asyncio.TimeoutError, Exception):
                pass


async def shutdown_server() -> None:
    """Gracefully shut down the warm search server. Call during app teardown."""
    global _server_proc, _server_ready

    async with _server_lock:
        if _server_proc is not None and _server_proc.returncode is None:
            logger.info("Shutting down search server (PID %d)...", _server_proc.pid)
            try:
                _server_proc.stdin.write(json.dumps({"command": "shutdown"}).encode() + b"\n")
                await _server_proc.stdin.drain()
                await asyncio.wait_for(_server_proc.wait(), timeout=5)
            except Exception:
                _kill_quietly(_server_proc)
            _server_proc = None
            _server_ready = False


def _current_session_uuid(context: dict[str, Any]) -> str | None:
    """Return the UUID of the currently-running orchestrator session, if any.

    The history index keys sessions by their JSONL file stem, which for an orchestrator
    session is its ``jsonl_id``. Excluding it keeps the in-progress conversation (its own
    questions and denials) out of its own search results.
    """
    session = context.get("session") if context else None
    if session is None:
        return None
    # `jsonl_id` returns the resume_id (when resuming) or local_id (fresh).
    for attr in ("jsonl_id", "_resume_id", "_local_id"):
        val = getattr(session, attr, None)
        if isinstance(val, str) and val:
            return val
    return None


_DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}")


def _iso_bound(value: Any) -> str | None:
    """Accept 'YYYY-MM-DD' or a full ISO timestamp; anything else is ignored."""
    if isinstance(value, str) and _DATE_RE.match(value.strip()):
        return value.strip()
    return None


_KINDS = ("orchestrator", "agent", "claude", "qwen", "gemini")


def _time_window(query: str, when: str | None) -> tuple[dict | None, str]:
    """Server-side time understanding: an explicit `when`, or a time phrase inside the query
    ("back in June", "semana passada"). Returns ({after, before, label} | None, query without
    the time words)."""
    from datetime import date

    from utils.timewords import find_time_expression, resolve_when

    today = date.today()
    if when:
        w = resolve_when(when, today)
        return ({"after": w.after.isoformat(), "before": w.before.isoformat(), "label": w.label} if w else None), query
    w, cleaned = find_time_expression(query, today)
    if not w or len(cleaned.split()) < 2:  # keep the words if nothing but the date would remain
        return ({"after": w.after.isoformat(), "before": w.before.isoformat(), "label": w.label} if w else None), query
    return {"after": w.after.isoformat(), "before": w.before.isoformat(), "label": w.label}, cleaned


@registry.register(
    name="search_history",
    description=(
        "Find PAST CONVERSATIONS — orchestrator (voice/text) sessions and agent sessions (Claude "
        "Code, Qwen, Gemini) — by what was said in them, including ones never saved to memory. "
        "Matches exact words (names, rare terms, Portuguese) and meaning. Returns sessions, best "
        "first, each with title, kind, dates, working directory, `relevance` (strong/weak), up to "
        "3 excerpts with their `turn` numbers, and `open` (how to get into it: agent sessions can "
        "be resumed with resume_conversation(session_id); your own orchestrator ones continued with "
        "switch_conversation(session_id); either read with read_conversation). Tips: pass 2–3 different phrasings in `queries` (e.g. English and "
        "Portuguese, or different words for the same thing) — they are searched together; put "
        "remembered times in `when` ('last week', 'em junho', '2026-05') — dates are worked out "
        "for you, and sessions from other times still show if they match strongly. If nothing "
        "matches strongly the results come back as `weak_matches`: say you didn't find it. For 'what did we do "
        "yesterday'-style questions with no topic words, use list_conversations. The current "
        "conversation is always excluded."
    ),
    input_schema={
        "type": "object",
        "properties": {
            "query": {
                "type": "string",
                "description": "What the conversation was about, in a few distinctive words, e.g. 'Shroud of Turin' or 'OBS not connecting to JACK'.",
            },
            "queries": {
                "type": "array",
                "items": {"type": "string"},
                "description": "Optional extra phrasings of the same request (another language, synonyms), searched together with `query`.",
            },
            "when": {
                "type": "string",
                "description": "When it happened, in plain words or a date: 'yesterday', 'last week', 'back in June', 'uns dois meses atrás', '2026-05'.",
            },
            "kind": {
                "type": "string",
                "enum": ["all", "orchestrator", "agent"],
                "description": "Only when the user clearly said which: 'orchestrator' (our voice/text chats) or 'agent' (Claude/Qwen/Gemini coding sessions). A preference — the other kind still shows if it matches better. Default all.",
            },
            "session_id": {
                "type": "string",
                "description": "Search inside one conversation only.",
            },
            "max_results": {
                "type": "integer",
                "description": "Maximum number of sessions to return (default 5, max 10).",
            },
            "after": {"type": "string", "description": "Hard lower date bound (YYYY-MM-DD)."},
            "before": {"type": "string", "description": "Hard upper date bound (YYYY-MM-DD)."},
        },
        "required": ["query"],
    },
)
async def search_history(
    context: dict[str, Any],
    query: str,
    queries: list[str] | None = None,
    when: str | None = None,
    kind: str | None = None,
    session_id: str | None = None,
    max_results: int = 5,
    after: str | None = None,
    before: str | None = None,
) -> str:
    current = _current_session_uuid(context)
    window, query_text = _time_window(query, when)
    extra = [q for q in (queries or []) if isinstance(q, str) and q.strip() and q.strip() != query.strip()][:3]
    request = {
        "command": "history_search",
        "query": query_text,
        "queries": extra,
        "max_sessions": max(1, min(int(max_results or 5), 10)),
        "exclude_sessions": [current] if current else [],
        "after": _iso_bound(after),
        "before": _iso_bound(before),
        "kind": kind if kind in _KINDS else None,
        "kind_mode": "prefer",
        "session_id": (session_id or "").strip() or None,
        "window": [window["after"], window["before"]] if window else None,
        "window_mode": "prefer",
        "include_memory": True,
        "rerank": True,
        "request_text": query,
    }
    logger.info("Searching history for: %s (+%d phrasings, window=%s)", query, len(extra), window)
    response = await _server_request(request, f"history query={query!r}")
    if response is None:
        response = await _history_search_cold(request)
    if response.get("error"):
        return json.dumps({"query": query, "error": response["error"], "sessions": []})
    sessions = response.get("sessions", [])
    out: dict[str, Any] = {"query": query}
    if window:
        out["time_window"] = window
    if any(s.get("relevance") == "strong" for s in sessions):
        out["sessions"] = sessions
    else:
        # Nothing clearly matches: keep loose matches apart so they aren't presented as the answer.
        out["sessions"] = []
        out["weak_matches"] = sessions
        out["note"] = (
            "No conversation clearly matches. Tell the user you didn't find it (or try once more with "
            "different words); mention a weak match only if it is obviously what they mean."
            if sessions else "No past conversation matches. Try other words, or search_memory."
        )
    if response.get("note"):
        out["note"] = f"{response['note']}. {out.get('note', '')}".strip(". ") + "."
    out["total_matching_sessions"] = response.get("total_sessions", len(sessions))
    if response.get("memory"):
        # Memory notes on the same subject (curated, maybe written from these very conversations).
        out["memory"] = response["memory"]
    return json.dumps(out, ensure_ascii=False)


@registry.register(
    name="list_conversations",
    description=(
        "Browse past conversations (orchestrator and agent sessions), newest first: by time "
        "('yesterday', 'last week', 'em agosto'), kind, or words in the title / first message. "
        "Use it for 'what did we do yesterday?' or 'the sessions from last week' when there are "
        "no topic words to search for. Each entry has title, kind, dates, working directory and "
        "how to open it. Page with `offset`."
    ),
    input_schema={
        "type": "object",
        "properties": {
            "when": {"type": "string", "description": "Time window in plain words or a date ('yesterday', 'last week', '2026-08')."},
            "kind": {"type": "string", "enum": ["all", "orchestrator", "agent"], "description": "Default all."},
            "text": {"type": "string", "description": "Words that must appear in the title or first message."},
            "limit": {"type": "integer", "description": "How many (default 10, max 30)."},
            "offset": {"type": "integer", "description": "Skip this many (paging)."},
        },
    },
)
async def list_conversations(
    context: dict[str, Any],
    when: str | None = None,
    kind: str | None = None,
    text: str | None = None,
    limit: int = 10,
    offset: int = 0,
) -> str:
    from utils import history_index, history_nav

    window, _ = _time_window("", when) if when else (None, "")
    if when and not window:
        return json.dumps({"error": f"Could not understand the time {when!r}; try 'last week', 'em junho' or '2026-06'."})
    current = _current_session_uuid(context)

    def run() -> dict:
        db = history_index.get_history_db_path()
        if not db.exists():
            return {"error": "history index not built yet"}
        conn = history_index.connect(db, readonly=True)
        try:
            return history_nav.list_conversations(
                conn,
                after=window["after"] if window else None,
                before=window["before"] if window else None,
                kind=kind if kind in _KINDS else None,
                text=text,
                limit=int(limit or 10),
                offset=max(0, int(offset or 0)),
                exclude=(current,) if current else (),
            )
        finally:
            conn.close()

    result = await asyncio.to_thread(run)
    if window:
        result["time_window"] = window
    return json.dumps(result, ensure_ascii=False)


@registry.register(
    name="grep_conversation",
    description=(
        "Find exact words inside ONE past conversation (accent- and case-insensitive; `regex` "
        "for patterns). Returns the turn numbers and snippets where they occur — then "
        "read_conversation(session_id, turn) to read around them."
    ),
    input_schema={
        "type": "object",
        "properties": {
            "session_id": {"type": "string"},
            "pattern": {"type": "string", "description": "Words to find, e.g. 'JACK' or 'Iriun'."},
            "regex": {"type": "boolean", "description": "Treat pattern as a regular expression."},
            "max_hits": {"type": "integer", "description": "Default 10."},
        },
        "required": ["session_id", "pattern"],
    },
)
async def grep_conversation(
    context: dict[str, Any],
    session_id: str,
    pattern: str,
    regex: bool = False,
    max_hits: int = 10,
) -> str:
    from utils import history_nav

    result = await asyncio.to_thread(
        history_nav.grep_conversation, session_id.strip(), pattern, regex=bool(regex), max_hits=int(max_hits or 10)
    )
    return json.dumps(result, ensure_ascii=False)


@registry.register(
    name="read_conversation",
    description=(
        "Read any part of a past conversation as clean user/assistant turns (no tool noise). "
        "After search_history: pass the session_id and a hit's `turn` to read around it "
        "(default 3 before, 6 after). Or pass `start`/`end` for an exact range (max 30 turns), "
        "or start=end for one turn in full (long turns are paged: call again with "
        "`char_offset` = the returned next_char_offset). Omit everything to read from the start."
    ),
    input_schema={
        "type": "object",
        "properties": {
            "session_id": {"type": "string", "description": "session_id from search_history / list_conversations."},
            "turn": {"type": "integer", "description": "Turn number to center on (from a search hit)."},
            "before": {"type": "integer", "description": "Turns to include before it (default 3)."},
            "after": {"type": "integer", "description": "Turns to include after it (default 6)."},
            "start": {"type": "integer", "description": "First turn of an exact range."},
            "end": {"type": "integer", "description": "Last turn of an exact range (inclusive)."},
            "char_offset": {"type": "integer", "description": "For a single long turn: where to continue reading."},
        },
        "required": ["session_id"],
    },
)
async def read_conversation(
    context: dict[str, Any],
    session_id: str,
    turn: int | None = None,
    before: int = 3,
    after: int = 6,
    start: int | None = None,
    end: int | None = None,
    char_offset: int = 0,
) -> str:
    from utils import history_nav

    result = await asyncio.to_thread(
        history_nav.read_conversation,
        session_id.strip(),
        turn=turn,
        before=int(before if before is not None else 3),
        after=int(after if after is not None else 6),
        start=start,
        end=end,
        char_offset=int(char_offset or 0),
    )
    return json.dumps(result, ensure_ascii=False)


async def _memory_search_cold(request: dict) -> dict:
    args = [str(_RUN_SH), str(_SEARCH_SCRIPT), request["query"], "--collection", "memory",
            "--n", str(request.get("max_files") or 5), "--json"]
    for q in request.get("queries") or ():
        args += ["--also", q]
    if request.get("folder"):
        args += ["--folder", request["folder"]]
    try:
        proc = await asyncio.create_subprocess_exec(*args, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE)
        stdout, stderr = await asyncio.wait_for(proc.communicate(), timeout=180)
    except asyncio.TimeoutError:
        _kill_quietly(proc)
        return {"files": [], "error": "Search timed out"}
    if proc.returncode != 0:
        return {"files": [], "error": f"Search failed: {stderr.decode(errors='replace')[-600:]}"}
    try:
        return json.loads(stdout.decode())
    except json.JSONDecodeError:
        return {"files": [], "error": "Search returned no parseable output"}


@registry.register(
    name="search_memory",
    description=(
        "Search the MEMORY FILES (context/memory/: curated notes on projects, people, devices, "
        "decisions) by keyword and meaning. Not raw past conversations — that's search_history "
        "(which also shows the best memory notes for its query). Returns notes, best first, with "
        "title, description, `relevance`, the matching sections (`lines` — read them with "
        "read_file(path, start_line, end_line)), and `from_conversations` when the note was "
        "written from a past conversation. Pass extra phrasings in `queries` (e.g. Portuguese)."
    ),
    input_schema={
        "type": "object",
        "properties": {
            "query": {"type": "string", "description": "What you want to know, in a few distinctive words."},
            "queries": {"type": "array", "items": {"type": "string"}, "description": "Optional extra phrasings, searched together."},
            "folder": {"type": "string", "description": "Only notes under this folder, e.g. 'projects/qvcm'."},
            "max_results": {"type": "integer", "description": "Maximum number of notes (default 5)."},
        },
        "required": ["query"],
    },
)
async def search_memory(
    context: dict[str, Any],
    query: str,
    queries: list[str] | None = None,
    folder: str | None = None,
    max_results: int = 5,
) -> str:
    request = {
        "command": "memory_search",
        "query": query,
        "queries": [q for q in (queries or []) if isinstance(q, str) and q.strip()][:3],
        "folder": (folder or "").strip().strip("/").removeprefix("context/memory").strip("/") or None,
        "max_files": max(1, min(int(max_results or 5), 10)),
    }
    response = await _server_request(request, f"memory query={query!r}")
    if response is None:
        response = await _memory_search_cold(request)
    if response.get("error"):
        return json.dumps({"query": query, "error": response["error"], "files": []})
    files = response.get("files", [])
    out: dict[str, Any] = {"query": query}
    if any(f.get("relevance") == "strong" for f in files):
        out["files"] = files
    else:
        out["files"] = []
        out["weak_matches"] = files
        out["note"] = "No memory note clearly matches; try search_history (it may never have been saved to memory)."
    return json.dumps(out, ensure_ascii=False)


@registry.register(
    name="browse_memory",
    description=(
        "List one folder of the memory wiki: its subfolders and its notes (title, one-line "
        "description, modified date), plus the folder's INDEX.md. Start with no folder for the "
        "top level. Read a note with read_file."
    ),
    input_schema={
        "type": "object",
        "properties": {"folder": {"type": "string", "description": "e.g. 'projects' or 'archie/voice'. Empty for the top level."}},
    },
)
async def browse_memory(context: dict[str, Any], folder: str = "") -> str:
    from utils import memory_index

    return json.dumps(await asyncio.to_thread(memory_index.browse, folder or ""), ensure_ascii=False)


@registry.register(
    name="grep_memory",
    description=(
        "Find exact words (accent- and case-insensitive; `regex` for patterns) across the memory "
        "notes, optionally within one folder. Returns file:line hits — then read_file around them."
    ),
    input_schema={
        "type": "object",
        "properties": {
            "pattern": {"type": "string"},
            "folder": {"type": "string", "description": "Only under this folder."},
            "regex": {"type": "boolean"},
            "max_hits": {"type": "integer", "description": "Default 20."},
        },
        "required": ["pattern"],
    },
)
async def grep_memory(
    context: dict[str, Any], pattern: str, folder: str = "", regex: bool = False, max_hits: int = 20
) -> str:
    from utils import memory_index

    result = await asyncio.to_thread(
        memory_index.grep, pattern, folder=folder or "", regex=bool(regex), max_hits=int(max_hits or 20)
    )
    return json.dumps(result, ensure_ascii=False)
