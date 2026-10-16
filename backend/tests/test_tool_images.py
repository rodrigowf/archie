"""Images in tool results, served from the session JSONL (utils/tool_images.py, spec 12 R-10)."""

from __future__ import annotations

import base64
import json
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from api.serializers import serialize_event
from manager.protocol import extract_blocks
from manager.types import ToolResult
from utils import tool_images
from utils.tool_images import find_image, live_tool_result_images, session_files, tool_result_images

PNG = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
)
PNG_B64 = base64.b64encode(PNG).decode()
JPG = b"\xff\xd8\xff\xe0fake-jpeg"
SID = "0b8f2a52-1111-4222-8333-944455556666"


def _image(data: str = PNG_B64, media_type: str = "image/png") -> dict:
    return {"type": "image", "source": {"type": "base64", "media_type": media_type, "data": data}}


def _result_line(tool_use_id: str, content) -> str:
    return json.dumps({"type": "user", "message": {"role": "user", "content": [
        {"tool_use_id": tool_use_id, "type": "tool_result", "content": content},
    ]}}) + "\n"


@pytest.fixture(autouse=True)
def _fresh_caches():
    tool_images.clear_caches()
    yield
    tool_images.clear_caches()


def test_urls_name_the_place_of_each_image():
    content = [{"type": "text", "text": "x"}, _image(), _image(base64.b64encode(JPG).decode(), "image/jpeg")]
    assert tool_result_images(content, SID, "toolu_1") == [
        {"url": f"/api/sessions/{SID}/tool-images/toolu_1/0", "media_type": "image/png"},
        {"url": f"/api/sessions/{SID}/tool-images/toolu_1/1", "media_type": "image/jpeg"},
    ]


def test_no_urls_without_images_or_without_a_findable_address():
    assert tool_result_images("text", SID, "t") == []
    assert tool_result_images([{"type": "text", "text": "a"}], SID, "t") == []
    assert tool_result_images([{"type": "image", "source": {"type": "url", "url": "http://x"}}], SID, "t") == []
    assert tool_result_images([_image(media_type="image/tiff")], SID, "t") == []
    assert tool_result_images([{"type": "image", "source": {"type": "base64", "data": "xx"}}], SID, "t") == []
    assert tool_result_images([_image()], None, "t") == []
    assert tool_result_images([_image()], SID, "") == []
    assert tool_result_images([_image()], "../etc", "t") == []


def test_unsupported_items_do_not_shift_the_index(tmp_path: Path):
    """n counts only servable images, the same on both sides."""
    content = [_image(media_type="image/tiff"), _image()]
    [img] = tool_result_images(content, SID, "toolu_1")
    assert img["url"].endswith("/0")
    f = tmp_path / f"{SID}.jsonl"
    f.write_text(_result_line("toolu_1", content))
    assert find_image(SID, "toolu_1", 0, [f]) == (PNG, "image/png")


def test_history_block_carries_urls_and_text():
    msg = {"message": {"content": [{
        "type": "tool_result", "tool_use_id": "toolu_1",
        "content": [_image(), {"type": "text", "text": "caption"}],
    }]}}
    [b] = extract_blocks(msg, SID)
    assert b.output == "caption"
    assert b.images == [{"url": f"/api/sessions/{SID}/tool-images/toolu_1/0", "media_type": "image/png"}]
    [plain] = extract_blocks({"message": {"content": [{"type": "tool_result", "tool_use_id": "t", "content": "ok"}]}}, SID)
    assert plain.images is None


def test_frame_has_images_only_when_present():
    img = {"url": f"/api/sessions/{SID}/tool-images/t/0", "media_type": "image/png"}
    assert serialize_event(ToolResult(tool_use_id="t", output="")) == {
        "type": "tool_result", "tool_use_id": "t", "output": "", "is_error": False,
    }
    assert serialize_event(ToolResult(tool_use_id="t", output="", images=(img,)))["images"] == [img]


def test_found_in_the_jsonl_and_in_subagent_transcripts(tmp_path: Path):
    main = tmp_path / f"{SID}.jsonl"
    main.write_text('{"type":"user","message":{"content":"hi"}}\n' + _result_line("toolu_main", [_image()]))
    sub = tmp_path / SID / "subagents" / "agent-a1.jsonl"
    sub.parent.mkdir(parents=True)
    sub.write_text(_result_line("toolu_sub", [_image(base64.b64encode(JPG).decode(), "image/jpeg")]))
    files = session_files(main)
    assert files == [main, sub]
    assert find_image(SID, "toolu_main", 0, files) == (PNG, "image/png")
    assert find_image(SID, "toolu_sub", 0, files) == (JPG, "image/jpeg")
    assert find_image(SID, "toolu_main", 1, files) is None
    assert find_image(SID, "toolu_nope", 0, files) is None


