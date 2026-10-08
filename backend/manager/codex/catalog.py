"""Codex harness catalog — models + options for the settings UIs.

Models come from, best first:

1. **Live** — ``model/list`` on a short-lived ``codex app-server`` (spawned
   with Archie's ``CODEX_HOME``; ≤ 5 s).  This is the list the logged-in
   ChatGPT plan can actually use, with per-model reasoning efforts and the
   plan's default model.
2. **Cache** — ``$CODEX_HOME/models_cache.json``, which the CLI refreshes
   itself (also the source of ``context_window`` for live rows).
3. **Built-in** — a snapshot taken on 2026-10-07 (CLI 0.161.0, free plan).

Options (keys the session manager maps onto app-server params):

``effort``             ``turn/start.effort`` — restricted per model by
                       ``HarnessModel.efforts``.
``reasoning_summary``  ``turn/start.summary``.  Codex models default to
                       ``none`` (no thinking shown), so when unset Archie
                       sends ``concise``; choose ``none`` to turn it off.
``verbosity``          ``config.model_verbosity``.
``web_search``         ``config.web_search`` (``disabled`` / ``cached`` / ``live``).
"""

from __future__ import annotations

import json
import logging
import os
import select
import subprocess
import time
from pathlib import Path
from typing import Any

from ..harness_catalog import (
    Choice,
    HarnessCatalog,
    HarnessModel,
    HarnessOption,
    effort_option,
)
from . import home

logger = logging.getLogger(__name__)

PROVIDER = "codex"
LIVE_TIMEOUT_S = 5.0

# Harness-specific option keys (``effort`` is shared).
REASONING_SUMMARY = "reasoning_summary"
VERBOSITY = "verbosity"
WEB_SEARCH = "web_search"

EFFORT_LEVELS: tuple[str, ...] = ("low", "medium", "high", "xhigh", "max", "ultra")
SUMMARY_CHOICES: tuple[str, ...] = ("auto", "concise", "detailed", "none")
# What Archie sends when ``reasoning_summary`` is unset (see module docstring).
DEFAULT_SUMMARY = "concise"
VERBOSITY_CHOICES: tuple[str, ...] = ("low", "medium", "high")
WEB_SEARCH_CHOICES: tuple[str, ...] = ("disabled", "cached", "live")

DEFAULT_CONTEXT_WINDOW = 272_000

# model/list on 2026-10-07 (CLI 0.161.0, ChatGPT free plan), visible rows only.
_BUILTIN: tuple[HarnessModel, ...] = (
    HarnessModel(
        id="gpt-6-luna", label="GPT-6-Luna",
        description="Fast and affordable model for easier tasks.",
        context_window=DEFAULT_CONTEXT_WINDOW, supports_thinking=True, supports_vision=True,
        efforts=("low", "medium", "high", "xhigh", "max"), default_effort="medium",
    ),
    HarnessModel(
        id="gpt-5.6-terra", label="GPT-5.6-Terra",
        description="Older balanced model for straightforward work.",
        context_window=DEFAULT_CONTEXT_WINDOW, supports_thinking=True, supports_vision=True,
        efforts=("low", "medium", "high", "xhigh", "max", "ultra"), default_effort="medium",
    ),
    HarnessModel(
        id="gpt-5.6-luna", label="GPT-5.6-Luna",
        description="Older fast and efficient model.",
        context_window=DEFAULT_CONTEXT_WINDOW, supports_thinking=True, supports_vision=True,
        efforts=("low", "medium", "high", "xhigh", "max"), default_effort="medium",
    ),
)
_BUILTIN_DEFAULT = "gpt-6-luna"


# ---------------------------------------------------------------------------
# Parsing
# ---------------------------------------------------------------------------


