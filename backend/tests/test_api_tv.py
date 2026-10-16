"""Tests for api/routes/tv.py — the Archie TV control channel (spec 15 §5)."""

from __future__ import annotations

import asyncio
import threading

import orjson
import pytest
from fastapi import FastAPI
from starlette.testclient import TestClient
from starlette.websockets import WebSocketDisconnect

from api.routes import tv
from api.routes.tv import (
    TvAmbiguous,
    TvCommandError,
    TvCommandTimeout,
    TvConnection,
    TvHub,
    TvNotConnected,
)

TOKEN = "tv-token-xyz789"
DEVICE = "6f1c0d2e-0000-4000-8000-000000000001"


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

class FakeWebSocket:
    """Minimal WebSocket stand-in that records what the hub sends."""

    def __init__(self, fail_send: bool = False) -> None:
        self.sent: list[dict] = []
        self.closed_with: int | None = None
        self._fail_send = fail_send

    async def send_text(self, text: str) -> None:
        if self._fail_send:
            raise RuntimeError("socket is gone")
        self.sent.append(orjson.loads(text))

    async def close(self, code: int = 1000) -> None:
        self.closed_with = code


def _conn(device_id: str = DEVICE, name: str = "Living room TV", ws=None) -> TvConnection:
    return TvConnection(ws=ws or FakeWebSocket(), device_id=device_id, device_name=name,
                        client="archie-tv", version="1.0.0")


def _make_app() -> FastAPI:
    """Mount only the TV router (no create_app lifespan: pool, watchers, search)."""
    app = FastAPI()
    app.state.tv_hub = TvHub()
    app.include_router(tv.router)
    return app


@pytest.fixture
def no_token(monkeypatch, tmp_path):
    """Unset the env var *and* point PROJECT_ROOT at an empty tree, so a real
    context/.env on the dev machine cannot leak into the test."""
    monkeypatch.delenv(tv.TOKEN_ENV_VAR, raising=False)
    monkeypatch.setattr(tv, "PROJECT_ROOT", tmp_path)


@pytest.fixture
def token(monkeypatch):
    monkeypatch.setenv(tv.TOKEN_ENV_VAR, TOKEN)
    return TOKEN


def _hello(token: str | None = None, device_id: str | None = DEVICE,
           name: str = "Living room TV") -> str:
    msg = {
        "type": "hello", "device_name": name, "client": "archie-tv",
        "version": "1.0.0", "capabilities": ["open_url", "open_visualization"],
    }
    if device_id is not None:
        msg["device_id"] = device_id
    if token is not None:
        msg["token"] = token
    return orjson.dumps(msg).decode()


# ---------------------------------------------------------------------------
# Token resolution
# ---------------------------------------------------------------------------

def test_token_from_environment(token):
    assert tv._resolve_token() == TOKEN


def test_token_falls_back_to_context_env(monkeypatch, tmp_path):
    monkeypatch.delenv(tv.TOKEN_ENV_VAR, raising=False)
    (tmp_path / "context").mkdir()
    (tmp_path / "context" / ".env").write_text(
        f"# c\nOTHER=x\n{tv.TOKEN_ENV_VAR}='quoted'\n"
    )
    monkeypatch.setattr(tv, "PROJECT_ROOT", tmp_path)
    assert tv._resolve_token() == "quoted"


def test_token_absent_or_blank_is_none(no_token, monkeypatch):
    assert tv._resolve_token() is None
    monkeypatch.setenv(tv.TOKEN_ENV_VAR, "   ")
    assert tv._resolve_token() is None


# ---------------------------------------------------------------------------
# Handshake
# ---------------------------------------------------------------------------

def test_hello_without_token_accepted_when_none_configured(no_token):
    with TestClient(_make_app()) as client:
        with client.websocket_connect("/api/tv/ws") as ws:
            ws.send_text(_hello())
            assert ws.receive_json() == {"type": "ready"}


def test_hello_with_valid_token_accepted(token):
    with TestClient(_make_app()) as client:
        with client.websocket_connect("/api/tv/ws") as ws:
            ws.send_text(_hello(TOKEN))
            assert ws.receive_json() == {"type": "ready"}


