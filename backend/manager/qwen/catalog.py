"""Qwen Code harness catalog — models + per-run options.

Two model sources are merged:

* ``source="settings"`` — ``~/.qwen/settings.json`` ``modelProviders`` (the
  same list ``qwen --model`` validates against, and what
  ``GET /api/config/harness/qwen/models`` still serves).  Authoritative for
  name, context window, modalities.
* ``source="live"`` — DashScope's OpenAI-compatible ``GET …/models`` with
  ``DASHSCOPE_API_KEY``, filtered to chat / coding models and with dated
  snapshots hidden.  Only ids that are not already in the settings list.
  The endpoint returns bare ids, so context window / thinking / vision for
  these rows come from :func:`qwen_model_traits` (a small family table).
  A live-only id still runs: :mod:`manager.qwen.run_settings` injects a
  DashScope provider entry for it into the per-run settings file.

Options (all optional; unset = whatever the provider entry / CLI does):

``thinking``         toggle → ``generationConfig.extra_body.enable_thinking``
``thinking_budget``  number → ``generationConfig.extra_body.thinking_budget``
``effort``           select → ``generationConfig.extra_body.reasoning_effort``
                     (only the Qwen3.8 family reads it on DashScope)
``temperature``      number → ``generationConfig.samplingParams.temperature``

Which models an option applies to is decided by :func:`qwen_model_traits`
— the same function the session manager uses at run time, so the UI and
the CLI flags never disagree.
"""

from __future__ import annotations

import json
import logging
import os
import re
from dataclasses import dataclass
from typing import Any

from ..harness_catalog import (
    EFFORT,
    THINKING,
    HarnessCatalog,
    HarnessModel,
    HarnessOption,
    effort_option,
)

logger = logging.getLogger(__name__)

PROVIDER = "qwen"

THINKING_BUDGET = "thinking_budget"
TEMPERATURE = "temperature"

THINKING_BUDGET_MIN = 1
THINKING_BUDGET_MAX = 32768

DASHSCOPE_INTL_BASE_URL = "https://dashscope-intl.aliyuncs.com/compatible-mode/v1"
DASHSCOPE_ENV_KEY = "DASHSCOPE_API_KEY"
LIVE_TIMEOUT_S = 5.0

# Thinking modes.
HYBRID = "hybrid"        # thinking can be switched on/off (enable_thinking)
MANDATORY = "mandatory"  # always thinks; enable_thinking:false is not honoured
NONE = "none"            # no thinking mode


@dataclass(frozen=True)
class QwenModelTraits:
    """What we know about a DashScope model id (from its family name)."""

    thinking: str | None = None             # HYBRID / MANDATORY / NONE / None = unknown
    budget: bool = False                    # accepts extra_body.thinking_budget
    efforts: tuple[str, ...] = ()           # values for extra_body.reasoning_effort
    default_effort: str | None = None
    context_window: int | None = None
    vision: bool | None = None


# Family table, checked in order (specific → general).  Sources: Qwen Code
# 0.25.0's bundled ModelStudio / token-plan model tables (capabilities,
# contextWindowSize, modalities) and DashScope's deep-thinking doc
# (hybrid vs thinking-only, thinking_budget range).  Context windows of
# live-only rows are approximate.
_QWEN38_EFFORTS = ("low", "medium", "xhigh")
_FAMILIES: tuple[tuple[re.Pattern[str], QwenModelTraits], ...] = (
    # Qwen3.8: effort tiers instead of a budget; max can't stop thinking.
    (re.compile(r"^qwen3\.8-max", re.I),
     QwenModelTraits(MANDATORY, False, _QWEN38_EFFORTS, "xhigh", 1_000_000, True)),
    (re.compile(r"^qwen3\.8-flash", re.I),
     QwenModelTraits(None, False, _QWEN38_EFFORTS, "xhigh", 1_000_000, True)),
    # Thinking-only models.
    (re.compile(r"-thinking|^deepseek-r1|^qwq", re.I),
     QwenModelTraits(MANDATORY, True)),
    (re.compile(r"^glm-5\.3", re.I), QwenModelTraits(MANDATORY, True, context_window=202_752)),
    (re.compile(r"^kimi-k2\.7", re.I),
     QwenModelTraits(MANDATORY, True, context_window=262_144, vision=True)),
    # Coder models: no thinking mode.
    (re.compile(r"coder", re.I), QwenModelTraits(NONE, context_window=1_000_000)),
    # Hybrid Qwen3.x commercial models.
    (re.compile(r"^qwen3\.[5-7]-(plus|flash|max)", re.I),
     QwenModelTraits(HYBRID, True, context_window=1_000_000)),
    (re.compile(r"^qwen3-max", re.I), QwenModelTraits(HYBRID, True, context_window=262_144)),
    (re.compile(r"^qwen-(plus|flash|turbo)", re.I), QwenModelTraits(HYBRID, True)),
    # Open-weight Qwen3.x sizes (qwen3-32b, qwen3.6-27b, …) are hybrid.
    (re.compile(r"^qwen3(\.\d+)?-\d+(\.\d+)?b", re.I), QwenModelTraits(HYBRID, True)),
    (re.compile(r"^glm-5\.2", re.I), QwenModelTraits(HYBRID, True, context_window=1_000_000)),
    (re.compile(r"^glm-", re.I), QwenModelTraits(HYBRID, True, context_window=202_752)),
    (re.compile(r"^kimi-k2\.[56]", re.I), QwenModelTraits(HYBRID, True, context_window=262_144)),
    (re.compile(r"^deepseek-v3\.2", re.I), QwenModelTraits(HYBRID, False, context_window=131_072)),
    (re.compile(r"^deepseek-v4", re.I), QwenModelTraits(HYBRID, False, context_window=1_000_000)),
)