def models_from_model_list(
    data: list[dict[str, Any]],
    *,
    context_windows: dict[str, int] | None = None,
    include_hidden: bool = False,
) -> tuple[list[HarnessModel], str | None]:
    """Turn ``model/list`` rows into catalog models; also return the default id."""
    models: list[HarnessModel] = []
    default: str | None = None
    windows = context_windows or {}
    for row in data:
        if not isinstance(row, dict):
            continue
        mid = row.get("model") or row.get("id")
        if not isinstance(mid, str) or not mid:
            continue
        if row.get("hidden") and not include_hidden:
            continue
        efforts = tuple(
            e.get("reasoningEffort")
            for e in row.get("supportedReasoningEfforts") or []
            if isinstance(e, dict) and isinstance(e.get("reasoningEffort"), str)
        )
        modalities = row.get("inputModalities")
        models.append(HarnessModel(
            id=mid,
            label=str(row.get("displayName") or mid),
            description=row.get("description") or None,
            context_window=windows.get(mid),
            supports_thinking=True,
            supports_vision=("image" in modalities) if isinstance(modalities, list) else None,
            efforts=efforts or None,
            default_effort=row.get("defaultReasoningEffort") or None,
            source="cli",
        ))
        if row.get("isDefault") and default is None:
            default = mid
    if default is None and models:
        default = models[0].id
    return models, default


def models_from_cache(data: dict[str, Any], *, include_hidden: bool = False) -> list[HarnessModel]:
    """Turn ``models_cache.json`` into catalog models (CLI's own snake_case shape)."""
    out: list[HarnessModel] = []
    for row in data.get("models") or []:
        if not isinstance(row, dict):
            continue
        slug = row.get("slug")
        if not isinstance(slug, str) or not slug:
            continue
        if row.get("visibility") == "hide" and not include_hidden:
            continue
        efforts = tuple(
            e.get("effort")
            for e in row.get("supported_reasoning_levels") or []
            if isinstance(e, dict) and isinstance(e.get("effort"), str)
        )
        modalities = row.get("input_modalities")
        out.append(HarnessModel(
            id=slug,
            label=str(row.get("display_name") or slug),
            description=row.get("description") or None,
            context_window=row.get("context_window") if isinstance(row.get("context_window"), int) else None,
            supports_thinking=True,
            supports_vision=("image" in modalities) if isinstance(modalities, list) else None,
            efforts=efforts or None,
            default_effort=row.get("default_reasoning_level") or None,
            source="settings",
        ))
    return out


def _read_cache(codex_home: Path) -> dict[str, Any] | None:
    try:
        data = json.loads((codex_home / "models_cache.json").read_text())
    except (OSError, json.JSONDecodeError):
        return None
    return data if isinstance(data, dict) else None


# ---------------------------------------------------------------------------
# Live model/list (short-lived app-server)
# ---------------------------------------------------------------------------


def fetch_model_list(codex_home: Path, *, timeout: float = LIVE_TIMEOUT_S) -> list[dict[str, Any]]:
    """Spawn ``codex app-server``, ask ``model/list`` and return its rows.

    Raises ``RuntimeError`` on any failure (missing binary, timeout, not
    logged in) — the caller turns that into a catalog warning.
    """
    env = home.codex_env(codex_home)
    try:
        proc = subprocess.Popen(
            [home.codex_executable(), "app-server", "--listen", "stdio://"],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
            env=env, cwd=str(Path.home()),
        )
    except OSError as e:
        raise RuntimeError(f"codex not runnable: {e}") from e
    deadline = time.monotonic() + timeout
    try:
        assert proc.stdin is not None and proc.stdout is not None
        msgs = [
            {"jsonrpc": "2.0", "id": 1, "method": "initialize",
             "params": {"clientInfo": {"name": home.CLIENT_NAME, "title": "Archie", "version": "catalog"}}},
            {"jsonrpc": "2.0", "method": "initialized"},
            {"jsonrpc": "2.0", "id": 2, "method": "model/list", "params": {"includeHidden": False}},
        ]
        proc.stdin.write("".join(json.dumps(m) + "\n" for m in msgs).encode())
        proc.stdin.flush()
        buf = b""
        fd = proc.stdout.fileno()
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise RuntimeError(f"model/list timed out after {timeout:.0f}s")
            ready, _, _ = select.select([fd], [], [], remaining)
            if not ready:
                continue
            chunk = os.read(fd, 65536)
            if not chunk:
                raise RuntimeError("codex app-server exited before answering model/list")
            buf += chunk
            while b"\n" in buf:
                line, buf = buf.split(b"\n", 1)
                try:
                    msg = json.loads(line)
                except json.JSONDecodeError:
                    continue
                if not isinstance(msg, dict) or msg.get("id") not in (1, 2):
                    continue
                if msg.get("error"):
                    err = msg["error"]
                    text = err.get("message") if isinstance(err, dict) else str(err)
                    raise RuntimeError(f"codex app-server error: {text}")
                if msg.get("id") == 2:
                    data = (msg.get("result") or {}).get("data")
                    if not isinstance(data, list):
                        raise RuntimeError("model/list returned no data")
                    return data
    finally:
        try:
            proc.kill()
        except OSError:
            pass
        try:
            proc.wait(timeout=2)
        except subprocess.TimeoutExpired:
            pass


