#!/usr/bin/env python3
"""
Usage: context/scripts/index-memory.py [options]
Description: Index memory and conversation history for search.

  - context/memory/**/*.md                          -> index/memory.sqlite3  (utils/memory_index.py)
  - context/*.jsonl + context/chats/*.jsonl (every harness) -> index/history.sqlite3 (utils/history_index.py)
    preceded by session summaries for new/grown sessions -> index/session_summaries.sqlite3
    (utils/session_summaries.py; needs OPENAI_API_KEY, skipped without it)

Both are incremental: unchanged files are skipped, changed ones re-derived with only their new
chunks embedded, deleted ones removed; one failing file never blocks the others. Embeddings use
each index's own model (meta.model), borrowed from the warm search server when it runs.

Options:
    --memory-only    Only index memory
    --history-only   Only index conversation history
    --reset          Rebuild the selected index files from scratch
    --no-summaries   History: skip generating session summaries this run
    --local-model    Embed in-process even if a warm server is running

Exit status is 1 when any file failed (the others are still indexed).

Examples:
    context/scripts/run.sh context/scripts/index-memory.py
    context/scripts/run.sh context/scripts/index-memory.py --history-only --local-model
"""
import argparse
import json
import sys
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
PROJECT_DIR = SCRIPT_DIR.parent.parent  # shared/scripts/ → repo root
sys.path.insert(0, str(PROJECT_DIR / "backend"))  # backend packages (utils, …)
sys.path.insert(0, str(SCRIPT_DIR))

from utils import history_index as hi  # noqa: E402


def _remove_db(path: Path) -> None:
    for suffix in ("", "-wal", "-shm"):
        Path(str(path) + suffix).unlink(missing_ok=True)


def _encoder(model: str, local: bool):
    import index_client

    return index_client.Encoder(model, use_server=not local)


def index_history(reset: bool = False, local_model: bool = False, summaries: bool = True) -> bool:
    """Index every conversation JSONL (all harnesses) into index/history.sqlite3. Returns False
    if any session failed."""
    from utils import session_summaries as ss

    lock = hi.IndexLock()
    if not lock.acquire():
        print("History indexer already running; skipping this run")
        return True
    try:
        db_path = hi.get_history_db_path()
        if reset:
            _remove_db(db_path)
        conn = hi.connect(db_path)
        sources = hi.session_sources()
        print(f"=== Indexing history: {len(sources)} session files -> {db_path} ===")

        sconn = ss.connect()
        if summaries:
            ss.summarize_pending(sconn, sources)
        all_summaries = {sid: json.loads(d) for sid, d in sconn.execute("SELECT session_id, data FROM summaries")}
        sconn.close()

        with _encoder(hi.index_model(conn), local_model) as enc:
            print(f"[history] model={hi.index_model(conn)} encoder={enc.mode}")
            stats = hi.index_all(conn, sources, enc.encode_many, summaries=all_summaries)

        total = conn.execute("SELECT COUNT(*) FROM chunks").fetchone()[0]
        n_sessions = conn.execute("SELECT COUNT(*) FROM sessions").fetchone()[0]
        conn.close()
        print(
            f"History: {stats.indexed} indexed, {stats.unchanged} unchanged, {stats.removed} removed, "
            f"{stats.embedded_chunks} chunks embedded; {n_sessions} sessions / {total} chunks in index"
        )
        for name, err in stats.failed:
            print(f"FAILED {name}: {err}", file=sys.stderr)
        return not stats.failed
    finally:
        lock.release()


def index_memory(reset: bool = False, local_model: bool = False) -> bool:
    """Index the memory wiki into index/memory.sqlite3. Returns False if any file failed."""
    from utils import memory_index as mi

    root = mi.get_memory_dir()
    if not root.exists():
        print(f"Memory directory not found: {root}")
        return True
    lock = hi.IndexLock(mi.get_memory_db_path())
    if not lock.acquire():
        print("Memory indexer already running; skipping this run")
        return True
    try:
        db_path = mi.get_memory_db_path()
        if reset:
            _remove_db(db_path)
        conn = mi.connect(db_path)
        with _encoder(hi.index_model(conn), local_model) as enc:
            print(f"=== Indexing memory: {root} -> {db_path} (model={hi.index_model(conn)}, encoder={enc.mode}) ===")
            stats = mi.index_all(conn, enc.encode_many, root=root)
        n_files, n_chunks = conn.execute("SELECT COUNT(*), COALESCE(SUM(n_chunks), 0) FROM files").fetchone()
        conn.close()
        print(
            f"Memory: {stats.indexed} indexed, {stats.unchanged} unchanged, {stats.removed} removed, "
            f"{stats.embedded_chunks} chunks embedded; {n_files} files / {n_chunks} chunks in index"
        )
        for name, err in stats.failed:
            print(f"FAILED {name}: {err}", file=sys.stderr)
        return not stats.failed
    finally:
        lock.release()


def main():
    parser = argparse.ArgumentParser(description="Index memory and conversation history")
    parser.add_argument("--memory-only", action="store_true")
    parser.add_argument("--history-only", action="store_true")
    parser.add_argument("--reset", action="store_true")
    parser.add_argument("--no-summaries", action="store_true")
    parser.add_argument("--local-model", action="store_true")
    args = parser.parse_args()

    ok = True
    if not args.history_only:
        ok = index_memory(reset=args.reset, local_model=args.local_model) and ok
    if not args.memory_only:
        ok = index_history(reset=args.reset, local_model=args.local_model, summaries=not args.no_summaries) and ok
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
