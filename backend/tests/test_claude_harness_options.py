"""Claude Code harness: catalog (models + options), harness_options → SDK
options/CLI flags, the SDK 0.2.x behaviour-preservation defaults (thinking
display, todo tools, MCP startup wait), empty-thinking filtering,
ConversationResetMessage handling and the Claude context-window table.
"""

from __future__ import annotations

import json
import logging
from unittest.mock import MagicMock, patch

import pytest

from manager.claude import catalog as cc
from manager.config import ManagerConfig
from manager.context_windows import context_window_for
from manager.harness_catalog import validate_options
from manager.types import ThinkingComplete, ThinkingDelta


@pytest.fixture(autouse=True)
def _clean_catalog_state():
    cc._reset_for_tests()
    yield
    cc._reset_for_tests()


@pytest.fixture
def no_mcp_env(monkeypatch: pytest.MonkeyPatch):
    monkeypatch.delenv("CLAUDE_CODE_MCP_STARTUP_WAIT_MS", raising=False)
    monkeypatch.delenv("CLAUDE_CODE_ENABLE_TODO_TOOLS", raising=False)


def _sm(model: str | None = None, opts: dict | None = None, **cfg):
    from manager.claude.session import ClaudeSessionManager

    return ClaudeSessionManager(config=ManagerConfig(model=model, harness_options=opts, **cfg))


def _argv(options) -> list[str]:
    from claude_agent_sdk._internal.transport.subprocess_cli import SubprocessCLITransport

    t = SubprocessCLITransport(prompt="", options=options)
    t._cli_path = "/bin/true"
    return t._build_command()


# ── live models payload (trimmed copy of a real GET /v1/models reply) ──


def _api_model(mid, name, efforts, types, ctx=1_000_000, image=True):
    return {
        "type": "model", "id": mid, "display_name": name,
        "max_input_tokens": ctx, "max_tokens": 128_000,
        "capabilities": {
            "effort": {"supported": bool(efforts), **{
                lvl: {"supported": lvl in efforts} for lvl in cc.EFFORT_LEVELS
            }},
            "thinking": {"supported": True, "types": {
                t: {"supported": t in types} for t in ("enabled", "adaptive", "disabled")
            }},
            "image_input": {"supported": image},
        },
    }


_LIVE = {"data": [
    _api_model("claude-sonnet-5-5", "Claude Sonnet 5.5", cc.EFFORT_LEVELS, ("adaptive",)),
    _api_model("claude-opus-5-5", "Claude Opus 5.5", cc.EFFORT_LEVELS, ("adaptive",)),
    _api_model("claude-haiku-5-5", "Claude Haiku 5.5", cc.EFFORT_LEVELS, ("adaptive", "disabled")),
    _api_model("claude-fable-5-1", "Claude Fable 5.1", cc.EFFORT_LEVELS, ("adaptive",)),
    _api_model("claude-sonnet-4-6", "Claude Sonnet 4.6", ("low", "medium", "high", "max"),
               ("enabled", "adaptive", "disabled")),
    _api_model("claude-haiku-4-5-20251001", "Claude Haiku 4.5", (), ("enabled", "disabled"), ctx=200_000),
    {"type": "model", "id": "not-a-claude-model"},
]}


class _Resp:
    def __init__(self, payload, status=200):
        self._payload, self.status_code = payload, status

    def raise_for_status(self):
        if self.status_code >= 400:
            import httpx

            req = httpx.Request("GET", cc.MODELS_URL)
            raise httpx.HTTPStatusError("boom", request=req, response=httpx.Response(self.status_code, request=req))

    def json(self):
        return self._payload


# ── catalog ────────────────────────────────────────────────────────────


