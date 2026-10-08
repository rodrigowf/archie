"""Tests for manager/qwen_session.py — mocked subprocess, no real qwen CLI.

The Qwen session manager is fundamentally different from Claude's:
- Each ``send()`` spawns a fresh ``qwen`` subprocess (one-shot per turn).
- ``--resume <session_id>`` is what makes multi-turn work.
- Output is stream-json on stdout, parsed line-by-line.
- No persistent SDK client — the lifecycle task only marks IDLE and waits.

These tests mock ``asyncio.create_subprocess_exec`` to feed canned
stream-json output and verify the event translation.
"""

from __future__ import annotations

import asyncio
import json
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from manager.config import ManagerConfig
from manager.qwen.session import (
    QwenAbandoned,
    QwenSessionManager,
    _qwen_executable,
)
from manager.types import (
    SessionStatus,
    TextDelta,
    TextComplete,
    ThinkingDelta,
    ThinkingComplete,
    ToolResult,
    ToolUse,
    TurnComplete,
)


# ---------------------------------------------------------------------------
# Helpers — build a fake asyncio.subprocess.Process
# ---------------------------------------------------------------------------

class _FakeStream:
    """Minimal asyncio StreamReader stand-in driven by a list of byte lines.

    Returns lines one-by-one from ``readline()``; once the buffer is exhausted
    it returns ``b""`` to signal EOF.
    """

    def __init__(self, lines: list[bytes]) -> None:
        self._lines = list(lines)

    async def readline(self) -> bytes:
        if not self._lines:
            return b""
        return self._lines.pop(0)


class _FakeStdin:
    """Stand-in for ``proc.stdin``; we don't actually consume what's written."""

    def __init__(self) -> None:
        self.written: list[bytes] = []
        self.closed = False

    def write(self, data: bytes) -> None:
        self.written.append(data)

    async def drain(self) -> None:
        pass

    def close(self) -> None:
        self.closed = True


def _make_fake_proc(
    stdout_lines: list[dict | bytes],
    stderr_lines: list[bytes] | None = None,
    returncode: int = 0,
):
    """Build a MagicMock ``asyncio.subprocess.Process`` that emits the given
    stdout events as stream-json lines, then EOFs."""
    proc = MagicMock()
    proc.pid = 12345
    proc.returncode = None  # mutated by wait()

    encoded: list[bytes] = []
    for item in stdout_lines:
        if isinstance(item, bytes):
            encoded.append(item)
        else:
            encoded.append(json.dumps(item).encode("utf-8") + b"\n")

    proc.stdout = _FakeStream(encoded)
    proc.stderr = _FakeStream(stderr_lines or [])
    proc.stdin = _FakeStdin()

    async def _wait():
        proc.returncode = returncode
        return returncode

    proc.wait = AsyncMock(side_effect=_wait)
    proc.send_signal = MagicMock()
    proc.kill = MagicMock()
    return proc


def _stream_event(evt_type: str, **kwargs) -> dict:
    """Build a Qwen ``stream_event`` envelope."""
    return {
        "type": "stream_event",
        "uuid": "evt-1",
        "session_id": "sess-1",
        "parent_tool_use_id": None,
        "event": {"type": evt_type, **kwargs},
    }


def _init_event(session_id: str = "sess-1") -> dict:
    return {
        "type": "system",
        "subtype": "init",
        "uuid": session_id,
        "session_id": session_id,
        "cwd": "/tmp",
        "tools": [],
        "model": "qwen3.6-plus",
    }


def _result_event(session_id: str = "sess-1", num_turns: int = 1) -> dict:
    return {
        "type": "result",
        "subtype": "success",
        "uuid": "res-1",
        "session_id": session_id,
        "is_error": False,
        "num_turns": num_turns,
        "result": "done",
        "usage": {"input_tokens": 10, "output_tokens": 5},
    }


# ---------------------------------------------------------------------------
# Lifecycle
# ---------------------------------------------------------------------------

