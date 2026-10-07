"""Write-time session summaries for the history index.

A cheap LLM reads each conversation once and writes a short title, a 2–4 sentence summary,
topics, names (people, products, places), decisions, and keywords in English and Portuguese.
The summary is indexed as an extra searchable document of its session (so "the one where we
decided X" can match even when no single message says it in those words) and is shown in
search results and listings (so a small voice model can tell sessions apart).

Summaries live in their own small SQLite file (``index/session_summaries.sqlite3``): they cost
API calls, so they are kept across index rebuilds and shared by every index variant. A session
is summarized again only when it has grown meaningfully (and not more than every few hours,
so an active conversation doesn't churn).

Requires OPENAI_API_KEY; without it summaries are skipped and search works as before.
"""
from __future__ import annotations

import asyncio
import hashlib
import json
import os
import sqlite3
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Callable

from utils import history_index as hi
from utils.paths import get_index_dir

SUMMARY_MODEL = "gpt-4.1-mini"
PROMPT_VERSION = 1
MAX_TRANSCRIPT_CHARS = 60000
TURN_CHARS = 1500
MIN_TURNS = 2
REFRESH_GROWTH = 0.25        # re-summarize when the session grew by 25% of its turns…
REFRESH_MIN_NEW_TURNS = 4    # …and at least this many turns
REFRESH_MIN_AGE_S = 6 * 3600  # …and the last summary is older than this

_SYSTEM = """You index a person's past conversations with his AI assistant "Archie" so he can find them later by voice.
The person is Rodrigo, a Brazilian developer (he speaks English and Portuguese). The transcript may be a voice chat with
the orchestrator, or a coding/agent session. Write what would help him recognise and find THIS conversation months later.
Be concrete and specific (names, products, files, bugs, decisions, people, places); no generic filler.
Return JSON only:
{"title": "<5-10 word descriptive title>",
 "summary": "<2-4 sentences: what it was about, what was done or decided, how it ended>",
 "topics": ["<3-6 short topics>"],
 "names": ["<people, products, projects, tools, places, files mentioned that matter>"],
 "decisions": ["<0-4 concrete decisions/outcomes>"],
 "keywords_en": ["<6-12 English search keywords>"],
 "keywords_pt": ["<6-12 Portuguese search keywords>"]}"""


def get_summaries_db_path() -> Path:
    return get_index_dir() / "session_summaries.sqlite3"


def connect(path: Path | None = None) -> sqlite3.Connection:
    path = Path(path or get_summaries_db_path())
    path.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(str(path), timeout=30)
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute(
        "CREATE TABLE IF NOT EXISTS summaries (session_id TEXT PRIMARY KEY, n_turns INTEGER NOT NULL, "
        "model TEXT NOT NULL, prompt_version INTEGER NOT NULL, data TEXT NOT NULL, created_at REAL NOT NULL)"
    )
    conn.commit()
    return conn


def get(conn: sqlite3.Connection, session_id: str) -> dict | None:
    row = conn.execute("SELECT data FROM summaries WHERE session_id=?", (session_id,)).fetchone()
    return json.loads(row[0]) if row else None


def summary_text(data: dict) -> str:
    """The searchable text of a summary (what gets embedded and keyword-indexed)."""
    parts = [data.get("title") or "", data.get("summary") or ""]
    for key in ("topics", "names", "decisions", "keywords_en", "keywords_pt"):
        vals = [v for v in data.get(key) or [] if isinstance(v, str)]
        if vals:
            parts.append(", ".join(vals))
    return "\n".join(p for p in parts if p).strip()


def summary_hash(data: dict | None) -> str:
    return hashlib.sha1(summary_text(data).encode("utf-8")).hexdigest()[:16] if data else ""


def transcript_for(doc: hi.SessionDoc) -> str:
    """The transcript sent to the model: every turn capped, spread windows when too long."""
    blocks = []
    for i, t in enumerate(doc.turns):
        text = t.text if len(t.text) <= TURN_CHARS else t.text[:TURN_CHARS] + " […]"
        blocks.append(f"[{i}] {t.role.upper()} ({(t.ts or '')[:10]}): {text}")
    total = sum(len(b) for b in blocks)
    if total <= MAX_TRANSCRIPT_CHARS:
        return "\n".join(blocks)
    window = 8
    n_windows = max(1, int(MAX_TRANSCRIPT_CHARS / (total / len(blocks) * window)))
    out, last = [], 0
    for k in range(n_windows):
        s0 = max(last, round(k * (len(blocks) - window) / max(1, n_windows - 1)))
        if s0 > last:
            out.append(f"[… turns {last}–{s0 - 1} omitted …]")
        out.extend(blocks[s0 : s0 + window])
        last = s0 + window
    if last < len(blocks):
        out.append(f"[… turns {last}–{len(blocks) - 1} omitted …]")
    return "\n".join(out)


