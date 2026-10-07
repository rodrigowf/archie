"""Harness catalog — what a session harness lets the user configure.

Each harness contributes a :class:`HarnessCatalog` through its
``HarnessSpec.catalog_loader``: the models it can run and the options
(reasoning effort, thinking, …) it understands.  The settings UIs render
straight from this schema, so a harness that grows a new knob only
changes its own catalog and the code that turns the chosen values into
CLI flags — no UI edits.

Values flow like this:

* Global defaults: ``assistant_config.json`` → ``harness_options[provider]``
  (``{key: value}``), next to the existing ``harness_model[provider]``.
* Per session: ``<session>.config.json`` → ``harness_options`` (``{key:
  value}`` or ``None``).  Keys the session leaves out inherit the global
  value; ``None`` inherits everything.
* :func:`merge_options` combines the two; the result lands in
  ``ManagerConfig.harness_options`` and the session manager maps each
  key onto its CLI (flags, env, ``-c`` overrides …).

A value of ``None`` (or a missing key) always means "let the CLI decide"
— the session manager must then pass nothing for that option.

Option *keys* are shared where the concept is the same, so the UIs and
the orchestrator can talk about them uniformly:

``effort``    reasoning effort (``low`` / ``medium`` / ``high`` / …)
``thinking``  extended thinking / reasoning mode (harness-specific values)

Anything else is harness-specific and documented by its own catalog.
"""

from __future__ import annotations

import logging
import threading
import time
from collections.abc import Callable
from dataclasses import dataclass
from typing import Any

logger = logging.getLogger(__name__)

# Shared option keys (see module docstring).
EFFORT = "effort"
THINKING = "thinking"

OptionKind = str  # "select" | "toggle" | "number"


@dataclass(frozen=True)
class Choice:
    """One value of a ``select`` option."""

    value: str
    label: str
    description: str | None = None

    def to_dict(self) -> dict[str, Any]:
        out: dict[str, Any] = {"value": self.value, "label": self.label}
        if self.description:
            out["description"] = self.description
        return out


@dataclass(frozen=True)
class HarnessModel:
    """One row of a harness's model picker.

    ``efforts`` lists the effort levels this model accepts (``None`` = the
    catalog's ``effort`` option applies unchanged, ``()`` = the model has
    no effort control).  ``supports_thinking`` likewise gates the
    ``thinking`` option; ``None`` = unknown, show it.
    """

    id: str
    label: str
    description: str | None = None
    context_window: int | None = None
    supports_thinking: bool | None = None
    supports_vision: bool | None = None
    efforts: tuple[str, ...] | None = None
    default_effort: str | None = None
    # Where the row came from: "builtin" (shipped list), "settings" (the
    # CLI's own config file), "live" (a provider models API), "cli" (asked
    # the CLI itself).
    source: str = "builtin"

    def to_dict(self) -> dict[str, Any]:
        out: dict[str, Any] = {"id": self.id, "label": self.label, "source": self.source}
        if self.description:
            out["description"] = self.description
        if self.context_window is not None:
            out["context_window"] = self.context_window
        if self.supports_thinking is not None:
            out["supports_thinking"] = self.supports_thinking
        if self.supports_vision is not None:
            out["supports_vision"] = self.supports_vision
        if self.efforts is not None:
            out["efforts"] = list(self.efforts)
        if self.default_effort is not None:
            out["default_effort"] = self.default_effort
        return out


@dataclass(frozen=True)
class HarnessOption:
    """One configurable knob.

    ``default`` is informational: what the CLI does when the option is
    left unset (shown as "Default (…)" in the UI).  We never send it.
    ``models`` restricts the option to those model ids (``None`` = every
    model); the UI hides it for other models and the session manager
    must ignore it for them too.
    """

    key: str
    label: str
    kind: OptionKind = "select"
    choices: tuple[Choice, ...] = ()
    default: Any = None
    help: str | None = None
    models: tuple[str, ...] | None = None
    min: float | None = None
    max: float | None = None
    step: float | None = None

    def to_dict(self) -> dict[str, Any]:
        out: dict[str, Any] = {"key": self.key, "label": self.label, "kind": self.kind}
        if self.choices:
            out["choices"] = [c.to_dict() for c in self.choices]
        if self.default is not None:
            out["default"] = self.default
        if self.help:
            out["help"] = self.help
        if self.models is not None:
            out["models"] = list(self.models)
        for k in ("min", "max", "step"):
            v = getattr(self, k)
            if v is not None:
                out[k] = v
        return out

    def validate(self, value: Any) -> Any:
        """Return the normalized value or raise ``ValueError``.

        ``None`` always passes (= unset / CLI default).
        """
        if value is None:
            return None
        if self.kind == "toggle":
            if isinstance(value, bool):
                return value
            raise ValueError(f"{self.key} must be true/false (got {value!r})")
        if self.kind == "number":
            if isinstance(value, bool) or not isinstance(value, (int, float)):
                raise ValueError(f"{self.key} must be a number (got {value!r})")
            if self.min is not None and value < self.min:
                raise ValueError(f"{self.key} must be ≥ {self.min} (got {value})")
            if self.max is not None and value > self.max:
                raise ValueError(f"{self.key} must be ≤ {self.max} (got {value})")
            return int(value) if isinstance(value, float) and value.is_integer() else value
        # select
        if not isinstance(value, str):
            raise ValueError(f"{self.key} must be a string (got {value!r})")
        allowed = {c.value for c in self.choices}
        if allowed and value not in allowed:
            raise ValueError(f"{self.key} must be one of {sorted(allowed)} (got {value!r})")
        return value