@pytest.mark.parametrize("presented", [None, "wrong"])
def test_hello_with_bad_token_rejected_4401(token, presented):
    with TestClient(_make_app()) as client:
        with client.websocket_connect("/api/tv/ws") as ws:
            ws.send_text(_hello(presented))
            assert ws.receive_json() == {"type": "error", "error": "unauthorized"}
            with pytest.raises(WebSocketDisconnect) as exc:
                ws.receive_json()
            assert exc.value.code == tv.WS_CLOSE_UNAUTHORIZED
        assert client.get("/api/tv/status").json()["devices"] == []


@pytest.mark.parametrize("frame,error", [
    ("not json", "invalid_json"),
    (orjson.dumps({"type": "state"}).decode(), "expected_hello"),
    (orjson.dumps([1, 2]).decode(), "expected_hello"),
    (_hello(device_id=None), "missing_device_id"),
    (_hello(device_id="  "), "missing_device_id"),
])
def test_malformed_hello_rejected(no_token, frame, error):
    with TestClient(_make_app()) as client:
        with client.websocket_connect("/api/tv/ws") as ws:
            ws.send_text(frame)
            assert ws.receive_json() == {"type": "error", "error": error}


def test_hello_timeout(no_token, monkeypatch):
    monkeypatch.setattr(tv, "HELLO_TIMEOUT_S", 0.05)
    with TestClient(_make_app()) as client:
        with client.websocket_connect("/api/tv/ws") as ws:
            assert ws.receive_json() == {"type": "error", "error": "hello_timeout"}


def test_ping_pong_and_junk_ignored(no_token):
    with TestClient(_make_app()) as client:
        with client.websocket_connect("/api/tv/ws") as ws:
            ws.send_text(_hello())
            ws.receive_json()  # ready
            ws.send_text("<<< junk >>>")
            ws.send_text(orjson.dumps([1]).decode())
            ws.send_text(orjson.dumps({"type": "mystery"}).decode())
            ws.send_text(orjson.dumps({"type": "result", "id": "never-issued", "ok": True}).decode())
            ws.send_text(orjson.dumps({"type": "ping"}).decode())
            assert ws.receive_json() == {"type": "pong"}


# ---------------------------------------------------------------------------
# Status
# ---------------------------------------------------------------------------

def test_status_empty(no_token):
    with TestClient(_make_app()) as client:
        body = client.get("/api/tv/status").json()
    assert body == {"devices": [], "token_configured": False}


def test_status_lists_device_and_caches_state_frames(no_token):
    with TestClient(_make_app()) as client:
        with client.websocket_connect("/api/tv/ws") as ws:
            ws.send_text(_hello())
            ws.receive_json()  # ready
            ws.send_text(orjson.dumps({
                "type": "state", "screen": "viewer", "url": "https://x/v.html",
                "title": "V", "voice": "idle",
            }).decode())
            ws.send_text(orjson.dumps({"type": "ping"}).decode())
            ws.receive_json()  # pong: the state frame was processed before it
            (dev,) = client.get("/api/tv/status").json()["devices"]
        after = client.get("/api/tv/status").json()["devices"]

    assert dev["device_id"] == DEVICE
    assert dev["device_name"] == "Living room TV"
    assert dev["client"] == "archie-tv"
    assert dev["version"] == "1.0.0"
    assert dev["capabilities"] == ["open_url", "open_visualization"]
    assert isinstance(dev["connected_at"], float)
    assert dev["state"] == {"screen": "viewer", "url": "https://x/v.html",
                            "title": "V", "voice": "idle"}
    assert after == []  # disconnect removes it


def test_name_defaults_to_device_id(no_token):
    with TestClient(_make_app()) as client:
        with client.websocket_connect("/api/tv/ws") as ws:
            ws.send_text(orjson.dumps({"type": "hello", "device_id": "abc"}).decode())
            ws.receive_json()
            (dev,) = client.get("/api/tv/status").json()["devices"]
    assert dev["device_name"] == "abc"
    assert dev["client"] == "unknown"


