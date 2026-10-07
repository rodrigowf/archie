"""Claude Code harness catalog — models + options for the settings UIs.

Models come from three places, best first:

1. **Live**: ``GET https://api.anthropic.com/v1/models`` with the same
   OAuth token the CLI uses (``CLAUDE_CODE_OAUTH_TOKEN``, sent as a Bearer
   token with the ``oauth-2025-04-20`` beta header) or, failing that,
   ``ANTHROPIC_API_KEY``.  Each entry carries ``capabilities.effort`` and
   ``capabilities.thinking.types``, which is what gates the effort and
   thinking options per model.
2. **Built-in**: the same data captured from that endpoint on 2026-10-07
   (CLI 2.1.292), used when the request fails or no credential is set —
   the catalog then carries a ``warnings`` entry.
3. **Aliases** (``default``, ``sonnet``, ``opus``, ``fable``, ``haiku``):
   what the CLI's own model picker offers.  A connected session records
   the CLI's list (``server_info["models"]``) through
   :func:`record_cli_models`; until one has, the alias → model map shipped
   with the pinned CLI is used.

:func:`claude_model_caps` answers "what does this model accept?" without
any network access, so the session manager can drop impossible option
combinations (e.g. ``--thinking disabled`` on an adaptive-only model)
before they reach the CLI.
"""

from __future__ import annotations

import logging
import os
import re
import threading
from dataclasses import dataclass
from typing import Any

from ..harness_catalog import (
    EFFORT,
    THINKING,
    Choice,
    HarnessCatalog,
    HarnessModel,
    HarnessOption,
    effort_option,
)

logger = logging.getLogger(__name__)

PROVIDER = "claude"

MODELS_URL = "https://api.anthropic.com/v1/models"
OAUTH_BETA = "oauth-2025-04-20"
ANTHROPIC_VERSION = "2023-06-01"
HTTP_TIMEOUT_S = 4.0

# Harness-specific option keys (``effort`` / ``thinking`` are shared).
THINKING_BUDGET = "thinking_budget"
FALLBACK_MODEL = "fallback_model"
TODO_TOOLS = "todo_tools"

EFFORT_LEVELS: tuple[str, ...] = ("low", "medium", "high", "xhigh", "max")
THINKING_TYPES: tuple[str, ...] = ("adaptive", "enabled", "disabled")
# Budget used when thinking=enabled is chosen without a thinking_budget.
DEFAULT_THINKING_BUDGET = 16_000
THINKING_BUDGET_MIN = 1_024
THINKING_BUDGET_MAX = 128_000


@dataclass(frozen=True)
class ClaudeModelCaps:
    """What one Claude model accepts.

    ``efforts`` = the ``--effort`` levels it supports (``()`` = none).
    ``thinking_types`` ⊆ {"adaptive", "enabled", "disabled"}; a model whose
    only type is ``adaptive`` always thinks and cannot be switched off.
    """

    id: str
    label: str
    efforts: tuple[str, ...]
    thinking_types: frozenset[str]
    context_window: int | None = None
    supports_vision: bool | None = True

    @property
    def adaptive_only(self) -> bool:
        return self.thinking_types == frozenset({"adaptive"})


def _caps(id_: str, label: str, efforts: tuple[str, ...], thinking: str, ctx: int) -> ClaudeModelCaps:
    return ClaudeModelCaps(id_, label, efforts, frozenset(thinking.split(",")), ctx)


_ALL_EFFORTS = EFFORT_LEVELS
_NO_XHIGH = ("low", "medium", "high", "max")

