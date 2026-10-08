"""ModelStudioSessionManager — Claude Code against Alibaba Model Studio.

Same bundled Claude Code CLI, same SDK wiring, same features as
:class:`manager.claude.session.ClaudeSessionManager` (permission gating,
compaction, MCP, slash commands, skills, SSH) — only the subprocess env
differs, so the CLI talks to Model Studio's Anthropic-compatible endpoint
with ``DASHSCOPE_API_KEY`` and runs DashScope models (GLM, DeepSeek, Kimi,
Qwen).

Env contract (local and over SSH):

* ``ANTHROPIC_BASE_URL`` = the Model Studio Anthropic endpoint and
  ``ANTHROPIC_AUTH_TOKEN`` = ``DASHSCOPE_API_KEY``.
* The Anthropic credentials (``CLAUDE_CODE_OAUTH_TOKEN``,
  ``ANTHROPIC_API_KEY``) are blanked in the child env, locally and on the
  SSH remote, so they can never reach a third party.  (Blanked, not
  removed: the SDK merges ``os.environ`` under ``options.env``.)
* Every model slot the CLI may use — main, opus/sonnet/haiku/fable
  aliases, small-fast (background calls), subagents — is mapped to the
  chosen DashScope model, so nothing asks DashScope for a ``claude-*`` id.
* ``CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1`` — no telemetry, error
  reporting or update checks.
* ``CLAUDE_CODE_MAX_CONTEXT_TOKENS`` = the model's window when known (the
  CLI assumes 200K for unknown models, which skews auto-compact).
* ``CLAUDE_CODE_EXTRA_BODY`` carries the ``thinking`` option.

Cost: the CLI prices every token at Anthropic rates (``costBasis:
unknown``), so ``TurnComplete.cost`` is reported as ``None``.
"""

from __future__ import annotations

import dataclasses
import json
import logging
import os
from collections.abc import AsyncIterator

from ..claude.session import ClaudeSessionManager
from ..config import ManagerConfig
from ..types import Event, TurnComplete
from .catalog import (
    ANTHROPIC_BASE_URL,
    API_KEY_ENV,
    DEFAULT_MODEL,
    DEFAULT_THINKING_BUDGET,
    PROVIDER,
    THINKING,
    THINKING_BUDGET,
    THINKING_BUDGET_MAX,
    is_claude_model,
    model_context_window,
    model_thinks,
)

logger = logging.getLogger(__name__)

# Optional override of the endpoint (e.g. the Beijing region or the Coding
# Plan endpoint); the key stays DASHSCOPE_API_KEY.
BASE_URL_ENV = "MODELSTUDIO_ANTHROPIC_BASE_URL"

# Anthropic credentials / provider switches that must never reach the child.
_DROPPED_ENV: tuple[str, ...] = (
    "CLAUDE_CODE_OAUTH_TOKEN",
    "ANTHROPIC_API_KEY",
    "ANTHROPIC_CUSTOM_HEADERS",
    "CLAUDE_CODE_USE_BEDROCK",
    "CLAUDE_CODE_USE_VERTEX",
    "CLAUDE_CODE_USE_FOUNDRY",
)

# Model slots the CLI resolves on its own (aliases, background, subagents).
_MODEL_ENV: tuple[str, ...] = (
    "ANTHROPIC_MODEL",
    "ANTHROPIC_DEFAULT_OPUS_MODEL",
    "ANTHROPIC_DEFAULT_SONNET_MODEL",
    "ANTHROPIC_DEFAULT_HAIKU_MODEL",
    "ANTHROPIC_DEFAULT_FABLE_MODEL",
    "ANTHROPIC_SMALL_FAST_MODEL",
    "CLAUDE_CODE_SUBAGENT_MODEL",
)


def resolve_model(model: str | None) -> str:
    """The DashScope model this session runs (never a Claude id/alias)."""
    if not model or is_claude_model(model):
        if model:
            logger.warning(
                "model %r is a Claude model; Model Studio sessions use %s instead",
                model, DEFAULT_MODEL,
            )
        return DEFAULT_MODEL
    return model.strip()


