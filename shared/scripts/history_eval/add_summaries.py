"""Re-index a history index file with the session summaries (index/session_summaries.sqlite3).

Only sessions whose summary is new or changed are re-indexed, and only their summary chunk is
embedded (everything else reuses stored embeddings), with the index file's own model.

Usage: context/scripts/run.sh shared/scripts/history_eval/add_summaries.py --db index/history_pml12.sqlite3
"""
from __future__ import annotations

import argparse
import json

import common  # noqa: F401
from utils import history_index as hi
from utils import session_summaries as ss


def load_all_summaries() -> dict[str, dict]:
    conn = ss.connect()
    try:
        return {sid: json.loads(data) for sid, data in conn.execute("SELECT session_id, data FROM summaries")}
    finally:
        conn.close()


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", required=True)
    ap.add_argument("--context-mode", default=None, choices=["none", "title", "title+prev"])
    ap.add_argument("--force", action="store_true", help="re-derive every session (e.g. after a context-mode change)")
    args = ap.parse_args()
    if args.context_mode:
        hi.CONTEXT_MODE = args.context_mode
    from sentence_transformers import SentenceTransformer

    conn = hi.connect(args.db)
    if args.force:
        with conn:
            conn.execute("UPDATE sessions SET chunker_version = 0")
    model = SentenceTransformer(hi.index_model(conn))
    if model.max_seq_length < 256:
        model.max_seq_length = 256
    stats = hi.index_all(conn, hi.session_sources(), lambda t: [v.tolist() for v in model.encode(t)],
                         summaries=load_all_summaries(), log=lambda m: None)
    print(f"{args.db}: {stats.indexed} re-indexed, {stats.embedded_chunks} chunks embedded, {len(stats.failed)} failed")


if __name__ == "__main__":
    main()
