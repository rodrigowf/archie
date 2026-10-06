"""Live Qwen-Omni realtime model discovery (DashScope /compatible-mode/v1/models)."""

from __future__ import annotations

import asyncio
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from orchestrator.providers import discovery
from orchestrator.providers.voice_registry import (
    _QWEN_FLASH_VOICES,
    _QWEN_PLUS_VOICES,
    get_model_entry,
    instantiate_provider,
    qwen_model_entry,
)

# Trimmed from a real 2026-10-06 response: omni realtime models mixed with
# realtime TTS / ASR / livetranslate and non-realtime omni models.
_API_IDS = [
    "qwen3.8-omni-flash-realtime",
    "qwen3.8-omni-flash",
    "qwen3.8-livetranslate-flash-realtime",
    "qwen3.5-omni-plus-realtime-2026-03-15",
    "qwen3.5-omni-plus-realtime",
    "qwen3.5-omni-flash-realtime",
    "qwen3-asr-flash-realtime",
    "qwen3-tts-flash-realtime",
    "qwen3-omni-flash-realtime-2025-12-01",
    "qwen3-omni-30b-a3b-captioner",
    "qwen-plus",
]


@pytest.fixture(autouse=True)
def _clear_cache():
    discovery._cache.clear()
    yield
    discovery._cache.clear()


def _mock_client(ids: list[str]):
    resp = MagicMock()
    resp.json.return_value = {"data": [{"id": i} for i in ids]}
    resp.raise_for_status.return_value = None
    client = MagicMock()
    client.get = AsyncMock(return_value=resp)
    client.__aenter__ = AsyncMock(return_value=client)
    client.__aexit__ = AsyncMock(return_value=None)
    return client


def test_fetch_keeps_only_omni_realtime(monkeypatch):
    monkeypatch.setenv("DASHSCOPE_API_KEY", "k")
    with patch.object(discovery.httpx, "AsyncClient", return_value=_mock_client(_API_IDS)):
        ids = asyncio.run(discovery._fetch_qwen_models())
    assert ids == [
        "qwen3.8-omni-flash-realtime",
        "qwen3.5-omni-plus-realtime-2026-03-15",
        "qwen3.5-omni-plus-realtime",
        "qwen3.5-omni-flash-realtime",
        "qwen3-omni-flash-realtime-2025-12-01",
    ]


def test_fetch_without_key_returns_empty(monkeypatch):
    monkeypatch.delenv("DASHSCOPE_API_KEY", raising=False)
    assert asyncio.run(discovery._fetch_qwen_models()) == []


def test_live_list_merges_static_and_sorts_newest_first(monkeypatch):
    monkeypatch.setattr(discovery, "_fetch_openai_realtime_models", AsyncMock(return_value=[]))
    monkeypatch.setattr(discovery, "_fetch_qwen_models", AsyncMock(return_value=[
        "qwen3.5-omni-plus-realtime-2026-03-15",
        "qwen3.8-omni-flash-realtime",
        "qwen3.5-omni-plus-realtime",
    ]))
    result = asyncio.run(discovery.list_voice_models_live())
    ids = [e["id"] for e in result["qwen"]]
    assert ids == [
        "qwen3.8-omni-flash-realtime",
        "qwen3.5-omni-plus-realtime",
        "qwen3.5-omni-plus-realtime-2026-03-15",
        "qwen3-omni-flash-realtime",  # static, not returned by the API
    ]
    assert [e["id"] for e in result["qwen"] if e["default"]] == ["qwen3.5-omni-plus-realtime"]


def test_live_list_falls_back_to_static_on_failure(monkeypatch):
    monkeypatch.setattr(discovery, "_fetch_openai_realtime_models", AsyncMock(return_value=[]))
    monkeypatch.setattr(discovery, "_fetch_qwen_models", AsyncMock(return_value=[]))
    result = asyncio.run(discovery.list_voice_models_live())
    assert {e["id"] for e in result["qwen"]} == {
        "qwen3.5-omni-plus-realtime", "qwen3-omni-flash-realtime",
    }


def test_snapshot_inherits_alias_entry():
    entry = qwen_model_entry("qwen3.5-omni-plus-realtime-2026-03-15")
    assert entry["label"] == "Qwen3.5-Omni Plus (2026-03-15)"
    assert entry["voices"] is _QWEN_PLUS_VOICES
    assert entry["default"] is False


def test_unknown_model_gets_family_voices_and_label():
    flash = qwen_model_entry("qwen3.8-omni-flash-realtime")
    assert flash["label"] == "Qwen3.8-Omni Flash"
    assert flash["voices"] is _QWEN_FLASH_VOICES
    plus = qwen_model_entry("qwen4-omni-plus-realtime")
    assert plus["label"] == "Qwen4-Omni Plus"
    assert plus["voices"] is _QWEN_PLUS_VOICES


def test_instantiate_keeps_flash_only_voice_for_discovered_flash_model():
    # Cherry is in the Flash catalog but not Plus; the old Plus-template
    # fallback silently reset it to the default voice.
    assert "Cherry" not in {v["id"] for v in _QWEN_PLUS_VOICES}
    assert get_model_entry("qwen", "qwen3.8-omni-flash-realtime")["voices"] is _QWEN_FLASH_VOICES
    provider = instantiate_provider("qwen", "qwen3.8-omni-flash-realtime", voice="Cherry")
    assert provider._voice == "Cherry"
