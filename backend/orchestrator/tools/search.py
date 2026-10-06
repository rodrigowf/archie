"""Search tools — memory files and past conversations, kept strictly apart.

- ``search_memory``: semantic search over context/memory/ (chroma ``memory`` collection).
- ``search_history``: hybrid keyword + semantic search over past conversation transcripts of
  every harness (index/history.sqlite3, see utils/history_index.py), grouped by session.
- ``read_conversation``: read the turns around a ``search_history`` hit.

Both searches go through a persistent search-server subprocess that loads the embedding model
once and accepts queries over stdin/stdout (JSON-line protocol). This avoids the ~60-70 second
cold-start penalty on ARM devices (Jetson Nano) for every search. Falls back to one-shot
search.py if the warm server can't be started.
"""

from __future__ import annotations

import asyncio
import json
import logging
import re
import sys
from functools import lru_cache
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
_MEMORY_DIR = (_PROJECT_DIR / "context" / "memory").resolve()

# Frontmatter parser — simple YAML subset (no nested structures except a list
# under `references:`). Keeps the dependency surface zero.
_FRONTMATTER_RE = re.compile(r"^---\n(.*?)\n---\n", re.DOTALL)


def _parse_frontmatter(text: str) -> dict[str, Any] | None:
    """Extract YAML frontmatter from the head of a markdown file.

    Returns a dict of {key: value}, with `references` as a list and `tags` as a
    list when bracketed-inline (e.g. `tags: [a, b]`). Returns None when there
    is no frontmatter or it can't be parsed.
    """
    m = _FRONTMATTER_RE.match(text)
    if not m:
        return None
    block = m.group(1)
    out: dict[str, Any] = {}
    current_key: str | None = None
    for raw in block.splitlines():
        line = raw.rstrip()
        if not line:
            continue
        # List continuation: "  - value"
        if line.lstrip().startswith("- ") and current_key is not None:
            existing = out.get(current_key)
            if not isinstance(existing, list):
                out[current_key] = []
            out[current_key].append(line.lstrip()[2:].strip())
            continue
        # Key: value
        if ":" in line:
            key, _, value = line.partition(":")
            key = key.strip()
            value = value.strip()
            if value == "":
                # Block-style list begins on next line
                current_key = key
                out[key] = []
            elif value.startswith("[") and value.endswith("]"):
                # Inline list: [a, b, c]
                items = [v.strip() for v in value[1:-1].split(",")]
                out[key] = [v for v in items if v]
                current_key = None
            else:
                out[key] = value
                current_key = None
    return out


@lru_cache(maxsize=128)
def _read_frontmatter_cached(file_path: str, mtime_ns: int) -> dict[str, Any] | None:
    """Cache-keyed read of frontmatter. mtime_ns invalidates on edits."""
    try:
        text = Path(file_path).read_text(encoding="utf-8")
    except (OSError, UnicodeDecodeError):
        return None
    return _parse_frontmatter(text)


def _frontmatter_for(file_path: str) -> dict[str, Any] | None:
    """Read frontmatter for a file, using mtime-keyed cache."""
    try:
        mtime_ns = Path(file_path).stat().st_mtime_ns
    except OSError:
        return None
    return _read_frontmatter_cached(file_path, mtime_ns)


def _enrich_memory_results(results: list[dict[str, Any]]) -> None:
    """In-place: attach `frontmatter` to each memory search hit.

    Each result chunk gets a `frontmatter` field with the parsed YAML
    metadata block from the head of its source file (name, category,
    tags, created, modified, summary, source, references). Missing
    frontmatter yields `null` — common only for MEMORY.md and
    ORCHESTRATOR_MEMORY*.md which intentionally skip it.
    """
    for r in results:
        fp = r.get("file_path")
        if not fp:
            r["frontmatter"] = None
            continue
        r["frontmatter"] = _frontmatter_for(fp)


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
            # `--socket` opens a Unix domain socket transport in addition
            # to the stdio one used by this client. The socket lets
            # external writers (embed.py, manager/index_utils.py) reach
            # the same warm server, keeping chroma single-writer.
            proc = await asyncio.create_subprocess_exec(
                str(_RUN_SH), str(_SEARCH_SERVER), "--socket",
                stdin=asyncio.subprocess.PIPE,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
            )
        except Exception as e:
            logger.error("Failed to start search server: %s", e)
            return None

        # Forward the server's stderr to our logger so boot-probe /
        # boot-repair / write-error messages surface in journalctl.
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
                proc.kill()
                return None
        except asyncio.TimeoutError:
            logger.error("Search server startup timed out (180s)")
            proc.kill()
            return None
        except Exception as e:
            logger.error("Search server startup error: %s", e)
            proc.kill()
            return None


