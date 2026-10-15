"""Images in tool results → cached files + ``images: [{url, media_type}]`` (utils/tool_images.py)."""

from __future__ import annotations

import base64
import hashlib

import pytest
from fastapi.testclient import TestClient

from api.serializers import serialize_event
from manager.protocol import extract_blocks
from manager.types import ToolResult
from utils.tool_images import images_dir, tool_result_images

PNG = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
)
PNG_B64 = base64.b64encode(PNG).decode()
NAME = hashlib.sha256(PNG_B64.encode()).hexdigest() + ".png"


def _image(data: str = PNG_B64, media_type: str = "image/png") -> dict:
    return {"type": "image", "source": {"type": "base64", "media_type": media_type, "data": data}}


@pytest.fixture(autouse=True)
def _state_dir(tmp_path, monkeypatch):
    monkeypatch.setenv("ARCHIE_STATE_DIR", str(tmp_path))


def test_images_are_written_once_and_named_by_hash():
    out = tool_result_images([{"type": "text", "text": "x"}, _image(), _image()])
    assert out == [{"url": f"/api/tool-images/{NAME}", "media_type": "image/png"}] * 2
    assert (images_dir() / NAME).read_bytes() == PNG
    assert [p.name for p in images_dir().iterdir()] == [NAME]


def test_non_images_and_unknown_shapes_yield_nothing():
    assert tool_result_images("text") == []
    assert tool_result_images(None) == []
    assert tool_result_images([{"type": "text", "text": "a"}]) == []
    assert tool_result_images([{"type": "image", "source": {"type": "url", "url": "http://x"}}]) == []
    assert tool_result_images([_image(media_type="image/tiff")]) == []
    assert tool_result_images([{"type": "image", "source": {"type": "base64", "data": "xx"}}]) == []


def test_jpeg_maps_to_jpg():
    out = tool_result_images([_image(media_type="image/jpeg")])
    assert out[0]["url"].endswith(".jpg") and out[0]["media_type"] == "image/jpeg"


def test_history_block_carries_images_and_text():
    msg = {"message": {"content": [{
        "type": "tool_result", "tool_use_id": "toolu_1",
        "content": [_image(), {"type": "text", "text": "caption"}],
    }]}}
    [b] = extract_blocks(msg)
    assert b.output == "caption"
    assert b.images == [{"url": f"/api/tool-images/{NAME}", "media_type": "image/png"}]


def test_history_block_without_images_has_none():
    [b] = extract_blocks({"message": {"content": [{"type": "tool_result", "tool_use_id": "t", "content": "ok"}]}})
    assert b.images is None


def test_frame_has_images_only_when_present():
    img = {"url": f"/api/tool-images/{NAME}", "media_type": "image/png"}
    assert serialize_event(ToolResult(tool_use_id="t", output="")) == {
        "type": "tool_result", "tool_use_id": "t", "output": "", "is_error": False,
    }
    assert serialize_event(ToolResult(tool_use_id="t", output="", images=(img,)))["images"] == [img]


def test_route_serves_cached_image_and_rejects_other_names(tmp_path, monkeypatch):
    import utils.paths
    from api.app import create_app

    monkeypatch.setattr(utils.paths, "PROJECT_ROOT", tmp_path)
    tool_result_images([_image()])
    client = TestClient(create_app())
    r = client.get(f"/api/tool-images/{NAME}")
    assert r.status_code == 200 and r.content == PNG
    assert r.headers["content-type"] == "image/png"
    assert "immutable" in r.headers["cache-control"]
    assert client.get("/api/tool-images/" + "0" * 64 + ".png").status_code == 404
    assert client.get("/api/tool-images/..%2F..%2Fetc%2Fpasswd").status_code == 404
    assert client.get("/api/tool-images/abc.png").status_code == 404
