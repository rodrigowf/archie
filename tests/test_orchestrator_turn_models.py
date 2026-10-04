"""Per-turn model routing for typed vs audio turns (2026-10-04).

OpenAI's gpt-audio chat models refuse text-only requests ("This model requires
that either input content or output modality contain audio") and text models
refuse input_audio blocks, so no single model takes both. These tests pin:

1. A typed turn on an audio-only model runs on ``TEXT_FALLBACK_MODEL_ID``; the
   configured model is unchanged afterwards.
2. An audio clip on a text model runs on ``AUDIO_FALLBACK_MODEL_ID`` for that
   turn only (it used to switch the session's model for good, which broke every
   later typed turn).
3. A clip on an audio model runs on the agent's own provider.
4. After an audio turn the clip is replaced in history by a text marker, so a
   later text-model turn never receives an input_audio block.
"""

from __future__ import annotations

import base64
from typing import Any
from unittest.mock import MagicMock, patch

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
            patch("orchestrator.session.convert_audio_to_wav", lambda d, f: (d, "wav")):
        await _drain(s._send_audio_inner(CLIP, "wav", "what did I say?"))
        await _drain(s.send("and now typed"))
    assert agent.served_by == [AUDIO_FALLBACK_MODEL_ID, "gpt-4o"]
    assert s._config.model == "gpt-4o"


@pytest.mark.asyncio
async def test_clip_on_audio_model_uses_own_provider() -> None:
    s, agent = _session("gpt-audio-mini")
    with patch("orchestrator.session.convert_audio_to_wav", lambda d, f: (d, "wav")):
        await _drain(s._send_audio_inner(CLIP, "wav", None))
    assert agent.served_by == ["gpt-audio-mini"]


@pytest.mark.asyncio
async def test_clip_leaves_no_input_audio_block_in_history() -> None:
    s, agent = _session("gpt-audio-mini")
    with patch("orchestrator.session.convert_audio_to_wav", lambda d, f: (d, "wav")):
        await _drain(s._send_audio_inner(CLIP, "webm", "note"))
    msg = agent.history[-1]
    assert msg["role"] == "user"
    assert msg["content"] == "[audio:webm] note"
