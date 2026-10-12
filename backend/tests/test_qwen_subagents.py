"""Qwen Code subagents (spec 12 §4.8): stream routing, lifecycle, history.

Stream lines are shaped like qwen-code 0.25's stream-json, as captured from
the real CLI on 2026-10-12 (a foreground ``agent`` call, a
``run_in_background`` one) plus the bundle's ``emitSubagentErrorResult``
shape for a failed subagent (zero durations, no ``parent_tool_use_id``).
"""

from __future__ import annotations

import json
from pathlib import Path
from unittest.mock import AsyncMock, patch

import pytest

from api.serializers import serialize_event
from manager.config import ManagerConfig
from manager.qwen.adapter import QwenAdapter
from manager.qwen.session import QwenSessionManager
from manager.qwen.subagent_files import qwen_subagent_dir, sanitize_cwd
from manager.types import (
    SessionStatus,
    SubagentEvent,
    SubagentFinished,
    SubagentStarted,
    TextComplete,
    ToolResult,
    ToolUse,
    TurnComplete,
    UnpromptedTurnStarted,
)
from tests.test_qwen_session import _init_event, _make_fake_proc, _result_event

SID = "5e3839d6-f046-48a8-bc55-f8c7e10231bc"
FG = "call_9849fa4fb8c7496f8aec23e6"
BG = "call_d60e82a107ee4661982ae124"
INNER = "call_9895cf713a2642a987f7619e"


def _se(event: dict, parent: str | None = None) -> dict:
    return {"type": "stream_event", "uuid": "u", "session_id": SID,
            "parent_tool_use_id": parent, "event": event}


def _assistant(blocks: list[dict], parent: str | None = None) -> dict:
    return {"type": "assistant", "uuid": "a", "session_id": SID, "parent_tool_use_id": parent,
            "message": {"id": "m", "type": "message", "role": "assistant", "model": "qwen3.6-plus",
                        "content": blocks, "stop_reason": None, "usage": {}}}


def _user(blocks: list[dict], parent: str | None = None) -> dict:
    return {"type": "user", "uuid": "u", "session_id": SID, "parent_tool_use_id": parent,
            "message": {"role": "user", "content": blocks}}


def _tool_use(tid: str, name: str, inp: dict) -> dict:
    return {"type": "tool_use", "id": tid, "name": name, "input": inp}


def _tool_result(tid: str, content: str, is_error: bool = False) -> dict:
    return {"type": "tool_result", "tool_use_id": tid, "is_error": is_error, "content": content}


def _system(subtype: str, data: dict) -> dict:
    return {"type": "system", "subtype": subtype, "uuid": "s", "session_id": SID,
            "parent_tool_use_id": None, "data": data}


def _subagent_error_result(message: str) -> dict:
    # BaseJsonOutputAdapter.buildSubagentErrorResult (qwen-code 0.25).
    return {"type": "result", "subtype": "error_during_execution", "uuid": "r", "session_id": SID,
            "is_error": True, "duration_ms": 0, "duration_api_ms": 0, "num_turns": 0,
            "usage": {"input_tokens": 0, "output_tokens": 0}, "permission_denials": [],
            "error": {"message": message}}


def _foreground_lines() -> list[dict]:
    return [
        _assistant([_tool_use(FG, "agent", {"description": "Read note.txt contents", "prompt": "Read note.txt",
                                            "subagent_type": "general-purpose", "run_in_background": False})]),
        _user([{"type": "text", "text": "Read note.txt"}], parent=FG),
        _se({"type": "content_block_start", "index": 0,
             "content_block": {"type": "tool_use", "id": INNER, "name": "read_file", "input": {}}}, parent=FG),
        _se({"type": "content_block_delta", "index": 0,
             "delta": {"type": "input_json_delta", "partial_json": "{}"}}, parent=FG),
        _se({"type": "content_block_stop", "index": 0}, parent=FG),
        _assistant([_tool_use(INNER, "read_file", {"file_path": "note.txt"})], parent=FG),
        _user([_tool_result(INNER, "hello world\n")], parent=FG),
        _user([_tool_result(FG, "The file says hello world")]),
    ]


