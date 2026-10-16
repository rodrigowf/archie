"""Tests for POST /api/transcribe (spec 15 §4.1).

The OpenAI call goes through an ``httpx.MockTransport`` — no network, no paid calls.
"""

from __future__ import annotations

import io
import struct

import httpx
import pytest
from fastapi import FastAPI
from starlette.testclient import TestClient

from api.routes import transcribe

# TestClient's default peer ("testclient") is not an address; the endpoint
# only serves the home network.
LOCAL = ("127.0.0.1", 50000)


def _wav(n_samples: int = 1600) -> bytes:
    """A valid 16 kHz mono PCM16 WAV of silence."""
    data = b"\x00\x00" * n_samples
    return (
        b"RIFF" + struct.pack("<I", 36 + len(data)) + b"WAVE"
        + b"fmt " + struct.pack("<IHHIIHH", 16, 1, 1, 16000, 32000, 2, 16)
        + b"data" + struct.pack("<I", len(data)) + data
    )


@pytest.fixture
def app() -> FastAPI:
    a = FastAPI()
    a.include_router(transcribe.router)
    return a


@pytest.fixture
def key(monkeypatch):
    monkeypatch.setattr(transcribe, "_resolve_openai_key", lambda: "sk-test")


@pytest.fixture
def openai(monkeypatch):
    """Route the OpenAI call to a handler; returns the list of captured requests
    and a dict to set the reply (``status``, ``json``, ``raise``)."""
    seen: list[httpx.Request] = []
    reply: dict = {"status": 200, "json": {"text": "  Hey Archie, open the solar dashboard. "}}

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        if "raise" in reply:
            raise reply["raise"]
        return httpx.Response(reply["status"], json=reply.get("json"))

    monkeypatch.setattr(
        transcribe, "_make_client",
        lambda: httpx.AsyncClient(transport=httpx.MockTransport(handler)),
    )
    return seen, reply


def _post(client, audio: bytes, language: str | None = None):
    data = {"language": language} if language is not None else {}
    return client.post(
        "/api/transcribe",
        files={"audio": ("burst.wav", io.BytesIO(audio), "audio/wav")},
        data=data,
    )


def test_transcribes_with_whisper_and_server_key(app, key, openai):
    seen, _ = openai
    with TestClient(app, client=LOCAL) as client:
        r = _post(client, _wav(), language="pt")
    assert r.status_code == 200
    assert r.json() == {"text": "Hey Archie, open the solar dashboard."}

    (req,) = seen
    assert str(req.url) == transcribe.OPENAI_TRANSCRIPTIONS_URL
    assert req.headers["authorization"] == "Bearer sk-test"
    body = req.content
    assert b'name="model"' in body and b"whisper-1" in body
    assert b'name="language"' in body and b"\r\npt\r\n" in body
    assert b'filename="audio.wav"' in body
    assert _wav() in body


def test_language_is_optional(app, key, openai):
    seen, _ = openai
    with TestClient(app, client=LOCAL) as client:
        assert _post(client, _wav()).status_code == 200
    assert b'name="language"' not in seen[0].content


