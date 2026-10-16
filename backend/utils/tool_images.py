"""Images inside tool results (a Read of a picture, an MCP screenshot), served from the session JSONL.

A ``tool_result`` block's ``content`` may hold ``{"type": "image", "source": {"type": "base64",
"media_type": ..., "data": ...}}`` items next to its text. The clients never get the base64: the
frame / history block carries ``images: [{"url": ..., "media_type": ...}]`` (spec 12 R-10) where
the url names the image's place, not a copy of it::

    /api/sessions/<sdk_session_id>/tool-images/<tool_use_id>/<n>      (n = 0-based image index)

``GET`` on it (``api/routes/sessions.py``) finds that result line in the session's JSONL — or in
one of its subagent transcripts — and decodes the image. The JSONL is the only copy (it is already
synced between the machines); nothing is written to disk. Two small caches keep it cheap:

* a per-file index ``tool_use_id → byte offset`` of the lines that hold images, extended
  incrementally as the append-only file grows, so a lookup reads one line;
* a byte-capped in-memory LRU of decoded images, filled by lookups and by live results
  (:func:`live_tool_result_images`), which also covers a card opened before the CLI wrote the line.

Both can be dropped at any time: every url can always be served again from the JSONL.
"""

from __future__ import annotations

import base64
import binascii
import json
import logging
import re
import threading
from collections import OrderedDict
from pathlib import Path
from typing import Any

logger = logging.getLogger(__name__)

SUPPORTED_TYPES = {
    "image/png": "image/png",
    "image/jpeg": "image/jpeg",
    "image/jpg": "image/jpeg",
    "image/gif": "image/gif",
    "image/webp": "image/webp",
}

#: Ids that may appear in a url (session ids are UUIDs or ``session-<ts>-<id>``; tool ids ``toolu_…``).
ID_RE = re.compile(r"^[A-Za-z0-9_-]{1,128}$")

_MEMORY_CAP_BYTES = 32 * 1024 * 1024
_INDEX_MAX_FILES = 256
_IMAGE_MARK = b'"image"'  # cheap pre-filter, whatever the JSON spacing
# Ids of a candidate line, read from the raw bytes: image lines carry the base64 twice and parsing
# them all made the first lookup take ~5 s on the Jetson. A stray match only costs a wasted read:
# the served line is parsed and checked (find_image).
_TOOL_USE_ID_RE = re.compile(rb'"tool_use_id"\s*:\s*"([A-Za-z0-9_-]{1,128})"')


def image_url(session_id: str, tool_use_id: str, n: int) -> str:
    return f"/api/sessions/{session_id}/tool-images/{tool_use_id}/{n}"


def _image_items(content: Any) -> list[tuple[str, str]]:
    """``(media_type, base64)`` of the servable images in a ``tool_result`` content, in order.

    The order defines ``n`` in the url, so this one function decides both what is listed and what
    is served.
    """
    if not isinstance(content, list):
        return []
    out: list[tuple[str, str]] = []
    for item in content:
        if not isinstance(item, dict) or item.get("type") != "image":
            continue
        source = item.get("source")
        if not isinstance(source, dict) or source.get("type") != "base64":
            continue
        data = source.get("data")
        media_type = SUPPORTED_TYPES.get(str(source.get("media_type") or "").lower())
        if isinstance(data, str) and data and media_type:
            out.append((media_type, data))
    return out


def tool_result_images(content: Any, session_id: str | None, tool_use_id: str | None) -> list[dict[str, str]]:
    """``[{url, media_type}]`` for the images in a ``tool_result`` content (no I/O).

    Empty without a usable session / tool id: such a result could not be found again.
    """
    if not session_id or not tool_use_id or not ID_RE.match(session_id) or not ID_RE.match(tool_use_id):
        return []
    return [
        {"url": image_url(session_id, tool_use_id, n), "media_type": media_type}
        for n, (media_type, _) in enumerate(_image_items(content))
    ]


def live_tool_result_images(content: Any, session_id: str | None, tool_use_id: str | None) -> list[dict[str, str]]:
    """:func:`tool_result_images` for a live result, which also keeps the decoded images in memory
    so a card expanded before the CLI wrote the JSONL line still loads."""
    images = tool_result_images(content, session_id, tool_use_id)
    if images:
        for n, (media_type, data) in enumerate(_image_items(content)):
            raw = _decode(data)
            if raw is not None:
                _memory.put((session_id, tool_use_id, n), (raw, media_type))
    return images


def _decode(data: str) -> bytes | None:
    try:
        return base64.b64decode(data, validate=False)
    except (binascii.Error, ValueError):
        logger.warning("tool image: undecodable base64 (%d chars)", len(data))
        return None


