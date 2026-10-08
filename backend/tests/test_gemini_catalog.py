"""Tests for manager/gemini/catalog.py — models (builtin ∪ live) + options."""

from __future__ import annotations

from unittest.mock import MagicMock, patch

import pytest

from manager import harness_catalog as hc
from manager.gemini import catalog as gc
from manager.harness_catalog import validate_options


def _live(*ids: str, method: str = "generateContent") -> dict:
    return {"models": [
        {"name": f"models/{i}", "displayName": i.upper(), "inputTokenLimit": 1048576,
         "supportedGenerationMethods": [method, "countTokens"]}
        for i in ids
    ]}


def _client(resp_json: dict | None = None, status: int = 200, exc: Exception | None = None):
    client = MagicMock()
    client.__enter__.return_value = client
    if exc is not None:
        client.get.side_effect = exc
    else:
        resp = MagicMock(status_code=status)
        resp.json.return_value = resp_json or {"models": []}
        client.get.return_value = resp
    return client


def _load(monkeypatch, client=None, key: str | None = "k"):
    if key:
        monkeypatch.setenv("GEMINI_API_KEY", key)
    else:
        monkeypatch.delenv("GEMINI_API_KEY", raising=False)
    with patch("httpx.Client", return_value=client or _client()):
        return gc.load_gemini_catalog()


def test_without_key_serves_builtin_with_warning(monkeypatch) -> None:
    cat = _load(monkeypatch, key=None)
    ids = [m.id for m in cat.models]
    assert ids[:4] == ["auto", "pro", "flash", "flash-lite"]
    assert "gemini-3.1-pro-preview" in ids and "gemini-2.5-flash" in ids
    assert all(m.source == "builtin" for m in cat.models)
    assert cat.default_model == "auto" and cat.allow_custom_model
    assert any("oauth-personal" in w for w in cat.warnings)


def test_live_list_is_merged_filtered_and_deduped(monkeypatch) -> None:
    live = _live(
        "gemini-3.1-pro-preview", "gemini-3-flash-preview", "gemini-3.7-flash",
        "gemini-2.5-flash", "gemini-3.8-flash-tts", "gemini-3.1-flash-image",
        "gemini-3.5-transcribe", "nano-banana-pro-preview", "gemini-omni-flash-preview",
        "gemini-3.1-pro-preview-customtools", "antigravity-preview-latest",
    )
    live["models"].append({"name": "models/gemini-embedding-2", "supportedGenerationMethods": ["embedContent"]})
    live["models"].append({"name": "models/gemini-3.8-live", "supportedGenerationMethods": ["bidiGenerateContent"]})
    client = _client(live)
    cat = _load(monkeypatch, client)
    ids = [m.id for m in cat.models]
    assert len(ids) == len(set(ids))
    assert "gemini-3.7-flash" in ids  # live-only row appended
    for bad in ("gemini-3.8-flash-tts", "gemini-3.1-flash-image", "gemini-3.5-transcribe",
                "nano-banana-pro-preview", "gemini-omni-flash-preview", "gemini-embedding-2",
                "gemini-3.8-live", "gemini-3.1-pro-preview-customtools", "antigravity-preview-latest"):
        assert bad not in ids
    rows = {m.id: m for m in cat.models}
    assert rows["gemini-3.1-pro-preview"].source == "live"
    assert rows["gemini-3.1-pro-preview"].label == "GEMINI-3.1-PRO-PREVIEW"
    assert rows["gemma-4-31b-it"].source == "builtin"
    assert any("gemma-4-31b-it" in w for w in cat.warnings)
    # the key travels in a header, not the URL
    kwargs = client.get.call_args.kwargs
    assert kwargs["headers"] == {"x-goog-api-key": "k"}
    assert "key" not in kwargs["params"]


@pytest.mark.parametrize("client", [
    _client(status=403),
    _client(exc=TimeoutError("slow")),
])
def test_upstream_failure_falls_back_to_builtin(monkeypatch, client) -> None:
    cat = _load(monkeypatch, client)
    assert [m.id for m in cat.models] == [b.id for b in gc.BUILTIN_MODELS]
    assert len(cat.warnings) == 1 and "built-in" in cat.warnings[0]


def test_options_and_model_restrictions(monkeypatch) -> None:
    cat = _load(monkeypatch, key=None)
    level = cat.option("thinking_level")
    budget = cat.option("thinking_budget")
    approval = cat.option("approval_mode")
    assert [c.value for c in level.choices] == ["minimal", "low", "medium", "high"]
    assert "gemini-3.1-pro-preview" in level.models and "auto" in level.models
    assert "gemini-2.5-flash" not in level.models
    assert set(budget.models) == {"gemini-2.5-pro", "gemini-2.5-flash", "gemini-2.5-flash-lite"}
    assert (budget.kind, budget.min, budget.max) == ("number", -1, 32768)
    assert [c.value for c in approval.choices] == ["yolo", "auto_edit", "plan", "default"]
    assert validate_options(cat, {"thinking_budget": -1, "thinking_level": "low"}) == {
        "thinking_budget": -1, "thinking_level": "low",
    }
    with pytest.raises(ValueError):
        validate_options(cat, {"thinking_budget": 50000})
    with pytest.raises(ValueError):
        validate_options(cat, {"thinking_level": "max"})
    d = cat.to_dict()
    assert d["provider"] == "gemini" and d["options"][0]["key"] == "thinking_level"


def test_registered_loader_served_through_get_catalog(monkeypatch) -> None:
    monkeypatch.delenv("GEMINI_API_KEY", raising=False)
    hc.clear_catalog_cache()
    try:
        cat = hc.get_catalog("gemini", refresh=True)
    finally:
        hc.clear_catalog_cache()
    assert cat is not None and cat.provider == "gemini" and cat.models


@pytest.mark.parametrize(("model", "family"), [
    (None, "alias"), ("auto", "alias"), ("flash-lite", "alias"), ("auto-gemini-3", "alias"),
    ("gemini-3.7-flash", "3"), ("gemma-4-31b-it", "3"), ("gemini-2.5-pro", "2.5"),
    ("gemini-flash-latest", None),
])
def test_model_family(model, family) -> None:
    assert gc.model_family(model) == family


@pytest.mark.parametrize(("model", "level", "expected"), [
    ("gemini-3-pro-preview", "low", "low"),
    ("gemini-3-pro-preview", "medium", "high"),
    ("gemini-3-pro-preview", "minimal", "low"),
    ("gemini-3.1-pro-preview", "minimal", "low"),
    ("auto", "minimal", "low"),
    ("flash", "minimal", "minimal"),
    ("gemini-3.8-flash", "minimal", "low"),
    ("gemini-3-flash-preview", "minimal", "minimal"),
    ("gemma-4-31b-it", "minimal", "minimal"),
])
def test_clamp_thinking_level(model, level, expected) -> None:
    assert gc.clamp_thinking_level(model, level) == expected


@pytest.mark.parametrize(("model", "budget", "expected"), [
    ("gemini-2.5-pro", -1, -1), ("gemini-2.5-pro", 0, 128), ("gemini-2.5-pro", 99999, 32768),
    ("gemini-2.5-flash", 0, 0), ("gemini-2.5-flash", 30000, 24576),
    ("gemini-2.5-flash-lite", 0, 0), ("gemini-2.5-flash-lite", 10, 512),
])
def test_clamp_thinking_budget(model, budget, expected) -> None:
    assert gc.clamp_thinking_budget(model, budget) == expected