class TestCatalog:
    def test_live_models_use_oauth_bearer_and_map_capabilities(self, monkeypatch):
        monkeypatch.setenv("CLAUDE_CODE_OAUTH_TOKEN", "tok-123")
        seen = {}

        def fake_get(url, headers, params, timeout):
            seen.update(url=url, headers=headers, timeout=timeout)
            return _Resp(_LIVE)

        with patch("httpx.get", fake_get):
            cat = cc.load_claude_catalog()

        assert seen["url"] == cc.MODELS_URL
        assert seen["headers"]["Authorization"] == "Bearer tok-123"
        assert seen["headers"]["anthropic-beta"] == "oauth-2025-04-20"
        assert "x-api-key" not in seen["headers"]
        assert seen["timeout"] <= 5
        assert cat.provider == "claude" and cat.warnings == ()

        rows = {m.id: m for m in cat.models}
        # aliases first, then the full ids; non-Claude ids dropped
        assert [m.id for m in cat.models][:5] == ["default", "sonnet", "opus", "fable", "haiku"]
        assert "not-a-claude-model" not in rows
        assert rows["claude-sonnet-5-5"].source == "live"
        assert rows["claude-sonnet-5-5"].context_window == 1_000_000
        assert rows["claude-sonnet-4-6"].efforts == ("low", "medium", "high", "max")
        assert rows["claude-haiku-4-5-20251001"].efforts == ()
        assert rows["claude-haiku-4-5-20251001"].context_window == 200_000
        # alias rows inherit the resolved model's caps
        assert rows["opus"].efforts == cc.EFFORT_LEVELS
        assert rows["haiku"].efforts == ()
        assert rows["opus"].source == "builtin"  # no CLI list recorded yet
        assert cat.default_model == "claude-sonnet-5-5"

    def test_api_key_used_when_no_oauth_token(self, monkeypatch):
        monkeypatch.delenv("CLAUDE_CODE_OAUTH_TOKEN", raising=False)
        monkeypatch.setenv("ANTHROPIC_API_KEY", "sk-x")
        seen = {}

        def fake_get(url, headers, params, timeout):
            seen.update(headers)
            return _Resp(_LIVE)

        with patch("httpx.get", fake_get):
            cc.load_claude_catalog()
        assert seen["x-api-key"] == "sk-x" and "Authorization" not in seen

    def test_http_failure_falls_back_to_builtin_with_warning(self, monkeypatch, caplog):
        monkeypatch.setenv("CLAUDE_CODE_OAUTH_TOKEN", "tok")
        with patch("httpx.get", return_value=_Resp({}, status=401)), caplog.at_level(logging.WARNING):
            cat = cc.load_claude_catalog()
        assert cat.warnings and "HTTP 401" in cat.warnings[0]
        rows = {m.id: m for m in cat.models}
        assert rows["claude-opus-5-5"].source == "builtin"
        assert len([m for m in cat.models if m.id.startswith("claude-")]) == len(cc._BUILTIN_MODELS)

    def test_network_error_falls_back(self, monkeypatch):
        monkeypatch.setenv("CLAUDE_CODE_OAUTH_TOKEN", "tok")
        import httpx

        with patch("httpx.get", side_effect=httpx.ConnectTimeout("slow")):
            cat = cc.load_claude_catalog()
        assert cat.warnings and "slow" in cat.warnings[0]
        assert any(m.id == "claude-sonnet-5-5" for m in cat.models)

    def test_no_credentials_falls_back_without_request(self, monkeypatch):
        monkeypatch.delenv("CLAUDE_CODE_OAUTH_TOKEN", raising=False)
        monkeypatch.delenv("ANTHROPIC_API_KEY", raising=False)
        with patch("httpx.get") as g:
            cat = cc.load_claude_catalog()
        g.assert_not_called()
        assert "CLAUDE_CODE_OAUTH_TOKEN" in cat.warnings[0]

    def test_options_shape_and_model_restrictions(self, monkeypatch):
        monkeypatch.delenv("CLAUDE_CODE_OAUTH_TOKEN", raising=False)
        monkeypatch.delenv("ANTHROPIC_API_KEY", raising=False)
        cat = cc.load_claude_catalog()
        keys = [o.key for o in cat.options]
        assert keys == ["effort", "thinking", "thinking_budget", "fallback_model", "todo_tools"]
        assert "permission_mode" not in keys  # gating relies on "default"

        effort = cat.option("effort")
        assert [c.value for c in effort.choices] == ["low", "medium", "high", "xhigh", "max"]

        thinking = cat.option("thinking")
        assert [c.value for c in thinking.choices] == ["adaptive", "enabled", "disabled"]
        # adaptive-only models (and the aliases resolving to them) never get the option
        for adaptive_only in ("claude-opus-5-5", "claude-sonnet-5-5", "claude-fable-5-1",
                              "claude-fable-5", "default", "sonnet", "opus", "fable"):
            assert adaptive_only not in thinking.models
        for switchable in ("claude-haiku-5-5", "claude-opus-5", "claude-sonnet-4-6",
                           "claude-opus-4-5-20251101", "haiku"):
            assert switchable in thinking.models

        budget = cat.option("thinking_budget")
        assert budget.kind == "number" and budget.min and budget.max
        assert set(budget.models) == {
            "claude-sonnet-4-6", "claude-opus-4-6", "claude-opus-4-5-20251101",
            "claude-haiku-4-5-20251001", "claude-sonnet-4-5-20250929", "haiku",
        }

        fallback = cat.option("fallback_model")
        assert {c.value for c in fallback.choices} == {m.id for m in cat.models}

        todo = cat.option("todo_tools")
        assert todo.kind == "toggle" and todo.default is True

        # the whole thing is JSON-serializable and validates its own values
        json.dumps(cat.to_dict())
        assert validate_options(cat, {"effort": "xhigh", "thinking": "enabled", "thinking_budget": 8192,
                                      "fallback_model": "sonnet", "todo_tools": False})
        with pytest.raises(ValueError):
            validate_options(cat, {"thinking": "sometimes"})
        with pytest.raises(ValueError):
            validate_options(cat, {"permission_mode": "bypassPermissions"})

    def test_cli_models_replace_builtin_aliases(self, monkeypatch):
        monkeypatch.delenv("CLAUDE_CODE_OAUTH_TOKEN", raising=False)
        monkeypatch.delenv("ANTHROPIC_API_KEY", raising=False)
        cc.record_cli_models([
            {"value": "default", "resolvedModel": "claude-opus-5-5", "displayName": "Default (recommended)",
             "description": "Opus 5.5", "supportedEffortLevels": ["low", "high"]},
            {"value": "haiku", "resolvedModel": "claude-haiku-5-5", "displayName": "Haiku"},
            "junk",
        ])
        cat = cc.load_claude_catalog()
        aliases = [m for m in cat.models if not m.id.startswith("claude-")]
        assert [m.id for m in aliases] == ["default", "haiku"]
        assert all(m.source == "cli" for m in aliases)
        assert aliases[0].efforts == ("low", "high")  # the CLI's word wins
        assert cat.default_model == "claude-opus-5-5"
        assert cc.claude_model_caps("haiku").id == "claude-haiku-5-5"

    def test_record_cli_models_ignores_garbage(self):
        cc.record_cli_models(None)
        cc.record_cli_models(MagicMock())
        cc.record_cli_models([{"nope": 1}])
        assert cc.claude_model_caps("opus").id == "claude-opus-5-5"

    def test_model_caps_lookup(self):
        assert cc.claude_model_caps(None).id == "claude-sonnet-5-5"
        assert cc.claude_model_caps("opus[1m]").id == "claude-opus-5-5"
        assert cc.claude_model_caps("claude-sonnet-5-5").adaptive_only
        assert cc.claude_model_caps("claude-opus-5-5-20261001").adaptive_only  # family rule
        assert not cc.claude_model_caps("claude-opus-5").adaptive_only
        assert cc.claude_model_caps("claude-sonnet-4-5-20250929").efforts == ()
        assert cc.claude_model_caps("gpt-5") is None

    def test_registered_as_catalog_loader(self):
        from manager.registry import ensure_all_registered, get_registry

        ensure_all_registered()
        spec = get_registry().get("claude")
        assert spec is not None and spec.catalog_loader is not None
        with patch.object(cc, "_fetch_live_models", return_value=(None, "offline")):
            cat = spec.catalog_loader()
        assert cat.provider == "claude" and "offline" in cat.warnings[0]


