"""Per-turn model routing for typed vs audio turns (2026-10-04).

OpenAI's gpt-audio chat models refuse text-only requests ("This model requires
that either input content or output modality contain audio") and text models
refuse input_audio blocks, so no single model takes both. These tests pin:

1. A typed turn on an audio-only model runs on ``TEXT_FALLBACK_MODEL_ID``; the
   configured model is unchanged afterwards.
2. An audio clip runs on the Settings audio model (``default_audio_model``,
   fallback ``AUDIO_FALLBACK_MODEL_ID``) for that turn only (it used to switch
   the session's model for good, which broke every later typed turn).
3. A clip on a session already on the Settings audio model uses its own provider.
4. After an audio turn the clip is replaced in history by a text marker, so a
   later text-model turn never receives an input_audio block.
5. ``default_audio_model`` is read from assistant_config.json and must take audio;
   the config API refuses a text model for it.
6. The OpenAI provider sends ``max_completion_tokens`` and retries once with
   ``reasoning_effort: "none"`` when a model refuses tools with reasoning.
"""

from __future__ import annotations

import asyncio
import base64
import json
from pathlib import Path
from typing import Any
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from orchestrator.config import (
    AUDIO_FALLBACK_MODEL_ID,
    TEXT_FALLBACK_MODEL_ID,
    OrchestratorConfig,
    model_requires_audio,
)
from orchestrator.session import OrchestratorSession

CLIP = base64.b64encode(b"RIFF....WAVEfmt fake").decode()


class FakeProvider:
    def __init__(self, model: str, max_tokens: int = 0) -> None:
        self.model = model


class FakeAgent:
    """Records which provider served each turn and keeps history like OrchestratorAgent."""

    def __init__(self, provider: Any) -> None:
        self._provider = provider
        self.history: list[Any] = []
        self.served_by: list[str] = []

    async def run(self, prompt):
        self.history.append({"role": "user", "content": prompt} if isinstance(prompt, str) else prompt)
        self.served_by.append(self._provider.model)
        return
        yield  # pragma: no cover - makes this an async generator


def _session(model: str) -> tuple[OrchestratorSession, FakeAgent]:
    config = OrchestratorConfig()
    assert config.set_model(model)
    s = OrchestratorSession(config=config, context={"pool": MagicMock(), "store": MagicMock()}, local_id="t-models")
    s._writer = MagicMock()
    agent = FakeAgent(FakeProvider(model))
    s._agent = agent
    return s, agent


async def _drain(gen) -> None:
    async for _ in gen:
        pass


def test_audio_only_models_are_detected() -> None:
    assert model_requires_audio("gpt-audio")
    assert model_requires_audio("gpt-audio-mini")
    assert model_requires_audio("gpt-4o-audio-preview")
    assert not model_requires_audio("gpt-4o")
    assert not model_requires_audio("claude-sonnet-4-5-20250929")


@pytest.mark.asyncio
async def test_typed_turn_on_audio_model_uses_text_fallback_for_that_turn() -> None:
    s, agent = _session("gpt-audio-mini")
    with patch("orchestrator.providers.openai_text.OpenAITextProvider", FakeProvider):
        await _drain(s.send("hello"))
    assert agent.served_by == [TEXT_FALLBACK_MODEL_ID]
    assert s._config.model == "gpt-audio-mini"
    assert agent._provider.model == "gpt-audio-mini"  # own provider restored


@pytest.mark.asyncio
async def test_typed_turn_on_text_model_uses_own_provider() -> None:
    s, agent = _session("gpt-4o")
    await _drain(s.send("hello"))
    assert agent.served_by == ["gpt-4o"]


@pytest.mark.asyncio
async def test_clip_on_text_model_uses_audio_fallback_once_then_text_again() -> None:
    s, agent = _session("gpt-4o")
    with patch("orchestrator.providers.openai_text.OpenAITextProvider", FakeProvider), \
            patch("orchestrator.session.resolve_audio_model", lambda: AUDIO_FALLBACK_MODEL_ID), \
            patch("orchestrator.session.convert_audio_to_wav", lambda d, f: (d, "wav")):
        await _drain(s._send_audio_inner(CLIP, "wav", "what did I say?"))
        await _drain(s.send("and now typed"))
    assert agent.served_by == [AUDIO_FALLBACK_MODEL_ID, "gpt-4o"]
    assert s._config.model == "gpt-4o"


@pytest.mark.asyncio
async def test_clip_uses_the_settings_audio_model() -> None:
    s, agent = _session("gpt-4o")
    with patch("orchestrator.providers.openai_text.OpenAITextProvider", FakeProvider), \
            patch("orchestrator.session.resolve_audio_model", lambda: "gpt-audio-mini"), \
            patch("orchestrator.session.convert_audio_to_wav", lambda d, f: (d, "wav")):
        await _drain(s._send_audio_inner(CLIP, "wav", None))
    assert agent.served_by == ["gpt-audio-mini"]
    assert s._config.model == "gpt-4o"


@pytest.mark.asyncio
async def test_clip_on_a_session_already_on_the_audio_model_uses_own_provider() -> None:
    s, agent = _session("gpt-audio-mini")
    with patch("orchestrator.session.resolve_audio_model", lambda: "gpt-audio-mini"), \
            patch("orchestrator.session.convert_audio_to_wav", lambda d, f: (d, "wav")):
        await _drain(s._send_audio_inner(CLIP, "wav", None))
    assert agent.served_by == ["gpt-audio-mini"]