# ---------------------------------------------------------------------------
# End to end: HTTP command → TV socket → result
# ---------------------------------------------------------------------------

def test_command_round_trip_over_the_socket(no_token):
    with TestClient(_make_app()) as client:
        with client.websocket_connect("/api/tv/ws") as ws:
            ws.send_text(_hello())
            ws.receive_json()  # ready

            out: dict = {}
            t = threading.Thread(target=lambda: out.setdefault("r", client.post(
                "/api/tv/command",
                json={"command": "open_visualization",
                      "params": {"path": "visualizations/x.html"}},
            )))
            t.start()
            cmd = ws.receive_json()
            assert cmd["type"] == "command"
            assert cmd["command"] == "open_visualization"
            assert cmd["params"] == {"path": "visualizations/x.html"}
            assert len(cmd["id"]) == 12
            ws.send_text(orjson.dumps({
                "id": cmd["id"], "type": "result", "ok": True,
                "result": {"screen": "viewer", "url": "u", "title": "X"},
            }).decode())
            t.join(5)

    r = out["r"]
    assert r.status_code == 200
    assert r.json() == {
        "ok": True,
        "device": {"device_id": DEVICE, "device_name": "Living room TV"},
        "command": "open_visualization",
        "result": {"screen": "viewer", "url": "u", "title": "X"},
    }


def test_reconnect_same_device_closes_old_socket_4409(no_token):
    with TestClient(_make_app()) as client:
        with client.websocket_connect("/api/tv/ws") as old:
            old.send_text(_hello())
            old.receive_json()
            with client.websocket_connect("/api/tv/ws") as new:
                new.send_text(_hello(name="Renamed TV"))
                assert new.receive_json() == {"type": "ready"}
                with pytest.raises(WebSocketDisconnect) as exc:
                    old.receive_json()
                assert exc.value.code == tv.WS_CLOSE_REPLACED
                (dev,) = client.get("/api/tv/status").json()["devices"]
                assert dev["device_name"] == "Renamed TV"


# ---------------------------------------------------------------------------
# Hub: dispatch and correlation
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
async def test_send_without_any_tv_raises_not_connected():
    with pytest.raises(TvNotConnected):
        await TvHub().send_command("home")


@pytest.mark.asyncio
async def test_results_correlate_by_id_not_arrival_order():
    hub = TvHub()
    conn = _conn()
    await hub.attach(conn)

    first = asyncio.create_task(hub.send_command("home"))
    second = asyncio.create_task(hub.send_command("back"))
    await asyncio.sleep(0)
    id_a, id_b = conn.ws.sent[0]["id"], conn.ws.sent[1]["id"]
    assert id_a != id_b

    hub.handle_result(conn, {"id": id_b, "type": "result", "ok": True, "result": "B"})
    hub.handle_result(conn, {"id": id_a, "type": "result", "ok": True, "result": "A"})
    assert (await first)[1] == "A"
    assert (await second)[1] == "B"
    assert conn.pending == {}


@pytest.mark.asyncio
async def test_failed_result_raises_command_error():
    hub = TvHub()
    conn = _conn()
    await hub.attach(conn)
    task = asyncio.create_task(hub.send_command("frobnicate"))
    await asyncio.sleep(0)
    hub.handle_result(conn, {"id": conn.ws.sent[0]["id"], "type": "result",
                             "ok": False, "error": "unknown command: frobnicate"})
    with pytest.raises(TvCommandError, match="unknown command"):
        await task


@pytest.mark.asyncio
async def test_timeout_then_late_result_is_ignored():
    hub = TvHub()
    conn = _conn()
    await hub.attach(conn)
    with pytest.raises(TvCommandTimeout, match="timed out"):
        await hub.send_command("home", timeout=0.05)
    assert conn.pending == {}
    hub.handle_result(conn, {"id": conn.ws.sent[0]["id"], "type": "result",
                             "ok": True, "result": "late"})  # must not raise