def test_index_follows_an_appended_file_and_a_rewound_one(tmp_path: Path):
    f = tmp_path / f"{SID}.jsonl"
    f.write_text(_result_line("toolu_a", [_image()]))
    assert find_image(SID, "toolu_a", 0, [f]) is not None
    assert find_image(SID, "toolu_b", 0, [f]) is None            # not written yet
    with f.open("a") as out:
        out.write(_result_line("toolu_b", [_image()]))
        out.write(_result_line("toolu_c", [_image()])[:40])      # partial line, still being written
    assert find_image(SID, "toolu_b", 0, [f]) == (PNG, "image/png")
    assert find_image(SID, "toolu_c", 0, [f]) is None
    tool_images.clear_caches()
    f.write_text(_result_line("toolu_z", [_image()]))            # rewound: shorter than indexed
    assert find_image(SID, "toolu_z", 0, [f]) == (PNG, "image/png")


def test_live_results_are_served_before_the_jsonl_has_them(tmp_path: Path):
    [img] = live_tool_result_images([_image()], SID, "toolu_live")
    assert img["url"] == f"/api/sessions/{SID}/tool-images/toolu_live/0"
    assert find_image(SID, "toolu_live", 0, []) == (PNG, "image/png")


def test_nothing_is_written_to_disk(tmp_path: Path, monkeypatch):
    monkeypatch.setenv("ARCHIE_STATE_DIR", str(tmp_path / "state"))
    f = tmp_path / f"{SID}.jsonl"
    f.write_text(_result_line("toolu_a", [_image()]))
    live_tool_result_images([_image()], SID, "toolu_b")
    extract_blocks({"message": {"content": [{"type": "tool_result", "tool_use_id": "t", "content": [_image()]}]}}, SID)
    find_image(SID, "toolu_a", 0, [f])
    assert not (tmp_path / "state").exists()
    assert sorted(p.name for p in tmp_path.iterdir()) == [f.name]


def test_memory_cache_drops_least_recently_used():
    cache = tool_images._MemoryCache(10)
    cache.put(("s", "a", 0), (b"12345", "image/png"))
    cache.put(("s", "b", 0), (b"12345", "image/png"))
    assert cache.get(("s", "a", 0)) is not None                  # a is now the most recent
    cache.put(("s", "c", 0), (b"12345", "image/png"))
    assert cache.get(("s", "b", 0)) is None
    assert cache.get(("s", "a", 0)) is not None and cache.get(("s", "c", 0)) is not None
    cache.put(("s", "big", 0), (b"x" * 11, "image/png"))         # larger than the cap: not kept
    assert cache.get(("s", "big", 0)) is None


def test_route_serves_from_the_session_jsonl(tmp_path: Path, monkeypatch):
    import utils.paths
    from api.app import create_app
    from api.deps import get_store
    from manager.store import SessionStore

    monkeypatch.setattr(utils.paths, "PROJECT_ROOT", tmp_path)
    ctx = tmp_path / "context"
    ctx.mkdir()
    (ctx / f"{SID}.jsonl").write_text(_result_line("toolu_1", [_image()]))
    app = create_app()
    store = SessionStore(tmp_path)
    app.dependency_overrides[get_store] = lambda: store
    client = TestClient(app)
    r = client.get(f"/api/sessions/{SID}/tool-images/toolu_1/0")
    assert r.status_code == 200 and r.content == PNG
    assert r.headers["content-type"] == "image/png"
    assert "immutable" in r.headers["cache-control"]
    for bad in (f"{SID}/tool-images/toolu_1/1", f"{SID}/tool-images/toolu_2/0",
                f"nope/tool-images/toolu_1/0", f"{SID}/tool-images/toolu_1/-1", f"{SID}/tool-images/to.lu/0"):
        assert client.get(f"/api/sessions/{bad}").status_code == 404, bad
    assert client.get(f"/api/sessions/{SID}/tool-images/toolu_1/x").status_code == 422