# Snapshot of GET /v1/models on 2026-10-07 (newest first, as the API lists them).
_BUILTIN_MODELS: tuple[ClaudeModelCaps, ...] = (
    _caps("claude-haiku-5-5", "Claude Haiku 5.5", _ALL_EFFORTS, "adaptive,disabled", 1_000_000),
    _caps("claude-sonnet-5-5", "Claude Sonnet 5.5", _ALL_EFFORTS, "adaptive", 1_000_000),
    _caps("claude-opus-5-5", "Claude Opus 5.5", _ALL_EFFORTS, "adaptive", 1_000_000),
    _caps("claude-fable-5-1", "Claude Fable 5.1", _ALL_EFFORTS, "adaptive", 1_000_000),
    _caps("claude-opus-5", "Claude Opus 5", _ALL_EFFORTS, "adaptive,disabled", 1_000_000),
    _caps("claude-sonnet-5", "Claude Sonnet 5", _ALL_EFFORTS, "adaptive,disabled", 1_000_000),
    _caps("claude-fable-5", "Claude Fable 5", _ALL_EFFORTS, "adaptive", 1_000_000),
    _caps("claude-opus-4-8", "Claude Opus 4.8", _ALL_EFFORTS, "adaptive,disabled", 1_000_000),
    _caps("claude-opus-4-7", "Claude Opus 4.7", _ALL_EFFORTS, "adaptive,disabled", 1_000_000),
    _caps("claude-sonnet-4-6", "Claude Sonnet 4.6", _NO_XHIGH, "enabled,adaptive,disabled", 1_000_000),
    _caps("claude-opus-4-6", "Claude Opus 4.6", _NO_XHIGH, "enabled,adaptive,disabled", 1_000_000),
    _caps("claude-opus-4-5-20251101", "Claude Opus 4.5", ("low", "medium", "high"), "enabled,disabled", 200_000),
    _caps("claude-haiku-4-5-20251001", "Claude Haiku 4.5", (), "enabled,disabled", 200_000),
    _caps("claude-sonnet-4-5-20250929", "Claude Sonnet 4.5", (), "enabled,disabled", 200_000),
)

# Family rules for ids not in either list (dated variants, ``[1m]``
# suffixes already stripped).  Checked in order, specific → general.
_FAMILY_RULES: tuple[tuple[re.Pattern[str], str], ...] = tuple(
    (re.compile(p), ref) for p, ref in (
        (r"^claude-opus-5-5(?!\d)", "claude-opus-5-5"),
        (r"^claude-sonnet-5-5(?!\d)", "claude-sonnet-5-5"),
        (r"^claude-haiku-5(?!\d)", "claude-haiku-5-5"),
        (r"^claude-fable-5(?!\d)", "claude-fable-5-1"),
        (r"^claude-opus-5(?!\d)", "claude-opus-5"),
        (r"^claude-sonnet-5(?!\d)", "claude-sonnet-5"),
        (r"^claude-opus-4-[78](?!\d)", "claude-opus-4-8"),
        (r"^claude-(opus|sonnet)-4-6(?!\d)", "claude-opus-4-6"),
        (r"^claude-opus-4-5(?!\d)", "claude-opus-4-5-20251101"),
        (r"^claude-(haiku|sonnet)-4-5(?!\d)", "claude-haiku-4-5-20251001"),
    )
)

# Aliases the pinned CLI (2.1.292) accepts for ``--model`` and what they
# resolve to on a Claude subscription.  Replaced by the CLI's own list once
# a session has connected (record_cli_models).
_BUILTIN_ALIASES: tuple[tuple[str, str, str], ...] = (
    ("default", "claude-sonnet-5-5", "Default (recommended)"),
    ("sonnet", "claude-sonnet-5-5", "Sonnet"),
    ("opus", "claude-opus-5-5", "Opus"),
    ("fable", "claude-fable-5-1", "Fable"),
    ("haiku", "claude-haiku-4-5-20251001", "Haiku"),
)


@dataclass(frozen=True)
class _Alias:
    value: str
    resolved: str
    label: str
    description: str | None = None
    efforts: tuple[str, ...] | None = None  # from the CLI, when it says
    adaptive: bool | None = None


