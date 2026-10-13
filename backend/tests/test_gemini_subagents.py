"""Gemini CLI subagents (``invoke_agent``, spec 12 §4.8): post-hoc lifecycle,
outcome parsing, history.

Shapes from a real Gemini CLI 0.63 run (2026-10-12): the stream's
``tool_result`` for ``invoke_agent`` carries **no** ``output`` (the tool's
display is a ``SubagentProgress`` object, which stream-json drops); the
parent chat file's ``toolCalls`` entry has ``agentId``, the progress as
``resultDisplay`` and the model-facing text; the subagent's own chat is
``chats/<parent session id>/<agentId>.jsonl`` (header ``kind: "subagent"``).
"""

from __future__ import annotations

import json
from pathlib import Path
from unittest.mock import AsyncMock, patch

import pytest

from manager.config import ManagerConfig
from manager.gemini import adapter as gemini_adapter
from manager.gemini.adapter import GeminiAdapter
from manager.gemini.session import GeminiSessionManager
from manager.gemini.subagent_files import (
    progress_outcome,
    readable_result,
    text_outcome,
)
from manager.types import SubagentFinished, SubagentStarted, ToolResult, ToolUse, TurnComplete
from tests.test_gemini_session import (  # noqa: F401 (autouse fixture)
    _gemini_env,
    _init_event,
    _make_fake_proc,
    _result_event,
    _tool_use_event,
)

TOOL = "invoke_agent__invoke_agent_1791821912867_0"
AGENT = "b42a9a21-8120-4a1e-8e05-7e829d070b4e"
PROMPT = "Read the file 'note.txt' in the current directory and report its contents."
ANSWER = "Contents of 'note.txt': 'hello world'."


def _progress(state: str = "completed", reason: str | None = "GOAL", result: str | None = None) -> dict:
    p = {
        "isSubagentProgress": True, "agentName": "generalist",
        "recentActivity": [
            {"id": "t1", "type": "thought", "content": "Initiating File Access", "status": "running"},
            {"id": f"{AGENT}#0-0", "type": "tool_call", "content": "read_file", "description": "note.txt",
             "args": json.dumps({"file_path": "note.txt"}), "status": "completed"},
        ],
        "state": state,
    }
    if state == "completed":
        p["result"] = result if result is not None else json.dumps({"response": ANSWER}, indent=2)
        p["terminateReason"] = reason
    if state == "error":
        p["recentActivity"].append({"id": "e", "type": "thought", "content": "Error: quota", "status": "error"})
    return p


def _call(progress: dict | None = None, with_agent_id: bool = True, finished: bool = True) -> dict:
    call = {"id": TOOL, "name": "invoke_agent", "args": {"agent_name": "generalist", "prompt": PROMPT}}
    if finished:
        call.update({
            "status": "success", "timestamp": "2026-10-12T16:18:37.531Z",
            "resultDisplay": progress or _progress(),
            "result": [{"functionResponse": {"id": TOOL, "name": "invoke_agent", "response": {"output": (
                "Subagent 'generalist' finished.\nTermination Reason: GOAL\nResult:\n"
                + json.dumps({"response": ANSWER}, indent=2))}}}],
        })
        if with_agent_id:
            call["agentId"] = AGENT
    return call


