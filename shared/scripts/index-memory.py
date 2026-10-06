#!/usr/bin/env python3
"""
Usage: context/scripts/index-memory.py [options]
Description: Index memory and conversation history for search.

This indexes from the context/ directory:
  - context/memory/**/*.md -> chroma 'memory' collection (via embed.py)
  - context/*.jsonl + context/chats/*.jsonl (every harness) -> index/history.sqlite3
    (keyword + semantic; see backend/utils/history_index.py)

Options:
    --memory-only    Only re-index memory files
    --history-only   Only re-index conversation history
    --reset          Clear the memory collection / history index before indexing
    --local-model    History: embed with an in-process model instead of the warm search-server
                     (for a bulk build on the laptop while no backend is running)

Exit status is 1 when any history session failed to index (the others still are).

Examples:
    context/scripts/index-memory.py
    context/scripts/index-memory.py --memory-only
    context/scripts/index-memory.py --history-only --local-model
"""
import argparse
import subprocess
import sys
from pathlib import Path

# Add project root to path for utils import
# Resolve the file first (follows symlinks), then get parent directory
SCRIPT_DIR = Path(__file__).resolve().parent
PROJECT_DIR = SCRIPT_DIR.parent.parent  # shared/scripts/ → repo root
sys.path.insert(0, str(PROJECT_DIR / "backend"))  # backend packages (utils, …)

from utils.paths import get_memory_dir, get_sessions_dir, get_index_dir

EMBED_SCRIPT = SCRIPT_DIR / "embed.py"


def run_embed(command: str, *args) -> bool:
    """Run embed.py with given arguments."""
    cmd = [sys.executable, str(EMBED_SCRIPT), command, *args]
    result = subprocess.run(cmd, capture_output=False)
    return result.returncode == 0


def index_memory(reset: bool = False) -> None:
    """Index memory files from context/memory/."""
    memory_dir = get_memory_dir()

    if not memory_dir.exists():
        print(f"Memory directory not found: {memory_dir}")
        print("(This is normal if no memory files exist yet)")
        return

    md_files = list(memory_dir.rglob("*.md"))
    if not md_files:
        print("No memory files found, skipping")
        return

    print(f"=== Indexing {len(md_files)} memory files (recursive) ===")
    if reset:
        run_embed("reset", "--collection", "memory")

    run_embed("index", str(memory_dir), "--collection", "memory", "--prune")


def index_history(reset: bool = False, local_model: bool = False) -> bool:
    """Index every conversation JSONL (all harnesses) into index/history.sqlite3.

    Incremental and per-session: unchanged sessions are skipped, an appended session only embeds
    its new messages, and one failing session is logged without stopping the rest. Returns False
    if any session failed (so the caller logs it), True otherwise.
    """
    from utils import history_index as hi

    lock = hi.IndexLock()
    if not lock.acquire():
        print("History indexer already running; skipping this run")
        return True
    try:
        db_path = hi.get_history_db_path()
        if reset and db_path.exists():
            for suffix in ("", "-wal", "-shm"):
                Path(str(db_path) + suffix).unlink(missing_ok=True)
        conn = hi.connect(db_path)
        sources = hi.session_sources()
        print(f"=== Indexing history: {len(sources)} session files -> {db_path} ===")

        if local_model:
            model = None

            def encode(texts):  # load the model only if some session actually changed
                nonlocal model
                if model is None:
                    from sentence_transformers import SentenceTransformer
                    model = SentenceTransformer(hi.MODEL_NAME)
                return [v.tolist() for v in model.encode(texts, batch_size=hi.ENCODE_BATCH)]

            stats = hi.index_all(conn, sources, encode)
        else:
            sys.path.insert(0, str(SCRIPT_DIR))
            import index_client
            with index_client.IndexFacade() as facade:
                print(f"[history] encoder mode={facade.mode}")
                stats = hi.index_all(conn, sources, facade.encode_many)

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


def main():
    parser = argparse.ArgumentParser(description="Index memory and history")
    parser.add_argument("--memory-only", action="store_true", help="Only index memory/")
    parser.add_argument("--history-only", action="store_true", help="Only index history/")
    parser.add_argument("--reset", action="store_true", help="Clear collections first")
    parser.add_argument("--local-model", action="store_true", help="History: embed in-process")
    args = parser.parse_args()

    do_memory = not args.history_only
    do_history = not args.memory_only

    memory_dir = get_memory_dir()
    sessions_dir = get_sessions_dir()
    index_dir = get_index_dir()

    print(f"Memory dir:   {memory_dir}")
    print(f"Sessions dir: {sessions_dir}")
    print(f"Index dir:    {index_dir}\n")

    if do_memory:
        index_memory(reset=args.reset)

    ok = True
    if do_history:
        ok = index_history(reset=args.reset, local_model=args.local_model)

    if do_memory:
        print("\n=== Stats ===")
        run_embed("stats", "--collection", "memory")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