class TestLifecycle:
    @pytest.mark.asyncio
    async def test_start_returns_local_id(self):
        sm = QwenSessionManager(local_id="my-local")
        sid = await sm.start()
        assert sid == "my-local"
        assert sm.provider_name == "qwen"
        assert sm.status == SessionStatus.IDLE
        await sm.stop()
        assert sm.status == SessionStatus.DISCONNECTED

    @pytest.mark.asyncio
    async def test_start_records_resume_id_as_provider_session_id(self):
        sm = QwenSessionManager(session_id="resumed-qwen-id", local_id="local")
        await sm.start()
        # Resume id is captured up front so the first send() can use it.
        assert sm.sdk_session_id == "resumed-qwen-id"
        assert sm.is_resumed is True
        await sm.stop()

    @pytest.mark.asyncio
    async def test_double_start_raises(self):
        sm = QwenSessionManager()
        await sm.start()
        with pytest.raises(RuntimeError, match="start"):
            await sm.start()
        await sm.stop()

    @pytest.mark.asyncio
    async def test_stop_is_idempotent(self):
        sm = QwenSessionManager()
        await sm.start()
        await sm.stop()
        # Second stop should no-op cleanly.
        await sm.stop()
        assert sm.status == SessionStatus.DISCONNECTED


# ---------------------------------------------------------------------------
# send() — event translation
# ---------------------------------------------------------------------------

