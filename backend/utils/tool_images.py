"""Images inside tool results (a Read of a picture, an MCP screenshot) → small URLs for the clients.

A ``tool_result`` block's ``content`` may hold ``{"type": "image", "source": {"type": "base64",
"media_type": ..., "data": ...}}`` items next to its text. The clients never get the base64 (a
phone screenshot is ~1 MB per frame, and history pages would carry every one): each image is
written once to ``state/tool-images/<sha256>.<ext>`` and the frame / history block carries
``images: [{"url": "/api/tool-images/<sha256>.<ext>", "media_type": ...}]`` instead
(``api/routes/tool_images.py`` serves the files, spec 12 §4.2 ``tool_result``).

The name is the hash of the base64 text, so the live event and every later history read of the
same JSONL line map to the same file, and each machine rebuilds its own cache from the JSONL
(the cache is machine-local state, never synced).
"""

from __future__ import annotations

import base64
import binascii
import hashlib
import logging
import os
import re
from pathlib import Path
from typing import Any

from utils.paths import get_state_dir

logger = logging.getLogger(__name__)

URL_PREFIX = "/api/tool-images/"

_EXT_BY_TYPE = {
    "image/png": "png",
    "image/jpeg": "jpg",
    "image/jpg": "jpg",
    "image/gif": "gif",
    "image/webp": "webp",
}
TYPE_BY_EXT = {"png": "image/png", "jpg": "image/jpeg", "gif": "image/gif", "webp": "image/webp"}

#: ``<sha256>.<ext>`` — the only names the route serves.
NAME_RE = re.compile(r"^[0-9a-f]{64}\.(png|jpg|gif|webp)$")


def images_dir() -> Path:
    return get_state_dir() / "tool-images"


def _store(data: str, media_type: str) -> str | None:
    """Write one base64 image to the cache (once) and return its file name."""
    ext = _EXT_BY_TYPE.get(media_type.lower())
    if ext is None:
        return None
    name = f"{hashlib.sha256(data.encode('ascii', 'ignore')).hexdigest()}.{ext}"
    path = images_dir() / name
    if path.exists():
        return name
    try:
        raw = base64.b64decode(data, validate=False)
    except (binascii.Error, ValueError):
        logger.warning("tool image: undecodable base64 (%d chars)", len(data))
        return None
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        tmp = path.with_suffix(f".{os.getpid()}.tmp")
        tmp.write_bytes(raw)
        tmp.replace(path)
    except OSError:
        logger.exception("tool image: could not write %s", path)
        return None
    return name


def tool_result_images(content: Any) -> list[dict[str, str]]:
    """``[{url, media_type}]`` for the base64 images in a ``tool_result`` content list.

    Anything else (a string, text items, URL-sourced images, unknown types) yields nothing; a
    write failure drops that image rather than the result.
    """
    if not isinstance(content, list):
        return []
    out: list[dict[str, str]] = []
    for item in content:
        if not isinstance(item, dict) or item.get("type") != "image":
            continue
        source = item.get("source")
        if not isinstance(source, dict) or source.get("type") != "base64":
            continue
        data = source.get("data")
        media_type = source.get("media_type") or ""
        if not isinstance(data, str) or not data or not isinstance(media_type, str):
            continue
        name = _store(data, media_type)
        if name:
            out.append({"url": URL_PREFIX + name, "media_type": TYPE_BY_EXT[name.rsplit(".", 1)[1]]})
    return out
