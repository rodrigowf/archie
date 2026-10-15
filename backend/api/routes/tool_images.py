"""``GET /api/tool-images/<sha256>.<ext>`` — images from tool results (``utils/tool_images.py``).

The files are content-addressed, so a response never changes: clients may cache it forever. A
name that is not in the cache yet (history of another machine's session not read here yet) is a
404; reading that history writes it.
"""

from __future__ import annotations

from fastapi import APIRouter, HTTPException
from fastapi.responses import FileResponse

from utils.tool_images import NAME_RE, TYPE_BY_EXT, images_dir

router = APIRouter(prefix="/api/tool-images", tags=["tool-images"])


@router.get("/{name}")
def get_tool_image(name: str) -> FileResponse:
    if not NAME_RE.match(name):
        raise HTTPException(status_code=404, detail="Not found")
    path = images_dir() / name
    if not path.is_file():
        raise HTTPException(status_code=404, detail="Not found")
    return FileResponse(
        path,
        media_type=TYPE_BY_EXT[name.rsplit(".", 1)[1]],
        headers={"Cache-Control": "public, max-age=31536000, immutable"},
    )