@dataclass(frozen=True)
class HarnessCatalog:
    """Models + options for one harness, as served to the settings UIs."""

    provider: str
    models: tuple[HarnessModel, ...] = ()
    options: tuple[HarnessOption, ...] = ()
    # The model the CLI uses when we pass none (informational, may be None).
    default_model: str | None = None
    # Whether ids outside ``models`` are accepted (custom text entry).
    allow_custom_model: bool = True
    # Non-fatal problems while building the catalog (e.g. a live models
    # API was unreachable and only the builtin list is shown).
    warnings: tuple[str, ...] = ()

    def option(self, key: str) -> HarnessOption | None:
        return next((o for o in self.options if o.key == key), None)

    def to_dict(self) -> dict[str, Any]:
        return {
            "provider": self.provider,
            "models": [m.to_dict() for m in self.models],
            "options": [o.to_dict() for o in self.options],
            "default_model": self.default_model,
            "allow_custom_model": self.allow_custom_model,
            "warnings": list(self.warnings),
        }


CatalogLoader = Callable[[], HarnessCatalog]


def effort_option(
    levels: tuple[str, ...],
    *,
    default: str | None = None,
    help: str | None = None,
    labels: dict[str, str] | None = None,
    models: tuple[str, ...] | None = None,
) -> HarnessOption:
    """Shorthand for the shared ``effort`` select."""
    labels = labels or {}
    return HarnessOption(
        key=EFFORT,
        label="Reasoning effort",
        choices=tuple(Choice(v, labels.get(v, v.replace("_", " ").capitalize())) for v in levels),
        default=default,
        help=help,
        models=models,
    )


def validate_options(catalog: HarnessCatalog | None, options: dict[str, Any] | None) -> dict[str, Any]:
    """Validate a ``{key: value}`` map against *catalog*.

    Unknown keys raise ``ValueError`` (a typo must not silently land in the
    config).  ``None`` values are kept — they mean "unset" and let a session
    override a global choice back to the CLI default.  When *catalog* is
    ``None`` (harness without a catalog) only ``None`` values are accepted.
    """
    if not options:
        return {}
    if not isinstance(options, dict):
        raise ValueError("harness options must be an object")
    out: dict[str, Any] = {}
    for key, value in options.items():
        opt = catalog.option(key) if catalog else None
        if opt is None:
            known = sorted(o.key for o in catalog.options) if catalog else []
            raise ValueError(f"Unknown option {key!r}; expected one of {known}")
        out[key] = opt.validate(value)
    return out


def merge_options(
    global_options: dict[str, Any] | None,
    session_options: dict[str, Any] | None,
) -> dict[str, Any]:
    """Overlay the per-session map on the global one, dropping unset keys.

    A key present in *session_options* wins even when its value is
    ``None`` — that is how a session says "CLI default here, whatever the
    global default is".  The returned map has no ``None`` values, so a
    session manager can treat "key present" as "pass it to the CLI".
    """
    merged: dict[str, Any] = dict(global_options or {})
    if session_options:
        merged.update(session_options)
    return {k: v for k, v in merged.items() if v is not None}


# ── Catalog cache ─────────────────────────────────────────────────────
#
# Catalog loaders may hit the network (a provider's models API) or spawn
# the CLI, so results are cached per provider.  Loaders must never raise
# for an unreachable upstream — they return a builtin list plus a
# ``warnings`` entry — but if one does, we fall back to an empty catalog
# so the settings page still renders.

_CACHE_TTL_S = 300.0
_cache: dict[str, tuple[float, HarnessCatalog]] = {}
_cache_lock = threading.Lock()


def get_catalog(provider: str, *, refresh: bool = False) -> HarnessCatalog | None:
    """Return the (cached) catalog for *provider*, ``None`` if it has none."""
    from .registry import ensure_all_registered, get_registry

    ensure_all_registered()
    spec = get_registry().get(provider)
    if spec is None or spec.catalog_loader is None:
        return None
    now = time.monotonic()
    with _cache_lock:
        hit = _cache.get(provider)
        if hit and not refresh and now - hit[0] < _CACHE_TTL_S:
            return hit[1]
    try:
        catalog = spec.catalog_loader()
    except Exception as e:  # noqa: BLE001 — a broken loader must not break settings
        logger.exception("Catalog loader for %s failed", provider)
        catalog = HarnessCatalog(provider=provider, warnings=(f"catalog unavailable: {e}",))
    with _cache_lock:
        _cache[provider] = (now, catalog)
    return catalog


def clear_catalog_cache() -> None:
    with _cache_lock:
        _cache.clear()


__all__ = [
    "EFFORT",
    "THINKING",
    "Choice",
    "HarnessModel",
    "HarnessOption",
    "HarnessCatalog",
    "CatalogLoader",
    "effort_option",
    "validate_options",
    "merge_options",
    "get_catalog",
    "clear_catalog_cache",
]
