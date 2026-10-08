"""Tests for manager/gemini/session.py — mocked subprocess, no real gemini CLI.

The Gemini session manager is shaped like Qwen's: each ``send()`` spawns
a fresh subprocess (one-shot per turn) and parses stream-json on stdout.
These tests mock ``asyncio.create_subprocess_exec`` to feed canned
stream-json output and verify the event translation.
"""

from __future__ import annotations

import asyncio
import json
import uuid
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from manager.config import ManagerConfig
from manager.gemini.session import (
    AUTH_HELP,
    GeminiAbandoned,
    GeminiSessionManager,
    _gemini_executable,
)
from manager.types import (
    SessionStatus,
    TextComplete,
    TextDelta,
    ThinkingComplete,
    ThinkingDelta,
    ToolResult,
    ToolUse,
    TurnComplete,
)


@pytest.fixture(autouse=True)
def _gemini_env(monkeypatch):
    """Every test runs with an API key, no real CLI warm-up and without
    touching the repo's real .gemini/settings.json."""
    monkeypatch.setenv("GEMINI_API_KEY", "test-key")
    monkeypatch.delenv("ARCHIE_GEMINI_AUTH_TYPE", raising=False)
    with patch.object(GeminiSessionManager, "_prewarm", AsyncMock()), patch(
        "manager.gemini.workspace_settings.ensure_workspace_settings", return_value=False,
    ):
        yield


# ---------------------------------------------------------------------------
# Fake subprocess helpers — same shape as test_qwen_session.py
# ---------------------------------------------------------------------------


class _FakeStream:
    def __init__(self, lines: list[bytes]) -> None:
        self._lines = list(lines)

    async def readline(self) -> bytes:
        if not self._lines:
            return b""
        return self._lines.pop(0)


class _FakeStdin:
    def __init__(self) -> None:
        self.closed = False

    def write(self, data: bytes) -> None:
        pass

    async def drain(self) -> None:
        pass

    def close(self) -> None:
        self.closed = True


def _make_fake_proc(
    stdout_lines: list[dict | bytes],
    stderr_lines: list[bytes] | None = None,
    returncode: int = 0,
):
    """Build a MagicMock asyncio.subprocess.Process emitting the given
    stream-json lines, then EOFing."""
    proc = MagicMock()
    proc.pid = 54321
    proc.returncode = None

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


# Build a couple of canonical stream-json events.

def _init_event(session_id: str = "11111111-1111-1111-1111-111111111111") -> dict:
    return {
        "type": "init",
        "timestamp": "2026-05-15T20:00:00Z",
        "session_id": session_id,
        "model": "gemini-3-flash-preview",
    }


def _user_echo_event(content: str = "hi") -> dict:
    return {
        "type": "message",
        "timestamp": "2026-05-15T20:00:00Z",
        "role": "user",
        "content": content,
    }


def _assistant_delta_event(content: str) -> dict:
    return {
        "type": "message",
        "timestamp": "2026-05-15T20:00:01Z",
        "role": "assistant",
        "content": content,
        "delta": True,
    }


def _tool_use_event(name: str, tid: str, params: dict) -> dict:
    return {
        "type": "tool_use",
        "timestamp": "2026-05-15T20:00:02Z",
        "tool_name": name,
        "tool_id": tid,
        "parameters": params,
    }


def _tool_result_event(tid: str, output: str, error: bool = False) -> dict:
    if error:
        return {
            "type": "tool_result",
            "timestamp": "2026-05-15T20:00:03Z",
            "tool_id": tid,
            "status": "error",
            "error": {"type": "x", "message": output},
        }
    return {
        "type": "tool_result",
        "timestamp": "2026-05-15T20:00:03Z",
        "tool_id": tid,
        "status": "success",
        "output": output,
    }


def _result_event(status: str = "success") -> dict:
    return {
        "type": "result",
        "timestamp": "2026-05-15T20:00:04Z",
        "status": status,
        "stats": {
            "total_tokens": 100, "input_tokens": 50, "output_tokens": 10, "cached": 0,
        },
    }


def _session_file(tmp_path, session_id: str, *, resumable: bool, snapshot: bool = False):
    """A Gemini session JSONL: resumable, or the stub a failed turn leaves."""
    lines: list[dict] = [{
        "sessionId": session_id, "projectHash": "h", "startTime": "2026-10-07T10:00:00Z",
        "lastUpdated": "2026-10-07T10:00:00Z", "kind": "main",
    }]
    if snapshot:
        lines.append({"$set": {"messages": [{
            "id": "ctx", "timestamp": "t", "type": "user",
            "content": [{"text": "<session_context>\nThis is the Gemini CLI."}],
        }], "lastUpdated": "2026-10-07T10:00:01Z"}})
    if resumable:
        lines.append({"id": "u1", "timestamp": "t", "type": "user", "content": [{"text": "hi"}]})
        lines.append({"id": "g1", "timestamp": "t", "type": "gemini", "content": "hello"})
    path = tmp_path / f"session-2026-10-07T10-00-{session_id[:8]}.jsonl"
    path.write_text("\n".join(json.dumps(x) for x in lines) + "\n")
    return path


