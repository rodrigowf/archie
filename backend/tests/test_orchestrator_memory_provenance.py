"""write_file stamps the writing session into memory-note frontmatter.

The orchestrator cannot reliably recall its own session UUID (it only appears
in the prompt as part of a file path), and has been observed inventing
well-formed UUIDs to satisfy the `source:` frontmatter rule. The session
identity is available to the tool via ``context["session"]``, so it is stamped
in code rather than trusted to recall.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from orchestrator.tools.files import write_file

REAL_ID = "26a0098b-cb9b-4740-b045-75907c075b06"
OTHER_ID = "69923e6e-a722-4140-acbb-40bfd888c9a4"
FAKE_ID = "a4d9f5b8-3c2a-4e7d-9b1f-6e8d2a4c7b9e"

# Five real turns, plus entries that are deliberately NOT turns: a tool call, a
# tool result, an interruption and a metadata line. The turn count must be 5.
CONVERSATION = "".join(
    json.dumps(entry) + "\n"
    for entry in [
        {"type": "user", "message": {"role": "user", "content": "hi"}},
        {"type": "assistant", "message": {"role": "assistant", "content": "hello"}},
        {"type": "tool_use", "tool_name": "search_memory"},
        {"type": "tool_result", "content": "..."},
        {"type": "user", "message": {"role": "user", "content": "tell me"}},
        {"type": "voice_interrupted"},
        {"type": "assistant", "message": {"role": "assistant", "content": "sure"}},
        {"type": "orchestrator_meta", "note": "ignored"},
        {"type": "user", "message": {"role": "user", "content": "save that"}},
    ]
)


class _Session:
    def __init__(self, jsonl_path: Path) -> None:
        self._jsonl_path = jsonl_path


@pytest.fixture()
def env(tmp_path: Path):
    """A project dir with a context/ holding two real conversations."""
    ctx = tmp_path / "context"
    ctx.mkdir()
    (ctx / f"{REAL_ID}.jsonl").write_text(CONVERSATION, encoding="utf-8")
    (ctx / f"{OTHER_ID}.jsonl").write_text("{}\n", encoding="utf-8")
    (ctx / ".titles.json").write_text(
        json.dumps({REAL_ID: "Qwen bio talk"}), encoding="utf-8"
    )
    (ctx / "memory").mkdir()
    return {
        "project_dir": str(tmp_path),
        "session": _Session(ctx / f"{REAL_ID}.jsonl"),
    }


def _note(source: str | None) -> str:
    """A memory note; ``source=None`` omits the field, as the model is told to."""
    source_line = f"source: {source}\n" if source is not None else ""
    return (
        "---\n"
        "name: demo\n"
        "category: projects\n"
        f"{source_line}"
        "---\n\n"
        "# Demo\n\nBody text.\n"
    )


async def _write(env, rel: str, content: str) -> dict:
    return json.loads(await write_file(context=env, path=rel, content=content))


@pytest.mark.asyncio
async def test_written_by_is_stamped_from_the_live_session(env, tmp_path):
    res = await _write(env, "context/memory/projects/demo.md", _note("curated (test)"))

    assert res["status"] == "written"
    assert res["written_by"] == f'session {REAL_ID} ("Qwen bio talk")'

    text = (tmp_path / "context/memory/projects/demo.md").read_text()
    assert f'written_by: session {REAL_ID} ("Qwen bio talk")' in text
    # A legitimate non-UUID source is untouched.
    assert "source: curated (test)" in text
    assert "source_corrected" not in res


@pytest.mark.asyncio
async def test_invented_uuid_in_source_is_replaced_with_the_real_one(env, tmp_path):
    res = await _write(
        env,
        "context/memory/projects/demo.md",
        _note(f'session {FAKE_ID} ("Content Strategy Discussion")'),
    )

    assert res["source_corrected"] == [FAKE_ID]
    assert "Never compose a session UUID" in res["note"]

    text = (tmp_path / "context/memory/projects/demo.md").read_text()
    assert FAKE_ID not in text
    assert f"source: session {REAL_ID}" in text


@pytest.mark.asyncio
async def test_a_real_other_conversation_is_preserved(env, tmp_path):
    """Digesting another conversation is legitimate — don't rewrite it."""
    res = await _write(env, "context/memory/projects/demo.md", _note(f"session {OTHER_ID}"))

    assert "source_corrected" not in res
    text = (tmp_path / "context/memory/projects/demo.md").read_text()
    assert f"source: session {OTHER_ID}" in text
    # ...but we still record who physically wrote it.
    assert f"written_by: session {REAL_ID}" in text


