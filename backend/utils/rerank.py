"""LLM re-ranking of conversation-search candidates.

A cheap model sees the request and the top candidates (title, dates, summary, best excerpts)
and returns the ones that really are the conversation meant, best first — or none. Measured on
the dev eval (2026-10-06, gpt-4.1-mini, top 8): hit@1 0.57 → 0.72, MRR 0.73 → 0.79, every
never-discussed request rejected, ~0.7 s. gpt-4o-mini was far too conservative (hit@1 0.35).

Confirmed candidates come first and are marked strong; the others follow, marked weak, so the
re-rank can only reorder and relabel, never lose a result. Any failure (no key, timeout, bad
JSON) returns None and the caller keeps its own ranking and labels.
"""
from __future__ import annotations

import json
import logging
import os
import time

logger = logging.getLogger(__name__)

RERANK_MODEL = "gpt-4.1-mini"
RERANK_K = 8
TIMEOUT_S = 6.0
PAUSE_ON_QUOTA_S = 30 * 60  # after a quota/rate-limit error, don't try again for this long
_paused_until = 0.0

_PROMPT = """Rodrigo asked his assistant to find a past conversation. Candidates found by search are below.
Decide which candidates are the conversation he means, best first. Only include candidates that
really match what he describes (topic, details, time); if none does, return an empty list.
Request: {request}

Candidates:
{candidates}

Answer JSON only: {{"matches": ["<id>", ...]}}"""

_client = None


def _describe(i: int, s: dict) -> str:
    lines = [f"[{i}] id={s['session_id'][:8]} | {s.get('title') or s.get('first_message') or '(untitled)'} | "
             f"{(s.get('started_at') or '')[:10]} | {s.get('kind')}"]
    if s.get("summary"):
        lines.append(f"    summary: {s['summary'][:500]}")
    for h in s.get("hits", [])[:2]:
        lines.append(f"    excerpt ({h['role']}): {h['text'][:300]}")
    return "\n".join(lines)


def is_quota_error(e: Exception) -> bool:
    """OpenAI 429s: rate limits and exhausted credits (insufficient_quota)."""
    return getattr(e, "status_code", None) == 429 or "insufficient_quota" in str(e) or "no credits" in str(e)


def rerank_sessions(request: str, sessions: list[dict], *, model: str = RERANK_MODEL) -> list[dict] | None:
    """Reordered and relabelled sessions, or None when re-ranking isn't possible."""
    global _client, _paused_until
    if len(sessions) < 1 or not os.environ.get("OPENAI_API_KEY") or time.monotonic() < _paused_until:
        return None
    try:
        import openai

        if _client is None:
            _client = openai.OpenAI(timeout=TIMEOUT_S, max_retries=0)
        prompt = _PROMPT.format(request=request, candidates="\n".join(_describe(i, s) for i, s in enumerate(sessions)))
        r = _client.chat.completions.create(
            model=model, temperature=0, response_format={"type": "json_object"},
            messages=[{"role": "user", "content": prompt}],
        )
        picked = json.loads(r.choices[0].message.content or "{}").get("matches", [])
    except Exception as e:  # noqa: BLE001 — search must work without it
        if is_quota_error(e):
            # Out of credits / rate limited: every search would otherwise wait on a failing call.
            _paused_until = time.monotonic() + PAUSE_ON_QUOTA_S
            logger.warning("rerank paused for %d min: %s", PAUSE_ON_QUOTA_S // 60, e)
        else:
            logger.warning("rerank skipped: %s: %s", type(e).__name__, e)
        return None
    by_short = {s["session_id"][:8]: s for s in sessions}
    order: list[dict] = []
    for x in picked if isinstance(picked, list) else []:
        s = by_short.get(str(x)[:8])
        if s is not None and s not in order:
            order.append(s)
    confirmed = {id(s) for s in order}
    out = []
    for s in order:
        s["relevance"] = "strong"
        out.append(s)
    for s in sessions:
        if id(s) not in confirmed:
            s["relevance"] = "weak"
            out.append(s)
    return out
