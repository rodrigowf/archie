"""Model Studio harness catalog — models + options for the settings UIs.

The ``modelstudio`` harness runs the bundled Claude Code CLI against
Alibaba Model Studio's Anthropic-compatible endpoint, so its models are
DashScope ids (``glm-5.1``, ``deepseek-v4-pro``, ``qwen3.6-plus`` …), never
``claude-*``.

Models come from DashScope's OpenAI-compatible ``GET …/models`` (the same
``DASHSCOPE_API_KEY``; the Anthropic endpoint has no models route),
filtered to the chat/coding families the Anthropic endpoint serves —
verified 2026-10-07 with one tiny request each: ``glm-*``, ``deepseek-*``,
``kimi-*``, ``qwen3*`` (incl. coder) and ``qwq``.  When the list cannot
be fetched the built-in list is shown with a ``warnings`` entry.

Options (all optional; unset = whatever the CLI/endpoint does):

``thinking``         toggle → ``CLAUDE_CODE_EXTRA_BODY`` ``thinking``
                     ``{"type": "enabled", "budget_tokens": N}`` / ``{"type":
                     "disabled"}``.  Left unset the CLI sends
                     ``{"type": "adaptive"}`` (it treats unknown models as
                     adaptive-capable) and the endpoint decides.
``thinking_budget``  number → ``budget_tokens`` when thinking is on.
``todo_tools``       same as the claude harness (``CLAUDE_CODE_ENABLE_TODO_TOOLS``).

There is no ``effort`` option: the CLI always sends
``output_config.effort`` but Model Studio does not document it for these
models.  There is no ``fallback_model`` either — the CLI's fallback list
is Anthropic models, which this endpoint does not serve.

Per-model traits (context window, thinking mode) reuse
:func:`manager.qwen.catalog.qwen_model_traits` — the same DashScope
family table the Qwen harness uses.
"""

from __future__ import annotations

import logging
import os
import re

from ..harness_catalog import (
    THINKING,
    HarnessCatalog,
    HarnessModel,
    HarnessOption,
)

logger = logging.getLogger(__name__)

PROVIDER = "modelstudio"

# Alibaba Model Studio (international) — Anthropic Messages API.
ANTHROPIC_BASE_URL = "https://dashscope-intl.aliyuncs.com/apps/anthropic"
# The model list lives on the OpenAI-compatible endpoint (same key).
MODELS_BASE_URL = "https://dashscope-intl.aliyuncs.com/compatible-mode/v1"
API_KEY_ENV = "DASHSCOPE_API_KEY"
LIVE_TIMEOUT_S = 4.0

THINKING_BUDGET = "thinking_budget"
TODO_TOOLS = "todo_tools"  # same key + meaning as the claude harness

DEFAULT_MODEL = "glm-5.1"
DEFAULT_THINKING_BUDGET = 16_000
THINKING_BUDGET_MIN = 1_024
# The CLI asks for max_tokens=32000 on these models; budget must stay below.
THINKING_BUDGET_MAX = 31_000

# Families the Anthropic-compatible endpoint serves (probed 2026-10-07).
_FAMILY_RE = re.compile(r"^(glm-|deepseek-|kimi-|qwen3|qwq)", re.I)

# (id, label, description) — shown when the live list is unavailable, and
# used to order the live list (these first, in this order).
_BUILTIN: tuple[tuple[str, str, str], ...] = (
    ("glm-5.1", "GLM-5.1", "Zhipu GLM-5.1 — strong agentic coder (default)."),
    ("glm-5.3", "GLM-5.3", "Zhipu GLM-5.3 — always thinks."),
    ("glm-5.2", "GLM-5.2", "Zhipu GLM-5.2."),
    ("deepseek-v4-pro", "DeepSeek V4 Pro", "DeepSeek V4 Pro — streams its reasoning."),
    ("deepseek-v4-flash", "DeepSeek V4 Flash", "DeepSeek V4 Flash — fast and cheap."),
    ("kimi-k2.7-code", "Kimi K2.7 Code", "Moonshot Kimi K2.7 coding model — always thinks."),
    ("qwen3.7-plus", "Qwen3.7 Plus", "Qwen3.7 Plus."),
    ("qwen3.6-plus", "Qwen3.6 Plus", "Qwen3.6 Plus."),
    ("qwen3-coder-plus", "Qwen3 Coder Plus", "Qwen3 Coder Plus — no thinking mode."),
)
_BUILTIN_LABELS = {mid: (label, desc) for mid, label, desc in _BUILTIN}
_VENDOR_ORDER = ("glm-", "deepseek-", "kimi-", "qwen", "qwq")


def _traits(model_id: str | None):
    # Lazy + guarded: the Qwen catalog owns the DashScope family table.
    try:
        from ..qwen.catalog import qwen_model_traits
    except Exception:  # pragma: no cover — qwen package broken/missing
        return None
    try:
        return qwen_model_traits(model_id)
    except Exception:  # pragma: no cover
        return None


def model_context_window(model_id: str | None) -> int | None:
    """Context window of a Model Studio model id (``None`` = unknown)."""
    t = _traits(model_id or DEFAULT_MODEL)
    return getattr(t, "context_window", None) if t is not None else None


