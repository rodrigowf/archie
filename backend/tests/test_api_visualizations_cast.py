"""Tests for the "Show on TV" endpoints (BX-2).

Every adb call is mocked — nothing is ever cast during tests.
"""

from __future__ import annotations

import asyncio
from pathlib import Path
from unittest.mock import AsyncMock, MagicMock

import pytest
from fastapi.testclient import TestClient

import api.routes.visualizations as viz
from api.app import create_app

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
    assert resp.json() == {"available": True, "reason": ""}


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
    assert body == {"ok": False, "message": "The TV did not respond in time"}


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
