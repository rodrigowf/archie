"""Provider protocol — abstract interface for reading provider-specific JSONL
and mapping it to the normalized types used by SessionStore and the UI.

Each provider adapter handles:
- Parsing a native JSONL line into a provider-agnostic representation
- Detecting whether a file belongs to this provider
- Extracting session metadata (title, timestamps, message count)
"""

from __future__ import annotations

from abc import ABC, abstractmethod
from datetime import datetime
from pathlib import Path

from .types import ContentBlock, MessagePreview, SessionInfo


def _parse_timestamp(ts: str | None) -> datetime | None:
    """Parse an ISO-8601 timestamp; return None on empty input."""
    if ts is None:
        return None
    ts = ts.replace("Z", "+00:00")
    return datetime.fromisoformat(ts)


class ProviderAdapter(ABC):
    """Abstract base for provider-specific JSONL adapters.

    Concrete adapters normalize their native JSONL into a common shape::

        {"type": "user"|"assistant", "timestamp": "...", "message": {
            "role": "user"|"assistant",
            "content": <str | list[block]>,
        }}

    where each block is one of:

        {"type": "text", "text": "..."}
        {"type": "thinking", "text": "..."}
        {"type": "tool_use", "id": "...", "name": "...", "input": {...}}
        {"type": "tool_result", "tool_use_id": "...", "content": "...", "is_error": bool}

    This is identical to Claude Code's native content-block shape, so
    Claude's adapter is essentially a pass-through; Qwen's adapter
    translates from its native ``parts``/``functionCall`` shape.
    """

    @property
    @abstractmethod
    def provider_name(self) -> str:
        """Canonical provider identifier, e.g. 'claude' or 'qwen'."""

    @abstractmethod
    def detect_provider(self, jsonl_path: Path) -> bool:
        """Return True if the given JSONL file belongs to this provider.

        Uses heuristic detection based on the format of the first few
        parseable lines.
        """

    @abstractmethod
    def read_messages(self, jsonl_path: Path) -> list[dict]:
        """Read user/assistant messages from a native JSONL file, normalized.

        Returns a list of normalized message dicts (see class docstring).
        """

    @abstractmethod
    def parse_session_info(
        self,
        jsonl_path: Path,
        session_id: str,
        titles: dict[str, str] | None = None,
    ) -> SessionInfo | None:
        """Extract summary metadata from a JSONL file without loading all messages."""

    # -- Default implementations that operate on the normalized shape --

    def to_previews(self, messages: list[dict]) -> list[MessagePreview]:
        """Convert normalized messages to MessagePreview objects.

        Operates on the normalized shape produced by ``read_messages``,
        so the default implementation suffices for both providers.
        """
        previews: list[MessagePreview] = []
        for msg in messages:
            role = msg.get("type")
            if role not in ("user", "assistant"):
                continue
            previews.append(MessagePreview(
                role=role,
                text=extract_text(msg),
                blocks=extract_blocks(msg),
                timestamp=_parse_timestamp(msg.get("timestamp")),
            ))
        return previews

    def is_visible_message(self, obj: dict) -> bool:
        """Return True if *obj* is a raw JSONL line representing a user-visible
        message (a real conversation turn), not an internal/protocol line.

        Used by :meth:`SessionStore.truncate_session` to count visible turns
        from the bottom of a file so it can drop the last *N* of them.
        Providers whose raw line shape diverges (e.g. Gemini, which uses
        flat ``content`` instead of ``message.content``) override this; the
        default delegates to :func:`is_visible_message_default`.
        """
        return is_visible_message_default(obj)

    def visible_line_indices(self, objs: list[dict | None]) -> list[int]:
        """Indices of *objs* (parsed raw lines, ``None`` for blank or
        unparseable ones) that count as visible messages for
        ``drop_last_n``.  Adapters whose REST messages do not map 1:1 onto
        raw lines override this to stay consistent with ``read_messages``.
        """
        return [
            i for i, obj in enumerate(objs)
            if obj is not None and self.is_visible_message(obj)
        ]


def is_visible_message_default(obj: dict) -> bool:
    """Default visibility check for the normalized message shape.

    Matches Claude / Qwen / orchestrator native formats — all three
    normalize a "user" line whose entire content is tool_result blocks as
    internal (a protocol wrapper, not a real turn).  Exposed at module
    level so callers without an adapter (unrecognized files) can still
    apply the common rule instead of treating everything as invisible.
    """
    if not isinstance(obj, dict):
        return False
    msg_type = obj.get("type")
    if msg_type == "assistant":
        return True
    if msg_type != "user":
        return False
    msg = obj.get("message")
    content = msg.get("content") if isinstance(msg, dict) else None
    # Qwen native format puts blocks under `parts`, not `content`.
    if (not content) and isinstance(msg, dict):
        content = msg.get("parts")
    if isinstance(content, list):
        # Claude embeds tool_result as user-message content blocks;
        # those wrap a tool result and aren't a real user turn.
        return any(
            isinstance(b, dict) and b.get("type") != "tool_result"
            for b in content
        )
    return bool(content)


