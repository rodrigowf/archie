"""Imported-ChatGPT JSONL adapter (read-only).

The importer writes Claude-shaped content blocks on purpose, so ``read_messages`` is nearly
identity — the only real work is skipping the ``chatgpt_meta`` header and lifting the title and
``read_only`` flag out of it.

Registers a :class:`ProviderAdapter` but **no** ``HarnessSpec``: ``chatgpt`` is not a harness the
user can pick for a new session, and nothing here can be spawned or resumed. The read-only
invariant is enforced in :mod:`api.routes.chat` / :mod:`api.pool` and in the orchestrator's resume
tools; this adapter only parses.

Format: ``docs/harnesses/chatgpt-import.md``.
"""

from __future__ import annotations

import json
from pathlib import Path

from ..protocol import ProviderAdapter, _parse_timestamp, extract_text, register_provider
from ..types import SessionInfo

PROVIDER_NAME = "chatgpt"
HEADER_TYPE = "chatgpt_meta"


def read_header(jsonl_path: Path) -> dict | None:
    """The ``chatgpt_meta`` header line, or None when this isn't an imported conversation."""
    try:
        with open(jsonl_path, encoding="utf-8", errors="replace") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                try:
                    obj = json.loads(line)
                except json.JSONDecodeError:
                    return None
                if isinstance(obj, dict) and obj.get("type") == HEADER_TYPE:
                    return obj
                return None  # the header is always the first non-blank line
    except (OSError, PermissionError):
        return None
    return None


class ChatGptAdapter(ProviderAdapter):
    """Adapter for conversations imported from a ChatGPT data export."""

    @property
    def provider_name(self) -> str:
        return PROVIDER_NAME

    def detect_provider(self, jsonl_path: Path) -> bool:
        """An imported conversation is identified by its ``chatgpt_meta`` header.

        The turn lines alone are indistinguishable from Claude's, which is why the Claude adapter
        explicitly rejects this header rather than relying on registration order.
        """
        return read_header(jsonl_path) is not None

    def read_messages(self, jsonl_path: Path) -> list[dict]:
        """User/assistant turns in the normalized shape, header skipped.

        Content blocks are already ``[{"type": "text", "text": …}]``, so they pass through. Voice
        turns carry their transcription as text; a turn whose audio expired still has its text.
        """
        out: list[dict] = []
        try:
            with open(jsonl_path, encoding="utf-8", errors="replace") as f:
                for line in f:
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        obj = json.loads(line)
                    except json.JSONDecodeError:
                        continue
                    if not isinstance(obj, dict):
                        continue
                    if obj.get("type") not in ("user", "assistant"):
                        continue  # the header, and any future non-turn line
                    msg = obj.get("message")
                    if not isinstance(msg, dict):
                        continue
                    out.append({
                        "type": obj["type"],
                        "timestamp": obj.get("timestamp"),
                        "message": {
                            "role": msg.get("role") or obj["type"],
                            "content": msg.get("content") or [],
                        },
                    })
        except (OSError, PermissionError):
            return []
        return out

    def parse_session_info(
        self,
        jsonl_path: Path,
        session_id: str,
        titles: dict[str, str] | None = None,
    ) -> SessionInfo | None:
        """Summary metadata, preferring the header's own title.

        A rename in ``context/.titles.json`` still wins (the user's own label beats the imported
        one); otherwise the title is the one the conversation had in ChatGPT.
        """
        header = read_header(jsonl_path)
        if header is None:
            return None

        first_ts: str | None = header.get("started_at") or header.get("timestamp")
        last_ts: str | None = header.get("ended_at")
        count = 0
        first_user_text = ""
        try:
            with open(jsonl_path, encoding="utf-8", errors="replace") as f:
                for line in f:
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        obj = json.loads(line)
                    except json.JSONDecodeError:
                        continue
                    if not isinstance(obj, dict) or obj.get("type") not in ("user", "assistant"):
                        continue
                    count += 1
                    ts = obj.get("timestamp")
                    if ts:
                        if first_ts is None:
                            first_ts = ts
                        last_ts = ts
                    if obj["type"] == "user" and not first_user_text:
                        first_user_text = extract_text(obj)
        except (OSError, PermissionError):
            return None

        started = _parse_timestamp(first_ts)
        last = _parse_timestamp(last_ts) or started
        if started is None:
            return None

        header_title = header.get("title")
        header_title = header_title.strip() if isinstance(header_title, str) else ""
        title = (titles or {}).get(session_id) or header_title or first_user_text[:100]

        return SessionInfo(
            session_id=session_id,
            started_at=started,
            last_activity=last or started,
            title=title,
            message_count=count,
            is_orchestrator=False,
            provider=PROVIDER_NAME,
            read_only=True,
        )


register_provider(ChatGptAdapter())
