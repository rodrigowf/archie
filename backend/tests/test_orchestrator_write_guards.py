"""write_file refuses accidental overwrites.

`write_file` replaces the whole file, so a short payload destroys a long one.
On 2026-10-16 the orchestrator wrote 751 bytes over a 19 KB memory note; it
happened to read back, notice and restore, but the tool allowed it. Two guards,
both overridable with `confirm=True`:

  1. a write that would cut an existing file below half its size
  2. a write replacing a file this conversation has not read, in full, recently
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from orchestrator.tools.files import read_file, write_file
from orchestrator.tools import files as files_mod

SESSION_ID = "26a0098b-cb9b-4740-b045-75907c075b06"
BIG = "".join(f"line {i} xxxxxxxxxxxxxxxxxxxx\n" for i in range(600))  # multi-line, ~20 KB


class _Session:
    def __init__(self, jsonl_path: Path) -> None:
        self._jsonl_path = jsonl_path


def _turns(n: int) -> str:
    return "".join(
        json.dumps({"type": "user" if i % 2 == 0 else "assistant",
                    "message": {"role": "user" if i % 2 == 0 else "assistant",
                                "content": f"m{i}"}}) + "\n"
        for i in range(n)
    )


@pytest.fixture(autouse=True)
def _clean_state():
    files_mod._READS.clear()
    files_mod._TURN_CACHE.clear()
    yield
    files_mod._READS.clear()
    files_mod._TURN_CACHE.clear()


@pytest.fixture()
def env(tmp_path: Path):
    ctx = tmp_path / "context"
    (ctx / "memory" / "projects").mkdir(parents=True)
    (ctx / f"{SESSION_ID}.jsonl").write_text(_turns(10), encoding="utf-8")
    (ctx / "memory" / "projects" / "note.md").write_text(BIG, encoding="utf-8")
    return {
        "project_dir": str(tmp_path),
        "session": _Session(ctx / f"{SESSION_ID}.jsonl"),
    }


NOTE = "context/memory/projects/note.md"


async def _write(env, rel, content, **kw):
    return json.loads(await write_file(context=env, path=rel, content=content, **kw))


async def _read(env, rel, **kw):
    return json.loads(await read_file(context=env, path=rel, **kw))


@pytest.mark.asyncio
async def test_dramatic_shrink_is_refused(env, tmp_path):
    await _read(env, NOTE)  # full read, so only the shrink guard can fire
    res = await _write(env, NOTE, "tiny stub")

    assert "refused" in res["error"]
    assert res["existing_bytes"] == len(BIG)
    assert res["new_bytes"] == len("tiny stub")
    assert "confirm=true" in res["why"]
    # The file is untouched.
    assert (tmp_path / NOTE).read_text() == BIG


@pytest.mark.asyncio
async def test_shrink_goes_through_with_confirm(env, tmp_path):
    await _read(env, NOTE)
    res = await _write(env, NOTE, "tiny stub", confirm=True)

    assert res["status"] == "written"
    assert res["confirmed_overwrite"] is True
    assert (tmp_path / NOTE).read_text() == "tiny stub"


@pytest.mark.asyncio
async def test_overwriting_an_unread_file_is_refused(env, tmp_path):
    res = await _write(env, NOTE, BIG.replace("x", "y"))  # same size, never read

    assert "not just read in full" in res["error"]
    assert "have not read this file" in res["why"]
    assert (tmp_path / NOTE).read_text() == BIG


@pytest.mark.asyncio
async def test_a_partial_read_does_not_authorise_an_overwrite(env, tmp_path):
    await _read(env, NOTE, start_line=1, end_line=1)  # a slice, not the file
    res = await _write(env, NOTE, BIG.replace("x", "y"))

    assert "only read part of this file" in res["why"]
    assert (tmp_path / NOTE).read_text() == BIG


@pytest.mark.asyncio
async def test_a_full_read_authorises_a_same_size_rewrite(env, tmp_path):
    await _read(env, NOTE)
    res = await _write(env, NOTE, BIG.replace("x", "y"))

    assert res["status"] == "written"
    assert "confirmed_overwrite" not in res
    assert "yyy" in (tmp_path / NOTE).read_text()


@pytest.mark.asyncio
async def test_a_stale_read_is_refused(env, tmp_path):
    await _read(env, NOTE)
    # The conversation moves on well past the recency window.
    (tmp_path / "context" / f"{SESSION_ID}.jsonl").write_text(_turns(40), encoding="utf-8")

    res = await _write(env, NOTE, BIG.replace("x", "y"))

    assert "turns ago" in res["why"]
    assert (tmp_path / NOTE).read_text() == BIG


@pytest.mark.asyncio
async def test_a_new_file_needs_no_prior_read(env, tmp_path):
    res = await _write(env, "context/memory/projects/brand_new.md", "# New\n")

    assert res["status"] == "written"
    assert (tmp_path / "context/memory/projects/brand_new.md").is_file()


@pytest.mark.asyncio
async def test_growing_an_unread_file_is_still_refused(env):
    """The read guard is about not destroying what you did not see."""
    res = await _write(env, NOTE, BIG * 2)

    assert "refused" in res["error"]


@pytest.mark.asyncio
async def test_an_empty_existing_file_is_not_protected(env, tmp_path):
    empty = tmp_path / "context/memory/projects/empty.md"
    empty.write_text("", encoding="utf-8")

    res = await _write(env, "context/memory/projects/empty.md", "# Content\n")

    assert res["status"] == "written"


@pytest.mark.asyncio
async def test_reads_are_tracked_per_session(env, tmp_path):
    """A read in one conversation must not authorise a write in another."""
    await _read(env, NOTE)

    other = dict(env)
    other_jsonl = tmp_path / "context" / "11111111-2222-3333-4444-555555555555.jsonl"
    other_jsonl.write_text(_turns(4), encoding="utf-8")
    other["session"] = _Session(other_jsonl)

    res = await _write(other, NOTE, "tiny")
    assert "refused" in res["error"]