class TestSendEventTranslation:
    @pytest.mark.asyncio
    async def test_basic_text_response(self):
        """A minimal Qwen interaction: init → text block start/delta/stop →
        assistant message → result. We should emit TextDelta, TextComplete,
        and TurnComplete."""
        proc = _make_fake_proc([
            _init_event(),
            _stream_event(
                "message_start",
                message={"id": "m1", "role": "assistant", "content": []},
            ),
            _stream_event(
                "content_block_start",
                index=0,
                content_block={"type": "text", "text": ""},
            ),
            _stream_event(
                "content_block_delta",
                index=0,
                delta={"type": "text_delta", "text": "Hello"},
            ),
            _stream_event(
                "content_block_delta",
                index=0,
                delta={"type": "text_delta", "text": " there"},
            ),
            _stream_event("content_block_stop", index=0),
            _stream_event("message_stop"),
            _result_event(),
        ])

        sm = QwenSessionManager(local_id="t1")
        await sm.start()
        events = []
        with patch("asyncio.create_subprocess_exec", AsyncMock(return_value=proc)):
            async for ev in sm.send("hi"):
                events.append(ev)
        await sm.stop()

        text_deltas = [e for e in events if isinstance(e, TextDelta)]
        text_completes = [e for e in events if isinstance(e, TextComplete)]
        turn_completes = [e for e in events if isinstance(e, TurnComplete)]

        assert [e.text for e in text_deltas] == ["Hello", " there"]
        assert len(text_completes) == 1
        assert text_completes[0].text == "Hello there"
        assert len(turn_completes) == 1
        assert turn_completes[0].session_id == "sess-1"
        assert turn_completes[0].num_turns == 1

    @pytest.mark.asyncio
    async def test_thinking_block_emits_thinking_events(self):
        proc = _make_fake_proc([
            _init_event(),
            _stream_event(
                "content_block_start",
                index=0,
                content_block={"type": "thinking", "thinking": ""},
            ),
            _stream_event(
                "content_block_delta",
                index=0,
                delta={"type": "thinking_delta", "thinking": "let me think"},
            ),
            _stream_event("content_block_stop", index=0),
            _result_event(),
        ])

        sm = QwenSessionManager()
        await sm.start()
        events = []
        with patch("asyncio.create_subprocess_exec", AsyncMock(return_value=proc)):
            async for ev in sm.send("hi"):
                events.append(ev)
        await sm.stop()

        thinking_deltas = [e for e in events if isinstance(e, ThinkingDelta)]
        thinking_completes = [e for e in events if isinstance(e, ThinkingComplete)]
        assert [e.text for e in thinking_deltas] == ["let me think"]
        assert len(thinking_completes) == 1
        assert thinking_completes[0].text == "let me think"

    @pytest.mark.asyncio
    async def test_assistant_message_with_tool_use_emits_tooluse_event(self):
        """When Qwen emits an assistant message with a tool_use block, the
        wrapper should emit a ToolUse event with the right id/name/input."""
        proc = _make_fake_proc([
            _init_event(),
            {
                "type": "assistant",
                "uuid": "asst-1",
                "session_id": "sess-1",
                "parent_tool_use_id": None,
                "message": {
                    "id": "m1", "type": "message", "role": "assistant",
                    "content": [
                        {"type": "text", "text": "running tool"},
                        {
                            "type": "tool_use",
                            "id": "call_42",
                            "name": "Bash",
                            "input": {"command": "ls"},
                        },
                    ],
                },
            },
            _result_event(),
        ])

        sm = QwenSessionManager()
        await sm.start()
        events = []
        with patch("asyncio.create_subprocess_exec", AsyncMock(return_value=proc)):
            async for ev in sm.send("run ls"):
                events.append(ev)
        await sm.stop()

        tool_uses = [e for e in events if isinstance(e, ToolUse)]
        assert len(tool_uses) == 1
        assert tool_uses[0].tool_use_id == "call_42"
        assert tool_uses[0].tool_name == "Bash"
        assert tool_uses[0].tool_input == {"command": "ls"}

    @pytest.mark.asyncio
    async def test_tool_result_from_user_message(self):
        """Qwen sends tool results as user messages with tool_result blocks
        in the content array."""
        proc = _make_fake_proc([
            _init_event(),
            {
                "type": "user",
                "uuid": "user-tool-result",
                "session_id": "sess-1",
                "message": {
                    "role": "user",
                    "content": [
                        {
                            "type": "tool_result",
                            "tool_use_id": "call_42",
                            "content": "file1\nfile2",
                            "is_error": False,
                        },
                    ],
                },
            },
            _result_event(),
        ])

        sm = QwenSessionManager()
        await sm.start()
        events = []
        with patch("asyncio.create_subprocess_exec", AsyncMock(return_value=proc)):
            async for ev in sm.send("hi"):
                events.append(ev)
        await sm.stop()

        results = [e for e in events if isinstance(e, ToolResult)]
        assert len(results) == 1
        assert results[0].tool_use_id == "call_42"
        assert results[0].output == "file1\nfile2"
        assert results[0].is_error is False

    @pytest.mark.asyncio
    async def test_session_id_captured_from_init_event(self):
        """The first init event publishes the session_id; subsequent turns
        should reuse it via --resume."""
        proc = _make_fake_proc([
            _init_event(session_id="freshly-created-id"),
            _result_event(session_id="freshly-created-id"),
        ])

        sm = QwenSessionManager()
        await sm.start()
        with patch("asyncio.create_subprocess_exec", AsyncMock(return_value=proc)):
            async for _ in sm.send("hi"):
                pass
        assert sm.sdk_session_id == "freshly-created-id"
        await sm.stop()

    @pytest.mark.asyncio
    async def test_unparseable_stdout_lines_are_skipped(self):
        """A garbage line in the middle of valid output shouldn't crash the
        stream — we should just log a warning and keep going."""
        proc = _make_fake_proc([
            _init_event(),
            b"not valid json at all\n",
            _stream_event(
                "content_block_start", index=0,
                content_block={"type": "text", "text": ""},
            ),
            _stream_event(
                "content_block_delta", index=0,
                delta={"type": "text_delta", "text": "ok"},
            ),
            _stream_event("content_block_stop", index=0),
            _result_event(),
        ])

        sm = QwenSessionManager()
        await sm.start()
        events = []
        with patch("asyncio.create_subprocess_exec", AsyncMock(return_value=proc)):
            async for ev in sm.send("hi"):
                events.append(ev)
        await sm.stop()
        assert any(isinstance(e, TextDelta) for e in events)
        assert any(isinstance(e, TurnComplete) for e in events)