class _MemoryCache:
    """Decoded images by ``(session_id, tool_use_id, n)``, least recently used out past the cap."""

    def __init__(self, cap_bytes: int) -> None:
        self._cap = cap_bytes
        self._items: OrderedDict[tuple[str, str, int], tuple[bytes, str]] = OrderedDict()
        self._bytes = 0
        self._lock = threading.Lock()

    def get(self, key: tuple[str, str, int]) -> tuple[bytes, str] | None:
        with self._lock:
            hit = self._items.get(key)
            if hit is not None:
                self._items.move_to_end(key)
            return hit

    def put(self, key: tuple[str, str, int], value: tuple[bytes, str]) -> None:
        size = len(value[0])
        if size > self._cap:
            return
        with self._lock:
            old = self._items.pop(key, None)
            if old is not None:
                self._bytes -= len(old[0])
            self._items[key] = value
            self._bytes += size
            while self._bytes > self._cap:
                _, dropped = self._items.popitem(last=False)
                self._bytes -= len(dropped[0])

    def clear(self) -> None:
        with self._lock:
            self._items.clear()
            self._bytes = 0


class _FileIndex:
    """``tool_use_id → offset`` of the result lines with images, per JSONL file.

    The files are append-only, so an index is extended from where it stopped; a file that shrank
    (rewind / truncate) is indexed again from the start. A partial last line (still being written)
    is left for the next lookup.
    """

    def __init__(self, max_files: int) -> None:
        self._max = max_files
        self._files: OrderedDict[str, tuple[int, dict[str, int]]] = OrderedDict()
        self._lock = threading.Lock()

    def offset(self, path: Path, tool_use_id: str) -> int | None:
        key = str(path)
        with self._lock:
            done, offsets = self._files.get(key, (0, {}))
            if tool_use_id in offsets:
                self._files.move_to_end(key)
                return offsets[tool_use_id]
            try:
                size = path.stat().st_size
            except OSError:
                return None
            if size < done:
                done, offsets = 0, {}
            if size > done:
                done = self._scan(path, done, offsets)
            self._files[key] = (done, offsets)
            self._files.move_to_end(key)
            while len(self._files) > self._max:
                self._files.popitem(last=False)
            return offsets.get(tool_use_id)

    @staticmethod
    def _scan(path: Path, start: int, offsets: dict[str, int]) -> int:
        pos = start
        try:
            with path.open("rb") as f:
                f.seek(start)
                for line in f:
                    if not line.endswith(b"\n"):
                        break  # partial last line: next time
                    if _IMAGE_MARK in line and b'tool_result' in line:
                        for m in _TOOL_USE_ID_RE.finditer(line):
                            offsets.setdefault(m.group(1).decode(), pos)
                    pos += len(line)
        except OSError:
            logger.exception("tool image: could not index %s", path)
        return pos

    def clear(self) -> None:
        with self._lock:
            self._files.clear()


def _result_blocks(line: bytes) -> list[tuple[str, Any]]:
    """``(tool_use_id, content)`` of the ``tool_result`` blocks of one JSONL line."""
    try:
        obj = json.loads(line)
    except ValueError:
        return []
    msg = obj.get("message") if isinstance(obj, dict) else None
    content = msg.get("content") if isinstance(msg, dict) else None
    if not isinstance(content, list):
        return []
    return [
        (b["tool_use_id"], b.get("content"))
        for b in content
        if isinstance(b, dict) and b.get("type") == "tool_result" and isinstance(b.get("tool_use_id"), str)
    ]


_memory = _MemoryCache(_MEMORY_CAP_BYTES)
_index = _FileIndex(_INDEX_MAX_FILES)


def session_files(jsonl: Path) -> list[Path]:
    """The session's JSONL, then its subagent transcripts (``<sid>/subagents/**/agent-*.jsonl``)."""
    sub = jsonl.with_suffix("") / "subagents"
    return [jsonl, *sorted(sub.rglob("agent-*.jsonl"))] if sub.is_dir() else [jsonl]


def find_image(session_id: str, tool_use_id: str, n: int, files: list[Path]) -> tuple[bytes, str] | None:
    """The decoded image ``n`` of result ``tool_use_id`` and its media type, from memory or *files*."""
    key = (session_id, tool_use_id, n)
    hit = _memory.get(key)
    if hit is not None:
        return hit
    for path in files:
        offset = _index.offset(path, tool_use_id)
        if offset is None:
            continue
        try:
            with path.open("rb") as f:
                f.seek(offset)
                line = f.readline()
        except OSError:
            continue
        for tid, content in _result_blocks(line):
            if tid != tool_use_id:
                continue
            items = _image_items(content)
            if n >= len(items):
                return None
            media_type, data = items[n]
            raw = _decode(data)
            if raw is None:
                return None
            _memory.put(key, (raw, media_type))
            return raw, media_type
    return None


def clear_caches() -> None:
    """Drop both caches (tests)."""
    _memory.clear()
    _index.clear()
