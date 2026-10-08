"""Gemini CLI harness catalog — models + options for the settings UIs.

Models
------

1. **Built-in**: the aliases and concrete ids the pinned CLI (0.63) knows
   (``DEFAULT_MODEL_CONFIGS`` / ``VALID_GEMINI_MODELS`` in its bundle).
   ``auto`` lets the CLI's router pick Pro or Flash per turn; with an API
   key ``auto``/``pro`` resolve to ``gemini-3.1-pro-preview``, ``flash`` to
   ``gemini-3-flash-preview`` and ``flash-lite`` to ``gemini-3.1-flash-lite``.
   ``gemini-3-pro-preview`` is left out: AI Studio no longer serves it.
2. **Live**: ``GET generativelanguage.googleapis.com/v1beta/models`` with
   ``GEMINI_API_KEY`` (free call, ≤ 5 s), filtered to chat models
   (``generateContent``; no TTS / image / audio / embedding / … variants).
   Rows present there are tagged ``source="live"``; live-only rows are
   appended.  Without a key, or when the call fails, the built-in list is
   served with a ``warnings`` entry.

Options (keys are harness-specific; values reach the CLI through env vars
read by the workspace settings — see :mod:`.workspace_settings`)
---------------------------------------------------------------------

``thinking_level``  Gemini 3 models + the aliases: ``minimal`` / ``low`` /
                    ``medium`` / ``high`` (the CLI's own default is
                    ``high``).  A level a model does not accept is clamped
                    to the nearest one it does (:func:`clamp_thinking_level`).
``thinking_budget`` Gemini 2.5 models: thinking tokens per request,
                    ``-1`` = dynamic, ``0`` = off (Flash only; 2.5 Pro's
                    minimum is 128).  Clamped per model
                    (:func:`clamp_thinking_budget`).  CLI default 8192.
``approval_mode``   ``--approval-mode``: ``yolo`` (Archie's default — every
                    tool runs), ``auto_edit`` (edits run, shell denied),
                    ``plan`` (read-only planning), ``default`` (headless:
                    anything that would ask is denied → read-only).
"""

from __future__ import annotations

import logging
import os
import re
from dataclasses import dataclass

from ..harness_catalog import Choice, HarnessCatalog, HarnessModel, HarnessOption

logger = logging.getLogger(__name__)

PROVIDER = "gemini"
MODELS_URL = "https://generativelanguage.googleapis.com/v1beta/models"
HTTP_TIMEOUT_S = 5.0

# Option keys.
THINKING_LEVEL = "thinking_level"
THINKING_BUDGET = "thinking_budget"
APPROVAL_MODE = "approval_mode"

THINKING_LEVELS: tuple[str, ...] = ("minimal", "low", "medium", "high")
APPROVAL_MODES: tuple[str, ...] = ("yolo", "auto_edit", "plan", "default")
DEFAULT_APPROVAL_MODE = "yolo"
THINKING_BUDGET_MIN = -1
THINKING_BUDGET_MAX = 32768
DEFAULT_THINKING_BUDGET = 8192  # the CLI's DEFAULT_THINKING_MODE

ALIASES: tuple[str, ...] = ("auto", "pro", "flash", "flash-lite")

_CTX_1M = 1_048_576


@dataclass(frozen=True)
class _Builtin:
    id: str
    label: str
    description: str | None = None
    context_window: int | None = _CTX_1M


# Mirrored from the CLI 0.63 bundle (aliases first, then concrete ids).
BUILTIN_MODELS: tuple[_Builtin, ...] = (
    _Builtin("auto", "Auto", "CLI router picks Gemini 3.1 Pro or a Flash model per turn", None),
    _Builtin("pro", "Pro (alias)", "Resolves to gemini-3.1-pro-preview", None),
    _Builtin("flash", "Flash (alias)", "Resolves to gemini-3-flash-preview", None),
    _Builtin("flash-lite", "Flash-Lite (alias)", "Resolves to gemini-3.1-flash-lite", None),
    _Builtin("gemini-3.1-pro-preview", "Gemini 3.1 Pro Preview"),
    _Builtin("gemini-3-flash-preview", "Gemini 3 Flash Preview"),
    _Builtin("gemini-3.8-flash", "Gemini 3.8 Flash"),
    _Builtin("gemini-3.5-flash", "Gemini 3.5 Flash"),
    _Builtin("gemini-3.5-flash-lite", "Gemini 3.5 Flash-Lite"),
    _Builtin("gemini-3.1-flash-lite", "Gemini 3.1 Flash-Lite"),
    _Builtin("gemini-2.5-pro", "Gemini 2.5 Pro", "May be unavailable to newer API keys"),
    _Builtin("gemini-2.5-flash", "Gemini 2.5 Flash"),
    _Builtin("gemini-2.5-flash-lite", "Gemini 2.5 Flash-Lite", "May be unavailable to newer API keys"),
    _Builtin("gemma-4-31b-it", "Gemma 4 31B IT", None, 262_144),
    _Builtin("gemma-4-26b-a4b-it", "Gemma 4 26B A4B IT", None, 262_144),
)