@pytest.mark.asyncio
async def test_clip_leaves_no_input_audio_block_in_history() -> None:
    s, agent = _session("gpt-audio-mini")
    with patch("orchestrator.session.resolve_audio_model", lambda: "gpt-audio-mini"), \
            patch("orchestrator.session.convert_audio_to_wav", lambda d, f: (d, "wav")):
        await _drain(s._send_audio_inner(CLIP, "webm", "note"))
    msg = agent.history[-1]
    assert msg["role"] == "user"
    assert msg["content"] == "[audio:webm] note"


# ---------- 5. default_audio_model (Settings) ---------------------------------

def _write_config(root: Path, data: dict) -> None:
    (root / "assistant_config.json").write_text(json.dumps(data))


@pytest.mark.parametrize(
    ("value", "expected"),
    [
        ("gpt-audio-mini", "gpt-audio-mini"),
        ("gpt-audio-1.5", "gpt-audio-1.5"),       # live-discovered, inferred
        ("gpt-4o", AUDIO_FALLBACK_MODEL_ID),       # takes no audio
        ("gpt-4o-audio-preview", AUDIO_FALLBACK_MODEL_ID),  # retired
        ("", AUDIO_FALLBACK_MODEL_ID),
        (None, AUDIO_FALLBACK_MODEL_ID),
    ],
)
def test_resolve_audio_model_reads_settings(tmp_path: Path, monkeypatch: pytest.MonkeyPatch, value, expected) -> None:
    import orchestrator.config as oc

    monkeypatch.setattr(oc._paths, "PROJECT_ROOT", tmp_path)
    _write_config(tmp_path, {} if value is None else {"default_audio_model": value})
    assert oc.resolve_audio_model() == expected


def test_config_api_accepts_audio_models_only(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    import api.routes.config as cfg
    from fastapi import HTTPException

    path = tmp_path / "assistant_config.json"
    monkeypatch.setattr(cfg, "_get_config_path", lambda: path)
    asyncio.run(cfg.update_config(cfg.ConfigUpdate(default_audio_model="gpt-audio-mini")))
    assert json.loads(path.read_text())["default_audio_model"] == "gpt-audio-mini"
    with pytest.raises(HTTPException) as e:
        asyncio.run(cfg.update_config(cfg.ConfigUpdate(default_audio_model="gpt-4o")))
    assert e.value.status_code == 400
    asyncio.run(cfg.update_config(cfg.ConfigUpdate(default_audio_model="")))  # back to the server default
    assert json.loads(path.read_text())["default_audio_model"] == ""


# ---------- 6. provider: token parameter + reasoning retry ---------------------

class _Stream:
    def __aiter__(self):
        return self

    async def __anext__(self):
        raise StopAsyncIteration


@pytest.mark.asyncio
async def test_provider_sends_max_completion_tokens_and_retries_without_reasoning(monkeypatch: pytest.MonkeyPatch) -> None:
    import httpx
    import openai

    import orchestrator.providers.openai_text as ot

    monkeypatch.setenv("OPENAI_API_KEY", "test")
    monkeypatch.setattr(ot, "_NO_REASONING_WITH_TOOLS", set())
    calls: list[dict] = []
    refusal = openai.BadRequestError(
        "Function tools with reasoning_effort are not supported for gpt-6-luna",
        response=httpx.Response(400, request=httpx.Request("POST", "https://api.openai.com/v1/chat/completions")),
        body=None,
    )

    async def create(**kwargs):
        calls.append(dict(kwargs))
        if "reasoning_effort" not in kwargs:
            raise refusal
        return _Stream()

    tools = [{"name": "get_time", "description": "time", "input_schema": {"type": "object", "properties": {}}}]
    p = ot.OpenAITextProvider(model="gpt-6-luna", max_tokens=123)
    p._client = MagicMock()
    p._client.chat.completions.create = AsyncMock(side_effect=create)
    async for _ in p.create_message([{"role": "user", "content": "hi"}], tools, "sys"):
        pass
    assert [c.get("reasoning_effort") for c in calls] == [None, "none"]
    assert all(c["max_completion_tokens"] == 123 and "max_tokens" not in c for c in calls)

    # Remembered: the next turn sends it directly (one call).
    calls.clear()
    async for _ in p.create_message([{"role": "user", "content": "hi"}], tools, "sys"):
        pass
    assert [c.get("reasoning_effort") for c in calls] == ["none"]


@pytest.mark.asyncio
async def test_voice_message_history_line_keeps_only_the_users_text() -> None:
    """Drained notifications reach the model but are persisted as their own lines, not in the
    `[audio:…]` user line (a reloaded voice-message bubble must not show them)."""
    s, agent = _session("gpt-audio-mini")
    s._notifications = MagicMock()
    s._notifications.drain.return_value = [MagicMock()]
    with patch("orchestrator.session.resolve_audio_model", lambda: "gpt-audio-mini"), \
            patch("orchestrator.session._render_notifications", lambda pending: "[SESSION x event: done]"), \
            patch("orchestrator.session.convert_audio_to_wav", lambda d, f: (d, "wav")):
        await _drain(s.send_audio(CLIP, "wav", "note"))
    users = [c.args[0] for c in s._writer.append.call_args_list if c.args[0].get("type") == "user"]
    assert [u["message"]["content"] for u in users] == ["[audio:wav] note"]
    assert any(c.args[0].get("type") == "background_notification" for c in s._writer.append.call_args_list)
