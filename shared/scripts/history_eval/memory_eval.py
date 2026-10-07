"""Memory retrieval eval: does the right memory file come back for a spoken question?

Questions: context/evals/history_search/memory/batch_*.jsonl (gold = one file); split by file
in memory/files.json. Near-duplicate files (sharing half their chunks, plus the pairs the
writers flagged) count as correct for each other.

Searches utils/memory_index (--db picks an index file). The old chroma baseline was recorded
in docs/history-search/RESULTS.md before chroma was retired.

Usage: context/scripts/run.sh shared/scripts/history_eval/memory_eval.py --split dev --name x [--db index/memory_x.sqlite3]
"""
from __future__ import annotations

import argparse
import collections
import json
from pathlib import Path

from common import EVAL_DIR, RUNS_DIR
from utils import history_index as hi
from utils import memory_index as mi

MEM_DIR = EVAL_DIR / "memory"
FLAGGED_PAIRS = [
    ("projects/gender-vid1/final_script.md", "projects/gender-vid1/final_script_v3.md"),
    ("projects/gender-vid1/recording_plan.md", "projects/identity-vid1/recording_plan.md"),
]


def load(split: str) -> list[dict]:
    splits = json.loads((MEM_DIR / "files.json").read_text())
    out = []
    for f in sorted(MEM_DIR.glob("batch_*.jsonl")):
        for line in f.read_text().splitlines():
            if line.strip():
                q = json.loads(line)
                if "utterance" in q and (split == "all" or splits.get(q["file"]) == split):
                    out.append(q)
    for i, q in enumerate(out):
        q["id"] = f"m{i:03d}"
    return out


def equivalents(conn) -> dict[str, set[str]]:
    hashes = collections.defaultdict(set)
    for path, h in conn.execute("SELECT path, text_hash FROM chunks"):
        hashes[path].add(h)
    eq = collections.defaultdict(set)
    paths = sorted(hashes)
    for i, a in enumerate(paths):
        for b in paths[i + 1:]:
            small = min(len(hashes[a]), len(hashes[b]))
            # same text under another title has a different context prefix, so compare bodies too
            if small and len(hashes[a] & hashes[b]) >= 0.5 * small:
                eq[a].add(b)
                eq[b].add(a)
    for a, b in FLAGGED_PAIRS:
        eq[a].add(b)
        eq[b].add(a)
    return eq


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--split", default="dev", choices=["dev", "test", "all"])
    ap.add_argument("--name", required=True)
    ap.add_argument("--db", default=None)
    args = ap.parse_args()
    qs = load(args.split)
    db = Path(args.db) if args.db else mi.get_memory_db_path()
    conn = mi.connect(db, readonly=True)
    eq = equivalents(conn)
    model_name = hi.index_model(conn)
    conn.close()

    from retrieval import encoder
    encode = encoder(model_name)
    searcher = mi.MemorySearcher(db)

    def ranked(q):
        return [f["file"].removeprefix("context/memory/") for f in searcher.search(q, encode(q), max_files=10)["files"]], None

    rows = []
    for q in qs:
        files, _ = ranked(q["utterance"])
        ok = {q["file"], *eq.get(q["file"], set())}
        rank = next((i + 1 for i, f in enumerate(files) if f in ok), None)
        rows.append({"id": q["id"], "lang": q["lang"], "type": q["type"], "rank": rank, "top": files[:3]})

    def block(rs):
        n = len(rs)
        return {"n": n, "hit1": sum(r["rank"] == 1 for r in rs) / n, "hit5": sum(bool(r["rank"] and r["rank"] <= 5) for r in rs) / n,
                "mrr10": sum(1 / r["rank"] for r in rs if r["rank"]) / n}

    summary = {"all": block(rows)}
    for lang in ("en", "pt"):
        summary[f"lang={lang}"] = block([r for r in rows if r["lang"] == lang])
    RUNS_DIR.mkdir(parents=True, exist_ok=True)
    out = RUNS_DIR / f"memory_{args.split}_{args.name}.json"
    out.write_text(json.dumps({"summary": summary, "rows": rows}, indent=1))
    for k, m in summary.items():
        print(f"{k:<10} n={m['n']:<3} hit1={m['hit1']:.2f} hit5={m['hit5']:.2f} mrr10={m['mrr10']:.2f}")


if __name__ == "__main__":
    main()