def _chat(dir_: Path, sid: str, call: dict | None, child: bool = True) -> Path:
    dir_.mkdir(parents=True, exist_ok=True)
    parent = dir_ / f"session-2026-10-12T16-18-{sid[:8]}.jsonl"
    lines = [
        {"sessionId": sid, "projectHash": "h", "startTime": "2026-10-12T16:18:28Z",
         "lastUpdated": "2026-10-12T16:18:28Z", "kind": "main"},
        {"id": "u1", "timestamp": "2026-10-12T16:18:29Z", "type": "user", "content": [{"text": "delegate"}]},
    ]
    if call is not None:
        lines.append({"id": "g1", "timestamp": "2026-10-12T16:18:32.912Z", "type": "gemini", "content": "",
                      "toolCalls": [call]})
    parent.write_text("\n".join(json.dumps(x) for x in lines) + "\n")
    if child:
        cdir = dir_ / sid
        cdir.mkdir(exist_ok=True)
        (cdir / f"{AGENT}.jsonl").write_text("\n".join(json.dumps(x) for x in [
            {"sessionId": AGENT, "projectHash": "h", "startTime": "2026-10-12T16:18:32Z", "kind": "subagent"},
            {"id": "c1", "timestamp": "2026-10-12T16:18:32.923Z", "type": "user", "content": [{"text": PROMPT}]},
            {"id": "c2", "timestamp": "2026-10-12T16:18:35Z", "type": "gemini", "content": "",
             "tokens": {"total": 10368}, "toolCalls": [{
                 "id": f"{AGENT}#0-0", "name": "read_file", "args": {"file_path": "note.txt"}, "status": "success",
                 "result": [{"functionResponse": {"id": f"{AGENT}#0-0", "name": "read_file",
                                                  "response": {"output": "hello world\n"}}}]}]},
            {"id": "c3", "timestamp": "2026-10-12T16:18:37Z", "type": "gemini", "content": "",
             "tokens": {"total": 10459}, "toolCalls": [{
                 "id": f"{AGENT}#1-0", "name": "complete_task", "args": {"result": {"response": ANSWER}},
                 "status": "success", "result": [{"functionResponse": {
                     "id": f"{AGENT}#1-0", "name": "complete_task",
                     "response": {"output": "Output submitted and task completed."}}}]}]},
        ]) + "\n")
    return parent


# ---------------------------------------------------------------------------
# Outcome parsing
# ---------------------------------------------------------------------------

def test_readable_result():
    assert readable_result(json.dumps({"response": " hi "})) == "hi"
    assert readable_result("plain text") == "plain text"
    assert json.loads(readable_result(json.dumps({"a": 1, "b": 2}))) == {"a": 1, "b": 2}
    assert readable_result(None) == ""


def test_progress_and_text_outcomes():
    assert progress_outcome(_progress()) == ("done", ANSWER)
    assert progress_outcome(_progress(reason="MAX_TURNS", result="")) == ("failed", "Ended: MAX_TURNS")
    assert progress_outcome(_progress(state="cancelled")) == ("stopped", "Cancelled")
    assert progress_outcome(_progress(state="error")) == ("failed", "Error: quota")
    assert text_outcome(
        "Subagent 'generalist' finished.\nTermination Reason: GOAL\nResult:\n" + json.dumps({"response": "ok"})
    ) == ("done", "ok")
    assert text_outcome("Subagent 'generalist' failed. Error: boom") == ("failed", "boom")
    assert text_outcome("something else") is None


# ---------------------------------------------------------------------------
# Live
# ---------------------------------------------------------------------------

async def _run(sm: GeminiSessionManager, lines: list[dict]) -> list:
    proc = _make_fake_proc(lines)
    with patch("manager.gemini.session.asyncio.create_subprocess_exec", return_value=proc):
        return [ev async for ev in sm.send("delegate")]


def _tool_result_no_output(status: str = "success") -> dict:
    # Exactly what 0.63 streams for a local subagent: no ``output`` key.
    return {"type": "tool_result", "timestamp": "2026-10-12T16:18:37.531Z", "tool_id": TOOL, "status": status}


async def test_live_finish_reads_outcome_from_the_chat_file(tmp_path, monkeypatch):
    sm = GeminiSessionManager(local_id="g1", config=ManagerConfig(project_dir=str(tmp_path), provider="gemini"))
    await sm.start()
    try:
        sid = sm.sdk_session_id
        parent = _chat(tmp_path / "chats", sid, _call())
        monkeypatch.setattr(gemini_adapter, "_gemini_jsonl_candidates", lambda s: [parent] if s == sid else [])
        events = await _run(sm, [
            _init_event(sid),
            _tool_use_event("invoke_agent", TOOL, {"agent_name": "generalist", "prompt": PROMPT}),
            _tool_result_no_output(),
            _result_event(),
        ])
    finally:
        await sm.stop()
    kinds = [type(e).__name__ for e in events if not type(e).__name__.startswith("Thinking")]
    assert kinds == ["ToolUse", "SubagentStarted", "ToolResult", "SubagentFinished", "TurnComplete"]
    started = next(e for e in events if isinstance(e, SubagentStarted))
    assert (started.tool_use_id, started.agent_type, started.background) == (TOOL, "generalist", False)
    assert started.description.startswith("Read the file 'note.txt'")
    card = next(e for e in events if isinstance(e, ToolResult))
    assert card.output == ANSWER and not card.is_error
    fin = next(e for e in events if isinstance(e, SubagentFinished))
    assert (fin.status, fin.summary) == ("done", ANSWER)
    assert fin.usage == {"tokens": 20827, "tool_uses": 1, "duration_ms": None}
    assert sm.subagent_snapshot()[0]["status"] == "done"