async def _forward_stderr(proc: asyncio.subprocess.Process) -> None:
    """Pipe the search server's stderr into our logger one line at a time.

    Lines that look like our own `[search-server]` markers (boot-probe,
    boot-repair, etc.) are logged at WARNING — they're once-per-boot
    events worth surfacing under the default log config without
    needing to flip the orchestrator logger to INFO. Lines from chatty
    libraries we trust (sentence-transformers' "Loading weights:"
    progress bar, huggingface_hub's auth warning) are dropped.
    Anything else also goes to WARNING — that's the bucket for genuine
    surprises like chroma tracebacks."""
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


async def _do_search_cold(
    query: str,
    collection_name: str,
    max_results: int,
) -> list[dict[str, Any]]:
    """Fallback: run search.py as a one-shot subprocess (cold start)."""
    args = [
        str(_RUN_SH), str(_SEARCH_SCRIPT),
        query,
        "--collection", collection_name,
        "--n", str(max_results),
        "--json",
    ]

    logger.info("Cold search '%s' for: %s", collection_name, query)

    try:
        proc = await asyncio.create_subprocess_exec(
            *args,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )
        stdout, stderr = await asyncio.wait_for(
            proc.communicate(), timeout=120,
        )
    except asyncio.TimeoutError:
        logger.error("Cold search timed out for query: %s", query)
        proc.kill()
        return [{"error": "Search timed out"}]

    if proc.returncode != 0:
        stderr_text = stderr.decode().strip()
        # Tail of stderr carries the actual exception; the head is just the
        # traceback boilerplate ("Traceback (most recent call last)... in <module>").
        tail = stderr_text[-800:]
        print(
            f"[search cold-fallback FAILED] rc={proc.returncode} collection={collection_name} "
            f"query={query!r}\n--- stderr tail ---\n{tail}\n--- end ---",
            file=sys.stderr,
            flush=True,
        )
        if proc.returncode < 0:
            return [{"error": f"Search crashed (signal {-proc.returncode})"}]
        if "No index found" in stderr_text:
            return [{"error": "Index not found. Run index-memory.py to rebuild."}]
        elif "Collection" in stderr_text and "not found" in stderr_text:
            return [{"error": f"Collection '{collection_name}' not found."}]
        elif "empty" in stderr_text.lower():
            return [{"error": f"Collection '{collection_name}' is empty."}]
        else:
            return [{"error": f"Search failed: {tail}"}]

    stdout_text = stdout.decode().strip()
    if not stdout_text or stdout_text == "No results found.":
        return []

    try:
        return json.loads(stdout_text)
    except json.JSONDecodeError:
        return []