# ---------------------------------------------------------------------------
# Lifecycle
# ---------------------------------------------------------------------------


class TestLifecycle:
    @pytest.mark.asyncio
    async def test_start_returns_local_id_and_generates_session_id(self):
        sm = GeminiSessionManager(local_id="my-local")
        sid = await sm.start()
        assert sid == "my-local"
        assert sm.provider_name == "gemini"
        assert sm.status == SessionStatus.IDLE
        # Fresh session — manager generates a UUID up front so the very
        # first send() can pin it via --session-id.
        assert sm.sdk_session_id is not None
        uuid.UUID(sm.sdk_session_id)  # well-formed UUID; raises if not
        await sm.stop()
        assert sm.status == SessionStatus.DISCONNECTED

    @pytest.mark.asyncio
    async def test_start_records_resume_id_as_provider_session_id(self):
        sm = GeminiSessionManager(
            session_id="resume-gemini-id", local_id="local",
        )
        await sm.start()
        assert sm.sdk_session_id == "resume-gemini-id"
        assert sm.is_resumed is True
        await sm.stop()

    @pytest.mark.asyncio
    async def test_double_start_raises(self):
        sm = GeminiSessionManager()
        await sm.start()
        with pytest.raises(RuntimeError, match="start"):
            await sm.start()
        await sm.stop()

    @pytest.mark.asyncio
    async def test_stop_is_idempotent(self):
        sm = GeminiSessionManager()
        await sm.start()
        await sm.stop()
        await sm.stop()
        assert sm.status == SessionStatus.DISCONNECTED


# ---------------------------------------------------------------------------
# Argv construction
# ---------------------------------------------------------------------------


class TestArgvConstruction:
    @pytest.mark.asyncio
    async def test_argv_pins_session_id_on_first_turn(self):
        sm = GeminiSessionManager(local_id="local")
        await sm.start()
        argv = sm._build_argv("hello world")
        # Prompt is on argv (not stdin) per the CLI's --prompt semantic.
        assert "--prompt" in argv
        assert "hello world" in argv
        # First turn pins the id with --session-id, NOT --resume.
        assert "--session-id" in argv
        assert "--resume" not in argv
        # Always pass --skip-trust for headless mode.
        assert "--skip-trust" in argv
        # Stream-json output for parsing.
        assert "--output-format" in argv
        assert "stream-json" in argv
        await sm.stop()

    @pytest.mark.asyncio
    async def test_argv_uses_resume_after_first_turn(self, tmp_path):
        sm = GeminiSessionManager(local_id="local")
        await sm.start()
        # Simulate one completed turn: the CLI has written the session.
        sm._turns = 1
        f = _session_file(tmp_path, sm.sdk_session_id, resumable=True)
        with patch(
            "manager.gemini.adapter._gemini_jsonl_candidates", return_value=[f],
        ):
            argv = sm._build_argv("second prompt")
        assert "--resume" in argv
        assert "--session-id" not in argv
        await sm.stop()

    @pytest.mark.asyncio
    async def test_argv_pins_again_after_a_failed_first_turn(self):
        """A first turn that failed before the CLI wrote the session must not
        flip the next turn to --resume (the CLI can't find the id and every
        later turn would fail): with no JSONL on disk, keep --session-id."""
        sm = GeminiSessionManager(local_id="local")
        await sm.start()
        sm._turns = 1  # the failed turn still counted
        with patch(
            "manager.gemini.adapter._gemini_jsonl_candidates", return_value=[],
        ):
            argv = sm._build_argv("retry")
        assert "--session-id" in argv
        assert "--resume" not in argv
        await sm.stop()

    @pytest.mark.asyncio
    async def test_argv_uses_resume_on_first_turn_of_resumed_session(self, tmp_path):
        """A manager opened on an existing session must --resume from the
        very first turn: the CLI exits with "Session ID ... already exists"
        when given --session-id for an id it already has on disk."""
        sm = GeminiSessionManager(session_id="existing-gemini-id", local_id="local")
        await sm.start()
        f = _session_file(tmp_path, "existing-gemini-id", resumable=True)
        with patch(
            "manager.gemini.adapter._gemini_jsonl_candidates", return_value=[f],
        ):
            argv = sm._build_argv("continue")
        i = argv.index("--resume")
        assert argv[i + 1] == "existing-gemini-id"
        assert "--session-id" not in argv
        await sm.stop()

    @pytest.mark.asyncio
    async def test_argv_pins_resume_id_never_written_to_disk(self):
        """A resume id with no JSONL yet (tab reopened before its first
        turn) still pins with --session-id; --resume would not find it."""
        sm = GeminiSessionManager(session_id="unwritten-gemini-id", local_id="local")
        await sm.start()
        with patch(
            "manager.gemini.adapter._gemini_jsonl_candidates", return_value=[],
        ):
            argv = sm._build_argv("first")
        i = argv.index("--session-id")
        assert argv[i + 1] == "unwritten-gemini-id"
        assert "--resume" not in argv
        await sm.stop()

    @pytest.mark.asyncio
    async def test_argv_passes_model_when_configured(self):
        cfg = ManagerConfig(model="gemini-3-pro-preview")
        sm = GeminiSessionManager(config=cfg, local_id="local")
        await sm.start()
        argv = sm._build_argv("x")
        i = argv.index("--model")
        assert argv[i + 1] == "gemini-3-pro-preview"
        await sm.stop()

    @pytest.mark.asyncio
    async def test_env_sets_trust_workspace_belt_and_suspenders(self):
        sm = GeminiSessionManager()
        await sm.start()
        env = sm._build_env()
        # Even though we pass --skip-trust on argv, also set the env var
        # so a future CLI rename of the flag doesn't break us silently.
        assert env.get("GEMINI_CLI_TRUST_WORKSPACE") == "true"
        # CLAUDECODE marker is stripped (same as Qwen does) so the CLI
        # doesn't think it's nested inside Claude.
        assert "CLAUDECODE" not in env
        await sm.stop()


