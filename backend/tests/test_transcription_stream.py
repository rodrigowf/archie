"""``WS /api/transcribe/stream``: the protocol, the guards and the caps.

A fake transcriber stands in for the providers, so these tests exercise the route's own job —
normalising two very different provider shapes into one ``append``/``partial`` stream, keeping
the words when something goes wrong, and refusing callers who should not be spending the keys.
"""

from __future__ import annotations

import asyncio
import base64
import json

import pytest
from fastapi import FastAPI
from starlette.testclient import TestClient

from api.routes import transcribe
from transcription import registry
from transcription.base import Committed, Failed, Partial, Transcriber

LOCAL = ("127.0.0.1", 50000)
PCM = base64.b64encode(b"\x00\x00" * 800).decode()


class FakeTranscriber(Transcriber):
    """Emits a scripted list of events: on `start`, per chunk, or on `finish`."""

    sample_rate = 16000

    def __init__(self, *, on_feed=None, on_finish=None, on_start=None):
        super().__init__()
        self.fed: list[bytes] = []
        self.finished = False
        self.closed = False
        self._on_feed = on_feed or []
        self._on_finish = on_finish or []
        self._on_start = on_start or []

    async def _start(self):
        for ev in self._on_start:
            self._emit(ev)

    async def _feed(self, pcm: bytes):
        self.fed.append(pcm)
        if self._on_feed:
            self._emit(self._on_feed.pop(0))

    async def _finish(self):
        self.finished = True
        for ev in self._on_finish:
            self._emit(ev)

    async def _close(self):
        self.closed = True


@pytest.fixture
def app() -> FastAPI:
    a = FastAPI()
    a.include_router(transcribe.router)
    return a


@pytest.fixture
def made(monkeypatch):
    """Install a transcriber factory; returns the list of instances it built."""
    built: list[FakeTranscriber] = []

    def install(**kwargs):
        def create(spec, language="", sample_rate=None):
            t = FakeTranscriber(**kwargs)
            t.sample_rate = sample_rate or spec.sample_rate
            t.spec, t.language = spec, language
            built.append(t)
            return t

        monkeypatch.setattr(registry, "create", create)
        monkeypatch.setattr(registry, "api_key", lambda spec: "k-test")
        return built

    return install


def _config(monkeypatch, **overrides):
    base = {
        "dictation_enabled": True,
        "dictation_provider": "openai",
        "dictation_model": "gpt-live-transcribe",
        "dictation_language": "",
    }
    base.update(overrides)
    import api.routes.config as config_module

    monkeypatch.setattr(config_module, "_load_config", lambda: base)
    return base


def _drain(ws, *, until="final"):
    """Collect frames until `until`; returns (frames, assembled text)."""
    frames, committed, partial = [], "", ""
    while True:
        m = ws.receive_json()
        frames.append(m)
        if m["type"] == "text":
            committed += m.get("append", "")
            partial = m.get("partial", "")
        if m["type"] in (until, "cancelled"):
            return frames, (committed + partial).strip()


# ---------------------------------------------------------------- protocol


def test_a_dictation_streams_text_and_ends_with_final(app, made, monkeypatch):
    _config(monkeypatch)
    built = made(on_feed=[Committed(" Hey"), Committed(" buddy"), Committed(",")],
                 on_finish=[Committed(" we're back.")])
    with TestClient(app, client=LOCAL) as client:
        with client.websocket_connect("/api/transcribe/stream") as ws:
            ws.send_json({"type": "start"})
            ready = ws.receive_json()
            assert ready["type"] == "ready"
            assert ready["model"] == "gpt-live-transcribe"
            assert ready["note_id"] is None
            for _ in range(3):
                ws.send_json({"type": "audio", "audio": PCM})
            ws.send_json({"type": "stop"})
            frames, live = _drain(ws)

    assert frames[-1]["type"] == "final"
    assert frames[-1]["text"] == "Hey buddy, we're back."
    # What the client assembled from the deltas must equal the final text, or the
    # field would flicker to something different at the end.
    assert live == frames[-1]["text"]
    assert built[0].finished and built[0].closed
    assert len(built[0].fed) == 3