# ---------------------------------------------------------------------------
# argv construction
# ---------------------------------------------------------------------------

class TestBuildArgv:
    def test_argv_baseline(self):
        sm = QwenSessionManager(config=ManagerConfig(project_dir="/tmp"))
        argv = sm._build_argv()
        # First element is the qwen executable.
        assert argv[0] == _qwen_executable()
        # Required protocol flags
        assert "--input-format" in argv and "stream-json" in argv
        assert "--output-format" in argv
        assert "--include-partial-messages" in argv
        # Approval mode is yolo (our wrapper enforces gating at a higher level).
        assert "--approval-mode" in argv
        assert argv[argv.index("--approval-mode") + 1] == "yolo"

    @pytest.mark.asyncio
    async def test_argv_includes_resume_when_session_id_set(self):
        """``_provider_session_id`` is populated by ``_run_lifecycle`` when
        ``session_id`` is passed to the constructor; argv-build happens after."""
        sm = QwenSessionManager(session_id="prev-id")
        await sm.start()
        argv = sm._build_argv()
        await sm.stop()
        assert "--resume" in argv
        assert argv[argv.index("--resume") + 1] == "prev-id"

    @pytest.mark.asyncio
    async def test_argv_omits_resume_for_fresh_session(self):
        sm = QwenSessionManager()
        await sm.start()
        argv = sm._build_argv()
        await sm.stop()
        assert "--resume" not in argv

    def test_argv_includes_model_override(self):
        sm = QwenSessionManager(config=ManagerConfig(model="qwen3-coder-plus"))
        argv = sm._build_argv()
        assert "--model" in argv
        assert argv[argv.index("--model") + 1] == "qwen3-coder-plus"

    def test_argv_includes_max_turns(self):
        sm = QwenSessionManager(config=ManagerConfig(max_turns=20))
        argv = sm._build_argv()
        assert "--max-session-turns" in argv
        assert argv[argv.index("--max-session-turns") + 1] == "20"


# ---------------------------------------------------------------------------
# Prompt rendering
# ---------------------------------------------------------------------------

class TestRenderPrompt:
    def test_renders_as_stream_json_line(self):
        rendered = QwenSessionManager._render_prompt("Hello")
        assert rendered.endswith("\n")
        obj = json.loads(rendered.strip())
        assert obj == {
            "type": "user",
            "message": {
                "role": "user",
                "content": [{"type": "text", "text": "Hello"}],
            },
        }

    def test_handles_special_characters(self):
        rendered = QwenSessionManager._render_prompt('Quotes "and"\nnewlines')
        obj = json.loads(rendered.strip())
        assert obj["message"]["content"][0]["text"] == 'Quotes "and"\nnewlines'


# ---------------------------------------------------------------------------
# Per-turn PID tracking callbacks
# ---------------------------------------------------------------------------

class TestPidCallbacks:
    @pytest.mark.asyncio
    async def test_callbacks_fire_around_turn(self):
        """Pool-installed callbacks fire when a subprocess spawns and exits.

        Qwen spawns a fresh process per turn, so the pool needs spawn/exit
        notifications to keep its orphan-reaper bookkeeping in sync.
        """
        proc = _make_fake_proc([
            _init_event(),
            _result_event(),
        ])

        sm = QwenSessionManager()
        await sm.start()

        spawned: list[int] = []
        exited: list[int] = []
        sm.set_pid_callbacks(spawned.append, exited.append)

        with patch("asyncio.create_subprocess_exec", AsyncMock(return_value=proc)):
            async for _ in sm.send("hi"):
                pass

        assert spawned == [proc.pid]
        assert exited == [proc.pid]
        await sm.stop()

    @pytest.mark.asyncio
    async def test_callback_exceptions_dont_break_turn(self):
        """A misbehaving callback must NOT take down the in-flight turn.

        The session logs and keeps going — the pool's tracking might be
        stale but the user's message still gets through.
        """
        proc = _make_fake_proc([
            _init_event(),
            _stream_event(
                "content_block_start", index=0,
                content_block={"type": "text", "text": ""},
            ),
            _stream_event(
                "content_block_delta", index=0,
                delta={"type": "text_delta", "text": "ok"},
            ),
            _stream_event("content_block_stop", index=0),
            _result_event(),
        ])

        def explode(_pid: int) -> None:
            raise RuntimeError("callback boom")

        sm = QwenSessionManager()
        await sm.start()
        sm.set_pid_callbacks(explode, explode)

        events = []
        with patch("asyncio.create_subprocess_exec", AsyncMock(return_value=proc)):
            async for ev in sm.send("hi"):
                events.append(ev)
        await sm.stop()

        # The turn still completed despite the callback raising both times.
        assert any(isinstance(e, TurnComplete) for e in events)


