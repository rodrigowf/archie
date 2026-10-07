"""Orchestrator WS additions (O-3): ``jsonl_id`` in ``session_started`` and a
``user_message`` echo of typed ``send``s and ``send_audio`` voice messages to
the other subscribers."""

from __future__ import annotations

from unittest.mock import MagicMock

import orjson
from starlette.testclient import TestClient

from api.app import create_app
from api.pool import SessionPool
from orchestrator.types import TextComplete


def _fake_orchestrator():
    session = MagicMock()
    session.is_voice = False
    session.jsonl_id = "jsonl-abc"
    session.get_model_info.return_value = {"id": "test-model"}
    sent: list[str] = []

    async def _send(text):
        sent.append(text)
        yield TextComplete(text=f"echo {text}")

    async def _send_audio(audio, fmt, text):
        sent.append(("audio", audio, fmt, text))
        yield TextComplete(text="heard you")

    session.send = _send
    session.send_audio = _send_audio
    session.sent = sent
    return session


def _client_with_orchestrator():
    app = create_app()
    pool = SessionPool()
    session = _fake_orchestrator()
    pool._orchestrator = session
    pool._orchestrator_id = "orch-1"
    app.state.pool = pool
    app.state.store = MagicMock()
    return TestClient(app), session


def _recv(ws):
    return orjson.loads(ws.receive_bytes())


def test_session_started_carries_jsonl_id():
    client, _ = _client_with_orchestrator()
    with client.websocket_connect("/api/orchestrator/chat") as ws:
        ws.send_text(orjson.dumps({"type": "start", "local_id": "orch-1"}).decode())
        started = _recv(ws)
        assert started["type"] == "session_started"
        assert started["session_id"] == "orch-1"
        assert started["jsonl_id"] == "jsonl-abc"


def test_typed_send_is_echoed_to_other_subscribers_only():
    client, session = _client_with_orchestrator()
    start = orjson.dumps({"type": "start", "local_id": "orch-1"}).decode()
    with client.websocket_connect("/api/orchestrator/chat") as ws1, \
            client.websocket_connect("/api/orchestrator/chat") as ws2:
        ws1.send_text(start)
        assert _recv(ws1)["type"] == "session_started"
        ws2.send_text(start)
        assert _recv(ws2)["type"] == "session_started"

        ws1.send_text(orjson.dumps({"type": "send", "text": "hello"}).decode())

        # Observer: the prompt first, then the turn.
        assert _recv(ws2) == {"type": "user_message", "text": "hello"}
        assert _recv(ws2) == {"type": "status", "status": "streaming"}
        # Sender: no echo of its own prompt.
        assert _recv(ws1) == {"type": "status", "status": "streaming"}

        for ws in (ws1, ws2):
            assert _recv(ws)["type"] == "text_complete"
            assert _recv(ws) == {"type": "status", "status": "idle"}
    assert session.sent == ["hello"]


def test_empty_send_is_not_echoed():
    client, _ = _client_with_orchestrator()
    start = orjson.dumps({"type": "start", "local_id": "orch-1"}).decode()
    with client.websocket_connect("/api/orchestrator/chat") as ws1, \
            client.websocket_connect("/api/orchestrator/chat") as ws2:
        ws1.send_text(start)
        _recv(ws1)
        ws2.send_text(start)
        _recv(ws2)
        ws1.send_text(orjson.dumps({"type": "send", "text": ""}).decode())
        assert _recv(ws2) == {"type": "status", "status": "streaming"}


def _start_both(ws1, ws2):
    start = orjson.dumps({"type": "start", "local_id": "orch-1"}).decode()
    for ws in (ws1, ws2):
        ws.send_text(start)
        assert _recv(ws)["type"] == "session_started"


def _send_audio(ws, audio="UklGRg==", **extra):
    ws.send_text(orjson.dumps({"type": "send_audio", "audio": audio, "format": "wav", **extra}).decode())