def needs_summary(conn: sqlite3.Connection, doc: hi.SessionDoc, now: float | None = None) -> bool:
    n = len(doc.turns)
    if n < MIN_TURNS:
        return False
    row = conn.execute(
        "SELECT n_turns, prompt_version, created_at FROM summaries WHERE session_id=?", (doc.session_id,)
    ).fetchone()
    if row is None or row[1] != PROMPT_VERSION:
        return True
    old_n, _, created = row
    grown = n - old_n
    return (
        grown >= REFRESH_MIN_NEW_TURNS
        and grown >= REFRESH_GROWTH * max(old_n, 1)
        and (now or time.time()) - created >= REFRESH_MIN_AGE_S
    )


async def _summarize_one(client, doc: hi.SessionDoc, model: str) -> dict:
    header = f"Session kind: {doc.harness}. Dates: {doc.started_at} to {doc.ended_at}. Turns: {len(doc.turns)}."
    r = await client.chat.completions.create(
        model=model,
        temperature=0,
        response_format={"type": "json_object"},
        messages=[
            {"role": "system", "content": _SYSTEM},
            {"role": "user", "content": f"{header}\n\n{transcript_for(doc)}"},
        ],
    )
    data = json.loads(r.choices[0].message.content or "{}")
    return {k: data.get(k) for k in ("title", "summary", "topics", "names", "decisions", "keywords_en", "keywords_pt")}


def summarize_pending(
    conn: sqlite3.Connection,
    sources: list[Path],
    *,
    model: str = SUMMARY_MODEL,
    concurrency: int = 6,
    limit: int | None = None,
    log: Callable[[str], None] = print,
) -> dict:
    """Summarize every session that needs it. Returns counts; failures are logged and skipped."""
    if not os.environ.get("OPENAI_API_KEY"):
        log("  summaries skipped: OPENAI_API_KEY not set")
        return {"summarized": 0, "skipped": "no api key"}
    import openai

    docs = []
    for path in sources:
        try:
            doc = hi.extract_session(path)
        except Exception as e:  # noqa: BLE001
            log(f"  summary: cannot read {path.name}: {e}")
            continue
        if needs_summary(conn, doc):
            docs.append(doc)
    if limit is not None:
        docs = docs[:limit]
    if not docs:
        return {"summarized": 0, "failed": 0}

    async def run() -> tuple[int, int]:
        from utils.rerank import is_quota_error

        client = openai.AsyncOpenAI()
        sem = asyncio.Semaphore(concurrency)
        ok = failed = 0
        stop = asyncio.Event()  # set on a quota error: no point trying the rest this run

        async def one(doc):
            nonlocal ok, failed
            async with sem:
                if stop.is_set():
                    return
                try:
                    data = await _summarize_one(client, doc, model)
                except Exception as e:  # noqa: BLE001
                    failed += 1
                    if is_quota_error(e):
                        stop.set()
                        log(f"  summaries stopped (quota/rate limit): {e}")
                    else:
                        log(f"  summary FAILED {doc.session_id}: {type(e).__name__}: {e}")
                    return
            with conn:
                conn.execute(
                    "INSERT OR REPLACE INTO summaries(session_id, n_turns, model, prompt_version, data, created_at) "
                    "VALUES (?, ?, ?, ?, ?, ?)",
                    (doc.session_id, len(doc.turns), model, PROMPT_VERSION, json.dumps(data, ensure_ascii=False), time.time()),
                )
            ok += 1

        await asyncio.gather(*(one(d) for d in docs))
        return ok, failed

    ok, failed = asyncio.run(run())
    log(f"  summaries: {ok} written, {failed} failed")
    return {"summarized": ok, "failed": failed}


def created_at_iso(ts: float) -> str:
    return datetime.fromtimestamp(ts, tz=timezone.utc).isoformat()