# ── harness_options → SDK options / argv ───────────────────────────────


class TestOptionsMapping:
    def test_no_options_only_adds_the_deliberate_defaults(self, no_mcp_env):
        sm = _sm()
        o = sm._build_options()
        assert o.effort is None and o.thinking is None and o.fallback_model is None
        assert o.permission_mode == "default"
        assert o.extra_args == {"thinking-display": "summarized"}
        assert o.env["CLAUDE_CODE_ENABLE_TODO_TOOLS"] == "1"
        assert o.env["CLAUDE_CODE_MCP_STARTUP_WAIT_MS"] == "2000"
        argv = _argv(o)
        assert argv.count("--thinking-display") == 1
        assert "--effort" not in argv and "--thinking" not in argv
        assert "--max-thinking-tokens" not in argv and "--fallback-model" not in argv
        assert "--permission-mode" in argv and argv[argv.index("--permission-mode") + 1] == "default"
        assert "--setting-sources=project,local" in argv

    def test_chrome_flag_preserved_and_config_not_mutated(self, no_mcp_env):
        extra = {"chrome": None}
        sm = _sm(extra_args=extra)
        o = sm._build_options()
        assert o.extra_args == {"chrome": None, "thinking-display": "summarized"}
        assert extra == {"chrome": None}
        argv = _argv(o)
        assert "--chrome" in argv and argv.count("--thinking-display") == 1

    def test_existing_mcp_wait_env_is_respected(self, monkeypatch):
        monkeypatch.setenv("CLAUDE_CODE_MCP_STARTUP_WAIT_MS", "0")
        assert _sm()._build_options().env["CLAUDE_CODE_MCP_STARTUP_WAIT_MS"] == "0"

    @pytest.mark.parametrize("level", cc.EFFORT_LEVELS)
    def test_effort_every_level(self, level, no_mcp_env):
        o = _sm("claude-opus-5-5", {"effort": level})._build_options()
        assert o.effort == level
        argv = _argv(o)
        assert argv[argv.index("--effort") + 1] == level

    def test_effort_lowered_to_nearest_supported(self):
        assert _sm("claude-sonnet-4-6", {"effort": "xhigh"})._build_options().effort == "high"
        assert _sm("claude-sonnet-4-6", {"effort": "max"})._build_options().effort == "max"
        assert _sm("claude-opus-4-5-20251101", {"effort": "max"})._build_options().effort == "high"

    def test_effort_dropped_for_models_without_effort(self):
        assert _sm("haiku", {"effort": "high"})._build_options().effort is None
        assert _sm("claude-sonnet-4-5-20250929", {"effort": "low"})._build_options().effort is None

    def test_effort_passed_through_for_unknown_model(self):
        assert _sm("claude-mystery-9", {"effort": "xhigh"})._build_options().effort == "xhigh"

    def test_thinking_adaptive(self, no_mcp_env):
        o = _sm("claude-haiku-5-5", {"thinking": "adaptive"})._build_options()
        assert o.thinking == {"type": "adaptive", "display": "summarized"}
        assert "thinking-display" not in (o.extra_args or {})
        argv = _argv(o)
        assert argv[argv.index("--thinking") + 1] == "adaptive"
        assert argv.count("--thinking-display") == 1

    def test_thinking_enabled_with_budget(self):
        o = _sm("claude-sonnet-4-6", {"thinking": "enabled", "thinking_budget": 8192})._build_options()
        assert o.thinking == {"type": "enabled", "budget_tokens": 8192, "display": "summarized"}
        argv = _argv(o)
        assert argv[argv.index("--max-thinking-tokens") + 1] == "8192"
        assert argv.count("--thinking-display") == 1

    def test_thinking_enabled_default_budget(self):
        o = _sm("claude-opus-4-5-20251101", {"thinking": "enabled"})._build_options()
        assert o.thinking["budget_tokens"] == cc.DEFAULT_THINKING_BUDGET

    def test_thinking_disabled(self):
        o = _sm("claude-haiku-4-5-20251001", {"thinking": "disabled"})._build_options()
        assert o.thinking == {"type": "disabled"}
        assert "thinking-display" not in (o.extra_args or {})
        argv = _argv(o)
        assert argv[argv.index("--thinking") + 1] == "disabled"
        assert "--thinking-display" not in argv

    def test_budget_ignored_without_enabled(self):
        o = _sm("claude-sonnet-4-6", {"thinking": "adaptive", "thinking_budget": 4096})._build_options()
        assert o.thinking == {"type": "adaptive", "display": "summarized"}
        o = _sm("claude-sonnet-4-6", {"thinking_budget": 4096})._build_options()
        assert o.thinking is None and o.extra_args["thinking-display"] == "summarized"

    @pytest.mark.parametrize("model", [None, "default", "opus", "claude-opus-5-5", "claude-fable-5-1"])
    @pytest.mark.parametrize("mode", ["adaptive", "enabled", "disabled"])
    def test_thinking_ignored_on_adaptive_only_models(self, model, mode):
        o = _sm(model, {"thinking": mode})._build_options()
        assert o.thinking is None
        assert o.extra_args["thinking-display"] == "summarized"

    def test_thinking_mode_mapped_to_closest_supported(self):
        # 5.x without a budget mode: enabled → adaptive
        o = _sm("claude-opus-5", {"thinking": "enabled"})._build_options()
        assert o.thinking == {"type": "adaptive", "display": "summarized"}
        # 4.5 without adaptive: adaptive → enabled (default budget)
        o = _sm("claude-haiku-4-5-20251001", {"thinking": "adaptive"})._build_options()
        assert o.thinking["type"] == "enabled"

    def test_fallback_model(self):
        o = _sm("opus", {"fallback_model": "sonnet"})._build_options()
        assert o.fallback_model == "sonnet"
        argv = _argv(o)
        assert argv[argv.index("--fallback-model") + 1] == "sonnet"
        # the CLI refuses fallback == main model
        assert _sm("sonnet", {"fallback_model": "sonnet"})._build_options().fallback_model is None

    def test_todo_tools_off_removes_env(self, monkeypatch):
        monkeypatch.setenv("CLAUDE_CODE_ENABLE_TODO_TOOLS", "1")  # even if inherited
        o = _sm(None, {"todo_tools": False})._build_options()
        assert "CLAUDE_CODE_ENABLE_TODO_TOOLS" not in o.env
        assert _sm(None, {"todo_tools": True})._build_options().env["CLAUDE_CODE_ENABLE_TODO_TOOLS"] == "1"

    def test_unknown_keys_ignored(self, no_mcp_env):
        o = _sm(None, {"bogus": 1, "permission_mode": "bypassPermissions"})._build_options()
        assert o.permission_mode == "default"
        assert o.effort is None and o.thinking is None

    def test_ssh_wrapper_forwards_env_and_flags_survive(self, tmp_path, monkeypatch, no_mcp_env):
        """Env knobs ride the remote command; the SDK argv (with every option
        flag) round-trips byte-identical through the wrapper."""
        import os
        import subprocess

        from manager import _ssh

        monkeypatch.setattr("manager.claude.session.resolve_remote_cli_path", lambda *a, **k: "/remote/claude")
        monkeypatch.setattr("manager.claude.session.build_ssh_argv", lambda target: ["sh", "-c"])
        captured = {}
        real_write = _ssh.write_ssh_wrapper_script

        def spy(**kw):
            captured["remote_cmd"] = kw["remote_cmd"]
            return real_write(**kw)

        monkeypatch.setattr("manager.claude.session.write_ssh_wrapper_script", spy)
        sm = _sm("claude-sonnet-4-6", {"effort": "high", "thinking": "enabled", "thinking_budget": 2048,
                                      "fallback_model": "haiku"},
                 ssh_host="remote", ssh_claude_config_dir="/r/.claude_config", project_dir=str(tmp_path),
                 extra_args={"chrome": None})
        o = sm._build_options()
        try:
            assert "CLAUDE_CODE_ENABLE_TODO_TOOLS='1'" in captured["remote_cmd"]
            assert "CLAUDE_CODE_MCP_STARTUP_WAIT_MS='2000'" in captured["remote_cmd"]
            assert "CLAUDE_CONFIG_DIR='/r/.claude_config'" in captured["remote_cmd"]

            argv = _argv(o)[1:]
            for flag in ("--effort", "--max-thinking-tokens", "--thinking-display", "--fallback-model", "--chrome"):
                assert flag in argv
            # Run the real wrapper with the remote CLI swapped for an argv dumper.
            dumper = tmp_path / "dump.py"
            dumper.write_text("#!/usr/bin/env python3\nimport sys, json\nprint(json.dumps(sys.argv[1:]))\n")
            dumper.chmod(0o755)
            script = open(o.cli_path).read().replace("/remote/claude", str(dumper))
            wrapper = tmp_path / "wrapper.sh"
            wrapper.write_text(script)
            wrapper.chmod(0o755)
            out = subprocess.run([str(wrapper), *argv], capture_output=True, text=True, check=True,
                                 env={**os.environ})
            assert json.loads(out.stdout) == argv
        finally:
            _ssh.cleanup_ssh_wrapper_script(o.cli_path)