def qwen_model_traits(model_id: str | None) -> QwenModelTraits:
    """Traits of a model id (unknown family → all-unknown traits)."""
    if not model_id:
        return QwenModelTraits()
    for pattern, traits in _FAMILIES:
        if pattern.search(model_id):
            return traits
    return QwenModelTraits()


def option_applies(key: str, model_id: str | None, value: Any = None) -> bool:
    """Whether option *key* is meaningful for *model_id* (``HarnessOption.models``).

    Shared by the catalog (to build each option's ``models`` list) and the
    session manager (to drop an option for a model it does not apply to).
    """
    traits = qwen_model_traits(model_id)
    if key == THINKING:
        return traits.thinking == HYBRID
    if key == THINKING_BUDGET:
        return traits.budget
    if key == EFFORT:
        if not traits.efforts:
            return False
        return value is None or value in traits.efforts
    if key == TEMPERATURE:
        return True
    return False


# ── DashScope live model list ─────────────────────────────────────────

# Non-chat families (audio, image, embedding, translation, realtime, …).
_LIVE_EXCLUDE_RE = re.compile(
    r"realtime|omni|tts|asr|livetranslate|image|^wan|embedding|ocr|-mt-|^qwen-mt|s2s"
    r"|captioner|tingwu|character|audio|^qvq|-vl-|^qwen-vl|^qwen3-vl|ccai|^text-|^z-image",
    re.I,
)
# Dated snapshots: qwen-plus-2025-07-14, qwen3.8-max-0902, …-instruct-2507.
_SNAPSHOT_RE = re.compile(r"-(\d{4}-\d{2}-\d{2}|\d{4})$")


def filter_live_ids(ids: list[str]) -> list[str]:
    """Keep chat/coding model ids, drop dated snapshots and vendor-prefixed ids."""
    out: list[str] = []
    seen: set[str] = set()
    for mid in ids:
        if not isinstance(mid, str) or not mid or "/" in mid or mid in seen:
            continue
        if _LIVE_EXCLUDE_RE.search(mid) or _SNAPSHOT_RE.search(mid):
            continue
        seen.add(mid)
        out.append(mid)
    return out


def dashscope_base_url(settings: dict | None) -> str:
    """Base URL of the user's DashScope provider entries (intl by default)."""
    for entry in _iter_entries(settings):
        base = entry.get("baseUrl")
        if isinstance(base, str) and "dashscope" in base:
            return base.rstrip("/")
    return DASHSCOPE_INTL_BASE_URL


def fetch_live_model_ids(base_url: str, *, timeout: float = LIVE_TIMEOUT_S) -> list[str]:
    """``GET <base_url>/models`` → raw ids.  Raises on any failure."""
    api_key = os.environ.get(DASHSCOPE_ENV_KEY)
    if not api_key:
        raise RuntimeError(f"{DASHSCOPE_ENV_KEY} is not set")
    import httpx  # lazy — keep the registry import cheap

    resp = httpx.get(
        f"{base_url}/models",
        headers={"Authorization": f"Bearer {api_key}"},
        timeout=timeout,
    )
    resp.raise_for_status()
    data = resp.json().get("data") or []
    return [m.get("id") for m in data if isinstance(m, dict) and isinstance(m.get("id"), str)]


# ── settings.json ──────────────────────────────────────────────────────


def load_user_settings() -> dict:
    """Read ``~/.qwen/settings.json`` (``QWEN_HOME`` honoured); ``{}`` on any error."""
    from .models import _settings_path

    path = _settings_path()
    if not path.is_file():
        return {}
    try:
        with path.open() as f:
            data = json.load(f)
    except (OSError, json.JSONDecodeError) as e:
        logger.warning("Failed to read Qwen settings.json at %s: %s", path, e)
        return {}
    return data if isinstance(data, dict) else {}


def _iter_entries(settings: dict | None):
    providers = (settings or {}).get("modelProviders") or {}
    if not isinstance(providers, dict):
        return
    for entries in providers.values():
        if not isinstance(entries, list):
            continue
        for entry in entries:
            if isinstance(entry, dict) and isinstance(entry.get("id"), str) and entry["id"].strip():
                yield entry


def default_model(settings: dict | None) -> str | None:
    model = (settings or {}).get("model") or {}
    name = model.get("name") if isinstance(model, dict) else None
    return name if isinstance(name, str) and name else None


# ── catalog ────────────────────────────────────────────────────────────


