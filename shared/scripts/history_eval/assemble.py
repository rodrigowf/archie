"""Merge the writers' batch files (raw/batch_*.jsonl) and negatives (raw/negatives*.jsonl) into
questions.jsonl, tagging each question with the frozen dev/test split of its session.

Validates every line: known session, allowed type/lang, non-empty utterance. Negatives (no
session_id) are split alternately so both splits get some.

Usage: context/scripts/run.sh shared/scripts/history_eval/assemble.py
"""
from __future__ import annotations

import collections
import json

from common import EVAL_DIR, QUESTIONS_FILE, load_sessions

TYPES = {"topic", "detail", "resume", "temporal", "summary", "negative"}


def main() -> None:
    split_of = {s["session_id"]: s["split"] for s in load_sessions()["sessions"]}
    out, skips, problems = [], [], []
    for path in sorted((EVAL_DIR / "raw").glob("batch_*.jsonl")):
        for n, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            if not line.strip():
                continue
            q = json.loads(line)
            sid = q.get("session_id")
            if sid not in split_of:
                problems.append(f"{path.name}:{n} unknown session {sid}")
                continue
            if "skip" in q:
                skips.append((sid, q["skip"]))
                continue
            if q.get("type") not in TYPES or not (q.get("utterance") or "").strip():
                problems.append(f"{path.name}:{n} bad type/utterance")
                continue
            q["split"] = split_of[sid]
            q["lang"] = q.get("lang") or "en"
            out.append(q)
    neg_i = 0
    for path in sorted((EVAL_DIR / "raw").glob("negatives*.jsonl")):
        for line in path.read_text(encoding="utf-8").splitlines():
            if line.strip():
                q = json.loads(line)
                q.update(type="negative", session_id=None, split="dev" if neg_i % 2 == 0 else "test")
                q["lang"] = q.get("lang") or "en"
                out.append(q)
                neg_i += 1
    per_session = collections.Counter(q["session_id"] for q in out if q["session_id"])
    odd = [s for s, c in per_session.items() if c != 2]
    for i, q in enumerate(out, 1):
        q["id"] = f"q{i:03d}"
    QUESTIONS_FILE.write_text("".join(json.dumps(q, ensure_ascii=False) + "\n" for q in out), encoding="utf-8")
    c = collections.Counter((q["split"], q["type"]) for q in out)
    print(f"{len(out)} questions, {len(skips)} skipped sessions, {len(problems)} problems")
    print(" ", dict(sorted(c.items())))
    print("  lang:", dict(collections.Counter(q["lang"] for q in out)))
    if odd:
        print("  sessions without exactly 2 questions:", odd)
    for p in problems:
        print("  PROBLEM", p)
    for s, why in skips:
        print(f"  skip {s[:8]}: {why}")


if __name__ == "__main__":
    main()
