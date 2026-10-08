"""Per-session configuration — overrides for working directory, MCP servers, skills, and agents."""

from __future__ import annotations

import json
import logging
from pathlib import Path
from typing import Any

from utils.paths import get_context_dir

logger = logging.getLogger(__name__)

# Keys that are valid in a session config (subset of global config).
# ``provider`` is special-cased: once a session has a JSONL written by a
# particular CLI, switching providers mid-resume would corrupt that file's
# adapter shape — so we persist provider per session and the resume path
# treats it as authoritative, never the global default.
_ALLOWED_KEYS = {
    "working_directory",
    "enabled_mcps",
    "chrome_extension",
    "provider",         # registered harness id — pinned per session
    "harness_model",    # provider-appropriate model id, "" = CLI default
    "harness_options",  # {key: value} overlay on global harness_options[provider]
}

_DEFAULTS: dict[str, Any] = {
    "working_directory": None,     # None = inherit active from global config
    "enabled_mcps": None,          # None = inherit from global config
    "chrome_extension": None,      # None = inherit from global config
    "provider": None,              # None = inherit from global config (new sessions)
                                   # — pinned by the pool on the first turn
                                   #   (pin_session_provider) and on a detected
                                   #   resume (chat.py), so resume is deterministic.
    "harness_model": None,         # None = inherit from global harness_model[provider]
    "harness_options": None,       # None = inherit all; a key with value None = CLI default
}


def _config_path(session_id: str) -> Path:
    return get_context_dir() / f"{session_id}.config.json"


def load_session_config(session_id: str) -> dict[str, Any]:
    """Load per-session config from disk. Missing keys default to None (inherit)."""
    path = _config_path(session_id)
    if not path.is_file():
        return dict(_DEFAULTS)
    try:
        with open(path) as f:
            data = json.load(f)
        result = dict(_DEFAULTS)
        for k in _ALLOWED_KEYS:
            if k in data:
                result[k] = data[k]
        return result
    except (json.JSONDecodeError, IOError) as e:
        logger.error("Failed to load session config for %s: %s", session_id, e)
        return dict(_DEFAULTS)


def validate_session_config(session_id: str, data: dict[str, Any]) -> dict[str, Any]:
    """Check a PUT body before saving; returns it with normalized values.

    Only ``harness_options`` needs checking: its keys and values must fit
    the catalog of the harness the session will run (the provider in the
    body, else the session's pinned one, else the global default).
    Raises ``ValueError`` with a user-facing message.
    """
    opts = data.get("harness_options")
    if opts is None:
        return data
    from manager.harness_catalog import get_catalog, validate_options

    provider = data.get("provider") or load_session_config(session_id).get("provider")
    if not provider:
        from api.routes.config import _load_config
        provider = _load_config().get("provider") or "claude"
    out = dict(data)
    out["harness_options"] = validate_options(get_catalog(provider), opts)
    return out


def pin_session_provider(session_id: str, provider: str) -> bool:
    """Record *provider* as the session's harness unless one is pinned already.

    Called by the pool once a session has its provider-side id (first
    turn), for every harness.  Two harnesses can write the same JSONL
    format (``claude`` and ``modelstudio`` both run Claude Code), so format
    detection cannot tell them apart on resume — the pinned value can.
    Returns True when it wrote the config.
    """
    path = _config_path(session_id)
    if path.is_file() and load_session_config(session_id).get("provider"):
        return False
    save_session_config(session_id, {"provider": provider})
    return True


def save_session_config(session_id: str, data: dict[str, Any]) -> dict[str, Any]:
    """Persist per-session config overrides. Only allowed keys are saved."""
    path = _config_path(session_id)
    current = load_session_config(session_id)
    for k in _ALLOWED_KEYS:
        if k in data:
            current[k] = data[k]
    try:
        with open(path, "w") as f:
            json.dump(current, f, indent=2)
    except IOError as e:
        logger.error("Failed to save session config for %s: %s", session_id, e)
    return current