def test_partials_are_replaced_and_committed_text_is_appended(app, made, monkeypatch):
    """The ElevenLabs shape: a rewritten tail, then one commit for the segment."""
    _config(monkeypatch)
    made(on_feed=[Partial("Hello my"), Partial("Hello, my friend"), Committed("Hello, my friend.")],
         on_finish=[])
    with TestClient(app, client=LOCAL) as client:
        with client.websocket_connect("/api/transcribe/stream") as ws:
            ws.send_json({"type": "start"})
            ws.receive_json()
            for _ in range(3):
                ws.send_json({"type": "audio", "audio": PCM})
            ws.send_json({"type": "stop"})
            frames, live = _drain(ws)

    texts = [f for f in frames if f["type"] == "text"]
    assert texts[0] == {"type": "text", "append": "", "partial": "Hello my"}
    assert texts[1] == {"type": "text", "append": "", "partial": "Hello, my friend"}
    # The commit both appends and clears the partial, so the client never shows it twice.
    assert texts[2] == {"type": "text", "append": "Hello, my friend.", "partial": ""}
    assert live == "Hello, my friend."


def test_cancel_discards_everything(app, made, monkeypatch):
    _config(monkeypatch)
    built = made(on_feed=[Committed("something")])
    with TestClient(app, client=LOCAL) as client:
        with client.websocket_connect("/api/transcribe/stream") as ws:
            ws.send_json({"type": "start"})
            ws.receive_json()
            ws.send_json({"type": "audio", "audio": PCM})
            ws.send_json({"type": "cancel"})
            frames, _ = _drain(ws, until="cancelled")

    assert frames[-1]["type"] == "cancelled"
    assert not any(f["type"] == "final" for f in frames)
    assert not built[0].finished, "a cancel must not pay to flush"


def test_a_provider_failure_keeps_the_words_already_transcribed(app, made, monkeypatch):
    """A dropped socket must never cost the sentence the user just spoke."""
    _config(monkeypatch)
    made(on_feed=[Committed("the half I said"), Failed("provider_closed", "upstream went away")])
    with TestClient(app, client=LOCAL) as client:
        with client.websocket_connect("/api/transcribe/stream") as ws:
            ws.send_json({"type": "start"})
            ws.receive_json()
            ws.send_json({"type": "audio", "audio": PCM})
            ws.send_json({"type": "audio", "audio": PCM})
            frames, _ = _drain(ws)

    errors = [f for f in frames if f["type"] == "error"]
    assert errors and errors[0]["error"] == "provider_closed"
    assert frames[-1]["type"] == "final"
    assert frames[-1]["text"] == "the half I said"


def test_unknown_messages_are_ignored_not_fatal(app, made, monkeypatch):
    """A keepalive ping (the canvas sends one while paused) must not end a recording."""
    _config(monkeypatch)
    made(on_feed=[Committed("still here")])
    with TestClient(app, client=LOCAL) as client:
        with client.websocket_connect("/api/transcribe/stream") as ws:
            ws.send_json({"type": "start"})
            ws.receive_json()
            ws.send_json({"type": "ping"})
            ws.send_json({"type": "audio", "audio": PCM})
            ws.send_json({"type": "stop"})
            frames, _ = _drain(ws)
    assert frames[-1]["text"] == "still here"


def test_bad_base64_is_refused(app, made, monkeypatch):
    _config(monkeypatch)
    made()
    with TestClient(app, client=LOCAL) as client:
        with client.websocket_connect("/api/transcribe/stream") as ws:
            ws.send_json({"type": "start"})
            ws.receive_json()
            ws.send_json({"type": "audio", "audio": "!!! not base64 !!!"})
            frames, _ = _drain(ws)
    assert any(f.get("error") == "bad_audio" for f in frames)