async def _server_request(request: dict, label: str) -> dict | None:
    """Send one request to the warm server, restarting it once if it's unresponsive.

    Returns the reply dict, or None when the warm server can't be brought up at all (the
    caller then uses its cold fallback). Once the warm server is up, every query goes through
    it — chromadb's PersistentClient is not safe for concurrent multi-process access against
    the same path, so a cold subprocess opening the same index while the warm server holds it
    crashes with "Failed to apply logs to the hnsw segment writer".
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


async def _do_search(
    query: str,
    collection_name: str,
    max_results: int,
) -> list[dict[str, Any]]:
    """Search a chroma collection (memory) through the warm server, cold fallback otherwise."""
    logger.info("Searching '%s' for: %s", collection_name, query)
    label = f"collection={collection_name} query={query!r}"
    response = await _server_request(
        {"query": query, "collection": collection_name, "n_results": max_results}, label
    )
    if response is not None:
        error = response.get("error")
        if error:
            msg = f"[search warm-server ERROR] {label}: {error}"
            logger.warning(msg)
            print(msg, file=sys.stderr, flush=True)
            return [{"error": error}]
        results = response.get("results", [])
        logger.info("Warm search returned %d results.", len(results))
        return results

    # Cold fallback only runs when the warm server cannot be brought up.
    # This is mutually exclusive with a healthy warm server (so chromadb
    # multi-process access is not an issue here).
    results = await _do_search_cold(query, collection_name, max_results)
    logger.info("Cold search returned %d results.", len(results))
    return results


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
    try:
        proc = await asyncio.create_subprocess_exec(
            *args, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE,
        )
        stdout, stderr = await asyncio.wait_for(proc.communicate(), timeout=180)
    except asyncio.TimeoutError:
        proc.kill()
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
                _server_proc.kill()
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


@registry.register(
    name="search_history",
    description=(
        "Search PAST CONVERSATION TRANSCRIPTS (every chat, voice and agent session, including ones "
        "never digested into memory). Not for memory files — use search_memory for those. "
        "Combines exact keyword matching (names, rare words, Portuguese terms) with semantic "
        "similarity, and returns the best-matching sessions with title, dates and up to 3 "
        "matching excerpts each (with their turn numbers). 'relevance: weak' means only loose "
        "matches were found — say so instead of presenting them as the answer. Keep queries "
        "short and specific: distinctive words the conversation would actually contain. To read "
        "more of a hit, call read_conversation(session_id, turn). The current conversation is "
        "always excluded."
    ),
    input_schema={
        "type": "object",
        "properties": {
            "query": {
                "type": "string",
                "description": "Distinctive words or a short phrase, e.g. 'Shroud of Turin' or 'bicameral mind rituals'.",
            },
            "max_results": {
                "type": "integer",
                "description": "Maximum number of sessions to return (default 5, max 10).",
            },
            "after": {
                "type": "string",
                "description": "Only messages on or after this date (YYYY-MM-DD).",
            },
            "before": {
                "type": "string",
                "description": "Only messages before this date (YYYY-MM-DD).",
            },
        },
        "required": ["query"],
    },
)
async def search_history(
    context: dict[str, Any],
    query: str,
    max_results: int = 5,
    after: str | None = None,
    before: str | None = None,
) -> str:
    current = _current_session_uuid(context)
    request = {
        "command": "history_search",
        "query": query,
        "max_sessions": max(1, min(int(max_results or 5), 10)),
        "exclude_sessions": [current] if current else [],
        "after": _iso_bound(after),
        "before": _iso_bound(before),
    }
    logger.info("Searching history for: %s", query)
    response = await _server_request(request, f"history query={query!r}")
    if response is None:
        response = await _history_search_cold(request)
    if response.get("error"):
        return json.dumps({"query": query, "error": response["error"], "sessions": []})
    sessions = response.get("sessions", [])
    out: dict[str, Any] = {
        "query": query,
        "sessions": sessions,
        "count": len(sessions),
        "total_matching_sessions": response.get("total_sessions", len(sessions)),
    }
    if not sessions:
        out["note"] = "No past conversation matches. Try other distinctive words, or search_memory."
    return json.dumps(out, ensure_ascii=False)


@registry.register(
    name="read_conversation",
    description=(
        "Read part of a past conversation as clean user/assistant turns (no tool noise). Use it "
        "after search_history to explore a hit: pass the session_id and the hit's turn number "
        "to see what was said around it. Omit turn to read from the beginning; page forward by "
        "calling again with a later turn."
    ),
    input_schema={
        "type": "object",
        "properties": {
            "session_id": {"type": "string", "description": "session_id from a search_history result."},
            "turn": {"type": "integer", "description": "Turn number to center on (from a search hit)."},
            "before": {"type": "integer", "description": "Turns to include before it (default 3)."},
            "after": {"type": "integer", "description": "Turns to include after it (default 6)."},
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
) -> str:
    from utils import history_index

    result = await asyncio.to_thread(
        history_index.read_turns,
        session_id.strip(),
        turn=turn,
        before=max(0, min(int(before), 20)),
        after=max(0, min(int(after), 30)),
    )
    return json.dumps(result, ensure_ascii=False)


def _under_memory_dir(file_path: str | None) -> bool:
    if not file_path:
        return False
    try:
        return Path(file_path).resolve().is_relative_to(_MEMORY_DIR)
    except (OSError, ValueError):
        return False


@registry.register(
    name="search_memory",
    description=(
        "Search the MEMORY FILES (context/memory/: curated notes, project docs, people, decisions) "
        "using semantic search. Not for raw past conversations — use search_history for those. "
        "Each hit carries the file's frontmatter (category, tags) and line range; read the file "
        "with read_file for the full content."
    ),
    input_schema={
        "type": "object",
        "properties": {
            "query": {
                "type": "string",
                "description": "The search query.",
            },
            "max_results": {
                "type": "integer",
                "description": "Maximum number of results (default: 5).",
            },
        },
        "required": ["query"],
    },
)
async def search_memory(
    context: dict[str, Any], query: str, max_results: int = 5
) -> str:
    results = await _do_search(query, "memory", max_results)
    # Only memory files: the chroma collection once had conversation chunks replayed into it.
    results = [r for r in results if "error" in r or _under_memory_dir(r.get("file_path"))]
    _enrich_memory_results(results)
    return json.dumps({"query": query, "results": results, "count": len(results)})
