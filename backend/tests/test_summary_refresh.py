"""History-summary refresh: when it costs an LLM call and when it doesn't.

The voice prompt is the summary of the older prefix + the recent turns
verbatim (``OrchestratorSession._build_history_for_prompt``). These tests pin:

- a summary is valid while it covers the older prefix (message count), not
  only while the JSONL is byte-identical — so turns that stay in the
  verbatim window never cost a re-summary;
- one summarization per conversation at a time (concurrent refreshes, or a
  voice start during a refresh, share one LLM call);
- the cache is keyed to the JSONL as it was read, so turns appended during
  a minutes-long summary are not marked as covered;
- text turns refresh the summary once the conversation goes idle (debounced).
"""

from __future__ import annotations

import asyncio
import json
from pathlib import Path
from unittest.mock import AsyncMock, MagicMock

import pytest

from orchestrator import session as session_mod
from orchestrator import summary_cache
from orchestrator.persistence import HistoryLoader
from orchestrator.session import OrchestratorSession
from orchestrator.token_budget import (
    RECENT_VERBATIM_TOKENS,
    split_by_token_budget,
    truncate_tool_results,
)

LONG = "x" * 2000  # ~570 est tokens per message → ~9 messages fit verbatim


def _line(role: str, i: int) -> str:
    return json.dumps({
        "type": role,
        "message": {"role": role, "content": f"{role} {i} {LONG}"},
        "timestamp": "2026-10-13T00:00:00+00:00",
    }) + "\n"


def _append(path: Path, start: int, n: int) -> None:
    with open(path, "a") as f:
        for i in range(start, start + n):
            f.write(_line("user" if i % 2 == 0 else "assistant", i))


def _older_count(path: Path) -> int:
    older, _ = split_by_token_budget(
        truncate_tool_results(HistoryLoader(path).load()), RECENT_VERBATIM_TOKENS,
    )
    return len(older)


def _session(path: Path) -> OrchestratorSession:
    config = MagicMock()
    config.summarizer_model = None
    s = OrchestratorSession(
        config=config,
        context={"pool": MagicMock(), "store": MagicMock()},
        local_id="t-summary",
    )
    s._jsonl_path = path
    s._summarize_history = AsyncMock(return_value="SUMMARY")
    return s


@pytest.fixture
def jsonl(tmp_path: Path) -> Path:
    p = tmp_path / "conv.jsonl"
    _append(p, 0, 30)
    return p


def _cache(path: Path, count: int, text: str = "OLD") -> None:
    summary_cache.write(
        path, summary_text=text, input_message_count=count,
        summary_target_words=(100, 400), summarizer_model=None,
    )


@pytest.mark.asyncio
async def test_prompt_uses_cache_when_prefix_unchanged_even_if_jsonl_grew(jsonl: Path):
    n = _older_count(jsonl)
    _cache(jsonl, n)
    _append(jsonl, 30, 1)  # one more turn; the older prefix may or may not move
    s = _session(jsonl)
    recent, summary = await s._build_history_for_prompt()
    assert summary == "OLD"
    s._summarize_history.assert_not_awaited()
    # Every message is either summarised (first n) or verbatim — none lost.
    assert n + len(recent) == len(HistoryLoader(jsonl).load())


@pytest.mark.asyncio
async def test_prompt_promotes_uncovered_messages_into_verbatim(jsonl: Path):
    n = _older_count(jsonl)
    _cache(jsonl, n - 3)  # the summary covers 3 fewer messages than `older`
    s = _session(jsonl)
    recent, summary = await s._build_history_for_prompt()
    assert summary == "OLD"
    s._summarize_history.assert_not_awaited()
    assert (n - 3) + len(recent) == len(HistoryLoader(jsonl).load())


@pytest.mark.asyncio
async def test_prompt_summarises_on_miss_and_writes_back(jsonl: Path):
    s = _session(jsonl)
    _, summary = await s._build_history_for_prompt()
    assert summary == "SUMMARY"
    s._summarize_history.assert_awaited_once()
    cached = summary_cache.read(jsonl)
    assert cached is not None and cached.input_message_count == _older_count(jsonl)