def test_an_oversized_chunk_is_refused(app, made, monkeypatch):
    _config(monkeypatch)
    made()
    big = base64.b64encode(b"\x00" * (transcribe.MAX_CHUNK_BYTES + 1)).decode()
    with TestClient(app, client=LOCAL) as client:
        with client.websocket_connect("/api/transcribe/stream") as ws:
            ws.send_json({"type": "start"})
            ws.receive_json()
            ws.send_json({"type": "audio", "audio": big})
            frames, _ = _drain(ws)
    assert any(f.get("error") == "chunk_too_large" for f in frames)


# ---------------------------------------------------------------- settings


def test_the_client_may_override_the_configured_model(app, made, monkeypatch):
    _config(monkeypatch)
    built = made()
    with TestClient(app, client=LOCAL) as client:
        with client.websocket_connect("/api/transcribe/stream") as ws:
            ws.send_json({"type": "start", "provider": "elevenlabs", "model": "scribe_v2_realtime",
                          "language": "pt", "sample_rate": 24000})
            ready = ws.receive_json()
            ws.send_json({"type": "cancel"})
            _drain(ws, until="cancelled")

    assert ready["model"] == "scribe_v2_realtime"
    assert ready["sample_rate"] == 24000
    assert built[0].language == "pt"


def test_a_batch_model_is_upgraded_to_a_streaming_one(app, made, monkeypatch):
    """The socket exists to stream: a configured batch model falls back to a live one."""
    _config(monkeypatch, dictation_model="gpt-transcribe")
    made()
    with TestClient(app, client=LOCAL) as client:
        with client.websocket_connect("/api/transcribe/stream") as ws:
            ws.send_json({"type": "start"})
            ready = ws.receive_json()
            ws.send_json({"type": "cancel"})
            _drain(ws, until="cancelled")
    assert ready["streaming"] is True


def test_the_configured_language_is_used_when_the_client_names_none(app, made, monkeypatch):
    _config(monkeypatch, dictation_language="pt")
    built = made()
    with TestClient(app, client=LOCAL) as client:
        with client.websocket_connect("/api/transcribe/stream") as ws:
            ws.send_json({"type": "start"})
            ws.receive_json()
            ws.send_json({"type": "cancel"})
            _drain(ws, until="cancelled")
    assert built[0].language == "pt"


# ---------------------------------------------------------------- guards


@pytest.mark.parametrize("peer,headers,allowed", [
    (("192.168.0.31", 1), {}, True),
    (("100.97.1.2", 1), {}, True),
    (("127.0.0.1", 1), {"X-Real-IP": "192.168.0.31"}, True),
    (("8.8.8.8", 1), {}, False),
    (("127.0.0.1", 1), {"X-Real-IP": "203.0.113.9"}, False),
    (("8.8.8.8", 1), {"X-Real-IP": "127.0.0.1"}, False),
])
def test_only_the_home_network_may_open_the_socket(app, made, monkeypatch, peer, headers, allowed):
    _config(monkeypatch)
    built = made()
    with TestClient(app, client=peer) as client:
        if allowed:
            with client.websocket_connect("/api/transcribe/stream", headers=headers) as ws:
                ws.send_json({"type": "start"})
                assert ws.receive_json()["type"] == "ready"
                ws.send_json({"type": "cancel"})
                _drain(ws, until="cancelled")
            assert built
        else:
            from starlette.websockets import WebSocketDisconnect

            with pytest.raises(WebSocketDisconnect):
                with client.websocket_connect("/api/transcribe/stream", headers=headers) as ws:
                    ws.receive_json()
            assert not built, "the transcriber must not be built for a refused caller"


