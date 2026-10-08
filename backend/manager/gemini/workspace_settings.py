"""Archie's keys in the Gemini CLI workspace settings (``<repo>/.gemini/settings.json``).

The Gemini CLI has no ``--settings`` flag, and since 0.60 a per-run
``GEMINI_CLI_SYSTEM_SETTINGS_PATH`` file is only honoured when it is
root-owned.  What does work per run is a *static* workspace settings file
whose string values are environment templates — the CLI expands
``${VAR}`` / ``${VAR:-default}`` in every string of every settings file —
so the session manager picks the values for each turn through env vars.

The file is gitignored and per machine, so this module both defines the
Archie-owned keys and merges them into an existing file without touching
anything else.  It is stdlib-only on purpose: the installer runs it as a
plain script (``python3 backend/manager/gemini/workspace_settings.py
<project_dir>``) before the venv exists.

Archie-owned keys
-----------------

``context.fileFiltering``
    ``respectGitIgnore=false`` so the agent can read the gitignored
    ``context/`` (memory, skills, history).  Older Archie seeds put
    ``fileFiltering`` at the top level, where the CLI never read it; the
    merge moves it.
``general.sessionRetention.enabled = false``
    The CLI's retention sweep runs on *every* start (headless too) over
    ``<project temp dir>/chats`` — which is ``context/chats/`` through the
    install symlink — and deletes sessions older than 30 days plus every
    session file without resumable content.  Archie keeps that history.
``security.auth.selectedType``
    ``${ARCHIE_GEMINI_AUTH_TYPE:-gemini-api-key}``.  Google stopped serving
    Gemini CLI to personal Google logins (``oauth-personal``) on
    2026-06-18; ``~/.gemini/settings.json`` often still selects it and
    would win over ``GEMINI_API_KEY``.  Set ``ARCHIE_GEMINI_AUTH_TYPE``
    (e.g. ``vertex-ai``, or ``oauth-personal`` for Code Assist Standard /
    Enterprise accounts) to use something else.
``modelConfigs.customOverrides`` (entries mentioning ``ARCHIE_GEMINI_``)
    Thinking controls, each gated by env vars (see :data:`ENV_*`):

    * every Gemini 3 model of the CLI's alias table extends ``chat-base-3``,
      whose ``thinkingLevel`` becomes ``${ARCHIE_GEMINI_THINKING_LEVEL:-HIGH}``
      (``HIGH`` is the CLI's own value, so nothing changes when unset);
    * a Gemini 3 id outside that table (e.g. ``gemini-3.7-flash``) gets the
      same level through an override matched on
      ``${ARCHIE_GEMINI_LEVEL_MODEL:-archie-off}`` — a model name that never
      exists unless the session manager sets it;
    * Gemini 2.5 models get ``thinkingBudget`` from
      ``ARCHIE_GEMINI_THINKING_BUDGET`` through an override matched on
      ``${ARCHIE_GEMINI_BUDGET_MODEL:-archie-off}``.  The value reaches the
      API as a JSON string, which the Gemini API accepts for integer
      fields (proto3 JSON).
"""

from __future__ import annotations

import copy
import json
import os
import sys
import tempfile
from pathlib import Path
from typing import Any

# Env vars the session manager sets per turn (all non-secret).
ENV_AUTH_TYPE = "ARCHIE_GEMINI_AUTH_TYPE"
ENV_THINKING_LEVEL = "ARCHIE_GEMINI_THINKING_LEVEL"
ENV_LEVEL_MODEL = "ARCHIE_GEMINI_LEVEL_MODEL"
ENV_BUDGET_MODEL = "ARCHIE_GEMINI_BUDGET_MODEL"
ENV_THINKING_BUDGET = "ARCHIE_GEMINI_THINKING_BUDGET"

# Per-turn option vars (the auth var is a user-level setting, not per turn).
PER_TURN_ENV_VARS: tuple[str, ...] = (
    ENV_THINKING_LEVEL,
    ENV_LEVEL_MODEL,
    ENV_BUDGET_MODEL,
    ENV_THINKING_BUDGET,
)

# Anything in customOverrides mentioning this belongs to Archie.
_MARKER = "ARCHIE_GEMINI_"
# A model name no override can ever match (gates an override off).
_OFF = "archie-off"


def _thinking(cfg: dict[str, Any]) -> dict[str, Any]:
    return {"generateContentConfig": {"thinkingConfig": cfg}}


ARCHIE_OVERRIDES: list[dict[str, Any]] = [
    {
        "match": {"model": "chat-base-3"},
        "modelConfig": _thinking({"thinkingLevel": f"${{{ENV_THINKING_LEVEL}:-HIGH}}"}),
    },
    {
        "match": {"model": f"${{{ENV_LEVEL_MODEL}:-{_OFF}}}"},
        "modelConfig": _thinking({"thinkingLevel": f"${{{ENV_THINKING_LEVEL}:-HIGH}}"}),
    },
    {
        "match": {"model": f"${{{ENV_BUDGET_MODEL}:-{_OFF}}}"},
        "modelConfig": _thinking({"thinkingBudget": f"${{{ENV_THINKING_BUDGET}:-8192}}"}),
    },
]

