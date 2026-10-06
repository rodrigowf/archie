"""Tests for index-memory.py — indexes memory and session history from context/."""

import sys
from pathlib import Path
from unittest.mock import patch, MagicMock

import pytest

# index-memory.py has a hyphen, so import via importlib
import importlib.util

_spec = importlib.util.spec_from_file_location(
    "index_memory",
    Path(__file__).resolve().parents[2] / "shared" / "scripts" / "index-memory.py",
)
index_memory = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(index_memory)


class TestRunEmbed:
    def test_constructs_correct_command(self):
        with patch("subprocess.run") as mock_run:
            mock_run.return_value = MagicMock(returncode=0)
            index_memory.run_embed("index", "memory/")

            args = mock_run.call_args[0][0]
            assert args[0] == sys.executable
            assert "embed.py" in args[1]
            assert args[2] == "index"
            assert args[3] == "memory/"

    def test_returns_true_on_success(self):
        with patch("subprocess.run") as mock_run:
            mock_run.return_value = MagicMock(returncode=0)
            assert index_memory.run_embed("index", "memory/") is True

    def test_returns_false_on_failure(self):
        with patch("subprocess.run") as mock_run:
            mock_run.return_value = MagicMock(returncode=1)
            assert index_memory.run_embed("index", "memory/") is False


class TestIndexMemory:
    @pytest.fixture
    def setup_context_dirs(self, tmp_path):
        """Set up temporary context/ directory structure."""
        memory_dir = tmp_path / "context" / "memory"
        memory_dir.mkdir(parents=True)

        # Add memory files
        (memory_dir / "MEMORY.md").write_text("# Memory\nTest content")
        (memory_dir / "patterns.md").write_text("# Patterns\nMore content")

        return tmp_path, memory_dir

    def test_indexes_memory_files(self, setup_context_dirs):
        tmp_path, memory_dir = setup_context_dirs
        calls = []

        def fake_run_embed(command, *args):
            calls.append((command, args))
            return True

        with patch.object(index_memory, "run_embed", side_effect=fake_run_embed):
            with patch("utils.paths.PROJECT_ROOT", tmp_path):
                index_memory.index_memory(reset=False)

        commands = [c[0] for c in calls]
        assert "index" in commands

        # Should index memory collection
        index_call = next(c for c in calls if c[0] == "index")
        assert "memory" in index_call[1]

    def test_skips_empty_memory_dir(self, tmp_path, capsys):
        memory_dir = tmp_path / "context" / "memory"
        memory_dir.mkdir(parents=True)
        # Empty memory dir

        with patch("utils.paths.PROJECT_ROOT", tmp_path):
            index_memory.index_memory(reset=False)

        captured = capsys.readouterr()
        assert "No memory files" in captured.out

    def test_skips_missing_memory_dir(self, tmp_path, capsys):
        # No context/memory dir

        with patch("utils.paths.PROJECT_ROOT", tmp_path):
            index_memory.index_memory(reset=False)

        captured = capsys.readouterr()
        assert "Memory directory not found" in captured.out


class _FakeFacade:
    """Stands in for index_client.IndexFacade: a deterministic encoder, no warm server."""

    mode = "fake"

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False

    def encode_many(self, texts):
        return [[float(len(t) % 7 + 1)] + [0.0] * 383 for t in texts]


@pytest.fixture
def fake_index_client(monkeypatch):
    import types

    module = types.SimpleNamespace(IndexFacade=_FakeFacade)
    monkeypatch.setitem(sys.modules, "index_client", module)
    return module


class TestIndexHistory:
    @pytest.fixture
    def setup_sessions(self, tmp_path):
        """Session files from two harnesses: context/ root and context/chats/."""
        context_dir = tmp_path / "context"
        (context_dir / "chats").mkdir(parents=True)
        (context_dir / "session1.jsonl").write_text(
            '{"type": "user", "message": {"content": [{"type": "text", "text": "Hello there, how are you today"}]}}\n'
        )
        (context_dir / "chats" / "qwen1.jsonl").write_text(
            '{"type": "user", "message": {"role": "user", "parts": [{"text": "Explain the wake word pipeline please"}]}}\n'
        )
        return tmp_path, context_dir

    def test_indexes_every_harness_into_sqlite(self, setup_sessions, fake_index_client):
        import sqlite3

        tmp_path, _ = setup_sessions
        with patch("utils.paths.PROJECT_ROOT", tmp_path):
            assert index_memory.index_history(reset=False) is True

        db = sqlite3.connect(tmp_path / "index" / "history.sqlite3")
        sessions = {r[0]: r[1] for r in db.execute("SELECT id, harness FROM sessions")}
        assert sessions == {"session1": "claude", "qwen1": "qwen"}

    def test_second_run_skips_unchanged(self, setup_sessions, fake_index_client, capsys):
        tmp_path, _ = setup_sessions
        with patch("utils.paths.PROJECT_ROOT", tmp_path):
            index_memory.index_history(reset=False)
            capsys.readouterr()
            index_memory.index_history(reset=False)
        assert "0 indexed, 2 unchanged" in capsys.readouterr().out

    def test_empty_context_dir_is_fine(self, tmp_path, fake_index_client):
        (tmp_path / "context").mkdir()
        with patch("utils.paths.PROJECT_ROOT", tmp_path):
            assert index_memory.index_history(reset=False) is True


class TestMain:
    def test_runs_with_memory_only_flag(self, tmp_path):
        memory_dir = tmp_path / "context" / "memory"
        memory_dir.mkdir(parents=True)
        (memory_dir / "test.md").write_text("content")

        with patch("utils.paths.PROJECT_ROOT", tmp_path):
            with patch.object(index_memory, "PROJECT_DIR", tmp_path):
                with patch.object(index_memory, "run_embed", return_value=True) as mock_embed:
                    with patch("sys.argv", ["index-memory.py", "--memory-only"]):
                        with pytest.raises(SystemExit) as exit_info:
                            index_memory.main()
        assert exit_info.value.code == 0

        # Should have called index for memory
        calls = [c[0][0] for c in mock_embed.call_args_list]
        # Index should be called if memory files exist
        # Stats is always called

    def test_runs_with_history_only_flag(self, tmp_path, fake_index_client):
        context_dir = tmp_path / "context"
        context_dir.mkdir(parents=True)
        (context_dir / "session.jsonl").write_text(
            '{"type": "user", "message": {"content": "test"}}\n'
        )

        with patch("utils.paths.PROJECT_ROOT", tmp_path):
            with patch.object(index_memory, "PROJECT_DIR", tmp_path):
                with patch.object(index_memory, "run_embed", return_value=True):
                    with patch("sys.argv", ["index-memory.py", "--history-only"]):
                        with pytest.raises(SystemExit) as exit_info:
                            index_memory.main()

        assert exit_info.value.code == 0
