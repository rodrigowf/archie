"""Model Studio harness registration.

The CLI is Claude Code, so the JSONL is Claude's (``context/<id>.jsonl``)
and :class:`ModelStudioAdapter` is the Claude adapter under another name.
It is deliberately **not** passed to ``register_provider()``: format
detection must keep calling these files ``claude`` (the two are
indistinguishable on disk).  Which harness a session belongs to is the
per-session config ``provider`` — pinned on the session's first turn by
the pool — and :class:`manager.store.SessionStore` overlays it when
listing.
"""

from __future__ import annotations

from pathlib import Path

from ..claude.adapter import ClaudeAdapter
from ..registry import HarnessSpec, register_harness


class ModelStudioAdapter(ClaudeAdapter):
    """Claude's JSONL adapter, reporting ``modelstudio`` as its provider."""

    @property
    def provider_name(self) -> str:
        return "modelstudio"

    def detect_provider(self, jsonl_path: Path) -> bool:
        # Never claim a file by format — it is byte-for-byte Claude's.
        return False


_adapter = ModelStudioAdapter()


def _load_session_class():
    from .session import ModelStudioSessionManager
    return ModelStudioSessionManager


def _load_kill_helper():
    from .session import kill_modelstudio_subprocess
    return kill_modelstudio_subprocess


def _load_catalog():
    from .catalog import load_modelstudio_catalog
    return load_modelstudio_catalog()


def _jsonl_candidates(session_id: str) -> list[Path]:
    # Same storage as the claude harness (same CLI, same config dir).
    from ..claude.adapter import _claude_jsonl_candidates
    return list(_claude_jsonl_candidates(session_id))


register_harness(HarnessSpec(
    name="modelstudio",
    label="Claude Code · Model Studio",
    description="GLM, DeepSeek, Qwen (and other Model Studio models) driven by Claude Code",
    session_class_loader=_load_session_class,
    adapter_loader=lambda: _adapter,
    comm_prefix="claude",  # the same bundled claude binary
    kill_helper_loader=_load_kill_helper,
    ssh_control_path_prefix="modelstudio",
    jsonl_path_resolver=_jsonl_candidates,
    requirements_file="requirements-claude.txt",
    npm_package="@anthropic-ai/claude-code",
    cli_binary="claude",
    env_keys=("DASHSCOPE_API_KEY",),
    catalog_loader=_load_catalog,
))