# ---------------------------------------------------------------------------
# send() — event translation
# ---------------------------------------------------------------------------


class TestSendEventTranslation:
    @pytest.mark.asyncio
    async def test_simple_text_turn_emits_delta_complete_and_turn_complete(self):
        proc = _make_fake_proc([
            _init_event(),
            _user_echo_event("hi"),
            _assistant_delta_event("Hel"),
            _assistant_delta_event("lo!"),
            _result_event(),
        ])

        sm = GeminiSessionManager(local_id="local")
        await sm.start()
        with patch(
            "manager.gemini.session.asyncio.create_subprocess_exec",
            return_value=proc,
        ):
            events = [ev async for ev in sm.send("hi")]
        await sm.stop()

        # Should see two TextDelta chunks, then a TextComplete with the
        # concatenated text, then a TurnComplete.
        deltas = [e for e in events if isinstance(e, TextDelta)]
        completes = [e for e in events if isinstance(e, TextComplete)]
        turns = [e for e in events if isinstance(e, TurnComplete)]

        assert [d.text for d in deltas] == ["Hel", "lo!"]
        assert len(completes) == 1
        assert completes[0].text == "Hello!"
        assert len(turns) == 1
        # Stats are surfaced via the usage dict on TurnComplete.
        assert turns[0].usage.get("total_tokens") == 100

    @pytest.mark.asyncio
    async def test_tool_use_and_tool_result_are_translated(self):
        proc = _make_fake_proc([
            _init_event(),
            _user_echo_event("read"),
            _tool_use_event("read_file", "tid1", {"file_path": "/etc/hosts"}),
            _tool_result_event("tid1", "127.0.0.1 localhost"),
            _assistant_delta_event("Done."),
            _result_event(),
        ])

        sm = GeminiSessionManager(local_id="local")
        await sm.start()
        with patch(
            "manager.gemini.session.asyncio.create_subprocess_exec",
            return_value=proc,
        ):
            events = [ev async for ev in sm.send("read")]
        await sm.stop()

        tool_uses = [e for e in events if isinstance(e, ToolUse)]
        tool_results = [e for e in events if isinstance(e, ToolResult)]

        assert len(tool_uses) == 1
        assert tool_uses[0].tool_name == "read_file"
        assert tool_uses[0].tool_use_id == "tid1"
        assert tool_uses[0].tool_input == {"file_path": "/etc/hosts"}

        assert len(tool_results) == 1
        assert tool_results[0].tool_use_id == "tid1"
        assert tool_results[0].output == "127.0.0.1 localhost"
        assert tool_results[0].is_error is False

    @pytest.mark.asyncio
    async def test_tool_result_error_maps_to_is_error_true(self):
        proc = _make_fake_proc([
            _init_event(),
            _tool_use_event("read_file", "tid1", {"file_path": "/missing"}),
            _tool_result_event("tid1", "No such file", error=True),
            _result_event(),
        ])

        sm = GeminiSessionManager(local_id="local")
        await sm.start()
        with patch(
            "manager.gemini.session.asyncio.create_subprocess_exec",
            return_value=proc,
        ):
            events = [ev async for ev in sm.send("read missing")]
        await sm.stop()

        tool_results = [e for e in events if isinstance(e, ToolResult)]
        assert len(tool_results) == 1
        assert tool_results[0].is_error is True
        assert "No such file" in tool_results[0].output

    @pytest.mark.asyncio
    async def test_user_echo_message_is_filtered_out(self):
        """The CLI echoes the user prompt back on stream-json — we already
        broadcast it via the WS layer, so the session manager must NOT
        re-emit it as a TextDelta."""
        proc = _make_fake_proc([
            _init_event(),
            _user_echo_event("my prompt"),
            _assistant_delta_event("Ack."),
            _result_event(),
        ])

        sm = GeminiSessionManager(local_id="local")
        await sm.start()
        with patch(
            "manager.gemini.session.asyncio.create_subprocess_exec",
            return_value=proc,
        ):
            events = [ev async for ev in sm.send("my prompt")]
        await sm.stop()

        deltas = [e for e in events if isinstance(e, TextDelta)]
        assert all("my prompt" not in d.text for d in deltas)

    @pytest.mark.asyncio
    async def test_init_event_captures_session_id_if_not_yet_pinned(self):
        sm = GeminiSessionManager(local_id="local")
        await sm.start()
        # Force the pinned id back to None to mimic a session that didn't
        # generate one up-front (defensive: future code paths may skip
        # the lifecycle pre-generation).
        sm._provider_session_id = None

        proc = _make_fake_proc([
            _init_event(session_id="learned-from-init"),
            _result_event(),
        ])
        with patch(
            "manager.gemini.session.asyncio.create_subprocess_exec",
            return_value=proc,
        ):
            _ = [ev async for ev in sm.send("hi")]
        await sm.stop()

        assert sm.sdk_session_id == "learned-from-init"

    @pytest.mark.asyncio
    async def test_non_json_stdout_lines_are_skipped(self):
        """The CLI prints "Shell cwd was reset to..." on stdout in some
        builds.  Non-JSON lines must not crash the parser."""
        proc = _make_fake_proc([
            _init_event(),
            b"Shell cwd was reset to /home/rodrigo/assistant\n",
            _assistant_delta_event("ok"),
            _result_event(),
        ])

        sm = GeminiSessionManager(local_id="local")
        await sm.start()
        with patch(
            "manager.gemini.session.asyncio.create_subprocess_exec",
            return_value=proc,
        ):
            events = [ev async for ev in sm.send("hi")]
        await sm.stop()

        # Test passed if we got TurnComplete (didn't crash).
        assert any(isinstance(e, TurnComplete) for e in events)