@pytest.mark.asyncio
async def test_result_from_another_tv_does_not_resolve():
    """Pending futures are per connection: TV B cannot answer TV A's command."""
    hub = TvHub()
    a, b = _conn("a", "A"), _conn("b", "B")
    await hub.attach(a)
    await hub.attach(b)
    task = asyncio.create_task(hub.send_command("home", device="a", timeout=0.1))
    await asyncio.sleep(0)
    hub.handle_result(b, {"id": a.ws.sent[0]["id"], "type": "result", "ok": True})
    with pytest.raises(TvCommandTimeout):
        await task


@pytest.mark.asyncio
async def test_send_failure_surfaces_as_not_connected():
    hub = TvHub()
    conn = _conn(ws=FakeWebSocket(fail_send=True))
    await hub.attach(conn)
    with pytest.raises(TvNotConnected, match="failed to send"):
        await hub.send_command("home")
    assert conn.pending == {}


@pytest.mark.asyncio
async def test_get_state_result_updates_cached_state():
    hub = TvHub()
    conn = _conn()
    await hub.attach(conn)
    task = asyncio.create_task(hub.send_command("get_state"))
    await asyncio.sleep(0)
    hub.handle_result(conn, {"id": conn.ws.sent[0]["id"], "type": "result", "ok": True,
                             "result": {"type": "state", "screen": "home"}})
    await task
    assert hub.status()["devices"][0]["state"] == {"screen": "home"}


# ---------------------------------------------------------------------------
# Hub: lifecycle
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
async def test_disconnect_fails_only_that_devices_pending():
    hub = TvHub()
    a, b = _conn("a", "A"), _conn("b", "B")
    await hub.attach(a)
    await hub.attach(b)
    ta = asyncio.create_task(hub.send_command("home", device="a", timeout=30))
    tb = asyncio.create_task(hub.send_command("home", device="b", timeout=30))
    await asyncio.sleep(0)

    await hub.detach(a)
    with pytest.raises(TvNotConnected, match="disconnected"):
        await ta
    assert not tb.done()
    hub.handle_result(b, {"id": b.ws.sent[0]["id"], "type": "result", "ok": True,
                          "result": {"screen": "home"}})
    conn, result = await tb
    assert conn is b and result == {"screen": "home"}
    assert [d["device_id"] for d in hub.status()["devices"]] == ["b"]


@pytest.mark.asyncio
async def test_reconnect_replaces_and_fails_old_pending():
    hub = TvHub()
    old, new = _conn(name="old"), _conn(name="new")
    await hub.attach(old)
    task = asyncio.create_task(hub.send_command("home", timeout=30))
    await asyncio.sleep(0)

    await hub.attach(new)
    with pytest.raises(TvNotConnected, match="reconnected"):
        await task
    assert old.ws.closed_with == tv.WS_CLOSE_REPLACED

    # The displaced socket's receive loop also detaches; that must not tear
    # down its replacement.
    await hub.detach(old)
    assert hub.resolve() is new


# ---------------------------------------------------------------------------
# Hub: targeting
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
async def test_targeting_by_id_name_and_ambiguity():
    hub = TvHub()
    with pytest.raises(TvNotConnected):
        hub.resolve()
    one = _conn("id-1", "Living room TV")
    await hub.attach(one)
    assert hub.resolve() is one                       # exactly one: implicit
    assert hub.resolve("id-1") is one                  # by id
    assert hub.resolve("living ROOM tv") is one        # by name, case-insensitive
    with pytest.raises(TvNotConnected, match="Bedroom"):
        hub.resolve("Bedroom")

    two = _conn("id-2", "Bedroom")
    await hub.attach(two)
    with pytest.raises(TvAmbiguous) as exc:
        hub.resolve()
    assert exc.value.names == ["Living room TV", "Bedroom"]
    assert hub.resolve("Bedroom") is two

    twin = _conn("id-3", "Bedroom")
    await hub.attach(twin)
    with pytest.raises(TvAmbiguous):
        hub.resolve("bedroom")                         # duplicate names
    assert hub.resolve("id-3") is twin                 # the id still works


