"""Build a copy of the history index embedded with another model, for A/B evaluation.

Copies sessions + chunk texts from a source index unchanged and re-embeds every chunk with
--model (plus the model's passage prefix), recording the model in meta.model so searches use
the matching query encoder. Nothing else changes, so a comparison isolates the model.

Usage: context/scripts/run.sh shared/scripts/history_eval/build_variant.py --model intfloat/multilingual-e5-small --out index/history_e5.sqlite3 [--src index/history_dev.sqlite3]
"""
from __future__ import annotations

import argparse
import shutil
import time

import common  # noqa: F401
from utils import history_index as hi


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--src", default="index/history_dev.sqlite3")
    ap.add_argument("--max-seq", type=int, default=256)
    args = ap.parse_args()
    from sentence_transformers import SentenceTransformer

    src = hi.connect(args.src)
    src.execute("PRAGMA wal_checkpoint(TRUNCATE)")
    src.close()
    shutil.copyfile(args.src, args.out)
    conn = hi.connect(args.out)
    model = SentenceTransformer(args.model)
    if model.max_seq_length < args.max_seq:  # paraphrase-multilingual defaults to 128 tokens
        model.max_seq_length = args.max_seq
    prefix = hi.passage_prefix(args.model)
    rows = conn.execute("SELECT id, text FROM chunks").fetchall()
    t0 = time.time()
    vecs = model.encode([prefix + t for _, t in rows], batch_size=32, show_progress_bar=False)
    with conn:
        conn.executemany("UPDATE chunks SET embedding=? WHERE id=?", [(hi._to_blob(v), cid) for (cid, _), v in zip(rows, vecs)])
        conn.execute("UPDATE meta SET value=? WHERE key='model'", (args.model,))
        hi._bump_generation(conn)
    print(f"{args.model}: {len(rows)} chunks in {time.time() - t0:.0f}s -> {args.out}")


if __name__ == "__main__":
    main()