# ---------------------------------------------------------------------------
# Abandoned-turn watchdog
# ---------------------------------------------------------------------------


class TestAbandonedWatchdog:
    @pytest.mark.asyncio
    async def test_abandoned_when_no_events_received(self):
        """If the subprocess produces zero events for _TURN_ABANDON_S, raise
        GeminiAbandoned. We monkey-patch the threshold tiny for the test."""
        from manager.gemini import session as gs

        original_abandon = gs._TURN_ABANDON_S
        original_first = gs._STALL_FIRST_NOTICE_S
        gs._TURN_ABANDON_S = 0.2
        gs._STALL_FIRST_NOTICE_S = 0.1

        # A subprocess that hangs forever with no output.
        async def _slow_readline():
            await asyncio.sleep(5)
            return b""

        proc = MagicMock()
        proc.pid = 99999
        proc.returncode = None
        proc.stdout = MagicMock()
        proc.stdout.readline = _slow_readline
        proc.stderr = _FakeStream([])
        proc.stdin = _FakeStdin()
        proc.wait = AsyncMock(return_value=0)
        proc.send_signal = MagicMock()
        proc.kill = MagicMock()

        sm = GeminiSessionManager(local_id="local")
        await sm.start()
        try:
            with patch(
                "manager.gemini.session.asyncio.create_subprocess_exec",
                return_value=proc,
            ):
                with pytest.raises(GeminiAbandoned):
                    async for _ in sm.send("hi"):
                        pass
        finally:
            gs._TURN_ABANDON_S = original_abandon
            gs._STALL_FIRST_NOTICE_S = original_first
            await sm.stop()


# ---------------------------------------------------------------------------
# Executable resolution
# ---------------------------------------------------------------------------


def test_gemini_executable_default():
    assert _gemini_executable() == "gemini"


def test_gemini_executable_honors_env(monkeypatch):
    monkeypatch.setenv("GEMINI_CLI_PATH", "/opt/gemini/bin/gemini")
    assert _gemini_executable() == "/opt/gemini/bin/gemini"


# ---------------------------------------------------------------------------
# Env, auth and option mapping (harness_options → argv / env)
# ---------------------------------------------------------------------------


_ARCHIE_VARS = (
    "ARCHIE_GEMINI_THINKING_LEVEL",
    "ARCHIE_GEMINI_LEVEL_MODEL",
    "ARCHIE_GEMINI_BUDGET_MODEL",
    "ARCHIE_GEMINI_THINKING_BUDGET",
)


