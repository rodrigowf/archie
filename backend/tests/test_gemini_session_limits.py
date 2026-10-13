"""Gemini Live session limits: idle abort, context window, recovery.

Reproduced against Gemini Live on 2026-10-13:

- a session that gets no input for 150 s is closed with WS 1008 "The
  operation was aborted." (three field sessions died this way while the
  client's mic audio had stopped); a 20 ms silent chunk every 30 s keeps
  it open;
- resuming with the last ``sessionResumptionUpdate`` handle after that
  abort brings the same session (and its context) back;
- ``contextWindowCompression.slidingWindow`` trims the oldest turns inside
  the same session and keeps the system instructions.
"""

from __future__ import annotations

import asyncio
import dataclasses
import json
import time
from unittest.mock import AsyncMock

import pytest

from orchestrator.providers.gemini_voice import GeminiAIStudioBackend
from orchestrator.voice_errors import VoiceErrorCategory
from orchestrator.voice_relay import VoiceRelay
from orchestrator.voice_timeouts import VoiceTimeouts

ABORTED = Exception(
    "received 1008 (policy violation) The operation was aborted.; "
    "then sent 1008 (policy violation) The operation was aborted."
)


# --- context window compression ------------------------------------------------


def test_setup_enables_sliding_window_with_model_defaults(monkeypatch):
    monkeypatch.delenv("GEMINI_CONTEXT_TRIGGER_TOKENS", raising=False)
    monkeypatch.delenv("GEMINI_CONTEXT_TARGET_TOKENS", raising=False)
    cfg = GeminiAIStudioBackend().format_session_config("sys", [])
    assert cfg["setup"]["contextWindowCompression"] == {"slidingWindow": {}}


def test_compression_env_overrides(monkeypatch):
    monkeypatch.setenv("GEMINI_CONTEXT_TRIGGER_TOKENS", "100000")
    monkeypatch.setenv("GEMINI_CONTEXT_TARGET_TOKENS", "60000")
    cfg = GeminiAIStudioBackend().format_session_config("sys", [])
    assert cfg["setup"]["contextWindowCompression"] == {
        "slidingWindow": {"targetTokens": 60000},
        "triggerTokens": 100000,
    }


@pytest.mark.parametrize("bad", ["abc", "0", "-5"])
def test_invalid_compression_env_is_ignored(monkeypatch, bad):
    monkeypatch.setenv("GEMINI_CONTEXT_TRIGGER_TOKENS", bad)
    monkeypatch.delenv("GEMINI_CONTEXT_TARGET_TOKENS", raising=False)
    cfg = GeminiAIStudioBackend().format_session_config("sys", [])
    assert cfg["setup"]["contextWindowCompression"] == {"slidingWindow": {}}


def test_resumed_setup_keeps_compression_and_handle():
    p = GeminiAIStudioBackend()
    p.on_inbound_event({"sessionResumptionUpdate": {"newHandle": "H1", "resumable": True}})
    setup = p.format_session_config("sys", [])["setup"]
    assert setup["sessionResumption"] == {"handle": "H1"}
    assert "slidingWindow" in setup["contextWindowCompression"]


# --- idle abort: recovery ---------------------------------------------------------


def test_idle_abort_with_handle_is_recoverable_and_keeps_the_handle():
    p = GeminiAIStudioBackend()
    p.on_inbound_event({"sessionResumptionUpdate": {"newHandle": "H1", "resumable": True}})
    err = p.classify_close_reason(ABORTED, 1008, "The operation was aborted.")
    assert err is not None and err.recoverable is True
    assert err.category == VoiceErrorCategory.NETWORK
    assert p.is_recoverable_error(ABORTED) is True
    assert p._resumption_handle == "H1"  # resume the same session


def test_idle_abort_without_handle_stays_fatal():
    p = GeminiAIStudioBackend()
    err = p.classify_close_reason(ABORTED, 1008, "The operation was aborted.")
    assert err is None or err.recoverable is False or err.message != (
        "Gemini closed an idle voice session; resuming."
    )
    assert p.is_recoverable_error(ABORTED) is False


