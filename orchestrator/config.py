"""Configuration for the orchestrator agent.

Supports runtime model switching between Anthropic and OpenAI providers.
The provider/model can be changed mid-conversation for text and turn-based
interactions. Realtime voice sessions use a fixed model (set at session start).
"""

from __future__ import annotations

import json
import logging
import os
from dataclasses import dataclass, field
from enum import Enum
from pathlib import Path
from typing import Any

from utils import paths as _paths
from utils.paths import get_memory_dir

logger = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# Provider and Model Definitions
# ---------------------------------------------------------------------------

class Provider(str, Enum):
    """Supported model providers."""

    ANTHROPIC = "anthropic"
    OPENAI = "openai"


@dataclass(frozen=True, slots=True)
class ModelInfo:
    """Information about an available model."""

    provider: Provider
    model_id: str
    display_name: str
    supports_audio: bool = False
    supports_vision: bool = False
    supports_tools: bool = True
    max_tokens: int = 8192

    def to_dict(self) -> dict[str, Any]:
        """Convert to JSON-serializable dict."""
        from manager.context_windows import context_window_for
        return {
            "provider": self.provider.value,
            "model_id": self.model_id,
            "display_name": self.display_name,
            "supports_audio": self.supports_audio,
            "supports_vision": self.supports_vision,
            "supports_tools": self.supports_tools,
            "max_tokens": self.max_tokens,
            "context_window": context_window_for(self.provider.value, self.model_id),
        }


# Available models registry
AVAILABLE_MODELS: dict[str, ModelInfo] = {
    # Anthropic models
    "claude-sonnet-4-5-20250929": ModelInfo(
        provider=Provider.ANTHROPIC,
        model_id="claude-sonnet-4-5-20250929",
        display_name="Claude Sonnet 4.5",
        supports_vision=True,
        max_tokens=8192,
    ),
    "claude-opus-4-20250514": ModelInfo(
        provider=Provider.ANTHROPIC,
        model_id="claude-opus-4-20250514",
        display_name="Claude Opus 4",
        supports_vision=True,
        max_tokens=8192,
    ),
    "claude-haiku-3-5-20241022": ModelInfo(
        provider=Provider.ANTHROPIC,
        model_id="claude-haiku-3-5-20241022",
        display_name="Claude Haiku 3.5",
        supports_vision=True,
        max_tokens=4096,
    ),
    # OpenAI models
    # Note: gpt-4o does NOT support audio input - use gpt-4o-audio-preview for audio
    "gpt-4o": ModelInfo(
        provider=Provider.OPENAI,
        model_id="gpt-4o",
        display_name="GPT-4o",
        supports_audio=False,  # Use gpt-4o-audio-preview for audio
        supports_vision=True,
        max_tokens=16384,
    ),
    # Audio-capable chat models. OpenAI RENAMED the old `gpt-4o-audio-preview`
    # to the `gpt-audio` family; `gpt-4o-audio-preview` now 404s for accounts
    # provisioned after the rename (verified 2026-07-21 against this account:
    # gpt-4o-audio-preview -> 404 model_not_found; gpt-audio / gpt-audio-mini ->
    # OK with input_audio). Use `gpt-audio` for the voice/talk path.
    "gpt-audio": ModelInfo(
        provider=Provider.OPENAI,
        model_id="gpt-audio",
        display_name="GPT Audio 🔊",
        supports_audio=True,
        supports_vision=True,
        max_tokens=16384,
    ),
    "gpt-audio-mini": ModelInfo(
        provider=Provider.OPENAI,
        model_id="gpt-audio-mini",
        display_name="GPT Audio Mini 🔊",
        supports_audio=True,
        supports_vision=True,
        max_tokens=16384,
    ),
    "gpt-4o-mini": ModelInfo(
        provider=Provider.OPENAI,
        model_id="gpt-4o-mini",
        display_name="GPT-4o Mini",
        supports_audio=False,  # Use gpt-audio-mini for audio
        supports_vision=True,
        max_tokens=16384,
    ),
    "gpt-4-turbo": ModelInfo(
        provider=Provider.OPENAI,
        model_id="gpt-4-turbo",
        display_name="GPT-4 Turbo",
        supports_vision=True,
        max_tokens=4096,
    ),
}