class ModelStudioSessionManager(ClaudeSessionManager):
    """A Claude Code session whose CLI talks to Alibaba Model Studio."""

    _ssh_prefix = "modelstudio"

    def __init__(
        self,
        session_id: str | None = None,
        *,
        local_id: str | None = None,
        fork: bool = False,
        config: ManagerConfig | None = None,
    ) -> None:
        super().__init__(session_id=session_id, local_id=local_id, fork=fork, config=config)
        # Pin a concrete DashScope model so the CLI never falls back to its
        # own default (a Claude model this endpoint does not serve).
        self._config = dataclasses.replace(
            self._config, model=resolve_model(self._config.model),
        )

    @property
    def provider_name(self) -> str:
        return PROVIDER

    # ── start ──────────────────────────────────────────────────────────

    async def _pre_start_check(self) -> None:
        if not (os.environ.get(API_KEY_ENV) or "").strip():
            raise RuntimeError(
                f"{API_KEY_ENV} is not set — the Model Studio harness needs a "
                "DashScope API key in context/.env",
            )
        await super()._pre_start_check()

    # ── env / options ──────────────────────────────────────────────────

    def _thinking(self) -> bool | None:
        """The ``thinking`` option for this model (None = unset / n/a)."""
        value = (self._config.harness_options or {}).get(THINKING)
        if not isinstance(value, bool):
            return None
        if model_thinks(self._config.model) is False:
            return None  # e.g. qwen3-coder: no thinking mode (HarnessOption.models)
        return value

    def _harness_env(self) -> dict[str, str]:
        env = super()._harness_env()  # todo tools + MCP startup wait
        model = self._config.model or DEFAULT_MODEL
        env["ANTHROPIC_BASE_URL"] = (os.environ.get(BASE_URL_ENV) or "").strip() or ANTHROPIC_BASE_URL
        env["ANTHROPIC_AUTH_TOKEN"] = (os.environ.get(API_KEY_ENV) or "").strip()
        for key in _MODEL_ENV:
            env[key] = model
        env["CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC"] = "1"
        window = model_context_window(model)
        if window:
            env["CLAUDE_CODE_MAX_CONTEXT_TOKENS"] = str(window)
        thinking = self._thinking()
        if thinking is True:
            budget = (self._config.harness_options or {}).get(THINKING_BUDGET)
            if not isinstance(budget, int) or isinstance(budget, bool) or budget <= 0:
                budget = DEFAULT_THINKING_BUDGET
            budget = min(budget, THINKING_BUDGET_MAX)
            env["CLAUDE_CODE_EXTRA_BODY"] = json.dumps(
                {"thinking": {"type": "enabled", "budget_tokens": budget}},
            )
        elif thinking is False:
            env["CLAUDE_CODE_EXTRA_BODY"] = json.dumps({"thinking": {"type": "disabled"}})
        return env

    def _harness_option_kwargs(self) -> dict:
        """Only ``thinking=off`` reaches the SDK options (``--thinking disabled``,
        so the CLI drops its own adaptive request); thinking on rides in
        ``CLAUDE_CODE_EXTRA_BODY``.  No effort, no Anthropic fallback model."""
        if self._thinking() is False:
            return {"thinking": {"type": "disabled"}}
        return {}

    def _build_options(self):
        options = super()._build_options()
        env = options.env
        if isinstance(env, dict):
            # The SDK spawns the CLI with ``{**os.environ, **options.env}``,
            # so popping a key here would NOT remove the inherited value —
            # blank it instead (the CLI treats an empty value as unset).
            for key in _DROPPED_ENV:
                env[key] = ""
            # A stray CLAUDE_CODE_EXTRA_BODY from the parent env must not
            # reach the endpoint either.
            if "CLAUDE_CODE_EXTRA_BODY" not in self._harness_env():
                env["CLAUDE_CODE_EXTRA_BODY"] = ""
        return options

    def _ssh_auth_env(self) -> dict[str, str]:
        # The remote shell may export Anthropic credentials of its own; an
        # inline ``KEY=''`` assignment blanks them for the child (the CLI
        # treats an empty value as unset).  ANTHROPIC_AUTH_TOKEN itself comes
        # from _harness_env().
        return {"CLAUDE_CODE_OAUTH_TOKEN": "", "ANTHROPIC_API_KEY": ""}

    def _record_cli_models(self, models: object) -> None:
        # The CLI's picker here lists DashScope models under Claude aliases;
        # it must not leak into the claude harness catalog.
        return None

    # ── events ─────────────────────────────────────────────────────────

    async def _process_message(self, msg: object) -> AsyncIterator[Event]:
        async for event in super()._process_message(msg):
            if isinstance(event, TurnComplete) and event.cost is not None:
                # Anthropic prices applied to DashScope tokens — meaningless.
                event = dataclasses.replace(event, cost=None)
            yield event
        self._cost = 0.0


def kill_modelstudio_subprocess(pid: int) -> bool:
    from ..claude.session import kill_claude_subprocess

    return kill_claude_subprocess(pid)


__all__ = ["ModelStudioSessionManager", "kill_modelstudio_subprocess", "resolve_model"]