def _sm(tmp_path: Path | None = None) -> QwenSessionManager:
    cfg = ManagerConfig(project_dir=str(tmp_path or "/tmp"), provider="qwen")
    sm = QwenSessionManager(local_id="q1", config=cfg)
    sm._provider_session_id = SID
    return sm


def _translate(sm: QwenSessionManager, lines: list[dict]) -> list:
    meta: dict = {}
    text: list[str] = []
    thinking: list[str] = []
    out = []
    for line in lines:
        out.extend(sm._translate_event(line, meta, text, thinking))
    return out


class TestLiveRouting:
    def test_foreground_agent_lifecycle_and_inner_events(self):
        sm = _sm()
        events = _translate(sm, _foreground_lines())
        kinds = [type(e).__name__ for e in events]
        assert kinds == ["ToolUse", "SubagentStarted", "SubagentEvent", "SubagentEvent",
                         "ToolResult", "SubagentFinished"]
        started = events[1]
        assert isinstance(started, SubagentStarted)
        assert (started.tool_use_id, started.agent_type, started.description, started.background) == (
            FG, "general-purpose", "Read note.txt contents", False)
        inner = [e for e in events if isinstance(e, SubagentEvent)]
        assert all(e.tool_use_id == FG for e in inner)
        assert isinstance(inner[0].event, ToolUse) and inner[0].event.tool_name == "read_file"
        assert isinstance(inner[1].event, ToolResult) and inner[1].event.output == "hello world\n"
        # the spawn card keeps its own (parent-level) result
        assert isinstance(events[4], ToolResult) and events[4].tool_use_id == FG
        fin = events[5]
        assert isinstance(fin, SubagentFinished)
        assert (fin.status, fin.summary) == ("done", "The file says hello world")
        snap = sm.subagent_snapshot()
        assert [r["tool_use_id"] for r in snap] == [FG]
        assert snap[0]["status"] == "done" and snap[0]["activity"] == "read_file"

    def test_subagent_lines_never_flip_parent_status(self):
        sm = _sm()
        sm._status = SessionStatus.STREAMING
        _translate(sm, [
            _assistant([_tool_use("inner", "grep_search", {})], parent=FG),
            _se({"type": "content_block_start", "index": 0, "content_block": {"type": "text", "text": ""}}, parent=FG),
            _se({"type": "content_block_delta", "index": 0, "delta": {"type": "text_delta", "text": "x"}}, parent=FG),
        ])
        assert sm.status == SessionStatus.STREAMING

    def test_block_index_is_keyed_by_parent(self):
        """A subagent's block 0 must not close the parent's open block 0."""
        sm = _sm()
        events = _translate(sm, [
            _se({"type": "content_block_start", "index": 0, "content_block": {"type": "text", "text": ""}}),
            _se({"type": "content_block_delta", "index": 0, "delta": {"type": "text_delta", "text": "Hello "}}),
            _se({"type": "content_block_start", "index": 0,
                 "content_block": {"type": "tool_use", "id": INNER, "name": "read_file", "input": {}}}, parent=FG),
            _se({"type": "content_block_stop", "index": 0}, parent=FG),
            _se({"type": "content_block_delta", "index": 0, "delta": {"type": "text_delta", "text": "there"}}),
            _se({"type": "content_block_stop", "index": 0}),
        ])
        completes = [e for e in events if isinstance(e, TextComplete)]
        assert [c.text for c in completes] == ["Hello there"]

    def test_subagent_text_streams_into_its_own_frames(self):
        sm = _sm()
        events = _translate(sm, [
            _se({"type": "content_block_start", "index": 0, "content_block": {"type": "text", "text": ""}}, parent=FG),
            _se({"type": "content_block_delta", "index": 0, "delta": {"type": "text_delta", "text": "sub"}}, parent=FG),
            _se({"type": "content_block_stop", "index": 0}, parent=FG),
        ])
        assert [type(e.event).__name__ for e in events] == ["TextDelta", "TextComplete"]
        assert all(isinstance(e, SubagentEvent) and e.tool_use_id == FG for e in events)

    def test_background_agent_notification_reads_report_from_transcript(self, tmp_path, monkeypatch):
        monkeypatch.setenv("QWEN_HOME", str(tmp_path / "qwen-home"))
        project = tmp_path / "proj"
        sm = _sm(project)
        task_id = f"general-purpose-{BG}"
        sub_dir = qwen_subagent_dir(str(project), SID)
        assert sub_dir.parent.parent.name == sanitize_cwd(str(project))
        sub_dir.mkdir(parents=True)
        (sub_dir / f"agent-{task_id}.jsonl").write_text("\n".join(json.dumps(o) for o in [
            {"type": "user", "message": {"role": "user", "parts": [{"text": "Read note.txt"}]}},
            {"type": "assistant", "message": {"role": "model", "parts": [
                {"text": "thinking", "thought": True}, {"text": "It says hello world"}]}},
        ]) + "\n")
        events = _translate(sm, [
            _assistant([_tool_use(BG, "agent", {"description": "Read note.txt contents", "prompt": "Read note.txt",
                                                "run_in_background": True})]),
            _user([{"type": "text", "text": "Read note.txt"}], parent=BG),
            _system("task_started", {"task_id": task_id, "tool_use_id": BG,
                                     "description": "Read note.txt contents", "subagent_type": "general-purpose"}),
            _user([_tool_result(BG, f"Background agent launched successfully.\ntask_id: {task_id} (internal ID)")]),
            _user([{"type": "text", "text": 'Background agent "general-purpose: Read note.txt contents" completed.'}]),
            _system("task_notification", {"task_id": task_id, "tool_use_id": BG, "status": "completed",
                                          "usage": {"total_tokens": 34857, "tool_uses": 1, "duration_ms": 8156}}),
        ])
        kinds = [type(e).__name__ for e in events]
        assert kinds == ["ToolUse", "SubagentStarted", "ToolResult", "UnpromptedTurnStarted", "SubagentFinished"]
        assert events[1].background is True
        note = events[3]
        assert isinstance(note, UnpromptedTurnStarted) and note.source == "task_notification"
        assert note.text.startswith("<task-notification>") and f"<tool-use-id>{BG}</tool-use-id>" in note.text
        assert "<result>It says hello world</result>" in note.text
        fin = events[4]
        assert (fin.status, fin.summary) == ("done", "It says hello world")
        assert fin.usage == {"tokens": 34857, "tool_uses": 1, "duration_ms": 8156}
        assert sm._subagents.get(BG).harness_id == task_id
        assert serialize_event(note) == {"type": "user_message", "text": note.text, "source": "task_notification"}

    def test_monitor_task_messages_are_not_subagents(self):
        sm = _sm()
        events = _translate(sm, [
            _system("task_started", {"task_id": "mon-1", "tool_use_id": "call_monitor"}),
            _system("task_notification", {"task_id": "mon-1", "tool_use_id": "call_monitor", "status": "completed"}),
        ])
        assert events == [] and sm.subagent_snapshot() == []

    def test_failed_subagent_error_result_does_not_end_the_turn(self):
        sm = _sm()
        lines = _foreground_lines()[:5] + [
            _subagent_error_result("Task was cancelled"),
            _user([_tool_result(FG, "Subagent failed", is_error=True)]),
        ]
        events = _translate(sm, lines)
        assert not any(isinstance(e, TurnComplete) for e in events)
        fins = [e for e in events if isinstance(e, SubagentFinished)]
        assert [(f.tool_use_id, f.status, f.summary) for f in fins] == [(FG, "failed", "Task was cancelled")]

    def test_turn_error_result_still_ends_the_turn(self):
        sm = _sm()
        err = dict(_subagent_error_result("[API Error: 404 Model not exist.]"), duration_ms=4167, num_turns=1)
        events = _translate(sm, [err])
        assert isinstance(events[-1], TurnComplete) and events[-1].is_error

    def test_final_result_stops_agents_still_running(self):
        sm = _sm()
        events = _translate(sm, _foreground_lines()[:3] + [_result_event(SID)])
        assert [type(e).__name__ for e in events[-2:]] == ["SubagentFinished", "TurnComplete"]
        assert events[-2].status == "stopped"

    def test_nested_agent_is_keyed_by_its_own_call(self):
        sm = _sm()
        events = _translate(sm, _foreground_lines()[:2] + [
            _assistant([_tool_use("call_child", "agent", {"description": "inner", "prompt": "p"})], parent=FG),
            _assistant([_tool_use("leaf", "glob", {})], parent="call_child"),
            _user([_tool_result("call_child", "inner done")], parent=FG),
        ])
        started = [e for e in events if isinstance(e, SubagentStarted)]
        assert [(s.tool_use_id, s.parent_tool_use_id) for s in started] == [(FG, None), ("call_child", FG)]
        leaf = [e for e in events if isinstance(e, SubagentEvent) and e.tool_use_id == "call_child"]
        assert leaf and leaf[0].event.tool_name == "glob"
        fin = [e for e in events if isinstance(e, SubagentFinished)]
        assert [(f.tool_use_id, f.status) for f in fin] == [("call_child", "done")]