_lock = threading.Lock()
_live_caps: dict[str, ClaudeModelCaps] = {}
_cli_aliases: tuple[_Alias, ...] | None = None


def _builtin_aliases() -> tuple[_Alias, ...]:
    return tuple(_Alias(v, r, label) for v, r, label in _BUILTIN_ALIASES)


def record_cli_models(models: Any) -> None:
    """Remember the CLI's model picker (``server_info["models"]``).

    Called by the session manager after ``connect()``.  Malformed input is
    ignored — the built-in alias map stays in place.
    """
    global _cli_aliases
    if not isinstance(models, list):
        return
    out: list[_Alias] = []
    for m in models:
        if not isinstance(m, dict):
            continue
        value = m.get("value")
        if not isinstance(value, str) or not value:
            continue
        levels = m.get("supportedEffortLevels")
        # No list → unknown here; the resolved model's caps decide.
        efforts = tuple(x for x in levels if isinstance(x, str)) if isinstance(levels, list) else None
        out.append(_Alias(
            value=value,
            resolved=str(m.get("resolvedModel") or value),
            label=str(m.get("displayName") or value),
            description=m.get("description") if isinstance(m.get("description"), str) else None,
            efforts=efforts,
            adaptive=m.get("supportsAdaptiveThinking") if isinstance(m.get("supportsAdaptiveThinking"), bool) else None,
        ))
    if out:
        with _lock:
            _cli_aliases = tuple(out)


def _aliases() -> tuple[_Alias, ...]:
    with _lock:
        return _cli_aliases or _builtin_aliases()


def _strip_suffix(model: str) -> str:
    return re.sub(r"\[[^\]]*\]$", "", model.strip())


def claude_model_caps(model: str | None) -> ClaudeModelCaps | None:
    """Capabilities of *model* (id or alias; ``None`` = the CLI default).

    Never touches the network: uses the last live list, then the built-in
    snapshot, then family rules.  ``None`` = unknown model (pass options
    through unchanged and let the CLI decide).
    """
    name = _strip_suffix(model) if model else "default"
    alias = next((a for a in _aliases() if a.value == name), None)
    target = alias.resolved if alias else name
    caps = _lookup(target)
    if caps is None or alias is None:
        return caps
    # The CLI's own word on effort levels wins for an alias.
    if alias.efforts is not None:
        caps = ClaudeModelCaps(caps.id, caps.label, alias.efforts, caps.thinking_types,
                               caps.context_window, caps.supports_vision)
    return caps


def _lookup(model_id: str) -> ClaudeModelCaps | None:
    with _lock:
        hit = _live_caps.get(model_id)
    if hit is not None:
        return hit
    for caps in _BUILTIN_MODELS:
        if caps.id == model_id:
            return caps
    for pattern, ref in _FAMILY_RULES:
        if pattern.search(model_id):
            base = next(c for c in _BUILTIN_MODELS if c.id == ref)
            return ClaudeModelCaps(model_id, model_id, base.efforts, base.thinking_types,
                                   base.context_window, base.supports_vision)
    return None


# ── live fetch ────────────────────────────────────────────────────────


def _parse_live_model(m: dict) -> ClaudeModelCaps | None:
    mid = m.get("id")
    if not isinstance(mid, str) or not mid.startswith("claude-"):
        return None
    caps = m.get("capabilities") if isinstance(m.get("capabilities"), dict) else {}
    effort = caps.get("effort") if isinstance(caps.get("effort"), dict) else {}
    efforts = tuple(
        lvl for lvl in EFFORT_LEVELS
        if isinstance(effort.get(lvl), dict) and effort[lvl].get("supported")
    )
    thinking = caps.get("thinking") if isinstance(caps.get("thinking"), dict) else {}
    types = thinking.get("types") if isinstance(thinking.get("types"), dict) else {}
    ttypes = frozenset(
        t for t in THINKING_TYPES
        if isinstance(types.get(t), dict) and types[t].get("supported")
    )
    if not ttypes:
        ttypes = frozenset({"enabled", "disabled"}) if thinking.get("supported") else frozenset({"disabled"})
    image = caps.get("image_input") if isinstance(caps.get("image_input"), dict) else {}
    ctx = m.get("max_input_tokens")
    return ClaudeModelCaps(
        id=mid,
        label=str(m.get("display_name") or mid),
        efforts=efforts,
        thinking_types=ttypes,
        context_window=ctx if isinstance(ctx, int) and ctx > 0 else None,
        supports_vision=bool(image.get("supported")) if image else None,
    )


