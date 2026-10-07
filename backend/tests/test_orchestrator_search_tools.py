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


class TestResumeConversation:
    async def test_requires_a_session_id(self):
        from orchestrator.tools import agent_sessions

        out = json.loads(await agent_sessions.resume_conversation({}, ""))
        assert "required" in out["error"]

    async def test_resumes_through_open_agent_session(self):
        from orchestrator.tools import agent_sessions

        fake = AsyncMock(return_value=json.dumps({"session_id": "live-1", "status": "started"}))
        with patch.object(agent_sessions, "open_agent_session", fake):
            out = json.loads(await agent_sessions.resume_conversation({}, " abc "))
        assert fake.call_args.kwargs == {"resume_sdk_id": "abc"}
        assert out == {"session_id": "live-1", "status": "started", "resumed": "abc"}

    async def test_orchestrator_sessions_point_to_read_conversation(self):
        from orchestrator.tools import agent_sessions

        err = {"error": "Session 'o1' is an orchestrator session and cannot be resumed as an agent session."}
        with patch.object(agent_sessions, "open_agent_session", AsyncMock(return_value=json.dumps(err))):
            out = json.loads(await agent_sessions.resume_conversation({}, "o1"))
        assert "switch_conversation(session_id='o1')" in out["hint"] and "read_conversation" in out["hint"]


class _FakeSession:
    def __init__(self, voice=False, busy=False):
        self.jsonl_id, self.local_id = "current", "local-1"
        self.is_voice, self.is_busy = voice, busy
        self.voice_owner_ws = self.last_input_ws = None
        self.ended = []

    async def end_voice(self, reason):
        self.ended.append(reason)
        self.is_voice = False


class _FakePool:
    def __init__(self, session):
        self.session, self.events = session, []

    def get_orchestrator(self):
        return self.session

    async def stop_orchestrator(self):
        self.events.append("stopped")
        self.session = None


class _FakeWs:
    def __init__(self, pool):
        self.pool, self.frames = pool, []

    async def send_bytes(self, data):
        self.frames.append((json.loads(data), list(self.pool.events)))


def _store(info):
    return SimpleNamespace(get_session_info=lambda sid: info)


class TestSwitchConversation:
    @pytest.fixture(autouse=True)
    def _fast(self, monkeypatch):
        from orchestrator.tools import agent_sessions

        monkeypatch.setattr(agent_sessions, "SWITCH_SETTLE_S", 0)

    async def _switch(self, session, info, sid="past-1"):
        import asyncio

        from orchestrator.tools import agent_sessions

        pool = _FakePool(session)
        ws = _FakeWs(pool)
        session.voice_owner_ws = ws if session.is_voice else None
        session.last_input_ws = None if session.is_voice else ws
        out = json.loads(await agent_sessions.switch_conversation({"pool": pool, "store": _store(info)}, sid))
        await asyncio.gather(*agent_sessions._switch_tasks)
        return out, pool, ws

    async def test_voice_switch_ends_voice_stops_then_tells_the_owner(self):
        info = SimpleNamespace(is_orchestrator=True, title="Lamps")
        session = _FakeSession(voice=True)
        out, pool, ws = await self._switch(session, info)
        assert out["status"] == "switching" and out["title"] == "Lamps"
        assert session.ended == ["switch"]
        frame, events_before_send = ws.frames[0]
        assert events_before_send == ["stopped"]  # the old one is gone before the app resumes
        assert frame == {"type": "orchestrator_switch", "sdk_session_id": "past-1", "title": "Lamps",
                         "voice": True, "from_session_id": "local-1"}

    async def test_text_switch_goes_to_the_last_sender(self):
        session = _FakeSession()
        out, pool, ws = await self._switch(session, SimpleNamespace(is_orchestrator=True, title="T"))
        assert ws.frames[0][0]["voice"] is False and session.ended == []

    async def test_agent_conversations_point_to_resume(self):
        out, pool, ws = await self._switch(_FakeSession(), SimpleNamespace(is_orchestrator=False, title="x"))
        assert "resume_conversation" in out["hint"] and pool.events == [] and ws.frames == []

    async def test_refuses_the_current_and_unknown_conversations(self):
        out, _, _ = await self._switch(_FakeSession(), SimpleNamespace(is_orchestrator=True, title="x"), sid="current")
        assert "already" in out["error"]
        out, _, _ = await self._switch(_FakeSession(), None)
        assert "not found" in out["error"]

    async def test_no_switch_if_the_orchestrator_changed_meanwhile(self):
        import asyncio

        from orchestrator.tools import agent_sessions

        session = _FakeSession()
        pool = _FakePool(session)
        ws = _FakeWs(pool)
        session.last_input_ws = ws
        await agent_sessions.switch_conversation({"pool": pool, "store": _store(SimpleNamespace(is_orchestrator=True, title="x"))}, "p")
        pool.session = object()  # user replaced it before the switch ran
        await asyncio.gather(*agent_sessions._switch_tasks)
        assert pool.events == [] and ws.frames == []


class TestServerStartFailure:
    async def test_server_that_exits_at_start_does_not_raise(self):
        """Another server holds the lock: the child exits at once and kill() finds no process."""
        proc = SimpleNamespace(stdout=SimpleNamespace(readline=AsyncMock(return_value=b"")), pid=1,
                               kill=lambda: (_ for _ in ()).throw(ProcessLookupError()))
        with patch.object(tools.asyncio, "create_subprocess_exec", AsyncMock(return_value=proc)), \
                patch.object(tools, "_forward_stderr", AsyncMock()), \
                patch.object(tools, "_server_proc", None), patch.object(tools, "_server_ready", False):
            assert await tools._ensure_server() is None