def _thinking_flag(traits: QwenModelTraits) -> bool | None:
    if traits.thinking in (HYBRID, MANDATORY) or traits.efforts:
        return True
    if traits.thinking == NONE:
        return False
    return None


def _settings_row(info) -> HarnessModel:
    traits = qwen_model_traits(info.id)
    # Badge as before (enable_thinking set on the entry), widened with the
    # family table so hybrids that think by default still get the toggle.
    thinking = True if info.supports_thinking else _thinking_flag(traits)
    return HarnessModel(
        id=info.id,
        label=info.display_name,
        context_window=info.context_window or traits.context_window,
        supports_thinking=thinking,
        supports_vision=info.supports_vision,  # settings.json modalities are authoritative
        efforts=traits.efforts or (),
        default_effort=traits.default_effort,
        source="settings",
    )


def _live_row(model_id: str) -> HarnessModel:
    traits = qwen_model_traits(model_id)
    return HarnessModel(
        id=model_id,
        label=model_id,
        description="DashScope (live list; capabilities inferred from the model family)",
        context_window=traits.context_window,
        supports_thinking=_thinking_flag(traits),
        supports_vision=traits.vision,
        efforts=traits.efforts or (),
        default_effort=traits.default_effort,
        source="live",
    )


def build_catalog(
    settings: dict,
    live_ids: list[str] | None,
    *,
    warnings: tuple[str, ...] = (),
) -> HarnessCatalog:
    from .models import _parse_model_entry

    rows: list[HarnessModel] = []
    seen: set[str] = set()
    providers = settings.get("modelProviders") or {}
    if isinstance(providers, dict):
        for provider_key, entries in providers.items():
            if not isinstance(entries, list):
                continue
            for entry in entries:
                if not isinstance(entry, dict):
                    continue
                info = _parse_model_entry(entry, provider_key)
                if info is None or info.id in seen:
                    continue
                seen.add(info.id)
                rows.append(_settings_row(info))
    for mid in sorted(filter_live_ids(live_ids or [])):
        if mid in seen:
            continue
        seen.add(mid)
        rows.append(_live_row(mid))

    ids = [r.id for r in rows]

    def models_for(key: str) -> tuple[str, ...]:
        return tuple(m for m in ids if option_applies(key, m))

    options = (
        HarnessOption(
            key=THINKING,
            label="Thinking",
            kind="toggle",
            help=(
                "Sets enable_thinking on the model's provider entry for this run "
                "(extra_body.enable_thinking). Unset = what settings.json / the model "
                "default says. Offered only for hybrid models; thinking-only models "
                "(qwen3.8-max, glm-5.3, kimi-k2.7-code, …) cannot switch it off."
            ),
            models=models_for(THINKING),
        ),
        HarnessOption(
            key=THINKING_BUDGET,
            label="Thinking budget",
            kind="number",
            min=THINKING_BUDGET_MIN,
            max=THINKING_BUDGET_MAX,
            step=1024,
            help=(
                "Max reasoning tokens (extra_body.thinking_budget, 1–32768). Qwen3.x, "
                "GLM and Kimi on DashScope. Ignored when Thinking is off."
            ),
            models=models_for(THINKING_BUDGET),
        ),
        effort_option(
            _QWEN38_EFFORTS,
            default="xhigh",
            labels={"xhigh": "Extra high"},
            help=(
                "extra_body.reasoning_effort. Only the Qwen3.8 family reads it on "
                "DashScope; other models ignore effort, so it is hidden for them."
            ),
            models=models_for(EFFORT),
        ),
        HarnessOption(
            key=TEMPERATURE,
            label="Temperature",
            kind="number",
            min=0,
            max=2,
            step=0.1,
            help="Sampling temperature (generationConfig.samplingParams.temperature).",
        ),
    )
    return HarnessCatalog(
        provider=PROVIDER,
        models=tuple(rows),
        options=options,
        default_model=default_model(settings),
        allow_custom_model=True,
        warnings=warnings,
    )


def load_qwen_catalog() -> HarnessCatalog:
    """``HarnessSpec.catalog_loader`` for Qwen Code.  Never raises for an
    unreachable DashScope: returns the settings.json rows plus a warning."""
    settings = load_user_settings()
    try:
        live = fetch_live_model_ids(dashscope_base_url(settings))
    except Exception as e:  # noqa: BLE001 — upstream failure is a warning, not an error
        logger.warning("DashScope model list unavailable: %s", e)
        return build_catalog(
            settings,
            None,
            warnings=(f"Live DashScope model list unavailable ({e}); showing settings.json models only.",),
        )
    return build_catalog(settings, live)


__all__ = [
    "PROVIDER",
    "THINKING_BUDGET",
    "TEMPERATURE",
    "HYBRID",
    "MANDATORY",
    "NONE",
    "QwenModelTraits",
    "qwen_model_traits",
    "option_applies",
    "filter_live_ids",
    "dashscope_base_url",
    "fetch_live_model_ids",
    "load_user_settings",
    "default_model",
    "build_catalog",
    "load_qwen_catalog",
]