class TestSendIntegration:
    @pytest.mark.asyncio
    async def test_send_routes_and_keeps_one_turn_complete(self):
        proc = _make_fake_proc([_init_event(SID)] + _foreground_lines()[:5] + [
            _subagent_error_result("boom"),
            _user([_tool_result(FG, "Subagent failed", is_error=True)]),
            _result_event(SID),
        ])
        sm = QwenSessionManager(local_id="q2")
        await sm.start()
        try:
            with patch("asyncio.create_subprocess_exec", AsyncMock(return_value=proc)):
                events = [ev async for ev in sm.send("go")]
        finally:
            await sm.stop()
        tcs = [e for e in events if isinstance(e, TurnComplete)]
        assert len(tcs) == 1 and not tcs[0].is_error
        assert [f.status for f in events if isinstance(f, SubagentFinished)] == ["failed"]

    @pytest.mark.asyncio
    async def test_process_exit_without_result_stops_agents_out_of_turn(self):
        proc = _make_fake_proc([_init_event(SID)] + _foreground_lines()[:3])
        sm = QwenSessionManager(local_id="q3")
        oot: list = []
        sm.set_out_of_turn_sink(oot.append)
        await sm.start()
        try:
            with patch("asyncio.create_subprocess_exec", AsyncMock(return_value=proc)):
                [ev async for ev in sm.send("go")]
        finally:
            await sm.stop()
        assert [(e.tool_use_id, e.status) for e in oot if isinstance(e, SubagentFinished)] == [(FG, "stopped")]


