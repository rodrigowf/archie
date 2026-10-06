"""O-4: a fatal voice-relay failure ends the voice session so every client
receives ``voice_ended`` (no zombie "voice" state)."""

from __future__ import annotations

import asyncio
import json
from typing import Any
from unittest.mock import AsyncMock, MagicMock

import pytest

from orchestrator.voice_relay import VoiceRelay
from tests.test_voice_relay_reconnect import _FakeProvider, _FakeWS


async def _run_relay(provider, *, rebuild=None, max_reconnects=2):
    fatal_calls: list[bool] = []
    events: list[dict[str, Any]] = []

    async def on_audio(b64):
        pass

    async def on_event(ev):
        events.append(ev)

    relay = VoiceRelay(
        provider,
        on_audio_out=on_audio,
        on_event_for_frontend=on_event,
        session_id="t-fatal",
        rebuild_session_update=rebuild,
        max_reconnects=max_reconnects,
        on_fatal=lambda: fatal_calls.append(True),
    )
    await relay.start({"type": "session.update", "session": {"instructions": "x"}})
    for _ in range(20):
        await asyncio.sleep(0)
    await relay.stop()
    return fatal_calls, events


@pytest.mark.asyncio
async def test_fatal_drain_failure_calls_on_fatal_after_error_events():
    ws = _FakeWS(
        frames=[json.dumps({"type": "session.created"})],
        close_error=ConnectionError("some unrelated network error"),
    )
    fatal_calls, events = await _run_relay(_FakeProvider([ws]))
    assert fatal_calls == [True]
    assert any(e.get("type") == "error" for e in events)


@pytest.mark.asyncio
async def test_recovered_failure_does_not_call_on_fatal():
    sigil = ConnectionError("InvalidParameter: The provided URL does not appear to be valid.")
    ws_a = _FakeWS(frames=[json.dumps({"type": "session.created"})], close_error=sigil)
    ws_b = _FakeWS(frames=[json.dumps({"type": "session.created"})])

    async def rebuild():
        return {"type": "session.update", "session": {"instructions": "x"}}

    fatal_calls, _ = await _run_relay(_FakeProvider([ws_a, ws_b]), rebuild=rebuild)
    assert fatal_calls == []


def _session(tmp_path, monkeypatch):
    import utils.paths as _paths
    monkeypatch.setattr(_paths, "PROJECT_ROOT", tmp_path)
    from orchestrator.config import OrchestratorConfig
    from orchestrator.session import OrchestratorSession

    config = OrchestratorConfig(project_dir=str(tmp_path), memory_path=str(tmp_path / "m.md"))
    return OrchestratorSession(config=config, context={"orchestrator_sessions": {}})


@pytest.mark.asyncio
async def test_session_ends_voice_with_error_on_fatal(tmp_path, monkeypatch):
    from orchestrator.session import VoiceLifecycle

    session = _session(tmp_path, monkeypatch)
    relay = MagicMock()
    session._voice_relay = relay
    session._voice = True
    session._voice_state = VoiceLifecycle.ACTIVE
    session.end_voice = AsyncMock()

    session._on_voice_relay_fatal(relay)
    await session._voice_fatal_task
    session.end_voice.assert_awaited_once_with("error")


@pytest.mark.asyncio
async def test_session_ignores_fatal_from_stale_relay(tmp_path, monkeypatch):
    from orchestrator.session import VoiceLifecycle

    session = _session(tmp_path, monkeypatch)
    session._voice_relay = MagicMock()
    session._voice = True
    session._voice_state = VoiceLifecycle.ACTIVE
    session.end_voice = AsyncMock()

    session._on_voice_relay_fatal(MagicMock())  # a replaced relay
    assert session._voice_fatal_task is None
    session.end_voice.assert_not_awaited()


@pytest.mark.asyncio
async def test_session_ignores_fatal_while_already_ending(tmp_path, monkeypatch):
    from orchestrator.session import VoiceLifecycle

    session = _session(tmp_path, monkeypatch)
    relay = MagicMock()
    session._voice_relay = relay
    session._voice_state = VoiceLifecycle.ENDING
    session.end_voice = AsyncMock()

    session._on_voice_relay_fatal(relay)
    assert session._voice_fatal_task is None