def model_thinks(model_id: str | None) -> bool | None:
    """True = has a thinking mode, False = none (coder models), None = unknown."""
    t = _traits(model_id)
    mode = getattr(t, "thinking", None) if t is not None else None
    if mode is None:
        return None
    return mode != "none"


def is_claude_model(model: str | None) -> bool:
    """A Claude id or Claude Code alias — never valid on this endpoint."""
    if not model:
        return False
    m = re.sub(r"\[[^\]]*\]$", "", model.strip().lower())
    return m.startswith("claude") or m in {
        "default", "sonnet", "opus", "haiku", "fable", "opusplan", "best",
    }


def filter_model_ids(ids: list[str]) -> list[str]:
    """Keep the chat/coding ids the Anthropic endpoint serves, best first."""
    try:
        from ..qwen.catalog import filter_live_ids
    except Exception:  # pragma: no cover
        filter_live_ids = None  # type: ignore[assignment]
    base = filter_live_ids(ids) if filter_live_ids else [
        i for i in ids if isinstance(i, str) and i and "/" not in i
    ]
    kept = [i for i in base if _FAMILY_RE.search(i)]

    def key(mid: str) -> tuple[int, int, str]:
        if mid in _BUILTIN_LABELS:
            return (0, [b[0] for b in _BUILTIN].index(mid), mid)
        vendor = next((n for n, p in enumerate(_VENDOR_ORDER) if mid.startswith(p)), len(_VENDOR_ORDER))
        return (1, vendor, mid)

    return sorted(dict.fromkeys(kept), key=key)


def _fetch_live_ids() -> tuple[list[str] | None, str | None]:
    """``(ids, None)`` or ``(None, reason)``.  Never raises."""
    api_key = (os.environ.get(API_KEY_ENV) or "").strip()
    if not api_key:
        return None, f"{API_KEY_ENV} is not set"
    try:
        import httpx  # lazy

        resp = httpx.get(
            f"{MODELS_BASE_URL}/models",
            headers={"Authorization": f"Bearer {api_key}"},
            timeout=LIVE_TIMEOUT_S,
        )
        resp.raise_for_status()
        data = resp.json().get("data") or []
    except Exception as e:  # noqa: BLE001 — any upstream failure → builtin list
        status = getattr(getattr(e, "response", None), "status_code", None)
        return None, f"HTTP {status}" if status else (str(e) or type(e).__name__)
    ids = filter_model_ids([m.get("id") for m in data if isinstance(m, dict)])
    if not ids:
        return None, "the models API returned no supported models"
    return ids, None


def _row(mid: str, source: str) -> HarnessModel:
    label, desc = _BUILTIN_LABELS.get(mid, (mid, None))
    return HarnessModel(
        id=mid,
        label=label,
        description=desc,
        context_window=model_context_window(mid),
        supports_thinking=model_thinks(mid),
        efforts=(),
        source=source,
    )


def build_catalog(ids: list[str], *, source: str, warnings: tuple[str, ...] = ()) -> HarnessCatalog:
    rows = tuple(_row(m, source) for m in ids)
    thinking_models = tuple(r.id for r in rows if r.supports_thinking is not False)
    options = (
        HarnessOption(
            key=THINKING,
            label="Thinking",
            kind="toggle",
            help=(
                "On: thinking {type: enabled, budget_tokens} on every request; off: "
                "{type: disabled}. Unset: the CLI asks for adaptive thinking and the "
                "model decides. Models that always think (GLM-5.3, Kimi K2.7) ignore off."
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
            help="Max thinking tokens (budget_tokens). Only used with Thinking on.",
            models=thinking_models,
        ),
        HarnessOption(
            key=TODO_TOOLS,
            label="Checklist tools",
            kind="toggle",
            default=True,
            help=(
                "Offer the TodoWrite/Task checklist tools "
                "(CLAUDE_CODE_ENABLE_TODO_TOOLS=1); Archie renders them as checklist cards."
            ),
        ),
    )
    return HarnessCatalog(
        provider=PROVIDER,
        models=rows,
        options=options,
        default_model=DEFAULT_MODEL,
        allow_custom_model=True,
        warnings=warnings,
    )


def load_modelstudio_catalog() -> HarnessCatalog:
    """``HarnessSpec.catalog_loader``.  Never raises for an unreachable upstream."""
    live, err = _fetch_live_ids()
    if live:
        return build_catalog(live, source="live")
    logger.warning("Model Studio model list unavailable (%s); using the built-in list", err)
    return build_catalog(
        [m for m, _, _ in _BUILTIN],
        source="builtin",
        warnings=(f"Live Model Studio model list unavailable ({err}); showing the built-in list.",),
    )


__all__ = [
    "ANTHROPIC_BASE_URL",
    "API_KEY_ENV",
    "DEFAULT_MODEL",
    "DEFAULT_THINKING_BUDGET",
    "PROVIDER",
    "THINKING",
    "THINKING_BUDGET",
    "TODO_TOOLS",
    "build_catalog",
    "filter_model_ids",
    "is_claude_model",
    "load_modelstudio_catalog",
    "model_context_window",
    "model_thinks",
]
