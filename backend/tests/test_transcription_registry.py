"""The transcriber catalogue: resolution never raises, availability follows the keys.

``registry.resolve`` is the function that keeps dictation working when a saved choice goes bad —
a model removed from the table, a key deleted from ``context/.env``, a batch model picked where
live text was asked for. Every one of those has to degrade to something usable.
"""

from __future__ import annotations

import pytest

from transcription import registry
from transcription.base import TranscriberError


@pytest.fixture
def keys(monkeypatch):
    """Every provider has a key unless a test says otherwise."""
    monkeypatch.setattr(registry, "api_key", lambda spec: "k-test")


def test_every_spec_is_well_formed():
    assert registry.TRANSCRIBERS, "the table must not be empty"
    ids = [s.id for s in registry.TRANSCRIBERS]
    assert len(ids) == len(set(ids)), "ids must be unique"
    models = [s.model for s in registry.TRANSCRIBERS]
    assert len(models) == len(set(models)), "find() matches on model alone, so models are unique too"
    for spec in registry.TRANSCRIBERS:
        assert spec.id == f"{spec.provider}/{spec.model}"
        assert spec.env_key.endswith("_API_KEY")
        assert 8000 <= spec.sample_rate <= 48000
        assert spec.label and spec.cost_note


def test_defaults_name_a_real_entry():
    assert registry.find(registry.DEFAULT_PROVIDER, registry.DEFAULT_MODEL) is not None
    batch = registry.find(registry.BATCH_DEFAULT_PROVIDER, registry.BATCH_DEFAULT_MODEL)
    assert batch is not None and not batch.streaming


def test_whisper_1_is_gone():
    """It is retired by OpenAI on 2027-02-26; nothing should still offer it."""
    assert all(s.model != "whisper-1" for s in registry.TRANSCRIBERS)


def test_find_matches_on_model_alone():
    spec = registry.find(None, "gpt-live-transcribe")
    assert spec is not None and spec.provider == "openai"
    assert registry.find("elevenlabs", "gpt-live-transcribe") is None
    assert registry.find("openai", None) is None


def test_resolve_returns_the_exact_choice(keys):
    spec = registry.resolve("elevenlabs", "scribe_v2_realtime")
    assert spec.id == "elevenlabs/scribe_v2_realtime"


def test_resolve_falls_back_for_an_unknown_model(keys):
    spec = registry.resolve("openai", "a-model-that-never-existed")
    assert spec.model == registry.DEFAULT_MODEL


def test_resolve_honours_the_streaming_requirement(keys):
    """A client that asked for live text gets a different live model, never a batch one."""
    spec = registry.resolve("openai", "gpt-transcribe", streaming=True)
    assert spec.streaming

    spec = registry.resolve("openai", "gpt-live-transcribe", streaming=False)
    assert not spec.streaming


def test_resolve_prefers_a_provider_with_a_key(monkeypatch):
    """The default's key is gone: fall through to one that has a key, not to a dead row."""
    monkeypatch.setattr(registry, "api_key", lambda spec: "" if spec.env_key == "OPENAI_API_KEY" else "k")
    spec = registry.resolve(None, None, streaming=True)
    assert spec.env_key == "ELEVENLABS_API_KEY"


def test_resolve_never_raises_even_with_no_keys_at_all(monkeypatch):
    monkeypatch.setattr(registry, "api_key", lambda spec: "")
    assert registry.resolve(None, None) in registry.TRANSCRIBERS
    assert registry.resolve("nope", "nope", streaming=True).streaming


def test_list_transcribers_explains_unavailability(monkeypatch):
    monkeypatch.setattr(registry, "api_key", lambda spec: "" if spec.provider == "elevenlabs" else "k")
    listed = registry.list_transcribers()
    assert len(listed) == len(registry.TRANSCRIBERS)
    eleven = [t for t in listed if t["provider"] == "elevenlabs"]
    assert eleven and all(not t["available"] for t in eleven)
    assert all("ELEVENLABS_API_KEY" in t["unavailable_reason"] for t in eleven)
    openai = [t for t in listed if t["provider"] == "openai"]
    assert all(t["available"] and t["unavailable_reason"] == "" for t in openai)
    assert sum(1 for t in listed if t["default"]) == 1


def test_create_without_a_key_is_a_clear_error(monkeypatch):
    monkeypatch.setattr(registry, "api_key", lambda spec: "")
    with pytest.raises(TranscriberError) as e:
        registry.create(registry.TRANSCRIBERS[0])
    assert e.value.code == "no_api_key"


def test_create_builds_the_right_transcriber(keys):
    from transcription.batch import BatchTranscriber
    from transcription.elevenlabs_realtime import ElevenLabsRealtimeTranscriber
    from transcription.openai_realtime import OpenAIRealtimeTranscriber

    assert isinstance(registry.create(registry.find("openai", "gpt-live-transcribe")), OpenAIRealtimeTranscriber)
    assert isinstance(registry.create(registry.find("elevenlabs", "scribe_v2_realtime")), ElevenLabsRealtimeTranscriber)
    assert isinstance(registry.create(registry.find("openai", "gpt-transcribe")), BatchTranscriber)


def test_create_honours_an_overridden_sample_rate(keys):
    spec = registry.find("elevenlabs", "scribe_v2_realtime")
    assert registry.create(spec, sample_rate=24000).sample_rate == 24000
    assert registry.create(spec).sample_rate == spec.sample_rate


def test_auto_detect_is_an_offered_language():
    assert registry.LANGUAGES[0][0] == "", "auto-detect must be first (it is the default)"
    codes = {code for code, _ in registry.LANGUAGES}
    assert {"en", "pt"} <= codes, "Rodrigo switches between these two mid-sentence"