def _mgr(model: str | None = None, options: dict | None = None, **kw) -> GeminiSessionManager:
    sm = GeminiSessionManager(
        config=ManagerConfig(provider="gemini", model=model, harness_options=options, **kw),
        local_id="local",
    )
    sm._provider_session_id = "11111111-2222-3333-4444-555555555555"
    return sm


class TestEnvAndOptions:
    def test_env_single_process_and_api_key_auth(self):
        env = _mgr()._build_env()
        # Interrupt only reaches the real CLI without the self-relaunch.
        assert env["GEMINI_CLI_NO_RELAUNCH"] == "true"
        # A leftover oauth-personal in ~/.gemini/settings.json must not win.
        assert env["GEMINI_CLI_AUTH_OVERRIDE"] == "gemini-api-key"
        assert not any(k in env for k in _ARCHIE_VARS)

    def test_env_respects_explicit_auth_type(self, monkeypatch):
        monkeypatch.setenv("ARCHIE_GEMINI_AUTH_TYPE", "vertex-ai")
        env = _mgr()._build_env()
        assert "GEMINI_CLI_AUTH_OVERRIDE" not in env

    def test_env_strips_inherited_option_vars(self, monkeypatch):
        monkeypatch.setenv("ARCHIE_GEMINI_THINKING_LEVEL", "LOW")
        monkeypatch.setenv("ARCHIE_GEMINI_BUDGET_MODEL", "gemini-2.5-flash")
        env = _mgr(model="gemini-3.1-pro-preview")._build_env()
        assert not any(k in env for k in _ARCHIE_VARS)

    def test_no_options_means_no_flags_and_default_argv(self):
        sm = _mgr()
        with patch("manager.gemini.adapter._gemini_jsonl_candidates", return_value=[]):
            argv = sm._build_argv("hi")
        assert argv == [
            "gemini", "--prompt", "hi", "--skip-trust", "--output-format", "stream-json",
            "--approval-mode", "yolo", "--session-id", sm.sdk_session_id,
        ]
        assert sm._option_env() == {}

    @pytest.mark.parametrize("mode", ["yolo", "auto_edit", "plan", "default"])
    def test_approval_mode_option(self, mode):
        sm = _mgr(options={"approval_mode": mode})
        with patch("manager.gemini.adapter._gemini_jsonl_candidates", return_value=[]):
            argv = sm._build_argv("hi")
        assert argv[argv.index("--approval-mode") + 1] == mode

    def test_unknown_approval_mode_falls_back_to_yolo(self):
        sm = _mgr(options={"approval_mode": "bogus"})
        with patch("manager.gemini.adapter._gemini_jsonl_candidates", return_value=[]):
            argv = sm._build_argv("hi")
        assert argv[argv.index("--approval-mode") + 1] == "yolo"

    @pytest.mark.parametrize("level", ["minimal", "low", "medium", "high"])
    def test_thinking_level_every_value_on_flash(self, level):
        env = _mgr("gemini-3-flash-preview", {"thinking_level": level})._option_env()
        assert env == {
            "ARCHIE_GEMINI_THINKING_LEVEL": level.upper(),
            "ARCHIE_GEMINI_LEVEL_MODEL": "gemini-3-flash-preview",
        }

    @pytest.mark.parametrize(
        ("model", "level", "expected"),
        [
            ("gemini-3.1-pro-preview", "minimal", "LOW"),
            ("gemini-3-pro-preview", "medium", "HIGH"),
            ("gemini-3.8-flash", "minimal", "LOW"),
            ("gemini-3.5-flash-lite", "minimal", "MINIMAL"),
        ],
    )
    def test_thinking_level_clamped_per_model(self, model, level, expected):
        env = _mgr(model, {"thinking_level": level})._option_env()
        assert env["ARCHIE_GEMINI_THINKING_LEVEL"] == expected

    @pytest.mark.parametrize("model", [None, "auto", "pro", "flash"])
    def test_thinking_level_on_aliases_uses_chat_base_3_only(self, model):
        env = _mgr(model, {"thinking_level": "low"})._option_env()
        assert env == {"ARCHIE_GEMINI_THINKING_LEVEL": "LOW"}

    def test_thinking_level_ignored_for_gemini_25(self):
        assert _mgr("gemini-2.5-flash", {"thinking_level": "low"})._option_env() == {}

    @pytest.mark.parametrize(
        ("model", "budget", "expected"),
        [
            ("gemini-2.5-flash", 1024, "1024"),
            ("gemini-2.5-flash", 0, "0"),
            ("gemini-2.5-flash", -1, "-1"),
            ("gemini-2.5-flash", 30000, "24576"),
            ("gemini-2.5-pro", 0, "128"),
            ("gemini-2.5-flash-lite", 100, "512"),
        ],
    )
    def test_thinking_budget_on_gemini_25(self, model, budget, expected):
        env = _mgr(model, {"thinking_budget": budget})._option_env()
        assert env == {
            "ARCHIE_GEMINI_BUDGET_MODEL": model,
            "ARCHIE_GEMINI_THINKING_BUDGET": expected,
        }

    @pytest.mark.parametrize("model", [None, "auto", "gemini-3.1-pro-preview"])
    def test_thinking_budget_ignored_outside_gemini_25(self, model):
        assert _mgr(model, {"thinking_budget": 2048})._option_env() == {}

    def test_options_reach_the_spawn_env(self):
        env = _mgr("gemini-2.5-flash", {"thinking_budget": 2048, "thinking_level": "low"})._build_env()
        assert env["ARCHIE_GEMINI_THINKING_BUDGET"] == "2048"
        assert "ARCHIE_GEMINI_THINKING_LEVEL" not in env