@pytest.mark.asyncio
async def test_refresh_redates_without_llm_when_prefix_unchanged(jsonl: Path):
    n = _older_count(jsonl)
    _cache(jsonl, n)
    with open(jsonl, "a") as f:  # grows the file, adds no message
        f.write(json.dumps({"type": "voice_interrupted", "timestamp": "t"}) + "\n")
    assert not summary_cache.is_fresh(jsonl)
    s = _session(jsonl)
    assert await s.refresh_summary_cache_if_stale() is False
    s._summarize_history.assert_not_awaited()
    assert summary_cache.is_fresh(jsonl)
    assert summary_cache.read(jsonl).summary_text == "OLD"


@pytest.mark.asyncio
async def test_refresh_summarises_when_prefix_moved(jsonl: Path):
    _cache(jsonl, _older_count(jsonl))
    _append(jsonl, 30, 4)  # enough new turns to push messages into `older`
    assert _older_count(jsonl) > summary_cache.read_any(jsonl).input_message_count
    s = _session(jsonl)
    assert await s.refresh_summary_cache_if_stale() is True
    s._summarize_history.assert_awaited_once()
    assert summary_cache.read(jsonl).summary_text == "SUMMARY"


@pytest.mark.asyncio
async def test_concurrent_refreshes_share_one_llm_call(jsonl: Path):
    """Three devices opening the conversation at once → one summary."""
    gate = asyncio.Event()

    async def slow(*_a, **_k):
        await gate.wait()
        return "SUMMARY"

    sessions = [_session(jsonl) for _ in range(3)]
    for s in sessions:
        s._summarize_history = AsyncMock(side_effect=slow)
    tasks = [asyncio.create_task(s.refresh_summary_cache_if_stale()) for s in sessions]
    await asyncio.sleep(0.05)
    gate.set()
    results = await asyncio.gather(*tasks)
    assert sum(s._summarize_history.await_count for s in sessions) == 1
    assert results.count(True) == 1


@pytest.mark.asyncio
async def test_voice_start_during_refresh_waits_for_it(jsonl: Path):
    gate = asyncio.Event()

    async def slow(*_a, **_k):
        await gate.wait()
        return "SUMMARY"

    bg, voice = _session(jsonl), _session(jsonl)
    bg._summarize_history = AsyncMock(side_effect=slow)
    refresh = asyncio.create_task(bg.refresh_summary_cache_if_stale())
    await asyncio.sleep(0.05)
    build = asyncio.create_task(voice._build_history_for_prompt())
    await asyncio.sleep(0.05)
    assert not build.done()  # waiting on the running refresh, not summarising
    gate.set()
    await refresh
    _, summary = await build
    assert summary == "SUMMARY"
    voice._summarize_history.assert_not_awaited()


@pytest.mark.asyncio
async def test_turn_appended_during_summary_is_not_marked_covered(jsonl: Path):
    s = _session(jsonl)

    async def append_meanwhile(*_a, **_k):
        _append(jsonl, 30, 1)  # a turn lands while the LLM call runs
        return "SUMMARY"

    s._summarize_history = AsyncMock(side_effect=append_meanwhile)
    assert await s.refresh_summary_cache_if_stale() is True
    # Keyed to the JSONL as read → the newer file is NOT considered fresh.
    assert not summary_cache.is_fresh(jsonl)


@pytest.mark.asyncio
async def test_idle_timer_debounces_and_refreshes_once(jsonl: Path, monkeypatch):
    monkeypatch.setattr(session_mod, "SUMMARY_IDLE_REFRESH_S", 0.05)
    s = _session(jsonl)
    s.refresh_summary_cache_if_stale = AsyncMock(return_value=True)
    s._schedule_idle_summary_refresh()
    await asyncio.sleep(0.02)
    s._schedule_idle_summary_refresh()  # a second turn restarts the wait
    await asyncio.sleep(0.04)
    s.refresh_summary_cache_if_stale.assert_not_awaited()
    await asyncio.sleep(0.05)
    s.refresh_summary_cache_if_stale.assert_awaited_once()


@pytest.mark.asyncio
async def test_idle_timer_skips_while_voice_is_on(jsonl: Path, monkeypatch):
    monkeypatch.setattr(session_mod, "SUMMARY_IDLE_REFRESH_S", 0.01)
    s = _session(jsonl)
    s._voice = True
    s.refresh_summary_cache_if_stale = AsyncMock(return_value=True)
    s._schedule_idle_summary_refresh()
    await asyncio.sleep(0.05)
    s.refresh_summary_cache_if_stale.assert_not_awaited()
