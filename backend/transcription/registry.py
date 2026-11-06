"""The catalogue of transcribers: what exists, what has a key, what it costs.

One :class:`TranscriberSpec` per selectable model. The Settings UI renders this
list verbatim — including the entries with no key on this server, which are
shown greyed rather than hidden so the list explains itself.

Mirrors the shape of :mod:`orchestrator.providers.voice_registry`: a frozen
table, a default, and a :func:`resolve` that never raises, so a saved choice
that later loses its key (or disappears from the table) degrades to something
that works instead of breaking dictation.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass
from typing import Any

logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class TranscriberSpec:
    provider: str
    model: str
    label: str
    description: str
    #: True if text arrives while the speaker is still talking.
    streaming: bool
    #: PCM16 mono rate the provider wants, in Hz.
    sample_rate: int
    #: Name of the key in ``context/.env`` this provider spends.
    env_key: str
    #: Roughly what an hour of audio costs, for the Settings list.
    cost_note: str

    @property
    def id(self) -> str:
        return f"{self.provider}/{self.model}"


# Verified against the live APIs on 2026-11-05; see docs/integrations/transcription.md
# for the protocol quirks behind each one.
TRANSCRIBERS: tuple[TranscriberSpec, ...] = (
    TranscriberSpec(
        provider="openai",
        model="gpt-live-transcribe",
        label="OpenAI · gpt-live-transcribe",
        description="Word by word as you speak, punctuated.",
        streaming=True,
        sample_rate=24000,
        env_key="OPENAI_API_KEY",
        cost_note="≈ $0.36/h",
    ),
    TranscriberSpec(
        provider="elevenlabs",
        model="scribe_v2_realtime",
        label="ElevenLabs · Scribe v2 realtime",
        description="150 ms partials, 90+ languages.",
        streaming=True,
        sample_rate=16000,
        env_key="ELEVENLABS_API_KEY",
        cost_note="$0.39/h",
    ),
    TranscriberSpec(
        provider="openai",
        model="gpt-transcribe",
        label="OpenAI · gpt-transcribe",
        description="Transcribes on stop, no live text.",
        streaming=False,
        sample_rate=16000,
        env_key="OPENAI_API_KEY",
        cost_note="≈ $0.36/h",
    ),
    TranscriberSpec(
        provider="openai",
        model="gpt-4o-mini-transcribe",
        label="OpenAI · gpt-4o-mini-transcribe",
        description="Transcribes on stop. The cheapest option.",
        streaming=False,
        sample_rate=16000,
        env_key="OPENAI_API_KEY",
        cost_note="≈ $0.18/h",
    ),
    TranscriberSpec(
        provider="elevenlabs",
        model="scribe_v1",
        label="ElevenLabs · Scribe v1",
        description="Transcribes on stop. Word timestamps, 99 languages.",
        streaming=False,
        sample_rate=16000,
        env_key="ELEVENLABS_API_KEY",
        cost_note="≈ $0.22/h",
    ),
)

DEFAULT_PROVIDER = "openai"
DEFAULT_MODEL = "gpt-live-transcribe"

#: What ``POST /api/transcribe`` uses when the caller names no model. Batch by
#: definition — it is handed a finished WAV. ``whisper-1`` retires 2027-02-26.
BATCH_DEFAULT_PROVIDER = "openai"
BATCH_DEFAULT_MODEL = "gpt-transcribe"

#: Offered in Settings. "" means let the provider detect the language, which is
#: what Rodrigo wants by default — he switches between Portuguese and English
#: mid-sentence and a pinned language mangles the other one.
LANGUAGES: tuple[tuple[str, str], ...] = (
    ("", "Auto-detect"),
    ("en", "English"),
    ("pt", "Português"),
    ("es", "Español"),
    ("fr", "Français"),
    ("de", "Deutsch"),
    ("it", "Italiano"),
    ("ja", "日本語"),
)


def api_key(spec: TranscriberSpec) -> str:
    """The key this provider spends: the environment, else ``context/.env``."""
    from manager.accounts import envfile

    try:
        return envfile.effective(spec.env_key).strip()
    except Exception:  # unreadable .env must not take dictation down
        logger.exception("Could not read %s", spec.env_key)
        return ""


def find(provider: str | None, model: str | None) -> TranscriberSpec | None:
    """The spec for a provider+model, or None if it is not in the table.

    Model ids are unique across providers, so an API caller may name just the
    model and get the right entry; the provider only narrows the match.
    """
    if not model:
        return None
    for spec in TRANSCRIBERS:
        if spec.model == model and (not provider or spec.provider == provider):
            return spec
    return None


def resolve(provider: str | None, model: str | None, *, streaming: bool | None = None) -> TranscriberSpec:
    """The spec to actually use. Never raises.

    An **explicit** choice that exists is returned as-is, even with no key on this
    server: the caller then reports ``no_api_key``, which is the honest answer.
    Silently billing a different vendor because the chosen one was unconfigured
    would be worse than saying so.

    Everything else is a fallback, and fallbacks prefer a provider that actually
    works: the configured default if it has a key, else the first entry that
    does, else whatever is left. ``streaming=True`` keeps the fallbacks among
    providers that can stream — a client that asked for live text would rather
    have a different live model than silent batching.
    """
    def ok(spec: TranscriberSpec) -> bool:
        return streaming is None or spec.streaming == streaming

    spec = find(provider, model)
    if spec is not None and ok(spec):
        return spec
    if spec is not None:
        logger.info("Transcriber %s cannot stream; falling back", spec.id)

    default = find(DEFAULT_PROVIDER, DEFAULT_MODEL)
    if default is not None and ok(default) and api_key(default):
        return default
    for candidate in TRANSCRIBERS:
        if ok(candidate) and api_key(candidate):
            return candidate
    # Nothing on this server has a key: return something shaped right anyway, so
    # the caller fails with "no API key" rather than with a lookup error.
    if default is not None and ok(default):
        return default
    for candidate in TRANSCRIBERS:
        if ok(candidate):
            return candidate
    return TRANSCRIBERS[0]


def list_transcribers() -> list[dict[str, Any]]:
    """The catalogue for the Settings UI, with availability resolved."""
    out: list[dict[str, Any]] = []
    for spec in TRANSCRIBERS:
        available = bool(api_key(spec))
        out.append({
            "id": spec.id,
            "provider": spec.provider,
            "model": spec.model,
            "label": spec.label,
            "description": spec.description,
            "streaming": spec.streaming,
            "sample_rate": spec.sample_rate,
            "cost_note": spec.cost_note,
            "available": available,
            "unavailable_reason": "" if available else f"No {spec.env_key} on this server",
            "default": spec.provider == DEFAULT_PROVIDER and spec.model == DEFAULT_MODEL,
        })
    return out


def create(spec: TranscriberSpec, *, language: str = "", sample_rate: int | None = None):
    """Build the :class:`~transcription.base.Transcriber` for *spec*.

    Imports the provider module lazily, so a provider whose dependency is
    missing only fails when it is actually selected.
    """
    key = api_key(spec)
    if not key:
        from transcription.base import TranscriberError
        raise TranscriberError("no_api_key", f"{spec.env_key} is not configured on the backend")

    rate = sample_rate or spec.sample_rate
    if spec.streaming:
        if spec.provider == "openai":
            from transcription.openai_realtime import OpenAIRealtimeTranscriber
            return OpenAIRealtimeTranscriber(key, spec.model, language=language, sample_rate=rate)
        if spec.provider == "elevenlabs":
            from transcription.elevenlabs_realtime import ElevenLabsRealtimeTranscriber
            return ElevenLabsRealtimeTranscriber(key, spec.model, language=language, sample_rate=rate)
    else:
        from transcription.batch import BatchTranscriber
        return BatchTranscriber(key, spec, language=language, sample_rate=rate)

    from transcription.base import TranscriberError
    raise TranscriberError("unknown_provider", f"no transcriber for {spec.id}")
