#!/usr/bin/env python3
"""
Usage: context/scripts/search.py <query> [options]
Description: Search memory files or past conversations.

Options:
    --collection NAME    memory (default) or history
    --n N                Number of results (default: 5); for history, sessions
    --threshold FLOAT    memory: max distance threshold (default: 1.5)
    --file PATTERN       memory: filter by file path (substring match)
    --after DATE         history: only messages on/after YYYY-MM-DD
    --before DATE        history: only messages before YYYY-MM-DD
    --exclude ID         history: leave out a session (repeatable)
    --session ID         history: search inside one session only
    --json               Output as JSON for programmatic use

History search is hybrid keyword + semantic over index/history.sqlite3 (every harness's
transcripts), grouped by session; see backend/utils/history_index.py. It uses the warm
search-server's model when one is running, else loads the model in-process.

Examples:
    context/scripts/search.py "architecture decisions"
    context/scripts/search.py "how to create skills" --n 10
    context/scripts/search.py "Shroud of Turin" --collection history
    context/scripts/search.py "wake word" --collection history --after 2026-09-01
    context/scripts/search.py "session management" --file memory/ --json
"""
import argparse
import json
import sys
from pathlib import Path

# Add project root to path for utils import
SCRIPT_DIR = Path(__file__).resolve().parent
PROJECT_DIR = SCRIPT_DIR.parent.parent  # shared/scripts/ → repo root
sys.path.insert(0, str(PROJECT_DIR / "backend"))  # backend packages (utils, …)

from utils.paths import get_index_dir

INDEX_DIR = get_index_dir() / "chroma"


def search(query, collection_name="memory", n_results=5, threshold=1.5, file_filter=None):
    """Search the vector index and return results with metadata."""
    import chromadb
    from sentence_transformers import SentenceTransformer

    if not INDEX_DIR.exists():
        print("Error: No index found. Run 'context/scripts/embed.py index <path>' first.", file=sys.stderr)
        sys.exit(1)

    client = chromadb.PersistentClient(path=str(INDEX_DIR))

    try:
        collection = client.get_collection(collection_name)
    except Exception:
        print(f"Error: Collection '{collection_name}' not found.", file=sys.stderr)
        sys.exit(1)

    if collection.count() == 0:
        print(f"Collection '{collection_name}' is empty.", file=sys.stderr)
        sys.exit(1)

    model = SentenceTransformer("all-MiniLM-L6-v2")
    query_embedding = model.encode([query])[0].tolist()

    # Build query kwargs
    query_kwargs = {
        "query_embeddings": [query_embedding],
        "n_results": min(n_results, collection.count()),
    }

    results = collection.query(**query_kwargs)

    # Format results with post-query filtering
    formatted = []
    for i, doc in enumerate(results["documents"][0]):
        meta = results["metadatas"][0][i]
        distance = results["distances"][0][i]

        if distance > threshold:
            continue

        if file_filter and file_filter not in meta.get("file_path", ""):
            continue

        formatted.append({
            "text": doc,
            "file_path": meta["file_path"],
            "start_line": int(meta["start_line"]),
            "end_line": int(meta["end_line"]),
            "file_name": meta.get("file_name", ""),
            "distance": round(distance, 4),
        })

    return formatted


def search_history(query, n_sessions=5, exclude=(), after=None, before=None, session_id=None):
    """Hybrid search over past conversations. Returns {"sessions": [...], "total_sessions": n}."""
    from utils import history_index

    request = {
        "command": "history_search", "query": query, "max_sessions": n_sessions,
        "exclude_sessions": list(exclude), "after": after, "before": before, "session_id": session_id,
    }
    sys.path.insert(0, str(SCRIPT_DIR))
    import index_client

    client = index_client.try_connect(timeout=5)
    if client is not None:
        try:
            reply = client.call(request, request_timeout=120)
        finally:
            client.close()
        if reply.get("error"):
            print(f"Error: {reply['error']}", file=sys.stderr)
            sys.exit(1)
        reply.pop("error", None)
        return reply

    from sentence_transformers import SentenceTransformer

    model = SentenceTransformer(history_index.MODEL_NAME)
    try:
        return history_index.HistorySearcher().search(
            query, model.encode([query])[0].tolist(), max_sessions=n_sessions,
            exclude_sessions=exclude, after=after, before=before, session_id=session_id,
        )
    except FileNotFoundError as e:
        print(f"Error: {e}. Run index-memory.py --history-only.", file=sys.stderr)
        sys.exit(1)


def print_history(result, as_json=False):
    if as_json:
        print(json.dumps(result, indent=2, ensure_ascii=False))
        return
    if not result["sessions"]:
        print("No results found.")
        return
    for s in result["sessions"]:
        when = (s.get("started_at") or "")[:10]
        print(f"=== {s.get('title') or '(untitled)'} [{s['session_id']}] {when} {s.get('kind')} "
              f"relevance={s.get('relevance')}")
        if s.get("note"):
            print(f"    ({s['note']})")
        for h in s["hits"]:
            print(f"--- turn {h['turn']} {h['role']} ({h['match']}) {(h.get('date') or '')[:16]}")
            print(h["text"])
        print()


def print_results(results, as_json=False):
    """Display search results."""
    if not results:
        print("No results found.")
        return

    if as_json:
        print(json.dumps(results, indent=2))
        return

    for i, r in enumerate(results, 1):
        print(f"--- Result {i} (distance: {r['distance']}) ---")
        print(f"File: {r['file_path']}:{r['start_line']}-{r['end_line']}")
        print(r["text"])
        print()


def main():
    parser = argparse.ArgumentParser(description="Search the vector index")
    parser.add_argument("query", nargs="+", help="Search query")
    parser.add_argument("--collection", default="memory", help="Collection name (default: memory)")
    parser.add_argument("--n", type=int, default=5, help="Number of results (default: 5)")
    parser.add_argument("--threshold", type=float, default=1.5, help="Max distance (default: 1.5)")
    parser.add_argument("--file", default=None, help="Filter by file path substring")
    parser.add_argument("--after", default=None, help="History: only on/after YYYY-MM-DD")
    parser.add_argument("--before", default=None, help="History: only before YYYY-MM-DD")
    parser.add_argument("--exclude", action="append", default=[], help="History: skip a session id")
    parser.add_argument("--session", default=None, help="History: search one session only")
    parser.add_argument("--json", action="store_true", help="Output as JSON")

    args = parser.parse_args()
    query_text = " ".join(args.query)

    if args.collection == "history":
        result = search_history(
            query_text, n_sessions=args.n, exclude=args.exclude,
            after=args.after, before=args.before, session_id=args.session,
        )
        print_history(result, as_json=args.json)
        return

    results = search(
        query_text,
        collection_name=args.collection,
        n_results=args.n,
        threshold=args.threshold,
        file_filter=args.file,
    )

    print_results(results, as_json=args.json)


if __name__ == "__main__":
    main()