# ---------------------------------------------------------------------------
# Interrupt
# ---------------------------------------------------------------------------

class TestInterrupt:
    @pytest.mark.asyncio
    async def test_interrupt_idle_session_is_noop(self):
        """Interrupting when no subprocess is running shouldn't crash."""
        sm = QwenSessionManager()
        await sm.start()
        await sm.interrupt()
        assert sm.status == SessionStatus.INTERRUPTED
        await sm.stop()

    def test_cli_runs_without_relaunch(self):
        """Qwen Code relaunches itself as a child whose parent ignores SIGINT;
        without QWEN_CODE_NO_RELAUNCH an interrupt let the turn run on."""
        env = QwenSessionManager()._build_env()
        assert env["QWEN_CODE_NO_RELAUNCH"] == "true"

    @pytest.mark.asyncio
    async def test_interrupt_signals_group_and_reaps_detached_shell(self):
        """A real process group: SIGINT reaches the CLI, and a shell command it
        started detached (own session) is reaped afterwards."""
        import os as _os
        import sys as _sys

        proc = await asyncio.create_subprocess_exec(
            _sys.executable, "-c",
            "import subprocess, time; "
            "p = subprocess.Popen(['sleep', '30'], start_new_session=True); "
            "print(p.pid, flush=True); time.sleep(30)",
            stdout=asyncio.subprocess.PIPE, start_new_session=True,
        )
        child = int((await proc.stdout.readline()).decode().strip())
        sm = QwenSessionManager()
        sm._proc = proc
        await sm.interrupt()
        await asyncio.wait_for(proc.wait(), timeout=5)  # SIGINT ended the "CLI"
        for _ in range(60):
            await asyncio.sleep(0.1)
            try:
                with open(f"/proc/{child}/stat") as f:
                    state = f.read().rsplit(")", 1)[1].split()[0]
            except FileNotFoundError:
                return
            if state == "Z":
                return
        _os.kill(child, 9)
        pytest.fail("detached shell command survived the interrupt")


# ---------------------------------------------------------------------------
# Error paths
# ---------------------------------------------------------------------------

class TestErrorPaths:
    @pytest.mark.asyncio
    async def test_send_before_start_raises(self):
        sm = QwenSessionManager()
        # Not started → status is DISCONNECTED.
        with pytest.raises(RuntimeError, match="not connected"):
            async for _ in sm.send("hi"):
                pass

    @pytest.mark.asyncio
    async def test_qwen_cli_missing_raises_helpful_error(self):
        sm = QwenSessionManager()
        await sm.start()

        # Simulate the qwen binary not being on $PATH.
        with patch(
            "asyncio.create_subprocess_exec",
            AsyncMock(side_effect=FileNotFoundError("no qwen here")),
        ):
            with pytest.raises(RuntimeError, match="Executable not found"):
                async for _ in sm.send("hi"):
                    pass
        # Status returned to IDLE so the session is reusable once the CLI
        # is installed.
        assert sm.status == SessionStatus.IDLE
        await sm.stop()


# ---------------------------------------------------------------------------
# QwenAbandoned watchdog
# ---------------------------------------------------------------------------