# ---------------------------------------------------------------------------
# Normalized-shape helpers — operate on the common format produced by all
# adapters.  Kept at module level so callers (SessionStore, tests) can use
# them without instantiating an adapter.
# ---------------------------------------------------------------------------


def extract_text(message: dict) -> str:
    """Extract plain text from a normalized message dict.

    Skips thinking blocks (those are not user-facing text).
    """
    msg = message.get("message", {})
    content = msg.get("content", "")
    if isinstance(content, str):
        return content
    parts: list[str] = []
    for block in content:
        if isinstance(block, dict) and block.get("type") == "text":
            parts.append(block.get("text", ""))
    return "\n".join(parts)


def tool_result_text(content) -> str:
    """Normalize a ``tool_result`` block's ``content`` to a string.

    Strings pass through; a list keeps its text items (dict ``{"type":
    "text"}`` or bare strings) joined with ``\n``; ``None`` becomes ``""``.
    Shared by REST history (``extract_blocks``) and the live Claude
    ``UserMessage`` path so both report the same output for one result.
    """
    if content is None:
        return ""
    if isinstance(content, list):
        parts: list[str] = []
        for item in content:
            if isinstance(item, dict) and item.get("type") == "text":
                parts.append(item.get("text", ""))
            elif isinstance(item, str):
                parts.append(item)
        return "\n".join(parts)
    return str(content) if content else ""


def extract_blocks(message: dict) -> list[ContentBlock]:
    """Extract content blocks from a normalized message dict."""
    msg = message.get("message", {})
    content = msg.get("content", "")
    blocks: list[ContentBlock] = []

    if isinstance(content, str):
        if content:
            blocks.append(ContentBlock(type="text", text=content))
        return blocks

    for block in content:
        if not isinstance(block, dict):
            continue
        btype = block.get("type", "")

        if btype == "text":
            text = block.get("text", "")
            if text:
                blocks.append(ContentBlock(type="text", text=text))

        elif btype == "thinking":
            # Claude stores the reasoning under ``thinking``; the Qwen and
            # Gemini adapters normalize theirs under ``text``.  Kept as its
            # own block type so clients can render it apart from the answer.
            text = block.get("thinking") or block.get("text") or ""
            if text:
                blocks.append(ContentBlock(type="thinking", text=text))

        elif btype == "tool_use":
            blocks.append(ContentBlock(
                type="tool_use",
                tool_use_id=block.get("id"),
                tool_name=block.get("name"),
                tool_input=block.get("input", {}),
            ))

        elif btype == "tool_result":
            blocks.append(ContentBlock(
                type="tool_result",
                tool_use_id=block.get("tool_use_id"),
                output=tool_result_text(block.get("content", "")),
                is_error=block.get("is_error", False),
            ))

    return blocks


# ---------------------------------------------------------------------------
# Registry — populated by adapter modules at import time.
# ---------------------------------------------------------------------------


class ProviderRegistry:
    """Registry of provider adapters. Used for format detection when the
    provider of a session file is unknown."""

    def __init__(self) -> None:
        self._adapters: dict[str, ProviderAdapter] = {}

    def register(self, adapter: ProviderAdapter) -> None:
        self._adapters[adapter.provider_name] = adapter

    def get(self, name: str) -> ProviderAdapter | None:
        return self._adapters.get(name)

    def detect(self, jsonl_path: Path) -> ProviderAdapter | None:
        """Try each adapter's detection heuristic; return the first match."""
        for adapter in self._adapters.values():
            if adapter.detect_provider(jsonl_path):
                return adapter
        return None

    def all(self) -> dict[str, ProviderAdapter]:
        return dict(self._adapters)


_registry = ProviderRegistry()


def get_registry() -> ProviderRegistry:
    """Return the global provider registry."""
    return _registry


def register_provider(adapter: ProviderAdapter) -> None:
    """Register a provider adapter (idempotent — last wins)."""
    _registry.register(adapter)


def detect_provider(jsonl_path: Path) -> ProviderAdapter | None:
    """Detect which provider a JSONL file belongs to."""
    return _registry.detect(jsonl_path)


def ensure_all_registered() -> None:
    """Import every known adapter so its registration side-effect runs.

    Safe to call repeatedly.  Delegates to
    :func:`manager.registry.ensure_all_registered` — every adapter
    module also registers a :class:`~manager.registry.HarnessSpec`, so a
    single import does both.  Listing adapters here would be a place to
    forget to update when a new harness lands.
    """
    from .registry import ensure_all_registered as _ensure_harness
    _ensure_harness()