def _fetch_live_models() -> tuple[list[ClaudeModelCaps] | None, str | None]:
    """Return ``(models, None)`` or ``(None, reason)``.  Never raises."""
    token = os.environ.get("CLAUDE_CODE_OAUTH_TOKEN", "").strip()
    api_key = os.environ.get("ANTHROPIC_API_KEY", "").strip()
    headers = {"anthropic-version": ANTHROPIC_VERSION}
    if token:
        headers["Authorization"] = f"Bearer {token}"
        headers["anthropic-beta"] = OAUTH_BETA
    elif api_key:
        headers["x-api-key"] = api_key
    else:
        return None, "no CLAUDE_CODE_OAUTH_TOKEN or ANTHROPIC_API_KEY in the environment"
    try:
        import httpx

        resp = httpx.get(MODELS_URL, headers=headers, params={"limit": 100}, timeout=HTTP_TIMEOUT_S)
        resp.raise_for_status()
        data = resp.json().get("data", [])
    except Exception as e:  # noqa: BLE001 — any upstream failure → builtin list
        status = getattr(getattr(e, "response", None), "status_code", None)
        reason = f"HTTP {status}" if status else (str(e) or type(e).__name__)
        return None, reason
    models = [c for c in (_parse_live_model(m) for m in data if isinstance(m, dict)) if c]
    if not models:
        return None, "the models API returned no Claude models"
    return models, None


# ── catalog ───────────────────────────────────────────────────────────


def _model_row(caps: ClaudeModelCaps, source: str) -> HarnessModel:
    return HarnessModel(
        id=caps.id,
        label=caps.label,
        context_window=caps.context_window,
        supports_thinking=bool(caps.thinking_types - {"disabled"}),
        supports_vision=caps.supports_vision,
        efforts=caps.efforts,
        source=source,
    )


def _alias_row(alias: _Alias, source: str) -> HarnessModel | None:
    caps = claude_model_caps(alias.value)
    resolved_label = caps.label if caps else alias.resolved
    desc = alias.description or f"Alias → {resolved_label}"
    if alias.description and caps:
        desc = f"{alias.description} (→ {caps.id})"
    return HarnessModel(
        id=alias.value,
        label=alias.label if alias.value != "default" else "Default",
        description=desc,
        context_window=caps.context_window if caps else None,
        supports_thinking=bool(caps.thinking_types - {"disabled"}) if caps else None,
        supports_vision=caps.supports_vision if caps else None,
        efforts=caps.efforts if caps else alias.efforts,
        source=source,
    )