# The complete seed (install/cli-runtime/gemini/settings.json mirrors it;
# a test keeps the two equal).
ARCHIE_SETTINGS: dict[str, Any] = {
    "context": {
        "fileFiltering": {"respectGitIgnore": False, "respectGeminiIgnore": True},
    },
    "general": {"sessionRetention": {"enabled": False}},
    "security": {"auth": {"selectedType": f"${{{ENV_AUTH_TYPE}:-gemini-api-key}}"}},
    "modelConfigs": {"customOverrides": ARCHIE_OVERRIDES},
}


class WorkspaceSettingsError(RuntimeError):
    """The workspace settings file exists but cannot be merged safely."""


def _is_archie_override(entry: Any) -> bool:
    try:
        return _MARKER in json.dumps(entry)
    except (TypeError, ValueError):
        return False


def _sub(d: dict[str, Any], key: str) -> dict[str, Any]:
    v = d.get(key)
    if not isinstance(v, dict):
        v = {}
        d[key] = v
    return v


def merge_archie_settings(existing: dict[str, Any] | None) -> dict[str, Any]:
    """Return *existing* with Archie's keys applied (input left untouched).

    Keys outside Archie's set are preserved; Archie's own values always
    win (they are what makes the harness safe and working).
    """
    out = copy.deepcopy(existing) if isinstance(existing, dict) else {}

    # context.fileFiltering — migrate the dead top-level key first so a
    # hand-tuned value (e.g. respectGeminiIgnore) survives the move.
    ff = _sub(_sub(out, "context"), "fileFiltering")
    legacy = out.pop("fileFiltering", None)
    if isinstance(legacy, dict):
        for k, v in legacy.items():
            ff.setdefault(k, v)
    ff["respectGitIgnore"] = False
    ff.setdefault("respectGeminiIgnore", True)

    _sub(_sub(out, "general"), "sessionRetention")["enabled"] = False

    _sub(_sub(out, "security"), "auth")["selectedType"] = (
        ARCHIE_SETTINGS["security"]["auth"]["selectedType"]
    )

    mc = _sub(out, "modelConfigs")
    current = mc.get("customOverrides")
    kept = [e for e in current if not _is_archie_override(e)] if isinstance(current, list) else []
    mc["customOverrides"] = kept + copy.deepcopy(ARCHIE_OVERRIDES)
    return out


def settings_path(project_dir: str | os.PathLike[str]) -> Path:
    return Path(project_dir) / ".gemini" / "settings.json"


def ensure_workspace_settings(project_dir: str | os.PathLike[str]) -> bool:
    """Merge Archie's keys into ``<project_dir>/.gemini/settings.json``.

    Returns True when the file was (re)written.  Raises
    :class:`WorkspaceSettingsError` when an existing file is not a JSON
    object — we never overwrite what we cannot parse (the file may carry
    comments or hand edits); the caller refuses to run the CLI instead,
    because without these keys a run would delete old sessions.
    """
    path = settings_path(project_dir)
    existing: dict[str, Any] | None = None
    if path.exists():
        try:
            existing = json.loads(path.read_text(encoding="utf-8") or "{}")
        except (OSError, ValueError) as e:
            raise WorkspaceSettingsError(f"cannot parse {path}: {e}") from e
        if not isinstance(existing, dict):
            raise WorkspaceSettingsError(f"{path} is not a JSON object")
    merged = merge_archie_settings(existing)
    if merged == existing:
        return False
    path.parent.mkdir(parents=True, exist_ok=True)
    try:
        mode = path.stat().st_mode & 0o777
    except OSError:
        mode = 0o644
    fd, tmp = tempfile.mkstemp(prefix=".settings.", suffix=".json", dir=path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(merged, f, indent=2)
            f.write("\n")
        os.chmod(tmp, mode)  # mkstemp creates 0600
        os.replace(tmp, path)
    except BaseException:
        try:
            os.unlink(tmp)
        except OSError:
            pass
        raise
    return True


def render_seed() -> str:
    """The seed file's exact contents."""
    return json.dumps(ARCHIE_SETTINGS, indent=2) + "\n"


def _main(argv: list[str]) -> int:
    if len(argv) != 1:
        print("usage: workspace_settings.py <project_dir>", file=sys.stderr)
        return 2
    try:
        changed = ensure_workspace_settings(argv[0])
    except WorkspaceSettingsError as e:
        print(f"error: {e}", file=sys.stderr)
        return 1
    print(f"{settings_path(argv[0])}: {'updated' if changed else 'already up to date'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(_main(sys.argv[1:]))
