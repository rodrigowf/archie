"""Tests for orchestrator/tools/search.py — search_history, read_conversation, search_memory.

The warm server is mocked at ``_server_request``; these tests pin the request each tool sends
and how replies are shaped for the model.
"""
from __future__ import annotations

import json
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

import pytest

from orchestrator.tools import search as tools

pytestmark = pytest.mark.asyncio


def _ctx(jsonl_id: str | None = "current-session"):
    return {"session": SimpleNamespace(jsonl_id=jsonl_id)} if jsonl_id else {}


class TestSearchHistory:
    async def test_sends_history_request_excluding_current_session(self):
        reply = {"sessions": [{"session_id": "s1", "hits": []}], "total_sessions": 4, "error": None}
        with patch.object(tools, "_server_request", AsyncMock(return_value=reply)) as req:
            out = json.loads(await tools.search_history(
                _ctx(), "Shroud of Turin", max_results=50, after="2026-01-01", before="not a date"
            ))
        sent = req.call_args.args[0]
        assert sent["command"] == "history_search"
        assert sent["exclude_sessions"] == ["current-session"]
        assert sent["max_sessions"] == 10
        assert sent["after"] == "2026-01-01" and sent["before"] is None
        assert out["sessions"] == reply["sessions"]
        assert out["count"] == 1 and out["total_matching_sessions"] == 4
        assert "linked_memories" not in json.dumps(out)

    async def test_empty_result_carries_a_hint(self):
        with patch.object(tools, "_server_request", AsyncMock(return_value={"sessions": [], "error": None})):
            out = json.loads(await tools.search_history(_ctx(None), "nothing"))
        assert out["sessions"] == [] and "note" in out

    async def test_falls_back_to_cold_search_when_server_is_down(self):
        cold = AsyncMock(return_value={"sessions": [{"session_id": "s2"}], "total_sessions": 1})
        with patch.object(tools, "_server_request", AsyncMock(return_value=None)), \
                patch.object(tools, "_history_search_cold", cold):
            out = json.loads(await tools.search_history(_ctx(), "lamps"))
        assert cold.call_args.args[0]["exclude_sessions"] == ["current-session"]
        assert out["sessions"] == [{"session_id": "s2"}]

    async def test_server_error_is_reported(self):
        with patch.object(tools, "_server_request", AsyncMock(return_value={"error": "index not built"})):
            out = json.loads(await tools.search_history(_ctx(), "x"))
        assert out["error"] == "index not built"


class TestReadConversation:
    async def test_reads_turn_window(self, tmp_path):
        ctx_dir = tmp_path / "context"
        ctx_dir.mkdir()
        (ctx_dir / "abc.jsonl").write_text("".join(
            json.dumps({"type": "user" if i % 2 == 0 else "assistant",
                        "message": {"content": f"turn text {i}"}}) + "\n"
            for i in range(8)
        ))
        with patch("utils.paths.PROJECT_ROOT", tmp_path):
            out = json.loads(await tools.read_conversation({}, " abc ", turn=4, before=1, after=1))
        assert [t["turn"] for t in out["turns"]] == [3, 4, 5]
        assert out["total_turns"] == 8


class TestSearchMemory:
    async def test_drops_hits_outside_memory_dir(self):
        memory_file = str(tools._MEMORY_DIR / "projects" / "x.md")
        results = [
            {"text": "a", "file_path": memory_file},
            {"text": "b", "file_path": str(tools._PROJECT_DIR / ".index-temp" / "abc.md")},
        ]
        with patch.object(tools, "_do_search", AsyncMock(return_value=results)):
            out = json.loads(await tools.search_memory({}, "q"))
        assert [r["text"] for r in out["results"]] == ["a"]

    async def test_errors_pass_through(self):
        with patch.object(tools, "_do_search", AsyncMock(return_value=[{"error": "boom"}])):
            out = json.loads(await tools.search_memory({}, "q"))
        assert out["results"] == [{"error": "boom", "frontmatter": None}]
