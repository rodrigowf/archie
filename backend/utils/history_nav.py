"""Navigating past conversations: list them, grep inside one, read any part of one.

Companion of history_index.py (which finds candidates by keyword + meaning). These functions
give an agent precise access once it knows roughly where to look:

- ``list_conversations``: browse by time window, kind, or words in the title / first message,
  newest first, paged — for "what did we do yesterday?" questions that have no topic words.
- ``grep_conversation``: exact words (accent- and case-insensitive) or a regex inside one
  session → the turn numbers and snippets where they occur.
- ``read_conversation``: a window around a turn, an explicit turn range, or one long turn paged
  by characters, so nothing in a transcript is out of reach.

Every session entry says how to get into it (``open``): agent sessions (Claude, Qwen, Gemini)
can be resumed with ``open_agent_session(resume_sdk_id=…)``; orchestrator sessions can only be
read.
"""
from __future__ import annotations

import re
import sqlite3
import unicodedata
from pathlib import Path

from utils import history_index as hi

MAX_RANGE_TURNS = 30
MAX_TURN_CHARS = 2000
MAX_PAGE_CHARS = 8000
SNIPPET_RADIUS = 140


def fold(text: str) -> str:
    """Lowercase without accents, same length as the input for Latin text."""
    text = unicodedata.normalize("NFKD", text.lower())
    return "".join(c for c in text if not unicodedata.combining(c))


def open_hint(kind: str | None, session_id: str) -> dict:
    if kind == "orchestrator":
        return {
            "can_resume": False,
            "how": f"read_conversation(session_id='{session_id}') — orchestrator conversations can be read, not resumed",
        }
    return {
        "can_resume": True,
        "how": f"open_agent_session(resume_sdk_id='{session_id}') to continue it, or read_conversation(session_id='{session_id}') to read it",
    }


def session_entry(row: sqlite3.Row | dict, titles: dict[str, str]) -> dict:
    """Public description of one indexed session (shared by search results and listings)."""
    r = dict(row)
    sid = r["id"]
    title = titles.get(sid) or r.get("auto_title")
    entry = {
        "session_id": sid,
        "title": title or None,
        "kind": r.get("harness"),
        "started_at": r.get("started_at"),
        "last_activity": r.get("ended_at"),
        "turns": r.get("n_turns"),
    }
    if not title and r.get("first_prompt"):
        entry["first_message"] = r["first_prompt"]
    if r.get("cwd"):
        entry["cwd"] = r["cwd"]
    if r.get("summary"):
        entry["summary"] = r["summary"]
    entry["file"] = r.get("path")
    entry["open"] = open_hint(r.get("harness"), sid)
    return entry


def _session_rows(conn: sqlite3.Connection) -> list[dict]:
    conn.row_factory = sqlite3.Row
    try:
        return [dict(r) for r in conn.execute("SELECT * FROM sessions")]
    finally:
        conn.row_factory = None


def list_conversations(
    conn: sqlite3.Connection,
    *,
    after: str | None = None,
    before: str | None = None,
    kind: str | None = None,
    text: str | None = None,
    limit: int = 10,
    offset: int = 0,
    exclude: tuple[str, ...] = (),
    context_dir: Path | None = None,
) -> dict:
    """Sessions active in [after, before) (ISO dates), newest activity first.

    ``kind``: "orchestrator", "agent" (anything that can be resumed) or a harness name.
    ``text``: words that must all appear in the title or first message (accent-insensitive).
    Empty sessions (no turns) are left out.
    """
    titles = hi._load_titles(context_dir)
    words = fold(text or "").split()
    out = []
    for r in _session_rows(conn):
        if r["id"] in exclude or not r.get("n_turns"):
            continue
        start, end = r.get("started_at") or "", r.get("ended_at") or r.get("started_at") or ""
        if after and end and end < after:
            continue
        if before and start and start >= before:
            continue
        if kind:
            if kind == "agent" and r.get("harness") == "orchestrator":
                continue
            if kind not in ("agent",) and r.get("harness") != kind:
                continue
        if words:
            hay = fold(f"{titles.get(r['id'], '')} {r.get('auto_title') or ''} {r.get('first_prompt') or ''} {r.get('summary') or ''}")
            if not all(w in hay for w in words):
                continue
        out.append(r)
    out.sort(key=lambda r: r.get("ended_at") or "", reverse=True)
    page = out[offset : offset + max(1, min(limit, 30))]
    result = {
        "conversations": [session_entry(r, titles) for r in page],
        "total": len(out),
        "offset": offset,
    }
    if offset + len(page) < len(out):
        result["next_offset"] = offset + len(page)
    return result