class TestAbandonedWatchdog:
    @pytest.mark.asyncio
    async def test_abandoned_when_no_events_received(self):
        """If the subprocess produces zero events for _TURN_ABANDON_S, raise
        QwenAbandoned. We monkey-patch the threshold tiny for the test."""
        from manager.qwen import session as qs

        original_abandon = qs._TURN_ABANDON_S
        original_first = qs._STALL_FIRST_NOTICE_S
        qs._TURN_ABANDON_S = 0.2
        qs._STALL_FIRST_NOTICE_S = 0.1

        # A process that yields no lines, then EOF after a long wait.
        async def _slow_readline():
            await asyncio.sleep(5)
            return b""

        proc = MagicMock()
        proc.pid = 999
        proc.returncode = None
        proc.stdout = MagicMock()
        proc.stdout.readline = _slow_readline
        proc.stderr = _FakeStream([])
        proc.stdin = _FakeStdin()
        proc.wait = AsyncMock(return_value=0)
        proc.send_signal = MagicMock()
        proc.kill = MagicMock()

        sm = QwenSessionManager()
        await sm.start()
        try:
            with patch("asyncio.create_subprocess_exec", AsyncMock(return_value=proc)):
                with pytest.raises(QwenAbandoned) as excinfo:
                    async for _ in sm.send("hi"):
                        pass
            assert excinfo.value.elapsed_seconds >= 0.2
        finally:
            qs._TURN_ABANDON_S = original_abandon
            qs._STALL_FIRST_NOTICE_S = original_first
            await sm.stop()


# ---------------------------------------------------------------------------
# 0.25 upgrade: per-run settings, env, fork, error surfacing
# ---------------------------------------------------------------------------

@pytest.fixture
def qwen_settings_home(tmp_path, monkeypatch):
    """A fixture ~/.qwen/settings.json and a private runtime dir."""
    home = tmp_path / "qwen-home"
    home.mkdir()
    (home / "settings.json").write_text(json.dumps({
        "env": {"DASHSCOPE_API_KEY": "sk-never-copied"},
        "model": {"name": "qwen3.6-plus"},
        "modelProviders": {"openai": [{
            "id": "qwen3.6-plus",
            "name": "q36",
            "baseUrl": "https://dashscope-intl.aliyuncs.com/compatible-mode/v1",
            "envKey": "DASHSCOPE_API_KEY",
            "generationConfig": {"extra_body": {"enable_thinking": True}},
        }]},
    }))
    monkeypatch.setenv("QWEN_HOME", str(home))
    runtime = tmp_path / "runtime"
    runtime.mkdir()
    monkeypatch.setenv("XDG_RUNTIME_DIR", str(runtime))
    return runtime


async def _run_turn(sm, proc, captured: dict):
    """Run one turn; capture argv/env and the settings file content at spawn."""
    async def _spawn(*argv, **kwargs):
        captured["argv"] = list(argv)
        captured["env"] = kwargs.get("env") or {}
        path = captured["env"].get("QWEN_CODE_SYSTEM_SETTINGS_PATH")
        captured["path"] = path
        captured["settings"] = json.loads(open(path).read()) if path else None
        return proc

    events = []
    with patch("asyncio.create_subprocess_exec", AsyncMock(side_effect=_spawn)):
        async for ev in sm.send("hi"):
            events.append(ev)
    return events


