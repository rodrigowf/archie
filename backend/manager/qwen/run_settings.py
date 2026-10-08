"""Per-run Qwen Code settings — the ``QWEN_CODE_SYSTEM_SETTINGS_PATH`` file.

Qwen Code reads settings in layers (lowest → highest): defaults <
system-defaults < user ``~/.qwen/settings.json`` < project
``.qwen/settings.json`` < **system** (``QWEN_CODE_SYSTEM_SETTINGS_PATH``) <
env < CLI flags.  Every Archie turn gets its own system file, so per-session
knobs never touch the user's ``~/.qwen/settings.json``.

What the file always carries (deliberate fixes, see docs/harnesses/qwen-code.md):

* ``memory.enableManagedAutoMemory / enableManagedAutoDream / enableAutoSkill
  = false`` — Qwen's background memory extractor/recall/dream write into
  ``~/.qwen/projects/<mangled>/memory/``, which Archie symlinks to
  ``context/``, i.e. into Archie's curated memory wiki, and each costs an
  extra model call per turn.
* ``general.outputLanguage = "English"`` — 0.25 migrates a generated
  "always English" ``output-language.md`` to ``auto`` when this is unset.
* ``general.enableAutoUpdate = false`` — the CLI version is pinned by the
  installer; a self-update must not replace it mid-session.
* ``agents.crossSessionMessaging = false`` — no per-session local socket.

What it carries only when needed: a copy of the user's ``modelProviders``
(``mergeStrategy: "replace"``, so it must be the complete list) with the
selected model's ``generationConfig`` patched from the harness options, and
— for a model id that is not in the user's list (a DashScope "live" row) —
a synthetic DashScope entry.  Provider entries are "sealed" in Qwen Code:
top-level ``model.generationConfig`` is ignored for provider models, so the
knobs must live on the entry itself.

Secrets never land in the file: the user's ``env`` block and
``security`` section are not copied, and any key that looks like a
credential (``apiKey``, ``Authorization``, ``*token``, …) is stripped from
the copied entries.  Entries authenticate through ``envKey`` (the *name* of
an env var), which the CLI resolves from its own env / settings ``env``.
"""

from __future__ import annotations

import copy
import json
import logging
import os
import re
import tempfile
import uuid
from pathlib import Path
from typing import Any

from ..harness_catalog import EFFORT, THINKING
from .catalog import (
    DASHSCOPE_ENV_KEY,
    TEMPERATURE,
    THINKING_BUDGET,
    dashscope_base_url,
    default_model,
    option_applies,
    qwen_model_traits,
)

logger = logging.getLogger(__name__)

SYSTEM_SETTINGS_ENV = "QWEN_CODE_SYSTEM_SETTINGS_PATH"
OUTPUT_LANGUAGE = "English"
SETTINGS_SCHEMA_VERSION = 4

# Credential-looking keys, matched against the whole key name.  ``envKey``
# (an env var *name*) and ``max_tokens``-style knobs must survive.
_SECRET_KEY_RE = re.compile(
    r"^(x-)?(api[_-]?key|apikey|key|secret|.*[_-]secret|client[_-]?secret|password|passwd"
    r"|(access|refresh|auth|bearer|id|session)?[_-]?token|authorization|credentials?|cookie)$",
    re.I,
)


def fixed_settings() -> dict[str, Any]:
    """Keys every Archie run carries, whatever the options."""
    return {
        "memory": {
            "enableManagedAutoMemory": False,
            "enableManagedAutoDream": False,
            "enableAutoSkill": False,
        },
        "general": {
            "outputLanguage": OUTPUT_LANGUAGE,
            "enableAutoUpdate": False,
        },
        "agents": {"crossSessionMessaging": False},
        # Current schema version in 0.15.11 … 0.25.0; without it the CLI may
        # try to "migrate" (and rewrite) the file.
        "$version": SETTINGS_SCHEMA_VERSION,
    }


def _strip_secrets(value: Any) -> Any:
    if isinstance(value, dict):
        return {
            k: _strip_secrets(v)
            for k, v in value.items()
            if k != "env" and not (isinstance(k, str) and _SECRET_KEY_RE.match(k))
        }
    if isinstance(value, list):
        return [_strip_secrets(v) for v in value]
    return value


def _find_entry(providers: dict, model_id: str) -> dict | None:
    for entries in providers.values():
        if not isinstance(entries, list):
            continue
        for entry in entries:
            if isinstance(entry, dict) and entry.get("id") == model_id:
                return entry
    return None


def _dashscope_env_key(providers: dict) -> str:
    """``envKey`` of the user's DashScope entries (the env var *name*)."""
    for entries in providers.values():
        for entry in entries if isinstance(entries, list) else ():
            if (
                isinstance(entry, dict)
                and "dashscope" in str(entry.get("baseUrl", ""))
                and isinstance(entry.get("envKey"), str)
            ):
                return entry["envKey"]
    return DASHSCOPE_ENV_KEY


def _can_synthesize(user_settings: dict, providers: dict, env: dict[str, str]) -> bool:
    """A live-only id is runnable when the user already talks to DashScope
    (an entry with a DashScope baseUrl) or has ``DASHSCOPE_API_KEY`` set."""
    for entries in providers.values():
        for entry in entries if isinstance(entries, list) else ():
            if isinstance(entry, dict) and "dashscope" in str(entry.get("baseUrl", "")):
                return True
    return bool(env.get(DASHSCOPE_ENV_KEY))