def grep_conversation(
    session_id: str,
    pattern: str,
    *,
    regex: bool = False,
    max_hits: int = 10,
    context_dir: Path | None = None,
) -> dict:
    """Turns of one session containing ``pattern`` (accent/case-insensitive), with snippets."""
    path = hi.find_session_file(session_id, context_dir)
    if path is None:
        return {"error": f"No conversation file for session {session_id!r}."}
    if not pattern.strip():
        return {"error": "Empty pattern."}
    try:
        rx = re.compile(fold(pattern) if regex else re.escape(fold(pattern)))
    except re.error as e:
        return {"error": f"Invalid regex: {e}"}
    doc = hi.extract_session(path)
    hits, total = [], 0
    for i, t in enumerate(doc.turns):
        folded = fold(t.text)
        same = len(folded) == len(t.text)
        for m in rx.finditer(folded):
            total += 1
            if len(hits) >= max(1, min(max_hits, 50)):
                continue
            a, b = (m.start(), m.end()) if same else (0, 0)
            src = t.text if same else folded
            lo, hi_ = max(0, a - SNIPPET_RADIUS), min(len(src), b + SNIPPET_RADIUS)
            snippet = ("…" if lo else "") + src[lo:hi_].replace("\n", " ") + ("…" if hi_ < len(src) else "")
            hits.append({"turn": i, "role": t.role, "date": t.ts, "snippet": snippet})
    return {
        "session_id": session_id,
        "title": hi._load_titles(context_dir).get(session_id),
        "pattern": pattern,
        "total_matches": total,
        "hits": hits,
        "total_turns": len(doc.turns),
    }


def read_conversation(
    session_id: str,
    *,
    turn: int | None = None,
    before: int = 3,
    after: int = 6,
    start: int | None = None,
    end: int | None = None,
    char_offset: int = 0,
    context_dir: Path | None = None,
) -> dict:
    """Read part of a conversation.

    - ``start``/``end`` (inclusive): that range of turns (at most 30).
    - ``turn`` with ``before``/``after``: a window around it (default 3 before, 6 after).
    - a single turn (``start == end``, or ``turn`` with before=after=0): its full text, paged by
      ``char_offset`` in 8000-character pages, with ``next_char_offset`` while more remains.
    - nothing: from the beginning.
    Longer turns in multi-turn reads are cut at 2000 characters; the cut says how to read on.
    """
    path = hi.find_session_file(session_id, context_dir)
    if path is None:
        return {"error": f"No conversation file for session {session_id!r}."}
    doc = hi.extract_session(path)
    total = len(doc.turns)
    if total == 0:
        return {"session_id": session_id, "total_turns": 0, "turns": []}
    clamp = lambda n: max(0, min(int(n), total - 1))  # noqa: E731
    if start is not None or end is not None:
        a = clamp(start if start is not None else 0)
        b = clamp(end if end is not None else a + MAX_RANGE_TURNS - 1)
        a, b = min(a, b), max(a, b)
        b = min(b, a + MAX_RANGE_TURNS - 1)
    elif turn is not None:
        c = clamp(turn)
        a, b = clamp(c - max(0, min(before, 20))), clamp(c + max(0, min(after, MAX_RANGE_TURNS)))
    else:
        a, b = 0, clamp(max(0, before) + max(0, after))

    single = a == b
    turns = []
    for i in range(a, b + 1):
        t = doc.turns[i]
        item = {"turn": i, "role": t.role, "date": t.ts}
        if single:
            off = max(0, int(char_offset))
            page = t.text[off : off + MAX_PAGE_CHARS]
            item["text"] = page
            item["chars"] = len(t.text)
            if off + MAX_PAGE_CHARS < len(t.text):
                item["next_char_offset"] = off + MAX_PAGE_CHARS
        elif len(t.text) > MAX_TURN_CHARS:
            item["text"] = t.text[:MAX_TURN_CHARS]
            item["truncated"] = f"{len(t.text) - MAX_TURN_CHARS} more characters: read_conversation(session_id, start={i}, end={i})"
        else:
            item["text"] = t.text
        turns.append(item)
    result = {
        "session_id": session_id,
        "title": hi._load_titles(context_dir).get(session_id),
        "kind": doc.harness,
        "total_turns": total,
        "range": [a, b],
        "turns": turns,
    }
    if a > 0:
        result["earlier"] = f"turns 0–{a - 1} not shown"
    if b < total - 1:
        result["later"] = f"turns {b + 1}–{total - 1} not shown"
    result["open"] = open_hint(doc.harness, session_id)
    return result