# Live rows we never offer: not chat models, or not usable by the CLI.
_EXCLUDE_RE = re.compile(
    r"(tts|image|banana|transcribe|embedding|robotics|computer-use|omni|"
    r"-live|live-|native-audio|customtools|lyria|veo|aqa|antigravity|deep-research)",
)


# ── Model families ────────────────────────────────────────────────────


def model_family(model: str | None) -> str | None:
    """``"alias"`` (incl. no model = CLI default ``auto``), ``"3"``, ``"2.5"`` or None."""
    if not model or model in ALIASES or model.startswith("auto-"):
        return "alias"
    if model.startswith("gemini-2.5"):
        return "2.5"
    if model.startswith("gemini-3") or model.startswith("gemma-4"):
        return "3"
    return None


def thinking_levels_for(model: str | None) -> tuple[str, ...] | None:
    """Levels *model* accepts (Gemini API thinking docs); None = unknown/any."""
    m = model or "auto"
    if m in ("auto", "pro") or m.startswith("auto-"):
        return ("low", "medium", "high")  # 3.1 Pro (router may pick Flash: also fine)
    if m in ("flash", "flash-lite"):
        return THINKING_LEVELS
    if m.startswith("gemini-3-pro"):
        return ("low", "high")
    if m.startswith("gemini-3.1-pro"):
        return ("low", "medium", "high")
    if re.match(r"gemini-3\.[78]-flash", m):
        return ("low", "medium", "high")
    if m.startswith("gemini-3"):
        return THINKING_LEVELS
    return None


def clamp_thinking_level(model: str | None, level: str) -> str:
    """Return *level*, or the nearest level *model* accepts (ties → more thinking)."""
    allowed = thinking_levels_for(model)
    if not allowed or level in allowed or level not in THINKING_LEVELS:
        return level
    i = THINKING_LEVELS.index(level)
    for j in list(range(i + 1, len(THINKING_LEVELS))) + list(range(i - 1, -1, -1)):
        if THINKING_LEVELS[j] in allowed:
            return THINKING_LEVELS[j]
    return level


def clamp_thinking_budget(model: str | None, budget: int) -> int:
    """Clamp *budget* into what *model* accepts (``-1`` = dynamic always ok)."""
    budget = int(budget)
    if budget == -1:
        return -1
    m = model or ""
    if m.startswith("gemini-2.5-pro"):
        lo, hi, zero_ok = 128, 32768, False
    elif m.startswith("gemini-2.5-flash-lite"):
        lo, hi, zero_ok = 512, 24576, True
    elif m.startswith("gemini-2.5-flash"):
        lo, hi, zero_ok = 1, 24576, True
    else:
        lo, hi, zero_ok = 0, THINKING_BUDGET_MAX, True
    if budget <= 0:
        return 0 if zero_ok else lo
    return max(lo, min(hi, budget))


# ── Live models.list ──────────────────────────────────────────────────


def _fetch_live_models() -> tuple[list[dict] | None, str | None]:
    """Return ``(models, warning)``; ``models`` is None when unavailable."""
    key = os.environ.get("GEMINI_API_KEY")
    if not key:
        return None, (
            "GEMINI_API_KEY is not set — Gemini CLI needs it since Google retired "
            "personal-account (oauth-personal) access on 2026-06-18; showing the "
            "CLI's built-in model list."
        )
    try:
        import httpx

        out: list[dict] = []
        page_token: str | None = None
        with httpx.Client(timeout=HTTP_TIMEOUT_S) as client:
            for _ in range(5):
                params = {"pageSize": "1000"}
                if page_token:
                    params["pageToken"] = page_token
                resp = client.get(MODELS_URL, params=params, headers={"x-goog-api-key": key})
                if resp.status_code != 200:
                    return None, f"Gemini models.list returned HTTP {resp.status_code}; showing the built-in list."
                data = resp.json()
                out.extend(m for m in data.get("models", []) if isinstance(m, dict))
                page_token = data.get("nextPageToken")
                if not page_token:
                    break
        return out, None
    except Exception as e:  # noqa: BLE001 — unreachable upstream must not raise
        logger.info("Gemini models.list failed: %s", e)
        return None, f"Gemini models.list unavailable ({type(e).__name__}); showing the built-in list."


def _live_chat_models(raw: list[dict]) -> dict[str, dict]:
    out: dict[str, dict] = {}
    for m in raw:
        if "generateContent" not in (m.get("supportedGenerationMethods") or []):
            continue
        name = str(m.get("name") or "")
        mid = name.split("/", 1)[1] if "/" in name else name
        if not mid or not (mid.startswith("gemini-") or mid.startswith("gemma-")):
            continue
        if _EXCLUDE_RE.search(mid):
            continue
        out[mid] = m
    return out