def test_too_large_is_413_without_calling_openai(app, key, openai):
    seen, _ = openai
    big = _wav(transcribe.MAX_AUDIO_BYTES // 2 + 10)
    with TestClient(app, client=LOCAL) as client:
        assert _post(client, big).status_code == 413
    assert seen == []


def test_oversized_declared_length_is_413_before_parsing(app, key, openai, monkeypatch):
    """A Content-Length over the cap is refused without reading or parsing."""
    seen, _ = openai

    async def no_form(*a, **k):  # pragma: no cover - must not be reached
        raise AssertionError("the body was parsed")

    monkeypatch.setattr(transcribe.Request, "form", no_form)
    body = b"x" * (transcribe.MAX_BODY_BYTES + 1)
    with TestClient(app, client=LOCAL) as client:
        r = client.post("/api/transcribe", content=body,
                        headers={"Content-Type": "multipart/form-data; boundary=b"})
    assert r.status_code == 413
    assert seen == []


def test_oversized_chunked_body_is_413(app, key, openai):
    """No Content-Length (chunked): the stream is cut off at the cap."""
    seen, _ = openai

    def chunks():
        for _ in range(4):
            yield b"x" * (transcribe.MAX_BODY_BYTES // 2)

    with TestClient(app, client=LOCAL) as client:
        r = client.post("/api/transcribe", content=chunks(),
                        headers={"Content-Type": "multipart/form-data; boundary=b"})
    assert r.status_code == 413
    assert seen == []


@pytest.mark.parametrize("peer,headers,status", [
    (("192.168.0.31", 1), {}, 200),                       # LAN, direct
    (("100.97.1.2", 1), {}, 200),                         # Tailscale, direct
    (("127.0.0.1", 1), {"X-Real-IP": "192.168.0.31"}, 200),  # LAN via nginx
    (("127.0.0.1", 1), {"X-Real-IP": "100.80.0.7"}, 200),    # Tailscale via nginx
    (("8.8.8.8", 1), {}, 403),                            # the internet, direct
    (("127.0.0.1", 1), {"X-Real-IP": "203.0.113.9"}, 403),   # the internet via nginx
    (("8.8.8.8", 1), {"X-Real-IP": "127.0.0.1"}, 403),    # spoofed header ignored
    (("8.8.8.8", 1), {"X-Forwarded-For": "192.168.0.2"}, 403),
    # uvicorn rewrote the peer from a client-written X-Forwarded-For; nginx's
    # X-Real-IP still names the internet client.
    (("192.168.0.31", 1), {"X-Real-IP": "203.0.113.9"}, 403),
])
def test_only_the_home_network_may_spend_the_key(app, key, openai, peer, headers, status):
    seen, _ = openai
    with TestClient(app, client=peer) as client:
        r = client.post(
            "/api/transcribe",
            files={"audio": ("burst.wav", io.BytesIO(_wav()), "audio/wav")},
            headers=headers,
        )
    assert r.status_code == status
    assert len(seen) == (1 if status == 200 else 0)


@pytest.mark.parametrize("audio,status", [(b"", 400), (b"ID3\x04not a wav file", 415)])
def test_empty_or_not_wav(app, key, openai, audio, status):
    seen, _ = openai
    with TestClient(app, client=LOCAL) as client:
        assert _post(client, audio).status_code == status
    assert seen == []


def test_missing_audio_field_is_422(app, key, openai):
    with TestClient(app, client=LOCAL) as client:
        assert client.post("/api/transcribe", data={"language": "en"}).status_code == 422


def test_no_key_is_503(app, monkeypatch, openai):
    seen, _ = openai
    monkeypatch.setattr(transcribe, "_resolve_openai_key", lambda: None)
    with TestClient(app, client=LOCAL) as client:
        r = _post(client, _wav())
    assert r.status_code == 503
    assert "OPENAI_API_KEY" in r.json()["detail"]
    assert seen == []


@pytest.mark.parametrize("reply,status", [
    ({"status": 401, "json": {"error": {"message": "bad key"}}}, 502),
    ({"status": 200, "json": {"no_text": True}}, 502),
    ({"raise": httpx.ConnectError("unreachable")}, 502),
    ({"raise": httpx.ReadTimeout("slow")}, 504),
])
def test_upstream_failures(app, key, openai, reply, status):
    _, current = openai
    current.clear()
    current.update(reply)
    with TestClient(app, client=LOCAL) as client:
        assert _post(client, _wav()).status_code == status


def test_registered_in_create_app():
    from api.app import create_app

    paths = {getattr(r, "path", None) for r in create_app().routes}
    assert {"/api/transcribe", "/api/tv/ws", "/api/tv/status", "/api/tv/command"} <= paths