def generation_patch(model_id: str | None, options: dict[str, Any] | None) -> dict[str, Any]:
    """Map harness options onto a ``generationConfig`` patch for *model_id*.

    Options that do not apply to the model (``option_applies``) are dropped,
    as are unknown keys.  Returns ``{}`` when nothing applies.
    """
    opts = {k: v for k, v in (options or {}).items() if v is not None}
    extra: dict[str, Any] = {}
    sampling: dict[str, Any] = {}

    thinking = opts.get(THINKING)
    if isinstance(thinking, bool) and option_applies(THINKING, model_id):
        extra["enable_thinking"] = thinking

    budget = opts.get(THINKING_BUDGET)
    if (
        isinstance(budget, (int, float)) and not isinstance(budget, bool)
        and option_applies(THINKING_BUDGET, model_id)
        and extra.get("enable_thinking") is not False
    ):
        extra["thinking_budget"] = int(budget)

    effort = opts.get(EFFORT)
    if isinstance(effort, str) and option_applies(EFFORT, model_id, effort):
        extra["reasoning_effort"] = effort

    temperature = opts.get(TEMPERATURE)
    if isinstance(temperature, (int, float)) and not isinstance(temperature, bool):
        sampling["temperature"] = temperature

    patch: dict[str, Any] = {}
    if extra:
        patch["extra_body"] = extra
    if sampling:
        patch["samplingParams"] = sampling
    return patch


def _apply_patch(entry: dict, patch: dict[str, Any]) -> None:
    gen = entry.get("generationConfig")
    if not isinstance(gen, dict):
        gen = {}
        entry["generationConfig"] = gen
    for section, values in patch.items():
        current = gen.get(section)
        merged = dict(current) if isinstance(current, dict) else {}
        merged.update(values)
        gen[section] = merged


def _synthetic_entry(model_id: str, user_settings: dict, providers: dict) -> dict:
    traits = qwen_model_traits(model_id)
    gen: dict[str, Any] = {}
    if traits.context_window:
        gen["contextWindowSize"] = traits.context_window
    return {
        "id": model_id,
        "name": model_id,
        "baseUrl": dashscope_base_url(user_settings),
        "envKey": _dashscope_env_key(providers),
        "generationConfig": gen,
    }


def build_run_settings(
    model: str | None,
    options: dict[str, Any] | None,
    user_settings: dict | None,
    *,
    env: dict[str, str] | None = None,
    include_providers: bool = True,
) -> dict[str, Any]:
    """Build the per-run system settings dict.

    *model* is ``ManagerConfig.model`` (``None`` = the user's default
    ``model.name``).  *options* is ``ManagerConfig.harness_options``.
    With ``include_providers=False`` (SSH: the remote's provider list is
    unknown here) only :func:`fixed_settings` is returned.
    """
    settings = fixed_settings()
    if not include_providers:
        return settings

    user_settings = user_settings if isinstance(user_settings, dict) else {}
    env = env if env is not None else dict(os.environ)
    raw_providers = user_settings.get("modelProviders")
    providers: dict = raw_providers if isinstance(raw_providers, dict) else {}

    effective = model or default_model(user_settings)
    if not effective:
        return settings

    patch = generation_patch(effective, options)
    known = _find_entry(providers, effective) is not None
    needs_synthetic = bool(model) and not known
    if not patch and not needs_synthetic:
        return settings
    if needs_synthetic and not _can_synthesize(user_settings, providers, env):
        # Not a DashScope setup (OAuth / other provider) — leave --model to
        # the CLI exactly as before, without knobs we cannot place.
        return settings

    copied = copy.deepcopy(_strip_secrets(providers))
    entry = _find_entry(copied, effective)
    if entry is None:
        entry = _synthetic_entry(effective, user_settings, providers)
        bucket = copied.get("openai")
        if not isinstance(bucket, list):
            bucket = []
            copied["openai"] = bucket
        bucket.append(entry)
    if patch:
        _apply_patch(entry, patch)
    settings["modelProviders"] = copied
    return settings


# ── files ──────────────────────────────────────────────────────────────


def runtime_dir() -> Path:
    """Private (0700) directory for per-run files — never under ``context/``
    (that tree is synced between machines)."""
    base = os.environ.get("XDG_RUNTIME_DIR")
    if base and os.path.isdir(base) and os.access(base, os.W_OK):
        path = Path(base) / "archie-qwen"
    else:
        path = Path(tempfile.gettempdir()) / f"archie-qwen-{os.getuid()}"
    path.mkdir(mode=0o700, parents=True, exist_ok=True)
    try:
        if path.stat().st_uid == os.getuid():
            os.chmod(path, 0o700)
    except OSError:
        pass
    return path


def write_run_settings(settings: dict[str, Any], *, name: str) -> Path:
    """Write *settings* to a fresh 0600 file and return its path."""
    safe = re.sub(r"[^A-Za-z0-9_.-]", "_", name)[:80] or "run"
    path = runtime_dir() / f"{safe}-{uuid.uuid4().hex[:8]}.json"
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w") as f:
        json.dump(settings, f, indent=1)
    return path


def remove_run_settings(path: str | os.PathLike | None) -> None:
    if not path:
        return
    try:
        os.unlink(path)
    except FileNotFoundError:
        pass
    except OSError:
        logger.warning("Could not remove Qwen run settings %s", path, exc_info=True)


__all__ = [
    "SYSTEM_SETTINGS_ENV",
    "OUTPUT_LANGUAGE",
    "fixed_settings",
    "generation_patch",
    "build_run_settings",
    "runtime_dir",
    "write_run_settings",
    "remove_run_settings",
]
