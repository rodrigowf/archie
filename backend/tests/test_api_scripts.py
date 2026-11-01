"""Tests for the scripts API (api/routes/scripts.py) and utils/script_runner.py.

Covers the allowlist (JSON + ORCHESTRATOR_SCRIPTS.md, re-read per call, traversal), synchronous
runs (argv without a shell, stdin, JSON stdout, timeout), background jobs (output, cancel) and the
cross-site guard.
"""

from __future__ import annotations

import json
import os
import stat
import time
from pathlib import Path

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from api.app import create_app
from api.routes import scripts as scripts_routes
from utils import script_runner


def _jobs_client() -> TestClient:
    """Only the scripts router: jobs need one event loop across requests (``with`` the client),
    and the real app's lifespan would start the whole backend."""
    app = FastAPI()
    app.include_router(scripts_routes.router)
    return TestClient(app, base_url="http://127.0.0.1:8765")


@pytest.fixture
def root(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Path:
    import utils.paths

    monkeypatch.setattr(utils.paths, "PROJECT_ROOT", tmp_path)
    scripts = tmp_path / "context" / "scripts"
    scripts.mkdir(parents=True)
    (tmp_path / "context" / "memory").mkdir(parents=True)
    run_sh = scripts / "run.sh"
    run_sh.write_text('#!/bin/sh\nexec python3 "$@"\n')
    run_sh.chmod(run_sh.stat().st_mode | stat.S_IXUSR)
    return tmp_path


def _script(root: Path, name: str, body: str) -> str:
    p = root / "context" / "scripts" / name
    p.write_text(body)
    return f"context/scripts/{name}"


def _allow(root: Path, entries: dict) -> None:
    (root / "context" / "scripts" / "allowed_scripts.json").write_text(json.dumps({"scripts": entries}))


ECHO = "import sys, json\nprint(json.dumps({'argv': sys.argv[1:], 'stdin': sys.stdin.read() if not sys.stdin.isatty() else ''}))\n"


@pytest.fixture
def client(root: Path) -> TestClient:
    return TestClient(create_app(), base_url="http://127.0.0.1:8765")


class TestAllowlist:
    def test_json_and_orchestrator_lists_merge(self, root: Path) -> None:
        a = _script(root, "a.py", "print(1)")
        b = _script(root, "b.py", "print(2)")
        _allow(root, {a: {"description": "A", "timeout": 9999}})
        (root / "context" / "memory" / "ORCHESTRATOR_SCRIPTS.md").write_text(
            f"### b\n```\npath: {b}\n```\n### a again\n```\npath: ./{a}\n```\n"
        )
        allowed = script_runner.load_allowlist()
        assert set(allowed) == {a, b}
        assert allowed[a].source == "allowlist" and allowed[a].description == "A"
        assert allowed[a].timeout == script_runner.MAX_TIMEOUT_S  # capped
        assert allowed[b].source == "orchestrator"

    def test_reread_on_every_call(self, root: Path) -> None:
        a = _script(root, "a.py", "print(1)")
        assert script_runner.load_allowlist() == {}
        _allow(root, {a: {}})
        assert a in script_runner.load_allowlist()

    def test_list_endpoint(self, client: TestClient, root: Path) -> None:
        a = _script(root, "a.py", "print(1)")
        _allow(root, {a: {"description": "A", "usage": "a <x>"}, "context/scripts/gone.py": {}})
        rows = {r["script"]: r for r in client.get("/api/scripts").json()}
        assert rows[a]["description"] == "A" and rows[a]["exists"] is True
        assert rows["context/scripts/gone.py"]["exists"] is False

    @pytest.mark.parametrize("script", ["context/scripts/../scripts/x.py", "/etc/passwd", "../outside.py", "context/scripts/nope.py"])
    def test_not_allowlisted_is_403(self, client: TestClient, root: Path, script: str) -> None:
        _script(root, "x.py", "print(1)")
        _allow(root, {})
        r = client.post("/api/scripts/run", json={"script": script})
        assert r.status_code == 403

    def test_traversal_normalizes_onto_the_entry(self, client: TestClient, root: Path) -> None:
        a = _script(root, "a.py", "print('hi')")
        _allow(root, {a: {}})
        r = client.post("/api/scripts/run", json={"script": "context/scripts/../scripts/a.py"})
        assert r.status_code == 200 and r.json()["script"] == a

    def test_missing_file_is_404(self, client: TestClient, root: Path) -> None:
        _allow(root, {"context/scripts/gone.py": {}})
        assert client.post("/api/scripts/run", json={"script": "context/scripts/gone.py"}).status_code == 404

    def test_bad_body_is_400(self, client: TestClient, root: Path) -> None:
        assert client.post("/api/scripts/run", json={"args": []}).status_code == 400
        a = _script(root, "a.py", "print(1)")
        _allow(root, {a: {}})
        assert client.post("/api/scripts/run", json={"script": a, "args": "x"}).status_code == 400
        assert client.post("/api/scripts/run", json={"script": a, "args": [{"x": 1}]}).status_code == 400


class TestRun:
    def test_args_reach_the_script_verbatim(self, client: TestClient, root: Path) -> None:
        a = _script(root, "echo.py", ECHO)
        _allow(root, {a: {}})
        args = ["$(touch pwned)", "a b; rm -rf /", "`id`", 3, True]
        r = client.post("/api/scripts/run", json={"script": a, "args": args})
        body = r.json()
        assert r.status_code == 200 and body["exit_code"] == 0
        assert body["json"]["argv"] == ["$(touch pwned)", "a b; rm -rf /", "`id`", "3", "true"]
        assert not (root / "pwned").exists()

    def test_stdin_input(self, client: TestClient, root: Path) -> None:
        a = _script(root, "echo.py", ECHO)
        _allow(root, {a: {}})
        body = client.post("/api/scripts/run", json={"script": a, "input": {"k": 1}}).json()
        assert json.loads(body["json"]["stdin"]) == {"k": 1}

    def test_nonzero_exit_and_plain_output(self, client: TestClient, root: Path) -> None:
        a = _script(root, "fail.py", "import sys\nprint('plain')\nprint('oops', file=sys.stderr)\nsys.exit(3)\n")
        _allow(root, {a: {}})
        body = client.post("/api/scripts/run", json={"script": a}).json()
        assert body["exit_code"] == 3 and body["stdout"].strip() == "plain" and "oops" in body["stderr"]
        assert body["json"] is None and body["timed_out"] is False

    def test_timeout_kills_the_script(self, client: TestClient, root: Path) -> None:
        a = _script(root, "slow.py", "import time\nprint('start', flush=True)\ntime.sleep(30)\n")
        _allow(root, {a: {"timeout": 1}})
        t0 = time.monotonic()
        body = client.post("/api/scripts/run", json={"script": a}).json()
        assert time.monotonic() - t0 < 10
        assert body["timed_out"] is True and body["exit_code"] is None and "start" in body["stdout"]

    def test_request_timeout_is_capped(self, client: TestClient, root: Path) -> None:
        a = _script(root, "slow.py", "import time\ntime.sleep(30)\n")
        _allow(root, {a: {}})
        body = client.post("/api/scripts/run", json={"script": a, "timeout": 1}).json()
        assert body["timed_out"] is True

    def test_runs_from_repo_root(self, client: TestClient, root: Path) -> None:
        a = _script(root, "cwd.py", "import os\nprint(os.getcwd())\n")
        _allow(root, {a: {}})
        assert client.post("/api/scripts/run", json={"script": a}).json()["stdout"].strip() == str(root)

    def test_non_python_must_be_executable(self, client: TestClient, root: Path) -> None:
        a = _script(root, "tool.sh", "#!/bin/sh\necho \"sh:$1\"\n")
        _allow(root, {a: {}})
        assert client.post("/api/scripts/run", json={"script": a}).status_code == 400
        p = root / a
        p.chmod(p.stat().st_mode | stat.S_IXUSR)
        assert client.post("/api/scripts/run", json={"script": a, "args": ["x"]}).json()["stdout"].strip() == "sh:x"


class TestJobs:
    def _wait(self, client: TestClient, job_id: str, until=lambda j: j["status"] != "running", timeout: float = 10) -> dict:
        deadline = time.monotonic() + timeout
        while True:
            j = client.get(f"/api/scripts/jobs/{job_id}").json()
            if until(j) or time.monotonic() > deadline:
                return j
            time.sleep(0.1)

    def test_job_runs_and_reports(self, root: Path) -> None:
        a = _script(root, "job.py", "import json, sys\nprint(json.dumps({'ok': sys.argv[1]}))\n")
        _allow(root, {a: {}})
        with _jobs_client() as client:
            started = client.post("/api/scripts/run", json={"script": a, "args": ["y"], "background": True}).json()
            assert started["status"] == "running" and started["job_id"]
            j = self._wait(client, started["job_id"])
            assert j["status"] == "succeeded" and j["exit_code"] == 0 and j["json"] == {"ok": "y"}
            assert [x["job_id"] for x in client.get("/api/scripts/jobs").json()] == [started["job_id"]]

    def test_partial_output_and_cancel(self, root: Path) -> None:
        a = _script(root, "long.py", "import time\nfor i in range(100):\n    print('tick', i, flush=True)\n    time.sleep(0.2)\n")
        _allow(root, {a: {}})
        with _jobs_client() as client:
            job_id = client.post("/api/scripts/run", json={"script": a, "background": True}).json()["job_id"]
            j = self._wait(client, job_id, until=lambda j: "tick 1" in j["stdout"])
            assert j["status"] == "running"
            cancelled = client.delete(f"/api/scripts/jobs/{job_id}").json()
            assert cancelled["status"] == "cancelled" and cancelled["finished_at"]

    def test_job_max_runtime(self, root: Path) -> None:
        a = _script(root, "slow.py", "import time\ntime.sleep(30)\n")
        _allow(root, {a: {"max_runtime": 1}})
        with _jobs_client() as client:
            job_id = client.post("/api/scripts/run", json={"script": a, "background": True}).json()["job_id"]
            assert self._wait(client, job_id)["status"] == "timed_out"

    def test_unknown_job_is_404(self, client: TestClient) -> None:
        assert client.get("/api/scripts/jobs/nope").status_code == 404
        assert client.delete("/api/scripts/jobs/nope").status_code == 404

    def test_tail_keeps_the_newest_output(self) -> None:
        t = script_runner._Tail(limit=10)
        t.add("0123456789")
        t.add("abcde")
        assert t.text() == "56789abcde" and t.dropped == 5


class TestGuard:
    def test_cross_site_post_refused(self, client: TestClient, root: Path) -> None:
        a = _script(root, "a.py", "print(1)")
        _allow(root, {a: {}})
        r = client.post("/api/scripts/run", json={"script": a}, headers={"Origin": "https://evil.example", "Sec-Fetch-Site": "cross-site"})
        assert r.status_code == 403

    def test_cross_site_read_refused(self, client: TestClient) -> None:
        r = client.get("/api/scripts", headers={"Origin": "https://evil.example", "Sec-Fetch-Site": "cross-site"})
        assert r.status_code == 403

    def test_same_origin_page_allowed(self, client: TestClient, root: Path) -> None:
        a = _script(root, "a.py", "print(1)")
        _allow(root, {a: {}})
        r = client.post("/api/scripts/run", json={"script": a}, headers={"Origin": "http://127.0.0.1:8765", "Sec-Fetch-Site": "same-origin"})
        assert r.status_code == 200