# Default model — audio-capable so the voice/talk path works out of the box.
# (Was gpt-4o-audio-preview, which 404s on accounts created after OpenAI's
# rename to the gpt-audio family.)
DEFAULT_MODEL_ID = "gpt-audio"

# Model the session auto-switches to when it must handle audio input but the
# active model can't (see OrchestratorSession._send_audio_inner). Single source
# of truth so there's one place to change if the account's audio model changes.
AUDIO_FALLBACK_MODEL_ID = "gpt-audio"


def get_available_models() -> list[ModelInfo]:
    """Get list of all available models."""
    return list(AVAILABLE_MODELS.values())


def get_model_info(model_id: str) -> ModelInfo | None:
    """Get info for a specific model."""
    return AVAILABLE_MODELS.get(model_id)


def get_models_by_provider(provider: Provider) -> list[ModelInfo]:
    """Get all models for a specific provider."""
    return [m for m in AVAILABLE_MODELS.values() if m.provider == provider]


def get_audio_capable_models() -> list[ModelInfo]:
    """Get models that support audio input."""
    return [m for m in AVAILABLE_MODELS.values() if m.supports_audio]


def _infer_model_info(model_id: str) -> ModelInfo | None:
    """Infer provider + capabilities for a model ID not in AVAILABLE_MODELS.

    Used when the user picks a live-discovered model that this static
    registry doesn't know about. Returns None for IDs that don't match
    any known provider naming convention.
    """
    mid = model_id.lower()
    if mid.startswith("claude-"):
        return ModelInfo(
            provider=Provider.ANTHROPIC,
            model_id=model_id,
            display_name=model_id,
            supports_vision=True,
            max_tokens=8192,
        )
    if (
        mid.startswith("gpt-")
        or mid.startswith("chatgpt-")
        or mid.startswith("o1")
        or mid.startswith("o3")
        or mid.startswith("o4")
        # Qwen and other OpenAI-compatible models share the openai SDK
        # transport — route them through the same provider.
        or mid.startswith("qwen")
        or mid.startswith("glm-")
        or mid.startswith("gemini-")  # served via OpenAI-compatible endpoint
    ):
        return ModelInfo(
            provider=Provider.OPENAI,
            model_id=model_id,
            display_name=model_id,
            supports_audio="audio" in mid,
            supports_vision=True,
            max_tokens=16384,
        )
    return None


# Retired OpenAI audio model ids: renamed to the ``gpt-audio`` family and
# now 404 on Rodrigo's account.  A default naming one is skipped.
RETIRED_MODEL_IDS = frozenset({"gpt-4o-audio-preview", "gpt-4o-mini-audio-preview"})

# O-7 precedence switch — the ONE place to flip.  True: the Settings value
# (``assistant_config.default_model``) beats env ``ORCHESTRATOR_MODEL``.
# False: the env beats Settings.  Either way :data:`DEFAULT_MODEL_ID` is last.
SETTINGS_DEFAULT_MODEL_FIRST = True


def _usable_model_id(model_id: object, source: str) -> str | None:
    """Return *model_id* stripped if it is a usable orchestrator model,
    else log why (for non-empty values) and return None."""
    if not isinstance(model_id, str) or not model_id.strip():
        return None
    model_id = model_id.strip()
    if model_id in RETIRED_MODEL_IDS:
        logger.warning(
            "Ignoring retired model %r from %s (renamed to the gpt-audio family)",
            model_id, source,
        )
        return None
    if get_model_info(model_id) is None and _infer_model_info(model_id) is None:
        logger.warning("Ignoring unknown model %r from %s", model_id, source)
        return None
    return model_id


def configured_default_model() -> str | None:
    """``default_model`` from ``assistant_config.json`` (the Settings page),
    or None when the file / key is missing, the id can't be classified or
    it is a retired id (:data:`RETIRED_MODEL_IDS`).

    Read from the raw file on every call (no cached snapshot), so a change
    in Settings applies to the next orchestrator session.  Only the file's
    own value counts — not the API layer's built-in default.
    """
    path = _paths.PROJECT_ROOT / "assistant_config.json"
    try:
        data = json.loads(path.read_text())
    except (OSError, ValueError):
        return None
    model_id = data.get("default_model") if isinstance(data, dict) else None
    return _usable_model_id(model_id, "assistant_config.json default_model")