def test_denied_access_1008_is_still_fatal():
    p = GeminiAIStudioBackend()
    p.on_inbound_event({"sessionResumptionUpdate": {"newHandle": "H1", "resumable": True}})
    denied = Exception("received 1008 (policy violation) Your project has been denied access.")
    assert p.is_recoverable_error(denied) is False
    err = p.classify_close_reason(denied, 1008, None)
    assert err is not None and err.category == VoiceErrorCategory.AUTH


# --- relay keepalive loop ---------------------------------------------------------


class FakeWS:
    def __init__(self) -> None:
        self.sent: list[dict] = []

    async def send(self, raw: str) -> None:
        self.sent.append(json.loads(raw))

    async def close(self) -> None:
        pass


def _relay() -> tuple[VoiceRelay, FakeWS]:
    timeouts = dataclasses.replace(VoiceTimeouts.default(), keepalive_s=0.04)
    relay = VoiceRelay(
        GeminiAIStudioBackend(),
        on_audio_out=AsyncMock(),
        on_event_for_frontend=AsyncMock(),
        session_id="t-gemini-keepalive",
        voice_timeouts=timeouts,
    )
    ws = FakeWS()
    relay._ws = ws  # type: ignore[assignment]
    relay._handshake_complete.set()
    relay._started_at = time.monotonic() - 10  # no mic audio for 10 s
    return relay, ws


def _keepalives(ws: FakeWS) -> int:
    return sum(1 for f in ws.sent if "audio" in f.get("realtimeInput", {}))


@pytest.mark.asyncio
async def test_keepalive_reaches_gemini_while_no_mic_audio_arrives():
    relay, ws = _relay()
    task = asyncio.create_task(relay._keepalive_loop())
    await asyncio.sleep(0.15)
    relay._closed.set()
    await asyncio.wait_for(task, 1)
    assert _keepalives(ws) >= 2


@pytest.mark.asyncio
async def test_keepalive_survives_a_reconnect_gap():
    """``_ws`` is None for a moment while the relay swaps sockets."""
    relay, ws = _relay()
    task = asyncio.create_task(relay._keepalive_loop())
    relay._ws = None
    await asyncio.sleep(0.1)
    assert not task.done(), "keepalive loop died during the reconnect gap"
    new_ws = FakeWS()
    relay._ws = new_ws  # type: ignore[assignment]
    await asyncio.sleep(0.1)
    relay._closed.set()
    await asyncio.wait_for(task, 1)
    assert _keepalives(new_ws) >= 1


@pytest.mark.asyncio
async def test_keepalive_never_lands_before_setup_complete():
    relay, ws = _relay()
    relay._handshake_complete.clear()
    task = asyncio.create_task(relay._keepalive_loop())
    await asyncio.sleep(0.15)
    assert _keepalives(ws) == 0
    relay._handshake_complete.set()
    await asyncio.sleep(0.1)
    relay._closed.set()
    await asyncio.wait_for(task, 1)
    assert _keepalives(ws) >= 1


@pytest.mark.asyncio
async def test_no_keepalive_while_mic_audio_flows():
    relay, ws = _relay()
    task = asyncio.create_task(relay._keepalive_loop())
    for _ in range(8):
        relay._last_audio_in_at = time.monotonic()
        await asyncio.sleep(0.02)
    relay._closed.set()
    await asyncio.wait_for(task, 1)
    assert _keepalives(ws) == 0


# --- dropped connection: recovery -------------------------------------------------

DROPS = [
    Exception("sent 1011 (internal error) keepalive ping timeout; no close frame received"),
    Exception("no close frame received or sent"),
]


@pytest.mark.parametrize("exc", DROPS)
def test_dropped_connection_with_handle_resumes(exc):
    p = GeminiAIStudioBackend()
    p.on_inbound_event({"sessionResumptionUpdate": {"newHandle": "H1", "resumable": True}})
    assert p.is_recoverable_error(exc) is True
    err = p.classify_close_reason(exc, None, None)
    assert err is not None and err.recoverable is True
    assert p._resumption_handle == "H1"


@pytest.mark.parametrize("exc", DROPS)
def test_dropped_connection_without_handle_stays_fatal(exc):
    p = GeminiAIStudioBackend()
    assert p.is_recoverable_error(exc) is False