# ---------------------------------------------------------------------------
# POST /api/tv/command — status mapping and the loopback gate
# ---------------------------------------------------------------------------

class _StubHub:
    """Hub stand-in for endpoint tests: records calls, replays canned outcomes."""

    def __init__(self, result=None, raises=None):
        self.calls = []
        self._result = result if result is not None else {"screen": "home"}
        self._raises = raises
        self.connected = True

    async def send_command(self, command, params=None, device=None, timeout=None):
        self.calls.append((command, params, device, timeout))
        if self._raises:
            raise self._raises
        return _conn(), self._result

    def status(self):
        return {"devices": []}


def _app_with(hub) -> FastAPI:
    app = _make_app()
    app.state.tv_hub = hub
    return app


def test_command_relays_device_and_timeout(no_token):
    hub = _StubHub(result={"screen": "viewer"})
    with TestClient(_app_with(hub)) as client:
        r = client.post("/api/tv/command", json={
            "command": "open_url", "params": {"url": "/x/"},
            "device": "Living room TV", "timeout": 3,
        })
    assert r.status_code == 200
    assert r.json()["result"] == {"screen": "viewer"}
    assert r.json()["device"]["device_name"] == "Living room TV"
    assert hub.calls == [("open_url", {"url": "/x/"}, "Living room TV", 3.0)]


def test_command_default_timeout_and_clamp(no_token):
    hub = _StubHub()
    with TestClient(_app_with(hub)) as client:
        client.post("/api/tv/command", json={"command": "home"})
        client.post("/api/tv/command", json={"command": "home", "timeout": 9999})
    assert hub.calls[0][3] == tv.DEFAULT_COMMAND_TIMEOUT_S
    assert hub.calls[1][3] == tv.MAX_COMMAND_TIMEOUT_S


@pytest.mark.parametrize("raises,status", [
    (TvNotConnected("no Archie TV app is connected"), 503),
    (TvAmbiguous(["A", "B"]), 409),
    (TvCommandTimeout("timed out"), 504),
    (TvCommandError("unknown command"), 422),
])
def test_command_error_mapping(no_token, raises, status):
    with TestClient(_app_with(_StubHub(raises=raises))) as client:
        r = client.post("/api/tv/command", json={"command": "home"})
    assert r.status_code == status
    if status == 409:
        assert "'A', 'B'" in r.json()["detail"]


@pytest.mark.parametrize("body", [
    {"params": {}},
    {"command": ""},
    {"command": "home", "params": [1]},
    {"command": "home", "device": 5},
    {"command": "home", "timeout": "soon"},
])
def test_command_bad_requests(no_token, body):
    hub = _StubHub()
    with TestClient(_app_with(hub)) as client:
        assert client.post("/api/tv/command", json=body).status_code == 400
    assert hub.calls == []


def test_command_non_json_body(no_token):
    with TestClient(_app_with(_StubHub())) as client:
        r = client.post("/api/tv/command", content=b"nope",
                        headers={"Content-Type": "application/json"})
    assert r.status_code == 400


@pytest.mark.parametrize("header", ["X-Real-IP", "X-Forwarded-For"])
def test_command_refuses_lan_requests_through_the_proxy(no_token, header):
    hub = _StubHub()
    with TestClient(_app_with(hub)) as client:
        r = client.post("/api/tv/command", json={"command": "home"},
                        headers={header: "192.168.0.55"})
    assert r.status_code == 403
    assert "loopback-only" in r.json()["detail"]
    assert hub.calls == []


def test_command_allows_loopback_forwarded_value(no_token):
    with TestClient(_app_with(_StubHub())) as client:
        r = client.post("/api/tv/command", json={"command": "home"},
                        headers={"X-Real-IP": "127.0.0.1"})
    assert r.status_code == 200


def test_real_hub_without_tv_is_503(no_token):
    with TestClient(_make_app()) as client:
        r = client.post("/api/tv/command", json={"command": "home"})
    assert r.status_code == 503
    assert "not connected" in r.json()["detail"]
