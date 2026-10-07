"""Tests for shared/scripts/search-server.py — request dispatch and the single-instance lock."""
from __future__ import annotations

import importlib.util
import subprocess
import sys
from pathlib import Path

import numpy as np
import pytest

SCRIPT = Path(__file__).resolve().parents[2] / "shared" / "scripts" / "search-server.py"
_spec = importlib.util.spec_from_file_location("search_server", SCRIPT)
server_mod = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(server_mod)


class FakeModel:
    max_seq_length = 256

    def encode(self, texts, batch_size=32):
        return np.array([[float(len(t)), 1.0] for t in texts])


class FakeService:
    def history_search(self, request):
        return {"sessions": [{"session_id": "s", "q": request["query"]}], "error": None}

    def memory_search(self, request):
        raise ValueError("boom")


@pytest.fixture
def server(monkeypatch):
    s = server_mod.IndexServer.__new__(server_mod.IndexServer)
    loaded = []
    s.load_model = lambda name: loaded.append(name) or FakeModel()
    s.service = FakeService()
    s.default_model = "default-model"
    s.loaded = loaded
    return s


def test_ping_and_unknown(server):
    assert server.handle({"command": "ping"}) == {"status": "ready"}
    assert "Unknown command" in server.handle({"command": "nope"})["error"]


def test_encode_uses_requested_or_default_model(server):
    assert server.handle({"command": "encode", "text": "abc"})["embedding"] == [3.0, 1.0]
    r = server.handle({"command": "encode_many", "texts": ["a", "bb"], "model": "other"})
    assert r["embeddings"] == [[1.0, 1.0], [2.0, 1.0]]
    assert server.loaded == ["default-model", "other"]
    assert server.handle({"command": "encode_many", "texts": []})["embeddings"] == []


def test_searches_are_delegated_and_errors_contained(server):
    assert server.handle({"command": "history_search", "query": "q"})["sessions"][0]["q"] == "q"
    assert server.handle({"command": "memory_search", "query": "q"})["error"] == "ValueError: boom"


def test_lock_refuses_a_second_server(tmp_path):
    lock = tmp_path / ".search-server.lock"
    code = (
        "import importlib.util,sys,time;"
        f"s=importlib.util.spec_from_file_location('m', {str(SCRIPT)!r});m=importlib.util.module_from_spec(s);s.loader.exec_module(m);"
        f"m.acquire_lock(__import__('pathlib').Path({str(lock)!r}));print('locked',flush=True);time.sleep(5)"
    )
    first = subprocess.Popen([sys.executable, "-c", code], stdout=subprocess.PIPE, text=True)
    try:
        assert first.stdout.readline().strip() == "locked"
        second = subprocess.run([sys.executable, "-c", code], capture_output=True, text=True, timeout=30)
        assert second.returncode == 2 and "held by another process" in second.stderr
    finally:
        first.kill()
        first.wait()
