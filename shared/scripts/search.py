"""
Usage: context/scripts/search.py <query> [options]
Description: Search memory notes or past conversations (keyword + meaning, EN/PT).

Options:
    --collection NAME    memory (default) or history
    --n N                Number of results (notes or sessions, default 5)
    --also TEXT          Another phrasing of the same request (repeatable)
    --json               Output as JSON for programmatic use
  memory:
    --folder PATH        Only notes under this folder (e.g. projects/qvcm)
  history:
    --when TEXT          When it happened ('last week', 'em junho', '2026-05'); preferred window
    --window A B         Preferred window as ISO dates
    --after DATE         Hard bound: only messages on/after YYYY-MM-DD
    --before DATE        Hard bound: only messages before YYYY-MM-DD
    --kind KIND          Prefer orchestrator | agent | claude | qwen | gemini sessions
    --exclude ID         Leave out a session (repeatable)
    --session ID         Search inside one session only
    --rerank             LLM re-rank of the top candidates (needs OPENAI_API_KEY)

Indexes: index/history.sqlite3 and index/memory.sqlite3 (see backend/utils/search_service.py).
Uses the warm search server when it runs, else loads the embedding model in-process.

Examples:
    context/scripts/search.py "wake word confirmation" --collection memory
    context/scripts/search.py "Shroud of Turin" --collection history --rerank
    context/scripts/search.py "OBS JACK" --collection history --when "em maio" --also "OBS não conecta no JACK"
"""
import argparse
import json
import sys
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
PROJECT_DIR = SCRIPT_DIR.parent.parent  # shared/scripts/ → repo root
sys.path.insert(0, str(PROJECT_DIR / "backend"))  # backend packages (utils, …)
sys.path.insert(0, str(SCRIPT_DIR))


def _call(request: dict) -> dict:
    """Run a request on the warm server if reachable, else in-process."""
    import index_client

    client = index_client.try_connect(timeout=5)
    if client is not None:
        try:
            reply = client.call(request, request_timeout=120)
        finally:
            client.close()
    else:
        from utils import search_service

        service = search_service.SearchService(search_service.sentence_transformer_loader())
        reply = (service.history_search if request["command"] == "history_search" else service.memory_search)(request)
    if reply.get("error"):
        print(f"Error: {reply['error']}. Build the index with context/scripts/index-memory.py.", file=sys.stderr)
        sys.exit(1)
    reply.pop("error", None)
    return reply


def search_history(query, n_sessions=5, exclude=(), after=None, before=None, session_id=None,
                   kind=None, queries=(), window=None, rerank=False):
    """Hybrid search over past conversations. Returns {"sessions": [...], "total_sessions": n}."""
    return _call({
        "command": "history_search", "query": query, "max_sessions": n_sessions,
        "exclude_sessions": list(exclude), "after": after, "before": before, "session_id": session_id,
        "kind": kind, "kind_mode": "prefer", "queries": list(queries),
        "window": list(window) if window else None, "window_mode": "prefer",
        "rerank": rerank, "request_text": query,
    })


def search_memory(query, n_files=5, folder=None, queries=()):
    """Hybrid search over the memory notes. Returns {"files": [...], "total_files": n}."""
    return _call({"command": "memory_search", "query": query, "max_files": n_files, "folder": folder,
                  "queries": list(queries)})


def print_history(result, as_json=False):
    if as_json:
        print(json.dumps(result, indent=2, ensure_ascii=False))
        return
    if not result["sessions"]:
        print("No results found.")
        return
    for s in result["sessions"]:
        when = (s.get("started_at") or "")[:10]
        flag = " (outside the time window)" if s.get("outside_time_window") else ""
        print(f"=== {s.get('title') or s.get('first_message') or '(untitled)'} [{s['session_id']}] {when} "
              f"{s.get('kind')} relevance={s.get('relevance')}{flag}")
        if s.get("summary"):
            print(f"    {s['summary']}")
        if s.get("note"):
            print(f"    ({s['note']})")
        for h in s["hits"]:
            print(f"--- turn {h['turn']} {h['role']} ({h['match']}) {(h.get('date') or '')[:16]}")
            print(h["text"])
        if s.get("saved_in_memory"):
            print("    saved in memory:", ", ".join(f["file"] for f in s["saved_in_memory"]))
        print()
    for f in result.get("memory", []):
        print(f"[memory] {f['file']} — {f.get('title')}")


def print_memory(result, as_json=False):
    if as_json:
        print(json.dumps(result, indent=2, ensure_ascii=False))
        return
    if not result["files"]:
        print("No results found.")
        return
    for f in result["files"]:
        print(f"=== {f['file']} — {f.get('title')} relevance={f.get('relevance')}")
        for sec in f["sections"]:
            print(f"--- lines {sec['lines']} {sec.get('heading') or ''}")
            print(sec["text"])
        if f.get("from_conversations"):
            print("    written from:", ", ".join(c["session_id"] for c in f["from_conversations"]))
        print()


def main():
    parser = argparse.ArgumentParser(description="Search memory notes or past conversations")
    parser.add_argument("query", nargs="+")
    parser.add_argument("--collection", default="memory", choices=["memory", "history"])
    parser.add_argument("--n", type=int, default=5)
    parser.add_argument("--also", action="append", default=[])
    parser.add_argument("--json", action="store_true")
    parser.add_argument("--folder", default=None)
    parser.add_argument("--when", default=None)
    parser.add_argument("--window", nargs=2, default=None, metavar=("AFTER", "BEFORE"))
    parser.add_argument("--after", default=None)
    parser.add_argument("--before", default=None)
    parser.add_argument("--kind", default=None, choices=["orchestrator", "agent", "claude", "qwen", "gemini"])
    parser.add_argument("--exclude", action="append", default=[])
    parser.add_argument("--session", default=None)
    parser.add_argument("--rerank", action="store_true")
    args = parser.parse_args()
    query = " ".join(args.query)

    if args.collection == "memory":
        print_memory(search_memory(query, n_files=args.n, folder=args.folder, queries=args.also), as_json=args.json)
        return

    window = tuple(args.window) if args.window else None
    if args.when:
        from datetime import date

        from utils.timewords import resolve_when
        w = resolve_when(args.when, date.today())
        if w is None:
            print(f"Error: could not understand --when {args.when!r}", file=sys.stderr)
            sys.exit(1)
        window = w.iso()
    result = search_history(
        query, n_sessions=args.n, exclude=args.exclude, after=args.after, before=args.before,
        session_id=args.session, kind=args.kind, queries=args.also, window=window, rerank=args.rerank,
    )
    print_history(result, as_json=args.json)


if __name__ == "__main__":
    main()
