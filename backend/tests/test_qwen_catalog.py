"""Tests for manager/qwen/catalog.py — models (settings + live DashScope) and options."""

from __future__ import annotations

import json
from unittest.mock import MagicMock, patch

import pytest

from manager.harness_catalog import EFFORT, THINKING, clear_catalog_cache, get_catalog
from manager.qwen import catalog as qc

# A slice of the real DashScope /compatible-mode/v1/models answer (2026-10-07).
LIVE_IDS = [
    "qwen3.8-omni-flash-realtime", "glm-5.3-prime", "qwen-audio-3.1-realtime-plus",
    "ZHIPU/GLM-5.3", "ccai-pro", "deepseek-v3.2", "deepseek-v4-flash", "deepseek-v4-flash-0731",
    "deepseek-v4-pro", "glm-5.1", "glm-5.3", "kimi-k2.7-code", "kimi/kimi-k3", "qvq-max",
    "qwen-coder-plus", "qwen-flash", "qwen-flash-character", "qwen-image-2.0", "qwen-mt-flash",
    "qwen-plus", "qwen-plus-2025-07-14", "qwen-vl-max", "qwen3-235b-a22b-thinking-2507",
    "qwen3-asr-flash-realtime", "qwen3-coder-plus", "qwen3-livetranslate-flash",
    "qwen3-max", "qwen3-tts-flash", "qwen3-vl-plus", "qwen3.6-plus", "qwen3.6-plus-2026-04-02",
    "qwen3.7-plus", "qwen3.7-text-embedding", "qwen3.8-flash", "qwen3.8-max", "qwen3.8-max-0902",
    "qwq-plus", "text-embedding-v4", "tongyi-tingwu-slp", "wan2.7-image", "z-image-turbo",
]


@pytest.fixture
def qwen_home(tmp_path, monkeypatch):
    settings = {
        "env": {"DASHSCOPE_API_KEY": "sk-should-not-leak"},
        "model": {"name": "qwen3.6-plus"},
        "modelProviders": {
            "openai": [
                {
                    "id": "qwen3.6-plus",
                    "name": "[MS] qwen3.6-plus",
                    "baseUrl": "https://dashscope-intl.aliyuncs.com/compatible-mode/v1",
                    "envKey": "DASHSCOPE_API_KEY",
                    "generationConfig": {"extra_body": {"enable_thinking": True}, "contextWindowSize": 1000000},
                },
                {
                    "id": "deepseek-v4-pro",
                    "name": "[MS] deepseek-v4-pro",
                    "baseUrl": "https://dashscope-intl.aliyuncs.com/compatible-mode/v1",
                    "envKey": "DASHSCOPE_API_KEY",
                    "generationConfig": {"contextWindowSize": 1000000, "modalities": {"image": True}},
                },
            ],
        },
    }
    (tmp_path / "settings.json").write_text(json.dumps(settings))
    monkeypatch.setenv("QWEN_HOME", str(tmp_path))
    monkeypatch.setenv("DASHSCOPE_API_KEY", "test-key")
    clear_catalog_cache()
    yield tmp_path
    clear_catalog_cache()


def _fake_response(ids: list[str]) -> MagicMock:
    resp = MagicMock()
    resp.raise_for_status = MagicMock()
    resp.json = MagicMock(return_value={"object": "list", "data": [{"id": i, "object": "model"} for i in ids]})
    return resp


def test_filter_live_ids():
    out = qc.filter_live_ids(LIVE_IDS)
    assert out == [
        "glm-5.3-prime", "deepseek-v3.2", "deepseek-v4-flash", "deepseek-v4-pro", "glm-5.1",
        "glm-5.3", "kimi-k2.7-code", "qwen-coder-plus", "qwen-flash", "qwen-plus",
        "qwen3-coder-plus", "qwen3-max", "qwen3.6-plus", "qwen3.7-plus", "qwen3.8-flash",
        "qwen3.8-max", "qwq-plus",
    ]


def test_catalog_merges_settings_and_live(qwen_home):
    with patch("httpx.get", return_value=_fake_response(LIVE_IDS)) as get:
        cat = qc.load_qwen_catalog()
    url = get.call_args.args[0]
    assert url == "https://dashscope-intl.aliyuncs.com/compatible-mode/v1/models"
    assert get.call_args.kwargs["headers"] == {"Authorization": "Bearer test-key"}
    assert get.call_args.kwargs["timeout"] <= 5

    assert cat.provider == "qwen"
    assert cat.default_model == "qwen3.6-plus"
    assert cat.allow_custom_model is True
    assert cat.warnings == ()
    ids = [m.id for m in cat.models]
    # settings rows first, then live rows (sorted), deduped against settings.
    assert ids[:2] == ["qwen3.6-plus", "deepseek-v4-pro"]
    assert ids.count("qwen3.6-plus") == 1 and ids.count("deepseek-v4-pro") == 1
    live = [m for m in cat.models if m.source == "live"]
    assert [m.id for m in live] == sorted(m.id for m in live)
    assert "qwen3.8-max" in ids and "qwen3-tts-flash" not in ids

    by_id = {m.id: m for m in cat.models}
    q36 = by_id["qwen3.6-plus"]
    assert q36.source == "settings" and q36.label == "[MS] qwen3.6-plus"
    assert q36.context_window == 1000000 and q36.supports_thinking is True
    assert by_id["deepseek-v4-pro"].supports_vision is True
    q38 = by_id["qwen3.8-max"]
    assert q38.source == "live" and q38.efforts == ("low", "medium", "xhigh")
    assert q38.context_window == 1_000_000
    assert by_id["qwen3-coder-plus"].supports_thinking is False