# ---------------------------------------------------------------------------
# History (SA-8)
# ---------------------------------------------------------------------------

def _write_jsonl(path: Path, objs: list[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(json.dumps(o) for o in objs) + "\n")


def _history(tmp_path: Path) -> Path:
    data = tmp_path / "context"
    parent = data / "chats" / f"{SID}.jsonl"
    _write_jsonl(parent, [
        {"type": "user", "timestamp": "2026-10-12T16:13:50Z", "message": {"role": "user", "parts": [{"text": "go"}]}},
        {"type": "assistant", "timestamp": "2026-10-12T16:13:53Z", "message": {"role": "model", "parts": [
            {"functionCall": {"id": BG, "name": "agent", "args": {
                "description": "Read note.txt contents", "prompt": "Read", "run_in_background": True}}},
            {"functionCall": {"id": "call_bad", "name": "agent", "args": {"description": "never ran"}}},
        ]}},
        {"type": "tool_result", "timestamp": "2026-10-12T16:13:53Z", "message": {"role": "user", "parts": [
            {"functionResponse": {"id": BG, "name": "agent", "response": {"output": "Background agent launched successfully."}}}]},
         "toolCallResult": {"callId": BG, "status": "success"}},
        {"type": "tool_result", "timestamp": "2026-10-12T16:13:53Z", "message": {"role": "user", "parts": [
            {"functionResponse": {"id": "call_bad", "name": "agent", "response": {"error": "unknown subagent"}}}]},
         "toolCallResult": {"callId": "call_bad", "status": "error"}},
        {"type": "user", "subtype": "notification", "timestamp": "2026-10-12T16:14:01Z", "message": {
            "role": "user", "parts": [{"text": (
                f"<task-notification>\n<task-id>general-purpose-{BG}</task-id>\n<tool-use-id>{BG}</tool-use-id>\n"
                "<status>completed</status>\n<usage>\n<total_tokens>34857</total_tokens>\n<tool_uses>1</tool_uses>\n"
                "<duration_ms>8156</duration_ms>\n</usage>\n</task-notification>")}]}},
    ])
    sub = data / "subagents" / SID
    agent_id = f"general-purpose-{BG}"
    _write_jsonl(sub / f"agent-{agent_id}.jsonl", [
        {"type": "user", "isSidechain": True, "timestamp": "2026-10-12T16:13:53Z",
         "message": {"role": "user", "parts": [{"text": "Read note.txt"}]}},
        {"type": "assistant", "isSidechain": True, "timestamp": "2026-10-12T16:13:58Z", "message": {
            "role": "model", "parts": [{"functionCall": {"id": "c1", "name": "read_file", "args": {}}}]}},
        {"type": "tool_result", "isSidechain": True, "timestamp": "2026-10-12T16:13:58Z", "message": {
            "role": "user", "parts": [{"functionResponse": {"id": "c1", "name": "read_file",
                                                            "response": {"output": "hello world"}}}]}},
        {"type": "assistant", "isSidechain": True, "timestamp": "2026-10-12T16:14:01Z", "message": {
            "role": "model", "parts": [{"text": "hmm", "thought": True}, {"text": "It says hello world"}]}},
    ])
    (sub / f"agent-{agent_id}.meta.json").write_text(json.dumps({
        "agentId": agent_id, "agentType": "general-purpose", "description": "Read note.txt contents",
        "parentSessionId": SID, "toolUseId": BG, "parentAgentId": None, "createdAt": "2026-10-12T16:13:53.441Z",
        "status": "completed", "isBackgrounded": True, "lastUpdatedAt": "2026-10-12T16:14:01.619Z",
    }))
    # A nested agent spawned by the background one.
    _write_jsonl(sub / "agent-explore-call_nested.jsonl", [
        {"type": "user", "isSidechain": True, "message": {"role": "user", "parts": [{"text": "look"}]}}])
    (sub / "agent-explore-call_nested.meta.json").write_text(json.dumps({
        "agentId": "explore-call_nested", "agentType": "Explore", "toolUseId": "call_nested",
        "parentAgentId": agent_id, "createdAt": "2026-10-12T16:13:55Z", "status": "running"}))
    return parent


class TestHistory:
    def test_list_subagents(self, tmp_path):
        parent = _history(tmp_path)
        rows = QwenAdapter().list_subagents(parent)
        by_id = {r["tool_use_id"]: r for r in rows}
        assert set(by_id) == {BG, "call_bad", "call_nested"}
        bg = by_id[BG]
        assert (bg["status"], bg["background"], bg["agent_type"], bg["summary"]) == (
            "done", True, "general-purpose", "It says hello world")
        assert bg["usage"] == {"tokens": 34857, "tool_uses": 1, "duration_ms": 8156}
        assert bg["started_at"] == "2026-10-12T16:13:53.441Z"
        assert by_id["call_bad"]["status"] == "failed" and by_id["call_bad"]["summary"] == "unknown subagent"
        nested = by_id["call_nested"]
        assert nested["parent_tool_use_id"] == BG and nested["agent_type"] == "Explore"
        # never left "running" once its process is gone (live rows win while it runs)
        assert nested["status"] == "stopped"

    def test_read_subagent_messages(self, tmp_path):
        parent = _history(tmp_path)
        a = QwenAdapter()
        msgs = a.read_subagent_messages(parent, BG)
        assert [m["type"] for m in msgs] == ["user", "assistant", "user", "assistant"]
        assert msgs[2]["message"]["content"][0]["type"] == "tool_result"
        assert a.read_subagent_messages(parent, "call_bad") == []
        assert a.read_subagent_messages(parent, "nope") is None

    def test_no_subagents(self, tmp_path):
        parent = tmp_path / "context" / "chats" / "x.jsonl"
        _write_jsonl(parent, [{"type": "user", "message": {"role": "user", "parts": [{"text": "hi"}]}}])
        assert QwenAdapter().list_subagents(parent) == []