def test_voice_message_is_echoed_to_other_subscribers_only():
    client, session = _client_with_orchestrator()
    with client.websocket_connect("/api/orchestrator/chat") as ws1, \
            client.websocket_connect("/api/orchestrator/chat") as ws2:
        _start_both(ws1, ws2)
        _send_audio(ws1, text="about this photo")

        # Observer: the voice message (with its accompanying prompt) first, then the turn.
        assert _recv(ws2) == {
            "type": "user_message", "text": "about this photo", "source": "voice_message",
        }
        assert _recv(ws2) == {"type": "status", "status": "streaming"}
        # Sender: no echo of its own voice message.
        assert _recv(ws1) == {"type": "status", "status": "streaming"}

        for ws in (ws1, ws2):
            assert _recv(ws)["type"] == "text_complete"
            assert _recv(ws) == {"type": "status", "status": "idle"}
    assert session.sent == [("audio", b"RIFF", "wav", "about this photo")]


def test_voice_message_without_prompt_echoes_empty_text():
    client, _ = _client_with_orchestrator()
    with client.websocket_connect("/api/orchestrator/chat") as ws1, \
            client.websocket_connect("/api/orchestrator/chat") as ws2:
        _start_both(ws1, ws2)
        _send_audio(ws1)
        assert _recv(ws2) == {"type": "user_message", "text": "", "source": "voice_message"}
        assert _recv(ws2) == {"type": "status", "status": "streaming"}


def test_invalid_voice_message_is_not_echoed():
    client, session = _client_with_orchestrator()
    with client.websocket_connect("/api/orchestrator/chat") as ws1, \
            client.websocket_connect("/api/orchestrator/chat") as ws2:
        _start_both(ws1, ws2)
        _send_audio(ws1, audio="abc")
        # No turn starts, so nothing is echoed: only the error reaches the observer.
        assert _recv(ws2)["error"] == "invalid_audio"
        assert _recv(ws1)["error"] == "invalid_audio"
    assert session.sent == []


def test_switch_conversation_stops_then_tells_only_the_sender(monkeypatch):
    """switch_conversation over the real route + pool: every watcher sees the orchestrator close,
    only the socket that asked gets orchestrator_switch, and it comes after the close."""
    from types import SimpleNamespace

    from orchestrator.tools import agent_sessions

    monkeypatch.setattr(agent_sessions, "SWITCH_SETTLE_S", 0)
    client, session = _client_with_orchestrator()
    pool = client.app.state.pool
    store = MagicMock()
    store.get_session_info.return_value = SimpleNamespace(is_orchestrator=True, title="Lamps chat")
    session.local_id, session.is_busy, session.voice_owner_ws, session.last_input_ws = "orch-1", False, None, None

    async def _stop():
        session.stopped = True

    session.stop = _stop

    async def _send(text):  # the real send() holds the busy lock for the whole turn
        session.is_busy = True
        out = await agent_sessions.switch_conversation({"pool": pool, "store": store}, "past-123")
        yield TextComplete(text=out)
        session.is_busy = False

    session.send = _send
    with client.websocket_connect("/api/orchestrator/chat") as ws1, \
            client.websocket_connect("/api/orchestrator/chat") as ws2:
        _start_both(ws1, ws2)
        ws1.send_text(orjson.dumps({"type": "send", "text": "go back to the lamps chat"}).decode())
        assert _recv(ws2)["type"] == "user_message"
        for ws in (ws1, ws2):
            assert _recv(ws) == {"type": "status", "status": "streaming"}
            assert '"switching"' in _recv(ws)["text"]
            assert _recv(ws) == {"type": "status", "status": "idle"}
            assert _recv(ws) == {"type": "agent_session_closed", "session_id": "orch-1", "is_orchestrator": True}
        assert _recv(ws1) == {"type": "orchestrator_switch", "sdk_session_id": "past-123", "title": "Lamps chat",
                              "voice": False, "from_session_id": "orch-1"}
        assert session.stopped and not pool.has_orchestrator()
        ws2.send_text(orjson.dumps({"type": "ping"}).decode())  # ws2 got nothing else before this