def test_catalog_options(qwen_home):
    with patch("httpx.get", return_value=_fake_response(LIVE_IDS)):
        cat = qc.load_qwen_catalog()
    keys = [o.key for o in cat.options]
    assert keys == [THINKING, "thinking_budget", EFFORT, "temperature"]

    thinking = cat.option(THINKING)
    assert thinking.kind == "toggle"
    assert "qwen3.6-plus" in thinking.models and "deepseek-v4-pro" in thinking.models
    assert "qwen3.8-max" not in thinking.models  # thinking-only
    assert "glm-5.3" not in thinking.models
    assert "qwen3-coder-plus" not in thinking.models

    budget = cat.option("thinking_budget")
    assert budget.kind == "number" and budget.min == 1 and budget.max == 32768
    assert "qwen3.6-plus" in budget.models and "deepseek-v4-pro" not in budget.models

    effort = cat.option(EFFORT)
    assert [c.value for c in effort.choices] == ["low", "medium", "xhigh"]
    assert set(effort.models) == {"qwen3.8-max", "qwen3.8-flash"}

    temp = cat.option("temperature")
    assert temp.kind == "number" and temp.models is None

    # Serializes for the settings UIs.
    json.dumps(cat.to_dict())


def test_catalog_falls_back_on_http_failure(qwen_home):
    with patch("httpx.get", side_effect=RuntimeError("boom")):
        cat = qc.load_qwen_catalog()
    assert [m.id for m in cat.models] == ["qwen3.6-plus", "deepseek-v4-pro"]
    assert all(m.source == "settings" for m in cat.models)
    assert len(cat.warnings) == 1 and "boom" in cat.warnings[0]


def test_catalog_without_api_key(qwen_home, monkeypatch):
    monkeypatch.delenv("DASHSCOPE_API_KEY")
    with patch("httpx.get") as get:
        cat = qc.load_qwen_catalog()
    get.assert_not_called()
    assert [m.id for m in cat.models] == ["qwen3.6-plus", "deepseek-v4-pro"]
    assert "DASHSCOPE_API_KEY" in cat.warnings[0]


def test_catalog_without_settings_file(tmp_path, monkeypatch):
    monkeypatch.setenv("QWEN_HOME", str(tmp_path))
    monkeypatch.delenv("DASHSCOPE_API_KEY", raising=False)
    cat = qc.load_qwen_catalog()
    assert cat.models == () and cat.default_model is None
    assert cat.warnings


def test_live_base_url_follows_settings(tmp_path, monkeypatch):
    settings = {"modelProviders": {"openai": [
        {"id": "qwen-plus", "baseUrl": "https://dashscope.aliyuncs.com/compatible-mode/v1/", "envKey": "K"},
    ]}}
    (tmp_path / "settings.json").write_text(json.dumps(settings))
    monkeypatch.setenv("QWEN_HOME", str(tmp_path))
    monkeypatch.setenv("DASHSCOPE_API_KEY", "k")
    with patch("httpx.get", return_value=_fake_response([])) as get:
        qc.load_qwen_catalog()
    assert get.call_args.args[0] == "https://dashscope.aliyuncs.com/compatible-mode/v1/models"


def test_registered_as_catalog_loader(qwen_home):
    with patch("httpx.get", return_value=_fake_response(LIVE_IDS)):
        cat = get_catalog("qwen", refresh=True)
    assert cat is not None and cat.provider == "qwen"
    assert any(m.source == "live" for m in cat.models)


@pytest.mark.parametrize(
    "model,thinking,budget,effort",
    [
        ("qwen3.6-plus", True, True, False),
        ("qwen3.7-max", True, True, False),
        ("glm-5.1", True, True, False),
        ("deepseek-v4-flash", True, False, False),
        ("qwen3.8-max", False, False, True),
        ("qwen3.8-flash", False, False, True),
        ("glm-5.3", False, True, False),
        ("qwen3-coder-plus", False, False, False),
        ("some-custom-model", False, False, False),
        (None, False, False, False),
    ],
)
def test_option_applies(model, thinking, budget, effort):
    assert qc.option_applies(THINKING, model) is thinking
    assert qc.option_applies("thinking_budget", model) is budget
    assert qc.option_applies(EFFORT, model) is effort
    assert qc.option_applies("temperature", model) is True
    assert qc.option_applies("unknown", model) is False


def test_context_window_fallback_for_live_only_model(qwen_home):
    from manager.context_windows import context_window_for

    assert context_window_for("qwen", "qwen3.6-plus") == 1000000  # settings.json
    assert context_window_for("qwen", "glm-5.3") == 202_752       # family table
    assert context_window_for("qwen", "totally-unknown") is None