@pytest.mark.asyncio
async def test_relay_rebuilt_before_fatal_task_runs(tmp_path, monkeypatch):
    """Race 1: the check passes at scheduling time, then the relay is
    rebuilt before the task runs — the new relay must not be ended."""
    from orchestrator.session import VoiceLifecycle

    session = _session(tmp_path, monkeypatch)
    old_relay = MagicMock()
    session._voice_relay = old_relay
    session._voice = True
    session._voice_state = VoiceLifecycle.ACTIVE
    session.end_voice = AsyncMock()

    session._on_voice_relay_fatal(old_relay)
    assert session._voice_fatal_task is not None
    session._voice_relay = MagicMock()  # rebuild lands first
    await session._voice_fatal_task
    session.end_voice.assert_not_awaited()


@pytest.mark.asyncio
async def test_relay_dies_before_it_is_assigned(tmp_path, monkeypatch):
    """Race 2: the drain dies while start_voice_relay has not yet set
    ``self._voice_relay = relay`` — voice still ends once assigned."""
    from orchestrator.session import VoiceLifecycle

    session = _session(tmp_path, monkeypatch)
    relay = MagicMock()
    session._pending_voice_relay = relay
    session._voice_relay = None
    session._voice = True
    session._voice_state = VoiceLifecycle.STARTING
    session.end_voice = AsyncMock()

    session._on_voice_relay_fatal(relay)
    assert session._voice_fatal_task is not None
    # start_voice_relay finishes before the task runs.
    session._voice_relay = relay
    session._pending_voice_relay = None
    session._voice_state = VoiceLifecycle.ACTIVE
    await session._voice_fatal_task
    session.end_voice.assert_awaited_once_with("error")


@pytest.mark.asyncio
async def test_unassigned_relay_superseded_before_task_runs(tmp_path, monkeypatch):
    """Race 2 variant: the dead, never-assigned relay is superseded by a
    newer start before the task runs — nothing is ended."""
    from orchestrator.session import VoiceLifecycle

    session = _session(tmp_path, monkeypatch)
    relay = MagicMock()
    session._pending_voice_relay = relay
    session._voice = True
    session._voice_state = VoiceLifecycle.STARTING
    session.end_voice = AsyncMock()

    session._on_voice_relay_fatal(relay)
    session._pending_voice_relay = MagicMock()  # a newer start
    await session._voice_fatal_task
    session.end_voice.assert_not_awaited()


@pytest.mark.asyncio
async def test_voice_already_ending_when_task_runs(tmp_path, monkeypatch):
    from orchestrator.session import VoiceLifecycle

    session = _session(tmp_path, monkeypatch)
    relay = MagicMock()
    session._voice_relay = relay
    session._voice = True
    session._voice_state = VoiceLifecycle.ACTIVE
    session.end_voice = AsyncMock()

    session._on_voice_relay_fatal(relay)
    session._voice_state = VoiceLifecycle.ENDING  # user stopped meanwhile
    await session._voice_fatal_task
    session.end_voice.assert_not_awaited()


@pytest.mark.asyncio
async def test_start_voice_relay_tracks_pending_relay(tmp_path, monkeypatch):
    """start_voice_relay exposes the relay as pending during start() and
    clears it once assigned."""
    import orchestrator.voice_relay as relay_mod
    from orchestrator.session import VoiceLifecycle

    session = _session(tmp_path, monkeypatch)
    seen_pending = []

    class FakeRelay:
        def __init__(self, *args, **kwargs):
            pass

        async def start(self, update):
            seen_pending.append(session._pending_voice_relay is self)

    provider = MagicMock()
    provider.connection_type = "websocket"
    session._voice_provider = provider
    session._voice = True
    session._voice_state = VoiceLifecycle.IDLE
    monkeypatch.setattr(relay_mod, "VoiceRelay", FakeRelay)

    async def noop(*a, **k):
        pass

    await session.start_voice_relay(noop, noop, session_update={})
    assert seen_pending == [True]
    assert isinstance(session._voice_relay, FakeRelay)
    assert session._pending_voice_relay is None
    assert session._voice_state == VoiceLifecycle.ACTIVE