class TestRunSettings:
    def test_build_env_suppresses_yolo_warning_and_sets_path(self, monkeypatch):
        monkeypatch.setenv("CLAUDECODE", "1")
        monkeypatch.setenv("QWEN_CODE_SYSTEM_SETTINGS_PATH", "/stray/path.json")
        sm = QwenSessionManager()
        env = sm._build_env()
        assert env["QWEN_CODE_SUPPRESS_YOLO_WARNING"] == "1"
        assert "CLAUDECODE" not in env
        assert "QWEN_CODE_SYSTEM_SETTINGS_PATH" not in env
        env = sm._build_env("/run/x.json")
        assert env["QWEN_CODE_SYSTEM_SETTINGS_PATH"] == "/run/x.json"

    @pytest.mark.asyncio
    async def test_turn_writes_private_settings_and_removes_it(self, qwen_settings_home):
        proc = _make_fake_proc([_init_event(), _result_event()])
        sm = QwenSessionManager(local_id="rs1", config=ManagerConfig(project_dir="/tmp"))
        await sm.start()
        captured: dict = {}
        await _run_turn(sm, proc, captured)
        await sm.stop()

        path = captured["path"]
        assert path and path.startswith(str(qwen_settings_home / "archie-qwen"))
        # No options, no model → only the fixed keys (memory off etc.).
        s = captured["settings"]
        assert s["memory"]["enableManagedAutoMemory"] is False
        assert s["memory"]["enableManagedAutoDream"] is False
        assert s["memory"]["enableAutoSkill"] is False
        assert s["general"]["outputLanguage"] == "English"
        assert "modelProviders" not in s
        import os as _os
        assert not _os.path.exists(path)  # deleted after the turn

    @pytest.mark.asyncio
    async def test_no_options_no_model_argv_unchanged(self, qwen_settings_home):
        proc = _make_fake_proc([_init_event(), _result_event()])
        sm = QwenSessionManager(local_id="rs2", config=ManagerConfig(project_dir="/tmp"))
        await sm.start()
        captured: dict = {}
        await _run_turn(sm, proc, captured)
        await sm.stop()
        assert captured["argv"] == [
            _qwen_executable(),
            "--input-format", "stream-json",
            "--output-format", "stream-json",
            "--include-partial-messages",
            "--approval-mode", "yolo",
            "--channel", "SDK",
        ]

    @pytest.mark.asyncio
    async def test_options_and_model_land_in_settings(self, qwen_settings_home):
        proc = _make_fake_proc([_init_event(), _result_event()])
        cfg = ManagerConfig(
            project_dir="/tmp",
            model="qwen3.6-plus",
            harness_options={"thinking": False, "temperature": 0.4, "effort": "low"},
        )
        sm = QwenSessionManager(local_id="rs3", config=cfg)
        await sm.start()
        captured: dict = {}
        await _run_turn(sm, proc, captured)
        await sm.stop()

        assert captured["argv"][captured["argv"].index("--model") + 1] == "qwen3.6-plus"
        entry = captured["settings"]["modelProviders"]["openai"][0]
        assert entry["generationConfig"]["extra_body"] == {"enable_thinking": False}
        assert entry["generationConfig"]["samplingParams"] == {"temperature": 0.4}
        # effort does not apply to qwen3.6-plus → not sent.
        assert "reasoning_effort" not in json.dumps(captured["settings"])
        assert "sk-never-copied" not in json.dumps(captured["settings"])

    @pytest.mark.asyncio
    async def test_live_only_model_gets_synthetic_entry(self, qwen_settings_home):
        proc = _make_fake_proc([_init_event(), _result_event()])
        cfg = ManagerConfig(project_dir="/tmp", model="qwen3.8-max", harness_options={"effort": "medium"})
        sm = QwenSessionManager(local_id="rs4", config=cfg)
        await sm.start()
        captured: dict = {}
        await _run_turn(sm, proc, captured)
        await sm.stop()
        entries = captured["settings"]["modelProviders"]["openai"]
        assert [e["id"] for e in entries] == ["qwen3.6-plus", "qwen3.8-max"]
        assert entries[1]["envKey"] == "DASHSCOPE_API_KEY"
        assert entries[1]["generationConfig"]["extra_body"] == {"reasoning_effort": "medium"}

    @pytest.mark.asyncio
    async def test_settings_write_failure_still_runs_turn(self, qwen_settings_home):
        proc = _make_fake_proc([_init_event(), _result_event()])
        sm = QwenSessionManager(local_id="rs5", config=ManagerConfig(project_dir="/tmp"))
        await sm.start()
        captured: dict = {}
        with patch("manager.qwen.run_settings.write_run_settings", side_effect=OSError("disk full")):
            events = await _run_turn(sm, proc, captured)
        await sm.stop()
        assert captured["path"] is None
        assert any(isinstance(e, TurnComplete) for e in events)