@pytest.mark.asyncio
async def test_existing_written_by_is_overwritten_not_duplicated(env, tmp_path):
    content = _note("curated (test)").replace(
        "source: curated (test)",
        "source: curated (test)\nwritten_by: session deadbeef-dead-beef-dead-beefdeadbeef",
    )
    await _write(env, "context/memory/projects/demo.md", content)

    text = (tmp_path / "context/memory/projects/demo.md").read_text()
    assert text.count("written_by:") == 1
    assert "deadbeef" not in text


@pytest.mark.asyncio
async def test_non_memory_writes_are_untouched(env, tmp_path):
    res = await _write(env, "notes/scratch.md", _note(f"session {FAKE_ID}"))

    assert "written_by" not in res
    text = (tmp_path / "notes/scratch.md").read_text()
    assert "written_by:" not in text
    assert FAKE_ID in text  # not our business outside memory/


@pytest.mark.asyncio
async def test_memory_file_without_frontmatter_is_untouched(env, tmp_path):
    body = "# Just prose\n\nNo frontmatter here.\n"
    res = await _write(env, "context/memory/ORCHESTRATOR_MEMORY.md", body)

    assert "written_by" not in res
    assert (tmp_path / "context/memory/ORCHESTRATOR_MEMORY.md").read_text() == body


@pytest.mark.asyncio
async def test_missing_session_in_context_does_not_block_the_write(tmp_path):
    env = {"project_dir": str(tmp_path)}
    res = await _write(env, "context/memory/projects/demo.md", _note("curated (test)"))

    assert res["status"] == "written"
    assert "written_by" not in res


@pytest.mark.asyncio
async def test_missing_titles_file_still_stamps_the_uuid(tmp_path):
    ctx = tmp_path / "context"
    (ctx / "memory").mkdir(parents=True)
    (ctx / f"{REAL_ID}.jsonl").write_text("{}\n", encoding="utf-8")
    env = {"project_dir": str(tmp_path), "session": _Session(ctx / f"{REAL_ID}.jsonl")}

    res = await _write(env, "context/memory/projects/demo.md", _note("curated (test)"))

    assert res["written_by"] == f"session {REAL_ID}"


@pytest.mark.asyncio
async def test_omitted_source_is_filled_from_the_live_session(env, tmp_path):
    """The model is told to omit `source` for its own conversation; we fill it."""
    res = await _write(env, "context/memory/projects/demo.md", _note(None))

    expected = f'session {REAL_ID} ("Qwen bio talk")'
    assert res["source_stamped"] == expected
    assert "source_corrected" not in res

    text = (tmp_path / "context/memory/projects/demo.md").read_text()
    assert f"source: {expected}" in text
    assert text.count("source:") == 1
    # The body is untouched and the frontmatter is still well-formed.
    assert text.startswith("---\n")
    assert "# Demo" in text


@pytest.mark.asyncio
async def test_omitted_source_outside_memory_is_not_invented(env, tmp_path):
    res = await _write(env, "notes/scratch.md", _note(None))

    assert "source_stamped" not in res
    assert "source:" not in (tmp_path / "notes/scratch.md").read_text()


@pytest.mark.asyncio
async def test_written_at_turn_is_counted_from_the_jsonl(env, tmp_path):
    """The tool counts turns itself — the model cannot, so it never supplies them."""
    res = await _write(env, "context/memory/projects/demo.md", _note(None))

    assert res["written_at_turn"] == 5  # tool calls/results/interrupts are not turns

    text = (tmp_path / "context/memory/projects/demo.md").read_text()
    assert "written_at_turn: 5" in text


@pytest.mark.asyncio
async def test_written_at_turn_is_overwritten_not_duplicated(env, tmp_path):
    content = _note(None).replace(
        "category: projects", "category: projects\nwritten_at_turn: 99999"
    )
    await _write(env, "context/memory/projects/demo.md", content)

    text = (tmp_path / "context/memory/projects/demo.md").read_text()
    assert text.count("written_at_turn:") == 1
    assert "99999" not in text
    assert "written_at_turn: 5" in text


@pytest.mark.asyncio
async def test_uncountable_conversation_does_not_block_the_write(tmp_path):
    """A missing JSONL must cost the turn field, not the write."""
    ctx = tmp_path / "context"
    (ctx / "memory").mkdir(parents=True)
    env = {
        "project_dir": str(tmp_path),
        "session": _Session(ctx / f"{REAL_ID}.jsonl"),  # never created
    }

    res = await _write(env, "context/memory/projects/demo.md", _note(None))

    assert res["status"] == "written"
    assert "written_at_turn" not in res
    assert f"written_by: session {REAL_ID}" in (
        tmp_path / "context/memory/projects/demo.md"
    ).read_text()