# ── Catalog ───────────────────────────────────────────────────────────


def _supports_thinking(mid: str) -> bool | None:
    fam = model_family(mid)
    return True if fam in ("3", "2.5", "alias") else None


def build_catalog(live: dict[str, dict] | None, warnings: tuple[str, ...] = ()) -> HarnessCatalog:
    rows: list[HarnessModel] = []
    seen: set[str] = set()
    missing: list[str] = []
    for b in BUILTIN_MODELS:
        lm = (live or {}).get(b.id)
        if lm is not None:
            rows.append(HarnessModel(
                id=b.id,
                label=str(lm.get("displayName") or b.label),
                description=b.description,
                context_window=lm.get("inputTokenLimit") or b.context_window,
                supports_thinking=_supports_thinking(b.id),
                supports_vision=True,
                source="live",
            ))
        else:
            if live is not None and b.id not in ALIASES:
                missing.append(b.id)
            rows.append(HarnessModel(
                id=b.id,
                label=b.label,
                description=b.description,
                context_window=b.context_window,
                supports_thinking=_supports_thinking(b.id),
                supports_vision=True if b.id not in ALIASES else None,
                source="builtin",
            ))
        seen.add(b.id)
    for mid in sorted(live or {}, reverse=True):
        if mid in seen:
            continue
        lm = live[mid]  # type: ignore[index]
        rows.append(HarnessModel(
            id=mid,
            label=str(lm.get("displayName") or mid),
            description=(str(lm.get("description"))[:200] or None) if lm.get("description") else None,
            context_window=lm.get("inputTokenLimit"),
            supports_thinking=_supports_thinking(mid),
            supports_vision=True,
            source="live",
        ))
    warns = list(warnings)
    if missing:
        warns.append(
            "Not offered by models.list for this API key: " + ", ".join(missing)
            + " (the CLI still accepts the id)."
        )

    ids = [r.id for r in rows]
    level_models = tuple(i for i in ids if model_family(i) in ("3", "alias"))
    budget_models = tuple(i for i in ids if model_family(i) == "2.5")
    options = (
        HarnessOption(
            key=THINKING_LEVEL,
            label="Thinking level",
            choices=(
                Choice("minimal", "Minimal", "Least thinking (Flash / Flash-Lite only)"),
                Choice("low", "Low"),
                Choice("medium", "Medium"),
                Choice("high", "High", "The CLI's default"),
            ),
            default="high",
            help=(
                "Gemini 3 thinkingLevel. A level a model does not accept is clamped to the "
                "nearest one it does (3 Pro: low/high; 3.1 Pro, 3.7/3.8 Flash: low–high)."
            ),
            models=level_models,
            ordered=True,
        ),
        HarnessOption(
            key=THINKING_BUDGET,
            label="Thinking budget (tokens)",
            kind="number",
            default=DEFAULT_THINKING_BUDGET,
            help=(
                "Gemini 2.5 thinkingBudget: -1 = dynamic, 0 = off (Flash only; 2.5 Pro "
                "minimum 128). Clamped to the model's range."
            ),
            models=budget_models,
            min=THINKING_BUDGET_MIN,
            max=THINKING_BUDGET_MAX,
            step=1,
            unit="tokens",
            scale="log",
            presets=(Choice("-1", "Dynamic", "The model decides"), Choice("0", "Off", "Flash models only")),
            custom_min=128,
        ),
        HarnessOption(
            key=APPROVAL_MODE,
            label="Tool approval",
            choices=(
                Choice("yolo", "Run every tool", "Archie's default"),
                Choice("auto_edit", "Edits only", "File edits run; shell and other tools are denied"),
                Choice("plan", "Plan (read-only)", "Read-only planning mode"),
                Choice("default", "Read-only", "Anything that would need approval is denied"),
            ),
            default=DEFAULT_APPROVAL_MODE,
            help="--approval-mode. Headless runs cannot ask, so 'ask' decisions become 'deny'.",
            control="segmented",
        ),
    )
    return HarnessCatalog(
        provider=PROVIDER,
        models=tuple(rows),
        options=options,
        default_model="auto",
        allow_custom_model=True,
        warnings=tuple(warns),
    )


def load_gemini_catalog() -> HarnessCatalog:
    """``HarnessSpec.catalog_loader`` — never raises for an unreachable upstream."""
    raw, warning = _fetch_live_models()
    live = _live_chat_models(raw) if raw is not None else None
    return build_catalog(live, (warning,) if warning else ())


__all__ = [
    "PROVIDER",
    "THINKING_LEVEL",
    "THINKING_BUDGET",
    "APPROVAL_MODE",
    "THINKING_LEVELS",
    "APPROVAL_MODES",
    "BUILTIN_MODELS",
    "model_family",
    "thinking_levels_for",
    "clamp_thinking_level",
    "clamp_thinking_budget",
    "build_catalog",
    "load_gemini_catalog",
]