# ---------------------------------------------------------------------------
# Session stubs left by a failed first turn
# ---------------------------------------------------------------------------


class TestSessionStubs:
    @pytest.mark.parametrize("snapshot", [False, True])
    def test_stub_is_removed_and_id_pinned_again(self, tmp_path, snapshot):
        sm = _mgr()
        stub = _session_file(tmp_path, sm.sdk_session_id, resumable=False, snapshot=snapshot)
        with patch("manager.gemini.adapter._gemini_jsonl_candidates", return_value=[stub]):
            argv = sm._build_argv("retry")
        assert "--session-id" in argv and "--resume" not in argv
        assert not stub.exists()

    def test_other_sessions_file_is_never_removed(self, tmp_path):
        sm = _mgr()
        other = _session_file(tmp_path, "11111111-ffff-ffff-ffff-ffffffffffff", resumable=False)
        with patch("manager.gemini.adapter._gemini_jsonl_candidates", return_value=[other]):
            sm._build_argv("x")
        assert other.exists()

    def test_resumable_file_is_kept_and_resumed(self, tmp_path):
        sm = _mgr()
        f = _session_file(tmp_path, sm.sdk_session_id, resumable=True, snapshot=True)
        with patch("manager.gemini.adapter._gemini_jsonl_candidates", return_value=[f]):
            argv = sm._build_argv("x")
        assert argv[argv.index("--resume") + 1] == sm.sdk_session_id
        assert f.exists()


# ---------------------------------------------------------------------------
# Errors are surfaced, never an empty turn
# ---------------------------------------------------------------------------


async def _run_turn(proc, sm: GeminiSessionManager | None = None, prompt: str = "hi"):
    sm = sm or GeminiSessionManager(local_id="local")
    await sm.start()
    spawn = AsyncMock(return_value=proc)
    with patch("manager.gemini.session.asyncio.create_subprocess_exec", spawn):
        events = [ev async for ev in sm.send(prompt)]
    await sm.stop()
    return events, spawn


