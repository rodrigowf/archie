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
        reply = {"sessions": [{"session_id": "s1", "relevance": "strong", "hits": []}], "total_sessions": 4, "error": None}
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
        assert out["total_matching_sessions"] == 4
        assert sent["kind_mode"] == "prefer" and sent["window_mode"] == "prefer"
        assert "linked_memories" not in json.dumps(out)

    async def test_weak_only_results_are_set_apart(self):
        reply = {"sessions": [{"session_id": "w", "relevance": "weak", "hits": []}], "total_sessions": 1, "error": None}
        with patch.object(tools, "_server_request", AsyncMock(return_value=reply)):
            out = json.loads(await tools.search_history(_ctx(), "telegram bot"))
        assert out["sessions"] == [] and out["weak_matches"][0]["session_id"] == "w"
        assert "didn't find" in out["note"]

    async def test_time_phrase_and_phrasings_are_sent(self):
        reply = {"sessions": [], "error": None}
        with patch.object(tools, "_server_request", AsyncMock(return_value=reply)) as req:
            out = json.loads(await tools.search_history(
                _ctx(), "OBS not connecting to JACK back in May", queries=["OBS não conecta no JACK", "  "], kind="agent"))
        sent = req.call_args.args[0]
        assert sent["query"] == "OBS not connecting to JACK"
        assert sent["queries"] == ["OBS não conecta no JACK"] and sent["kind"] == "agent"
        assert sent["window"][0].endswith("-04-27") and out["time_window"]["label"].startswith("May")

    async def test_explicit_when_wins(self):
        with patch.object(tools, "_server_request", AsyncMock(return_value={"sessions": [], "error": None})) as req:
            await tools.search_history(_ctx(), "lamps", when="2026-06")
        assert req.call_args.args[0]["window"] == ["2026-05-28", "2026-07-05"]

    async def test_empty_result_carries_a_hint(self):
        with patch.object(tools, "_server_request", AsyncMock(return_value={"sessions": [], "error": None})):
            out = json.loads(await tools.search_history(_ctx(None), "nothing"))
        assert out["sessions"] == [] and "note" in out

    async def test_falls_back_to_cold_search_when_server_is_down(self):
        cold = AsyncMock(return_value={"sessions": [{"session_id": "s2", "relevance": "strong"}], "total_sessions": 1})
        with patch.object(tools, "_server_request", AsyncMock(return_value=None)), \
                patch.object(tools, "_history_search_cold", cold):
            out = json.loads(await tools.search_history(_ctx(), "lamps"))
        assert cold.call_args.args[0]["exclude_sessions"] == ["current-session"]
        assert out["sessions"] == [{"session_id": "s2", "relevance": "strong"}]

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
    async def test_sends_memory_request(self):
        reply = {"files": [{"file": "context/memory/a.md", "relevance": "strong", "sections": []}], "error": None}
        with patch.object(tools, "_server_request", AsyncMock(return_value=reply)) as req:
            out = json.loads(await tools.search_memory({}, "wake word", queries=["palavra de ativação"], folder="context/memory/assistant/"))
        sent = req.call_args.args[0]
        assert sent["command"] == "memory_search" and sent["folder"] == "assistant"
        assert sent["queries"] == ["palavra de ativação"]
        assert out["files"][0]["file"] == "context/memory/a.md"

    async def test_weak_only_and_errors(self):
        weak = {"files": [{"file": "x", "relevance": "weak"}], "error": None}
        with patch.object(tools, "_server_request", AsyncMock(return_value=weak)):
            out = json.loads(await tools.search_memory({}, "q"))
        assert out["files"] == [] and out["weak_matches"] and "search_history" in out["note"]
        with patch.object(tools, "_server_request", AsyncMock(return_value={"error": "boom"})):
            assert json.loads(await tools.search_memory({}, "q"))["error"] == "boom"

    async def test_history_search_attaches_memory(self):
        reply = {"sessions": [{"session_id": "s", "relevance": "strong"}], "memory": [{"file": "m.md"}], "error": None}
        with patch.object(tools, "_server_request", AsyncMock(return_value=reply)) as req:
            out = json.loads(await tools.search_history(_ctx(), "lamps"))
        assert req.call_args.args[0]["include_memory"] is True
        assert out["memory"] == [{"file": "m.md"}]


class TestMemoryNavigation:
    async def test_browse_and_grep(self, tmp_path):
        root = tmp_path / "context" / "memory"
        (root / "projects").mkdir(parents=True)
        (root / "projects" / "lamps.md").write_text("# Lamps\n\nThe Tuya scenes run at dusk.\n")
        (root / "MEMORY.md").write_text("# Index\n")
        with patch("utils.paths.PROJECT_ROOT", tmp_path):
            top = json.loads(await tools.browse_memory({}, ""))
            sub = json.loads(await tools.browse_memory({}, "projects"))
            hits = json.loads(await tools.grep_memory({}, "tuya SCENES"))
            bad = json.loads(await tools.browse_memory({}, "../.."))
        assert top["folders"] == [{"folder": "projects", "notes": 1}]
        assert sub["notes"][0]["file"] == "context/memory/projects/lamps.md"
        assert hits["hits"] == [{"file": "context/memory/projects/lamps.md", "line": 3, "text": "The Tuya scenes run at dusk."}]
        assert "error" in bad