# ---------------------------------------------------------------------------
# Options
# ---------------------------------------------------------------------------


def build_options(models: list[HarnessModel]) -> tuple[HarnessOption, ...]:
    levels = [lvl for lvl in EFFORT_LEVELS if any(lvl in (m.efforts or ()) for m in models)]
    # Levels a live model advertises that this snapshot does not know yet.
    for m in models:
        for lvl in m.efforts or ():
            if lvl not in levels:
                levels.append(lvl)
    return (
        effort_option(
            tuple(levels or EFFORT_LEVELS),
            default="medium",
            labels={"xhigh": "Extra high", "max": "Max", "ultra": "Ultra"},
            help="Reasoning effort per turn (turn/start effort). Levels depend on the model; "
                 "unset uses the model's default (medium).",
        ),
        HarnessOption(
            key=REASONING_SUMMARY,
            label="Reasoning summary",
            choices=(
                Choice("auto", "Auto"),
                Choice("concise", "Concise"),
                Choice("detailed", "Detailed"),
                Choice("none", "None", "No thinking shown in the UI"),
            ),
            default=DEFAULT_SUMMARY,
            help="How much of the model's reasoning Codex summarizes into the thinking stream. "
                 "Codex models default to none; when unset Archie sends concise.",
        ),
        HarnessOption(
            key=VERBOSITY,
            label="Verbosity",
            choices=tuple(Choice(v, v.capitalize()) for v in VERBOSITY_CHOICES),
            default="low",
            help="Length of the model's answers (config model_verbosity). Unset uses the model default (low).",
        ),
        HarnessOption(
            key=WEB_SEARCH,
            label="Web search",
            choices=(
                Choice("disabled", "Disabled"),
                Choice("cached", "Cached", "Search an OpenAI-maintained index (no live fetches)"),
                Choice("live", "Live", "Fetch live results"),
            ),
            help="Codex's built-in web_search tool (config web_search). Unset leaves Codex's default.",
        ),
    )


# ---------------------------------------------------------------------------
# Loader
# ---------------------------------------------------------------------------


def load_codex_catalog() -> HarnessCatalog:
    """``HarnessSpec.catalog_loader`` for Codex.  Never raises."""
    warnings: list[str] = []
    codex_home = home.codex_home()
    if home.is_shared_fallback():
        warnings.append(
            f"Codex is using the shared login in {home.shared_home()} (also used by VS Code / the "
            f"codex TUI) and its sessions stay there. For a dedicated Archie login run: {home.LOGIN_HINT}"
        )
    if not (codex_home / "auth.json").is_file() and not os.environ.get("CODEX_API_KEY"):
        warnings.append(f"Codex is not logged in ({codex_home}). Run: {home.LOGIN_HINT}")

    cache = _read_cache(codex_home) or _read_cache(home.shared_home())
    windows: dict[str, int] = {}
    if cache:
        for row in cache.get("models") or []:
            if isinstance(row, dict) and isinstance(row.get("slug"), str) and isinstance(row.get("context_window"), int):
                windows[row["slug"]] = row["context_window"]

    models: list[HarnessModel] = []
    default: str | None = None
    try:
        rows = fetch_model_list(codex_home)
        models, default = models_from_model_list(rows, context_windows=windows)
    except Exception as e:  # noqa: BLE001 — loader must not raise
        logger.info("Codex live model/list failed: %s", e)
        warnings.append(f"Live model list unavailable ({e}).")

    if not models and cache:
        models = models_from_cache(cache)
        default = models[0].id if models else None
        if models:
            warnings.append("Showing Codex's cached model list (models_cache.json).")
    if not models:
        models = list(_BUILTIN)
        default = _BUILTIN_DEFAULT
        warnings.append("Showing Codex's built-in model list (2026-10-07).")

    return HarnessCatalog(
        provider=PROVIDER,
        models=tuple(models),
        options=build_options(models),
        default_model=default,
        allow_custom_model=True,
        warnings=tuple(warnings),
    )
