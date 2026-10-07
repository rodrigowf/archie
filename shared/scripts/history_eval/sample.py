"""Pick the sessions the eval questions are written about, and split them dev/test once.

Eligible: at least 6 turns and 150 words said by the user (enough to recall something
specific). Copies of one conversation (sharing half their message windows) form a group: only
one member is sampled, and any member counts as a correct answer. The split is random with a
fixed seed and never changes afterwards; re-running refuses to overwrite it.

Usage: context/scripts/run.sh shared/scripts/history_eval/sample.py [--n 110] [--force]
"""
from __future__ import annotations

import argparse
import collections
import json
import random

from common import EVAL_DIR, SESSIONS_FILE
from utils import history_index as hi


def copy_groups(conn) -> dict[str, list[str]]:
    hashes: dict[str, set[str]] = collections.defaultdict(set)
    for sid, h in conn.execute("SELECT session_id, text_hash FROM chunks"):
        hashes[sid].add(h)
    ids = sorted(hashes)
    copies: dict[str, set[str]] = collections.defaultdict(set)
    for i, a in enumerate(ids):
        for b in ids[i + 1:]:
            small = min(len(hashes[a]), len(hashes[b]))
            if small >= 5 and len(hashes[a] & hashes[b]) >= 0.5 * small:
                copies[a].add(b)
                copies[b].add(a)
    # transitive closure (a~b, b~c → a~c)
    changed = True
    while changed:
        changed = False
        for a in list(copies):
            for b in list(copies[a]):
                new = copies[b] - copies[a] - {a}
                if new:
                    copies[a] |= new
                    changed = True
    return {k: sorted(v) for k, v in copies.items()}


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--n", type=int, default=110)
    ap.add_argument("--dev-share", type=float, default=0.6)
    ap.add_argument("--seed", type=int, default=20261006)
    ap.add_argument("--force", action="store_true")
    args = ap.parse_args()
    if SESSIONS_FILE.exists() and not args.force:
        raise SystemExit(f"{SESSIONS_FILE} exists; the split is frozen (pass --force to redo it)")

    conn = hi.connect(hi.get_history_db_path(), readonly=True)
    user_words: dict[str, int] = collections.Counter()
    for sid, text in conn.execute("SELECT session_id, text FROM chunks WHERE role='user' AND part=0"):
        user_words[sid] += len(text.split())
    rows = conn.execute("SELECT id, harness, n_turns, started_at FROM sessions ORDER BY id").fetchall()
    copies = copy_groups(conn)

    eligible = [r for r in rows if r[2] >= 6 and user_words[r[0]] >= 150]
    rng = random.Random(args.seed)
    rng.shuffle(eligible)
    picked, taken = [], set()
    for sid, harness, n_turns, started in eligible:
        if sid in taken:
            continue
        picked.append({"session_id": sid, "kind": harness, "turns": n_turns, "started_at": started})
        taken.add(sid)
        taken.update(copies.get(sid, []))
        if len(picked) >= args.n:
            break
    n_dev = round(len(picked) * args.dev_share)
    for i, p in enumerate(picked):
        p["split"] = "dev" if i < n_dev else "test"

    EVAL_DIR.mkdir(parents=True, exist_ok=True)
    SESSIONS_FILE.write_text(json.dumps(
        {"seed": args.seed, "eligible": len(eligible), "sessions": picked, "copies": copies}, indent=1
    ))
    counts = collections.Counter((p["split"], p["kind"]) for p in picked)
    print(f"{len(eligible)} eligible, {len(picked)} picked: {dict(counts)}")


if __name__ == "__main__":
    main()