async def test_live_progress_json_output_and_error_status():
    sm = GeminiSessionManager(local_id="g2", config=ManagerConfig(project_dir="/tmp", provider="gemini",
                                                                    ssh_host=None))
    await sm.start()
    try:
        events = await _run(sm, [
            _init_event(sm.sdk_session_id),
            _tool_use_event("invoke_agent", TOOL, {"agent_name": "generalist", "prompt": PROMPT}),
            dict(_tool_result_no_output(), output=json.dumps(_progress(state="error"))),
            _result_event(),
        ])
    finally:
        await sm.stop()
    fin = next(e for e in events if isinstance(e, SubagentFinished))
    assert (fin.status, fin.summary) == ("failed", "Error: quota")
    assert next(e for e in events if isinstance(e, ToolResult)).output == "Error: quota"


async def test_process_exit_mid_agent_stops_it_out_of_turn():
    sm = GeminiSessionManager(local_id="g3", config=ManagerConfig(project_dir="/tmp", provider="gemini"))
    oot: list = []
    sm.set_out_of_turn_sink(oot.append)
    await sm.start()
    try:
        events = await _run(sm, [
            _init_event(sm.sdk_session_id),
            _tool_use_event("invoke_agent", TOOL, {"agent_name": "generalist", "prompt": PROMPT}),
        ])
    finally:
        await sm.stop()
    assert isinstance(events[-1], TurnComplete)
    assert [(e.tool_use_id, e.status) for e in oot if isinstance(e, SubagentFinished)] == [(TOOL, "stopped")]


# ---------------------------------------------------------------------------
# History
# ---------------------------------------------------------------------------

SID = "6457ba86-1d49-4b9e-8173-89da4e0c252c"


def test_history_rows_transcript_and_card(tmp_path):
    parent = _chat(tmp_path / "chats", SID, _call())
    a = GeminiAdapter()
    rows = a.list_subagents(parent)
    assert len(rows) == 1
    row = rows[0]
    assert (row["tool_use_id"], row["agent_type"], row["status"], row["summary"]) == (
        TOOL, "generalist", "done", ANSWER)
    assert row["usage"] == {"tokens": 20827, "tool_uses": 1, "duration_ms": None}
    assert row["ended_at"] == "2026-10-12T16:18:37.531Z"
    msgs = a.read_subagent_messages(parent, TOOL)
    assert msgs[0]["type"] == "user"
    assert msgs[-1]["message"]["content"] == [{"type": "text", "text": ANSWER}]
    assert a.read_subagent_messages(parent, "nope") is None
    # the parent's card shows the answer, not the progress object / envelope
    results = [b for m in a.read_messages(parent) if isinstance(m["message"]["content"], list)
               for b in m["message"]["content"] if b.get("type") == "tool_result"]
    assert [r["content"] for r in results] == [ANSWER]


def test_history_without_child_file_is_synthesised(tmp_path):
    parent = _chat(tmp_path / "chats", SID, _call(), child=False)
    msgs = GeminiAdapter().read_subagent_messages(parent, TOOL)
    assert [m["type"] for m in msgs] == ["user", "assistant", "user", "assistant"]
    assert msgs[0]["message"]["content"] == PROMPT
    assert [b["type"] for b in msgs[1]["message"]["content"]] == ["thinking", "tool_use"]
    assert msgs[-1]["message"]["content"][0]["text"] == ANSWER


def test_running_call_matched_by_prompt(tmp_path):
    parent = _chat(tmp_path / "chats", SID, _call(finished=False))
    a = GeminiAdapter()
    rows = a.list_subagents(parent)
    # not finished on disk → "stopped" unless the live registry says running
    assert rows[0]["status"] == "stopped" and rows[0]["summary"] is None
    msgs = a.read_subagent_messages(parent, TOOL)
    assert msgs and msgs[0]["type"] == "user"


@pytest.mark.parametrize("call", [None])
def test_no_subagents(tmp_path, call):
    parent = _chat(tmp_path / "chats", SID, call, child=False)
    assert GeminiAdapter().list_subagents(parent) == []