class TestForkSession:
    @pytest.mark.asyncio
    async def test_fork_flag_on_first_turn_only(self, qwen_settings_home):
        sm = QwenSessionManager(session_id="parent-id", local_id="f1", fork=True)
        await sm.start()
        sm._cli_version = (0, 25, 0)
        captured: dict = {}
        proc = _make_fake_proc([_init_event("child-id"), _result_event("child-id")])
        await _run_turn(sm, proc, captured)
        argv = captured["argv"]
        assert argv[argv.index("--resume") + 1] == "parent-id"
        assert "--fork-session" in argv
        assert sm.sdk_session_id == "child-id"

        proc2 = _make_fake_proc([_init_event("child-id"), _result_event("child-id")])
        await _run_turn(sm, proc2, captured)
        await sm.stop()
        argv = captured["argv"]
        assert argv[argv.index("--resume") + 1] == "child-id"
        assert "--fork-session" not in argv

    @pytest.mark.asyncio
    async def test_init_adopts_child_id_during_fork_turn(self):
        sm = QwenSessionManager(session_id="parent-id", local_id="f2", fork=True)
        await sm.start()
        sm._forking_turn = True
        sm._translate_event(_init_event("child-id"), {}, [], [])
        await sm.stop()
        assert sm.sdk_session_id == "child-id"

    @pytest.mark.asyncio
    async def test_no_fork_flag_on_old_cli(self):
        sm = QwenSessionManager(session_id="parent-id", local_id="f3", fork=True)
        await sm.start()
        sm._cli_version = (0, 15, 11)
        argv = sm._build_argv()
        await sm.stop()
        assert "--fork-session" not in argv

    def test_non_fork_resume_has_no_fork_flag(self):
        sm = QwenSessionManager(session_id="x")
        sm._provider_session_id = "x"
        assert "--fork-session" not in sm._build_argv()

    def test_version_parse(self):
        from manager.qwen.session import _parse_version
        assert _parse_version("0.25.0\n") == (0, 25, 0)
        assert _parse_version("qwen 0.15.11") == (0, 15, 11)
        assert _parse_version("") is None


class TestResultAndIgnoredEvents:
    def _sm(self):
        return QwenSessionManager(local_id="e1")

    def test_error_message_surfaces(self):
        sm = self._sm()
        out = sm._translate_event({
            "type": "result", "subtype": "error_during_execution", "is_error": True,
            "num_turns": 1, "session_id": "s",
            "error": {"message": "[API Error: Connection error.]"},
        }, {}, [], [])
        assert len(out) == 1 and isinstance(out[0], TurnComplete)
        assert out[0].is_error is True
        assert out[0].result == "[API Error: Connection error.]"

    def test_error_without_message_falls_back_to_subtype(self):
        out = self._sm()._translate_event({
            "type": "result", "subtype": "error_max_turns", "is_error": True, "num_turns": 1,
        }, {}, [], [])
        assert out[0].result == "error_max_turns"

    def test_success_result_untouched(self):
        out = self._sm()._translate_event(_result_event(), {}, [], [])
        assert out[0].is_error is False and out[0].result == "done"

    @pytest.mark.parametrize("obj", [
        {"type": "stream_event", "event": {"type": "goal_state", "goal_state": {"v": 2}}},
        {"type": "control_response", "response": {"subtype": "success", "request_id": "r1"}},
        {"type": "system", "subtype": "some_new_warning"},
        {"type": "assistant", "message": {"content": [{"type": "thinking", "thinking": "x"}]}},
    ])
    def test_new_025_lines_produce_no_events(self, obj):
        assert self._sm()._translate_event(obj, {}, [], []) == []