# ── message processing ────────────────────────────────────────────────


async def _collect(sm, msg):
    return [e async for e in sm._process_message(msg)]


class TestMessageProcessing:
    @pytest.mark.asyncio
    async def test_empty_thinking_delta_skipped(self):
        from claude_agent_sdk.types import StreamEvent

        sm = _sm()

        def ev(text):
            return StreamEvent(uuid="u", session_id="s", event={
                "type": "content_block_delta", "delta": {"type": "thinking_delta", "thinking": text},
            })

        assert await _collect(sm, ev("")) == []
        out = await _collect(sm, ev("hmm"))
        assert len(out) == 1 and isinstance(out[0], ThinkingDelta) and out[0].text == "hmm"

    @pytest.mark.asyncio
    async def test_empty_thinking_block_skipped(self):
        from claude_agent_sdk import AssistantMessage, ThinkingBlock

        sm = _sm()
        empty = AssistantMessage(content=[ThinkingBlock(thinking="", signature="x")], model="m")
        full = AssistantMessage(content=[ThinkingBlock(thinking="deep", signature="x")], model="m")
        assert await _collect(sm, empty) == []
        out = await _collect(sm, full)
        assert len(out) == 1 and isinstance(out[0], ThinkingComplete)

    @pytest.mark.asyncio
    async def test_conversation_reset_adopts_next_session_id(self):
        from claude_agent_sdk import ConversationResetMessage, SystemMessage
        from claude_agent_sdk.types import StreamEvent

        sm = _sm()
        sm._provider_session_id = "old"
        out = await _collect(sm, ConversationResetMessage(new_conversation_id="conv", uuid="u", session_id="old"))
        assert out == [] and sm._provider_session_id == "old"
        # a message still carrying the old id does not end the wait
        await _collect(sm, StreamEvent(uuid="u", session_id="old", event={"type": "message_start"}))
        assert sm._provider_session_id == "old"
        await _collect(sm, SystemMessage(subtype="init", data={"session_id": "new"}))
        assert sm._provider_session_id == "new"
        # later messages don't keep re-keying
        await _collect(sm, StreamEvent(uuid="u", session_id="other", event={"type": "message_start"}))
        assert sm._provider_session_id == "new"


# ── context windows ───────────────────────────────────────────────────


@pytest.mark.parametrize("model,window", [
    ("claude-opus-5-5", 1_000_000),
    ("claude-sonnet-5-5", 1_000_000),
    ("claude-haiku-5-5", 1_000_000),
    ("claude-fable-5-1", 1_000_000),
    ("claude-opus-4-8", 1_000_000),
    ("claude-opus-4-6", 1_000_000),
    ("claude-sonnet-4-6", 1_000_000),
    ("claude-opus-4-5-20251101", 200_000),
    ("claude-sonnet-4-5-20250929", 200_000),
    ("claude-sonnet-4-5[1m]", 1_000_000),
    ("claude-haiku-4-5-20251001", 200_000),
    ("opus", 1_000_000),
    ("sonnet", 1_000_000),
    ("fable", 1_000_000),
    ("haiku", 200_000),
    (None, 1_000_000),
])
def test_claude_context_windows(model, window):
    assert context_window_for("claude", model) == window