class TestErrorSurfacing:
    @pytest.mark.asyncio
    async def test_oauth_shutdown_error_explains_api_key(self):
        proc = _make_fake_proc([], stderr_lines=[
            b"Error authenticating: IneligibleTierError: This client is no longer supported "
            b"for Gemini Code Assist for individuals.\n",
            b"  ineligibleTiers: [{ reasonCode: 'UNSUPPORTED_CLIENT' }]\n",
        ], returncode=1)
        events, _ = await _run_turn(proc)
        assert len(events) == 1
        tc = events[0]
        assert isinstance(tc, TurnComplete) and tc.is_error
        assert tc.result.startswith(AUTH_HELP)
        assert "IneligibleTierError" in tc.result

    @pytest.mark.asyncio
    async def test_nonzero_exit_without_result_carries_stderr(self):
        proc = _make_fake_proc([_init_event()], stderr_lines=[
            b"YOLO mode is enabled.\n", b"Error: model not found: gemini-9\n",
        ], returncode=1)
        events, _ = await _run_turn(proc)
        tc = events[-1]
        assert isinstance(tc, TurnComplete) and tc.is_error
        assert "status 1" in tc.result
        assert "model not found" in tc.result
        assert "YOLO" not in tc.result

    @pytest.mark.asyncio
    async def test_error_event_message_wins_over_stderr(self):
        proc = _make_fake_proc([
            _init_event(),
            {"type": "error", "severity": "error", "message": "Quota exceeded"},
        ], stderr_lines=[b"some trace\n"], returncode=1)
        events, _ = await _run_turn(proc)
        assert events[-1].is_error and events[-1].result == "Quota exceeded"

    @pytest.mark.asyncio
    async def test_warning_event_is_not_an_error(self):
        proc = _make_fake_proc([
            _init_event(),
            {"type": "error", "severity": "warning", "message": "Loop detected"},
            _assistant_delta_event("ok"),
            _result_event(),
        ])
        events, _ = await _run_turn(proc)
        turns = [e for e in events if isinstance(e, TurnComplete)]
        assert len(turns) == 1 and not turns[0].is_error

    @pytest.mark.asyncio
    async def test_result_status_error_is_an_error_turn(self):
        ev = _result_event("error")
        ev["error"] = {"type": "FatalError", "message": "Something broke"}
        proc = _make_fake_proc([_init_event(), ev], returncode=1)
        events, _ = await _run_turn(proc)
        turns = [e for e in events if isinstance(e, TurnComplete)]
        assert len(turns) == 1
        assert turns[0].is_error and turns[0].result == "Something broke"

    @pytest.mark.asyncio
    async def test_clean_exit_without_result_still_completes_the_turn(self):
        proc = _make_fake_proc([_init_event(), _assistant_delta_event("hi")], returncode=0)
        events, _ = await _run_turn(proc)
        assert isinstance(events[-1], TurnComplete) and not events[-1].is_error

    @pytest.mark.asyncio
    async def test_missing_api_key_fails_fast_without_spawning(self, monkeypatch):
        monkeypatch.delenv("GEMINI_API_KEY", raising=False)
        events, spawn = await _run_turn(_make_fake_proc([]))
        spawn.assert_not_called()
        assert len(events) == 1 and events[0].is_error and events[0].result == AUTH_HELP

    @pytest.mark.asyncio
    async def test_explicit_auth_type_runs_without_api_key(self, monkeypatch):
        monkeypatch.delenv("GEMINI_API_KEY", raising=False)
        monkeypatch.setenv("ARCHIE_GEMINI_AUTH_TYPE", "vertex-ai")
        events, spawn = await _run_turn(_make_fake_proc([_init_event(), _result_event()]))
        spawn.assert_called_once()
        assert not events[-1].is_error

    @pytest.mark.asyncio
    async def test_unsafe_workspace_settings_refuse_the_turn(self):
        from manager.gemini import workspace_settings as wsm

        with patch(
            "manager.gemini.workspace_settings.ensure_workspace_settings",
            side_effect=wsm.WorkspaceSettingsError("cannot parse x"),
        ):
            events, spawn = await _run_turn(_make_fake_proc([]))
        spawn.assert_not_called()
        assert events[0].is_error and "context/chats" in events[0].result

    @pytest.mark.asyncio
    async def test_spawn_gets_its_own_process_group(self):
        _, spawn = await _run_turn(_make_fake_proc([_init_event(), _result_event()]))
        assert spawn.call_args.kwargs["start_new_session"] is True
        assert spawn.call_args.kwargs["env"]["GEMINI_CLI_NO_RELAUNCH"] == "true"


# ---------------------------------------------------------------------------
# Interrupt reaches the whole process group
# ---------------------------------------------------------------------------


class TestInterrupt:
    @pytest.mark.asyncio
    async def test_interrupt_on_mock_uses_send_signal(self):
        import signal as _signal

        sm = GeminiSessionManager(local_id="local")
        proc = _make_fake_proc([])
        sm._proc = proc
        await sm.interrupt()
        proc.send_signal.assert_called_once_with(_signal.SIGINT)
        assert sm.status == SessionStatus.INTERRUPTED

    @pytest.mark.asyncio
    async def test_interrupt_kills_process_group_including_children(self):
        """A real group: SIGINT must stop the leader and the shell command it runs."""
        import os as _os

        import sys as _sys

        # A foreground child (a non-interactive sh would make `cmd &`
        # ignore SIGINT), like the shell tool's command under the CLI.
        proc = await asyncio.create_subprocess_exec(
            _sys.executable, "-c",
            "import subprocess, time; p = subprocess.Popen(['sleep', '30']); "
            "print(p.pid, flush=True); time.sleep(30)",
            stdout=asyncio.subprocess.PIPE, start_new_session=True,
        )
        child = int((await proc.stdout.readline()).decode().strip())
        sm = GeminiSessionManager(local_id="local")
        sm._proc = proc
        await sm.interrupt()
        await asyncio.wait_for(proc.wait(), timeout=5)
        for _ in range(50):
            try:
                _os.kill(child, 0)
            except ProcessLookupError:
                break
            await asyncio.sleep(0.05)
        else:
            _os.kill(child, 9)
            pytest.fail("child of the gemini process survived the interrupt")


    @pytest.mark.asyncio
    async def test_interrupt_reaps_detached_shell_command(self):
        """The CLI's shell tool spawns commands detached (own process group)
        and leaves them running when it exits on SIGINT; they are reaped."""
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
        sm = GeminiSessionManager(local_id="local")
        sm._proc = proc
        await sm.interrupt()
        await asyncio.wait_for(proc.wait(), timeout=5)
        await asyncio.wait_for(asyncio.gather(*sm._reaper_tasks), timeout=10)
        try:
            _os.kill(child, 0)
        except ProcessLookupError:
            return
        # zombie? reaped by init shortly; check state
        with open(f"/proc/{child}/stat") as f:
            state = f.read().rsplit(")", 1)[1].split()[0]
        if state != "Z":
            _os.kill(child, 9)
            pytest.fail("detached shell command survived the interrupt")

    @pytest.mark.asyncio
    async def test_kill_proc_reaps_detached_descendants(self):
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
        sm = GeminiSessionManager(local_id="local")
        sm._proc = proc
        await sm._kill_proc()
        await asyncio.sleep(1.2)
        try:
            with open(f"/proc/{child}/stat") as f:
                state = f.read().rsplit(")", 1)[1].split()[0]
        except FileNotFoundError:
            return
        if state != "Z":
            _os.kill(child, 9)
            pytest.fail("detached descendant survived _kill_proc")