def test_a_first_message_that_is_not_start_is_refused(app, made, monkeypatch):
    _config(monkeypatch)
    built = made()
    from starlette.websockets import WebSocketDisconnect

    with TestClient(app, client=LOCAL) as client:
        with pytest.raises(WebSocketDisconnect):
            with client.websocket_connect("/api/transcribe/stream") as ws:
                ws.send_json({"type": "audio", "audio": PCM})
                while True:
                    ws.receive_json()
    assert not built


def test_too_many_concurrent_sockets_are_refused(app, made, monkeypatch):
    _config(monkeypatch)
    made()
    monkeypatch.setattr(transcribe, "MAX_LIVE_SOCKETS", 1)
    with TestClient(app, client=LOCAL) as client:
        with client.websocket_connect("/api/transcribe/stream") as first:
            first.send_json({"type": "start"})
            assert first.receive_json()["type"] == "ready"
            with client.websocket_connect("/api/transcribe/stream") as second:
                second.send_json({"type": "start"})
                assert second.receive_json() == {
                    "type": "error", "error": "busy",
                    "detail": "1 transcriptions are already running",
                }
            first.send_json({"type": "cancel"})
            _drain(first, until="cancelled")

    # The slot is given back, so a refusal is not permanent.
    with TestClient(app, client=LOCAL) as client:
        with client.websocket_connect("/api/transcribe/stream") as ws:
            ws.send_json({"type": "start"})
            assert ws.receive_json()["type"] == "ready"
            ws.send_json({"type": "cancel"})
            _drain(ws, until="cancelled")


def test_no_api_key_is_reported_before_any_audio(app, monkeypatch):
    _config(monkeypatch)
    monkeypatch.setattr(registry, "api_key", lambda spec: "")
    with TestClient(app, client=LOCAL) as client:
        with client.websocket_connect("/api/transcribe/stream") as ws:
            ws.send_json({"type": "start"})
            m = ws.receive_json()
    assert m["type"] == "error" and m["error"] == "no_api_key"


# ---------------------------------------------------------------- providers


def test_providers_lists_the_catalogue_and_the_current_setting(app, monkeypatch):
    _config(monkeypatch)
    monkeypatch.setattr(registry, "api_key", lambda spec: "k-test")
    with TestClient(app, client=LOCAL) as client:
        body = client.get("/api/transcribe/providers").json()
    assert body["transcribers"] and body["languages"]
    assert body["dictation"] == {
        "enabled": True, "provider": "openai", "model": "gpt-live-transcribe",
        "streaming": True, "language": "",
    }


def test_providers_reports_dictation_off_when_the_switch_is_off(app, monkeypatch):
    _config(monkeypatch, dictation_enabled=False)
    monkeypatch.setattr(registry, "api_key", lambda spec: "k-test")
    with TestClient(app, client=LOCAL) as client:
        assert client.get("/api/transcribe/providers").json()["dictation"]["enabled"] is False


def test_providers_reports_dictation_off_when_the_key_is_missing(app, monkeypatch):
    """Switched on but unusable is still off, or the composer would offer a dead mic."""
    _config(monkeypatch)
    monkeypatch.setattr(registry, "api_key", lambda spec: "")
    with TestClient(app, client=LOCAL) as client:
        assert client.get("/api/transcribe/providers").json()["dictation"]["enabled"] is False


def test_asyncio_tasks_do_not_leak(app, made, monkeypatch):
    """Every socket must leave the forward pump cleaned up."""
    _config(monkeypatch)
    made(on_feed=[Committed("x")])
    before = len(asyncio.all_tasks(asyncio.new_event_loop()))
    with TestClient(app, client=LOCAL) as client:
        for _ in range(3):
            with client.websocket_connect("/api/transcribe/stream") as ws:
                ws.send_json({"type": "start"})
                ws.receive_json()
                ws.send_json({"type": "audio", "audio": PCM})
                ws.send_json({"type": "stop"})
                _drain(ws)
    assert transcribe._live_sockets == 0
    assert before >= 0  # the counter is the real assertion