def build_catalog(
    full: list[ClaudeModelCaps],
    *,
    full_source: str,
    warnings: tuple[str, ...] = (),
) -> HarnessCatalog:
    with _lock:
        alias_source = "cli" if _cli_aliases else "builtin"
    alias_rows = [r for r in (_alias_row(a, alias_source) for a in _aliases()) if r]
    rows = alias_rows + [_model_row(c, full_source) for c in full]
    ids = tuple(r.id for r in rows)

    def caps_of(mid: str) -> ClaudeModelCaps | None:
        return claude_model_caps(mid)

    # thinking is only offered where there is a real choice: adaptive-only
    # models (Opus 5.5, Sonnet 5.5, Fable 5.x) always think and cannot be
    # switched off, so the option is hidden for them.
    thinking_models = tuple(m for m in ids if (c := caps_of(m)) and len(c.thinking_types) > 1)
    budget_models = tuple(m for m in ids if (c := caps_of(m)) and "enabled" in c.thinking_types)

    default_caps = claude_model_caps(None)
    options = (
        effort_option(
            EFFORT_LEVELS,
            labels={"xhigh": "Extra high", "max": "Max"},
            help=(
                "--effort. Each model lists the levels it accepts (Claude 4.6 has no "
                "xhigh; Haiku/Sonnet 4.5 have no effort control). An unsupported level "
                "is lowered to the nearest one the model accepts. The CLI turns "
                "xhigh/max into high when thinking is disabled."
            ),
        ),
        HarnessOption(
            key=THINKING,
            label="Thinking",
            choices=(
                Choice("adaptive", "Adaptive", "The model decides when and how much to think (4.6 and newer)."),
                Choice("enabled", "Fixed budget",
                       "Think up to 'Thinking budget' tokens (Claude 4.5/4.6; newer models use adaptive instead)."),
                Choice("disabled", "Off", "No extended thinking."),
            ),
            help=(
                "--thinking. Hidden for models that always think adaptively (Opus 5.5, "
                "Sonnet 5.5, Fable 5.x). A mode the model lacks is mapped to the closest "
                "one it has. Thinking text is always requested as summaries."
            ),
            models=thinking_models,
        ),
        HarnessOption(
            key=THINKING_BUDGET,
            label="Thinking budget",
            kind="number",
            default=DEFAULT_THINKING_BUDGET,
            min=THINKING_BUDGET_MIN,
            max=THINKING_BUDGET_MAX,
            step=1024,
            help="Max thinking tokens (--max-thinking-tokens). Only used with Thinking = Fixed budget.",
            models=budget_models,
        ),
        HarnessOption(
            key=FALLBACK_MODEL,
            label="Fallback model",
            choices=tuple(Choice(r.id, r.label) for r in rows),
            help="--fallback-model: used when the main model is overloaded. Ignored if it equals the main model.",
        ),
        HarnessOption(
            key=TODO_TOOLS,
            label="Checklist tools",
            kind="toggle",
            default=True,
            help=(
                "Offer the TodoWrite/Task checklist tools on every model "
                "(CLAUDE_CODE_ENABLE_TODO_TOOLS=1). Claude Code hides them on 4.8 / 5.x "
                "models unless this is on; Archie's chat renders them as checklist cards."
            ),
        ),
    )
    return HarnessCatalog(
        provider=PROVIDER,
        models=tuple(rows),
        options=options,
        default_model=default_caps.id if default_caps else None,
        allow_custom_model=True,
        warnings=warnings,
    )


def load_claude_catalog() -> HarnessCatalog:
    """``HarnessSpec.catalog_loader`` for Claude Code.  Never raises for
    an unreachable upstream: falls back to the built-in list + a warning."""
    live, err = _fetch_live_models()
    if live:
        with _lock:
            _live_caps.clear()
            _live_caps.update({c.id: c for c in live})
        return build_catalog(live, full_source="live")
    logger.warning("Claude models API unavailable (%s); using the built-in list", err)
    return build_catalog(
        list(_BUILTIN_MODELS),
        full_source="builtin",
        warnings=(f"Live Claude model list unavailable ({err}); showing the built-in list.",),
    )


def _reset_for_tests() -> None:
    global _cli_aliases
    with _lock:
        _live_caps.clear()
        _cli_aliases = None


__all__ = [
    "ClaudeModelCaps",
    "DEFAULT_THINKING_BUDGET",
    "EFFORT",
    "EFFORT_LEVELS",
    "FALLBACK_MODEL",
    "THINKING",
    "THINKING_BUDGET",
    "TODO_TOOLS",
    "build_catalog",
    "claude_model_caps",
    "load_claude_catalog",
    "record_cli_models",
]
