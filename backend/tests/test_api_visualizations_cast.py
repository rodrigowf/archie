"""Tests for the "Show on TV" endpoints (BX-2, spec 15 §5.3).

Every adb call is mocked and the TV app is a fake connection on the hub —
nothing is ever cast during tests.
"""

from __future__ import annotations

import asyncio
from pathlib import Path
from unittest.mock import AsyncMock, MagicMock

import orjson
import pytest
from fastapi.testclient import TestClient

import api.routes.visualizations as viz
from api.app import create_app
from api.routes.tv import TvConnection, TvHub

FIRE_TV = "192.168.0.16:5555"
PHONE = "R58M123ABC"


@pytest.fixture
def public_dir(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Path:
    import utils.paths

    monkeypatch.setattr(utils.paths, "PROJECT_ROOT", tmp_path)
    d = tmp_path / "context" / "public"
    (d / "visualizations").mkdir(parents=True)
    (d / "visualizations" / "my chart.html").write_text("<html></html>")
    (d / "notes.md").write_text("not html")
    (tmp_path / "context" / "secret.html").write_text("secret")
    return d


@pytest.fixture
def adb(monkeypatch: pytest.MonkeyPatch):
    """Fake adb: a phone and a Fire TV connected; am start succeeds."""
    calls: list[tuple[str, ...]] = []
    responses: dict[str, object] = {
        "am": (0, "Starting: Intent { cmp=com.example.tvserverhub/"
                  ".WebPageViewActivity (has extras) }\n", ""),
    }

    async def fake_run(*args, timeout=10.0):
        calls.append(args)
        if args == ("devices",):
            return 0, (
                "List of devices attached\n"
                f"{PHONE}\tdevice\n{FIRE_TV}\tdevice\nemulator-5554\toffline\n"
            ), ""
        if "getprop" in args:
            serial = args[1]
            return 0, ("Amazon\n" if serial == FIRE_TV else "samsung\n"), ""
        if "am" in args:
            r = responses["am"]
            if isinstance(r, BaseException):
                raise r
            return r
        raise AssertionError(f"unexpected adb call {args}")

    monkeypatch.setattr(viz, "_run_adb", fake_run)
    monkeypatch.setattr(viz.shutil, "which", lambda name: "/usr/bin/adb")
    monkeypatch.setenv("VIZ_CAST_BASE_URL", "https://192.168.0.200/")
    monkeypatch.delenv("FIRE_TV_ADB_SERIAL", raising=False)
    return calls, responses


@pytest.fixture
def client(public_dir) -> TestClient:
    return TestClient(create_app())


def _am_calls(calls):
    return [c for c in calls if "am" in c]


def test_probe_available_picks_amazon_device(client, adb):
    resp = client.get("/api/visualizations/cast")
    assert resp.status_code == 200
    assert resp.json() == {"available": True, "via": "adb",
                           "reason": f"adb: Fire TV {FIRE_TV}"}


def test_probe_unavailable_without_adb(client, monkeypatch):
    monkeypatch.setattr(viz.shutil, "which", lambda name: None)
    run = AsyncMock()
    monkeypatch.setattr(viz, "_run_adb", run)
    body = client.get("/api/visualizations/cast").json()
    assert body["available"] is False
    assert "adb" in body["reason"]
    run.assert_not_awaited()


def test_probe_unavailable_without_fire_tv(client, adb, monkeypatch):
    async def only_phone(*args, timeout=10.0):
        if args == ("devices",):
            return 0, f"List of devices attached\n{PHONE}\tdevice\n", ""
        return 0, "samsung\n", ""
    monkeypatch.setattr(viz, "_run_adb", only_phone)
    assert client.get("/api/visualizations/cast").json()["available"] is False


def test_cast_runs_am_start_on_fire_tv(client, adb):
    calls, _ = adb
    resp = client.post(
        "/api/visualizations/cast",
        json={"path": "visualizations/my chart.html"},
    )
    assert resp.status_code == 200
    body = resp.json()
    assert body["ok"] is True
    assert body["via"] == "adb"
    (am,) = _am_calls(calls)
    assert am[:2] == ("-s", FIRE_TV)
    assert am[2:7] == ("shell", "am", "start", "-n", viz._TV_ACTIVITY)
    assert am[7] == "-e" and am[8] == "url"
    # URL is percent-encoded and shell-quoted for the remote shell.
    assert am[9] == "https://192.168.0.200/visualizations/my%20chart.html"


def test_cast_respects_pinned_serial(client, adb, monkeypatch):
    calls, _ = adb
    monkeypatch.setenv("FIRE_TV_ADB_SERIAL", PHONE)
    client.post("/api/visualizations/cast",
                json={"path": "visualizations/my chart.html"})
    (am,) = _am_calls(calls)
    assert am[1] == PHONE


@pytest.mark.parametrize("bad", [
    "../secret.html",
    "visualizations/../../secret.html",
    "/../secret.html",
    "notes.md",
    "visualizations/missing.html",
    "",
    "visualizations/../visualizations/my chart.html",
])
def test_cast_rejects_paths_outside_the_list(client, adb, bad):
    calls, _ = adb
    resp = client.post("/api/visualizations/cast", json={"path": bad})
    assert resp.status_code == 404
    assert _am_calls(calls) == []


def test_cast_reports_no_tv(client, adb, monkeypatch):
    monkeypatch.setattr(viz.shutil, "which", lambda name: None)
    body = client.post("/api/visualizations/cast",
                       json={"path": "visualizations/my chart.html"}).json()
    assert body["ok"] is False
    assert body["message"]


def test_cast_reports_timeout(client, adb):
    calls, responses = adb
    responses["am"] = asyncio.TimeoutError()
    body = client.post("/api/visualizations/cast",
                       json={"path": "visualizations/my chart.html"}).json()
    assert body == {"ok": False, "via": "adb", "message": "The TV did not respond in time"}


def test_cast_reports_am_error(client, adb):
    _, responses = adb
    responses["am"] = (0, "Starting: Intent {...}\nError type 3\n"
                          "Error: Activity class does not exist.\n", "")
    body = client.post("/api/visualizations/cast",
                       json={"path": "visualizations/my chart.html"}).json()
    assert body["ok"] is False
    assert "does not exist" in body["message"]


@pytest.mark.asyncio
async def test_run_adb_kills_on_timeout(monkeypatch):
    proc = MagicMock()

    async def hang():
        await asyncio.sleep(10)
    proc.communicate = hang
    proc.kill = MagicMock()
    proc.wait = AsyncMock()
    exec_mock = AsyncMock(return_value=proc)
    monkeypatch.setattr(viz.asyncio, "create_subprocess_exec", exec_mock)
    with pytest.raises(asyncio.TimeoutError):
        await viz._run_adb("devices", timeout=0.05)
    assert exec_mock.await_args.args[:2] == ("adb", "devices")
    proc.kill.assert_called_once()


# ---------------------------------------------------------------------------
# The Archie TV app route (preferred over adb when a TV is attached)
# ---------------------------------------------------------------------------

class FakeTvSocket:
    """A TV app connection that answers each command on the hub's loop:
    ``ok``, ``error``, ``refused`` (the user's setting is off) or ``silent``
    (never answers)."""

    def __init__(self, mode: str = "ok") -> None:
        self.mode = mode
        self.sent: list[dict] = []
        self.conn: TvConnection | None = None

    async def send_text(self, text: str) -> None:
        frame = orjson.loads(text)
        self.sent.append(frame)
        if self.mode == "silent":
            return
        reply = {"id": frame["id"], "type": "result", "ok": self.mode == "ok"}
        if self.mode == "ok":
            reply["result"] = {"screen": "viewer", "url": "u", "title": "My chart"}
        elif self.mode == "refused":
            reply["error"] = "agents may not change what this TV shows"
            reply["code"] = "refused"
        else:
            reply["error"] = "viewer crashed"
        asyncio.get_running_loop().call_soon(TvHub.handle_result, self.conn, reply)

    async def close(self, code: int = 1000) -> None:
        pass


def _attach_tv(client: TestClient, mode: str = "ok", device_id: str = "tv-1",
               name: str = "Living room TV") -> FakeTvSocket:
    ws = FakeTvSocket(mode)
    ws.conn = TvConnection(ws=ws, device_id=device_id, device_name=name)
    asyncio.run(client.app.state.tv_hub.attach(ws.conn))
    return ws


def test_cast_prefers_the_tv_app_and_skips_adb(client, adb):
    calls, _ = adb
    tv = _attach_tv(client)
    body = client.post("/api/visualizations/cast",
                       json={"path": "visualizations/my chart.html"}).json()
    assert body["ok"] is True
    assert body["via"] == "app"
    assert "Living room TV" in body["message"]
    (cmd,) = tv.sent
    assert cmd["type"] == "command"
    assert cmd["command"] == "open_visualization"
    # Root-relative, not percent-encoded: the TV encodes per segment (VZ-1).
    assert cmd["params"] == {"path": "visualizations/my chart.html"}
    assert calls == []  # not even `adb devices`


def test_cast_falls_back_to_adb_when_the_app_times_out(client, adb, monkeypatch):
    calls, _ = adb
    monkeypatch.setattr(viz, "_APP_CAST_TIMEOUT_S", 0.05)
    tv = _attach_tv(client, mode="silent")
    body = client.post("/api/visualizations/cast",
                       json={"path": "visualizations/my chart.html"}).json()
    assert len(tv.sent) == 1
    assert body["ok"] is True
    assert body["via"] == "adb"
    assert "did not answer" in body["message"]
    assert len(_am_calls(calls)) == 1


def test_cast_falls_back_to_adb_when_the_app_reports_an_error(client, adb):
    calls, _ = adb
    _attach_tv(client, mode="error")
    body = client.post("/api/visualizations/cast",
                       json={"path": "visualizations/my chart.html"}).json()
    assert body["via"] == "adb" and body["ok"] is True
    assert "viewer crashed" in body["message"]
    assert len(_am_calls(calls)) == 1


def test_cast_respects_the_tvs_refusal_and_skips_adb(client, adb):
    """The TV's user turned off "Allow agents to open pages": adb must not
    route around that choice."""
    calls, _ = adb
    tv = _attach_tv(client, mode="refused")
    body = client.post("/api/visualizations/cast",
                       json={"path": "visualizations/my chart.html"}).json()
    assert len(tv.sent) == 1
    assert body["ok"] is False and body["via"] == "app"
    assert "may not change" in body["message"]
    assert calls == []  # not even `adb devices`


def test_cast_falls_back_to_adb_when_the_send_fails(client, adb):
    calls, _ = adb
    tv = _attach_tv(client)

    async def broken(text: str) -> None:
        raise RuntimeError("socket is gone")

    tv.send_text = broken
    body = client.post("/api/visualizations/cast",
                       json={"path": "visualizations/my chart.html"}).json()
    assert body["ok"] is True and body["via"] == "adb"
    assert len(_am_calls(calls)) == 1


def test_cast_with_two_tvs_does_not_guess(client, adb):
    calls, _ = adb
    _attach_tv(client, device_id="a", name="A")
    _attach_tv(client, device_id="b", name="B")
    body = client.post("/api/visualizations/cast",
                       json={"path": "visualizations/my chart.html"}).json()
    assert body["ok"] is False and body["via"] == "app"
    assert "several TVs" in body["message"]
    assert calls == []


def test_cast_rejects_bad_path_before_asking_the_app(client, adb):
    tv = _attach_tv(client)
    assert client.post("/api/visualizations/cast",
                       json={"path": "../secret.html"}).status_code == 404
    assert tv.sent == []


def test_probe_available_via_app_without_adb(client, monkeypatch):
    monkeypatch.setattr(viz.shutil, "which", lambda name: None)
    run = AsyncMock()
    monkeypatch.setattr(viz, "_run_adb", run)
    _attach_tv(client)
    body = client.get("/api/visualizations/cast").json()
    assert body == {"available": True, "via": "app",
                    "reason": "Archie TV app: Living room TV"}
    run.assert_not_awaited()


def test_probe_unavailable_names_both_routes(client, monkeypatch):
    monkeypatch.setattr(viz.shutil, "which", lambda name: None)
    body = client.get("/api/visualizations/cast").json()
    assert body["available"] is False and body["via"] is None
    assert "Archie TV app" in body["reason"] and "adb" in body["reason"]
