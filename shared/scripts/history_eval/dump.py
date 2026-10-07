"""Print one session as a readable transcript, for the people (or models) writing eval questions.

The title is left out on purpose: questions must come from what was said, not from a label.
Sessions over --max-chars are shown as windows of consecutive turns spread evenly over the
whole conversation; each turn is capped at --turn-chars.

Usage: context/scripts/run.sh shared/scripts/history_eval/dump.py <session_id> [--max-chars 45000]
"""
from __future__ import annotations

import argparse

import common  # noqa: F401  (sets sys.path)
from utils import history_index as hi


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("session_id")
    ap.add_argument("--max-chars", type=int, default=45000)
    ap.add_argument("--turn-chars", type=int, default=1200)
    args = ap.parse_args()
    path = hi.find_session_file(args.session_id)
    if path is None:
        raise SystemExit(f"no session {args.session_id}")
    doc = hi.extract_session(path)
    print(f"# session {doc.session_id}  kind={doc.harness}  from {doc.started_at} to {doc.ended_at}  turns={len(doc.turns)}\n")
    blocks = []
    for i, t in enumerate(doc.turns):
        text = t.text if len(t.text) <= args.turn_chars else t.text[: args.turn_chars] + " […]"
        blocks.append(f"[{i}] {t.role.upper()} ({(t.ts or '')[:16]}):\n{text}\n")
    total = sum(len(b) for b in blocks)
    if total <= args.max_chars:
        print("\n".join(blocks))
        return
    # Too long: windows of consecutive turns spread evenly over the whole session, so questions
    # aren't all about how the conversation started.
    window = 6
    n_windows = max(1, int(args.max_chars / (total / len(blocks) * window)))
    starts = sorted({round(k * (len(blocks) - window) / max(1, n_windows - 1)) for k in range(n_windows)})
    used, last_end = 0, 0
    for s0 in starts:
        s0 = max(s0, last_end)
        chunk = blocks[s0 : s0 + window]
        if not chunk or used + sum(len(b) for b in chunk) > args.max_chars:
            break
        if s0 > last_end:
            print(f"[… turns {last_end}–{s0 - 1} not shown]\n")
        print("\n".join(chunk))
        used += sum(len(b) for b in chunk)
        last_end = s0 + len(chunk)
    if last_end < len(blocks):
        print(f"[… turns {last_end}–{len(blocks) - 1} not shown]")

if __name__ == "__main__":
    main()