# ---------------------------------------------------------------------------
# Thinking: tailed from the session JSONL
# ---------------------------------------------------------------------------


class _AppendingStream(_FakeStream):
    """stdout that appends *records* to *path* just before returning line *at*."""

    def __init__(self, lines, path, at: int, records: list[dict]):
        super().__init__(lines)
        self._n = 0
        self._path, self._at, self._records = path, at, records

    async def readline(self) -> bytes:
        if self._n == self._at:
            with open(self._path, "a") as f:
                for r in self._records:
                    f.write(json.dumps(r) + "\n")
        self._n += 1
        return await super().readline()


class TestThinkingTail:
    def test_tail_reports_only_new_thoughts(self, tmp_path):
        from manager.gemini.session import _ThoughtTail

        sid = "abcdef12-0000-0000-0000-000000000000"
        f = _session_file(tmp_path, sid, resumable=False)
        with open(f, "a") as fh:
            fh.write(json.dumps({"id": "old", "type": "gemini", "content": "x",
                                 "thoughts": [{"subject": "Old", "description": "seen"}]}) + "\n")
        tail = _ThoughtTail(sid)
        with patch("manager.gemini.adapter._gemini_jsonl_candidates", return_value=[f]):
            tail.prime()
            with open(f, "a") as fh:
                # resume snapshot restating the old message: nothing new
                fh.write(json.dumps({"$set": {"messages": [{"id": "old", "type": "gemini",
                         "thoughts": [{"subject": "Old", "description": "seen"}]}]}}) + "\n")
                fh.write(json.dumps({"id": "g2", "type": "gemini", "content": "",
                                     "thoughts": [{"subject": "Plan", "description": "a"}]}) + "\n")
                # upsert of g2 with one more thought
                fh.write(json.dumps({"id": "g2", "type": "gemini", "content": "",
                                     "thoughts": [{"subject": "Plan", "description": "a"},
                                                  {"subject": "Then", "description": "b"}]}) + "\n")
                fh.write('{"id": "g3", "type": "gem')  # partial line
            assert tail.poll() == ["Plan\na", "Then\nb"]
            with open(f, "a") as fh:
                fh.write('ini", "thoughts": [{"subject": "Last", "description": "c"}]}\n')
            assert tail.poll() == ["Last\nc"]
            assert tail.poll() == []

    @pytest.mark.asyncio
    async def test_send_emits_thinking_from_the_session_file(self, tmp_path):
        sm = GeminiSessionManager(local_id="local")
        await sm.start()
        sid = sm.sdk_session_id
        f = _session_file(tmp_path, sid, resumable=True)  # a previous turn
        lines = [json.dumps(e).encode() + b"\n" for e in (
            _init_event(sid),
            _tool_use_event("run_shell_command", "t1", {"command": "ls"}),
            _tool_result_event("t1", "a b"),
            _assistant_delta_event("Done"),
            _result_event(),
        )]
        proc = _make_fake_proc([])
        proc.stdout = _AppendingStream(lines, f, at=2, records=[{
            "id": "g1", "type": "gemini", "content": "",
            "thoughts": [{"subject": "Listing", "description": "run ls"}],
            "toolCalls": [{"id": "t1", "name": "run_shell_command", "args": {}}],
        }])
        with patch("manager.gemini.adapter._gemini_jsonl_candidates", return_value=[f]), \
             patch("manager.gemini.session.asyncio.create_subprocess_exec",
                   AsyncMock(return_value=proc)):
            events = [ev async for ev in sm.send("list")]
        await sm.stop()
        kinds = [type(e).__name__ for e in events]
        assert kinds == [
            "ToolUse", "ThinkingDelta", "ThinkingComplete", "ToolResult",
            "TextDelta", "TextComplete", "TurnComplete",
        ]
        thinking = [e for e in events if isinstance(e, ThinkingComplete)]
        assert thinking[0].text == "Listing\nrun ls"
        assert [e.text for e in events if isinstance(e, ThinkingDelta)] == ["Listing\nrun ls"]
