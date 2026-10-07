"""Tests for index-memory.py — builds index/history.sqlite3 and index/memory.sqlite3 from context/."""

import importlib.util
import sqlite3
import sys
from pathlib import Path
from unittest.mock import patch

import pytest

_spec = importlib.util.spec_from_file_location(
    "index_memory",
    Path(__file__).resolve().parents[2] / "shared" / "scripts" / "index-memory.py",
)
index_memory = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(index_memory)


class _FakeEncoder:
    """Stands in for index_client.Encoder: deterministic vectors, no model, no server."""

    def __init__(self, model, use_server=True):
        self.model = model
        self.mode = "fake"

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False

    def encode_many(self, texts):
        return [[float(len(t) % 7 + 1)] + [0.0] * 383 for t in texts]


@pytest.fixture
def ctx(tmp_path, monkeypatch):
    context = tmp_path / "context"
    (context / "chats").mkdir(parents=True)
    (context / "memory" / "projects").mkdir(parents=True)
    (context / "session1.jsonl").write_text(
        '{"type": "user", "message": {"content": [{"type": "text", "text": "Hello there, how are you today"}]}}\n'
    )
    (context / "chats" / "qwen1.jsonl").write_text(
        '{"type": "user", "message": {"role": "user", "parts": [{"text": "Explain the wake word pipeline please"}]}}\n'
    )
    (context / "memory" / "projects" / "lamps.md").write_text("# Lamps\n\nThe Tuya scenes run at dusk in the hallway.\n")
    import index_client
    monkeypatch.setattr(index_client, "Encoder", _FakeEncoder)
    monkeypatch.delenv("OPENAI_API_KEY", raising=False)  # no summaries in tests
    with patch("utils.paths.PROJECT_ROOT", tmp_path):
        yield tmp_path


def test_history_covers_every_harness(ctx, capsys):
    assert index_memory.index_history() is True
    db = sqlite3.connect(ctx / "index" / "history.sqlite3")
    assert {r[0]: r[1] for r in db.execute("SELECT id, harness FROM sessions")} == {"session1": "claude", "qwen1": "qwen"}
    assert "summaries skipped" in capsys.readouterr().out


def test_second_run_skips_unchanged(ctx, capsys):
    index_memory.index_history()
    capsys.readouterr()
    index_memory.index_history()
    assert "0 indexed, 2 unchanged" in capsys.readouterr().out


def test_memory_into_sqlite(ctx, capsys):
    assert index_memory.index_memory() is True
    db = sqlite3.connect(ctx / "index" / "memory.sqlite3")
    assert [r[0] for r in db.execute("SELECT path FROM files")] == ["projects/lamps.md"]
    assert "Memory: 1 indexed" in capsys.readouterr().out


def test_main_runs_both_and_exits_zero(ctx):
    with patch.object(sys, "argv", ["index-memory.py"]), pytest.raises(SystemExit) as e:
        index_memory.main()
    assert e.value.code == 0
    assert (ctx / "index" / "history.sqlite3").exists() and (ctx / "index" / "memory.sqlite3").exists()


def test_missing_memory_dir_is_fine(tmp_path, monkeypatch):
    (tmp_path / "context").mkdir()
    with patch("utils.paths.PROJECT_ROOT", tmp_path):
        assert index_memory.index_memory() is True