def env_default_model() -> str | None:
    """Env ``ORCHESTRATOR_MODEL``, with the same validation."""
    return _usable_model_id(os.environ.get("ORCHESTRATOR_MODEL"), "env ORCHESTRATOR_MODEL")


def resolve_default_model() -> str:
    """Model a new orchestrator session starts on (O-7).

    The first usable value of Settings / env (order set by
    :data:`SETTINGS_DEFAULT_MODEL_FIRST`), then :data:`DEFAULT_MODEL_ID`.
    """
    sources = (configured_default_model, env_default_model)
    if not SETTINGS_DEFAULT_MODEL_FIRST:
        sources = sources[::-1]
    for source in sources:
        model_id = source()
        if model_id:
            return model_id
    return DEFAULT_MODEL_ID


# ---------------------------------------------------------------------------
# Orchestrator Configuration
# ---------------------------------------------------------------------------

@dataclass(slots=True)
class OrchestratorConfig:
    """Configuration for an orchestrator agent session.

    Supports runtime model switching via set_model(). The provider is
    automatically determined from the model.

    Attributes:
        model: Current model ID (e.g., "claude-sonnet-4-5-20250929", "gpt-4o")
        max_tokens: Maximum tokens for model response
        project_dir: Base project directory
        memory_path: Path to orchestrator memory file
    """

    model: str = DEFAULT_MODEL_ID
    max_tokens: int = 8192
    project_dir: str = ""
    memory_path: str = ""

    # Runtime state (not persisted)
    _model_info: ModelInfo | None = field(default=None, repr=False)

    def __post_init__(self) -> None:
        """Initialize model info from model ID."""
        self._model_info = AVAILABLE_MODELS.get(self.model)
        if self._model_info is None:
            self._model_info = _infer_model_info(self.model) or ModelInfo(
                provider=Provider.ANTHROPIC,
                model_id=self.model,
                display_name=self.model,
            )

    @property
    def provider(self) -> Provider:
        """Get the provider for the current model."""
        return self._model_info.provider if self._model_info else Provider.ANTHROPIC

    @property
    def model_info(self) -> ModelInfo | None:
        """Get full model info for current model."""
        return self._model_info

    @property
    def supports_audio(self) -> bool:
        """Whether current model supports audio input."""
        return self._model_info.supports_audio if self._model_info else False

    def set_model(self, model_id: str) -> bool:
        """Change the current model.

        Accepts any non-empty model ID. Unknown IDs are classified by
        prefix (claude-* → Anthropic, gpt-*/o*/chatgpt-* → OpenAI) so the
        live-discovered model lists work without requiring a static entry.

        Args:
            model_id: The model identifier to switch to

        Returns:
            True if a provider could be inferred, False otherwise
        """
        if not model_id:
            return False

        info = AVAILABLE_MODELS.get(model_id)
        if info is None:
            inferred = _infer_model_info(model_id)
            if inferred is None:
                return False
            info = inferred

        self.model = model_id
        self._model_info = info
        self.max_tokens = min(self.max_tokens, info.max_tokens)
        return True

    def to_dict(self) -> dict[str, Any]:
        """Convert config to JSON-serializable dict."""
        return {
            "model": self.model,
            "provider": self.provider.value,
            "max_tokens": self.max_tokens,
            "supports_audio": self.supports_audio,
            "model_info": self._model_info.to_dict() if self._model_info else None,
        }

    @classmethod
    def load(cls) -> OrchestratorConfig:
        """Load config from Settings (``default_model``), environment
        variables and defaults."""
        project_dir = os.environ.get(
            "ORCHESTRATOR_PROJECT_DIR",
            str(Path(__file__).resolve().parent.parent),
        )

        # Use context/memory/ directly for the orchestrator memory file
        memory_path = str(get_memory_dir() / "ORCHESTRATOR_MEMORY.md")

        model = resolve_default_model()
        max_tokens = int(os.environ.get("ORCHESTRATOR_MAX_TOKENS", "8192"))

        return cls(
            model=model,
            max_tokens=max_tokens,
            project_dir=project_dir,
            memory_path=memory_path,
        )
