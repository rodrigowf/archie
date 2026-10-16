"""File tools — read and write files anywhere on the host.

Absolute paths (and `~`) resolve as-is; relative paths resolve against the
project directory.
"""

from __future__ import annotations

import json
import logging
import re
from pathlib import Path
from typing import Any

from orchestrator.tools import registry

logger = logging.getLogger(__name__)

MAX_FILE_SIZE = 100_000  # 100KB max read size


def _resolve_path(base_dir: str, path: str) -> Path:
    """Resolve a path. Absolute paths and `~` are honored verbatim; relative
    paths resolve against `base_dir` (the project directory)."""
    expanded = Path(path).expanduser()
    if expanded.is_absolute():
        return expanded.resolve()
    return (Path(base_dir) / expanded).resolve()


# --- Memory-note provenance -------------------------------------------------
#
# Memory notes carry a `source:` frontmatter field naming the conversation the
# knowledge came from. The orchestrator cannot reliably know its own session
# UUID — it is only visible to the model as part of a file path — so when asked
# to fill that field it has been observed inventing well-formed UUIDs that
# match no conversation on disk (2026-10-16: a content-strategy note was
# written with the fabricated id `a4d9f5b8-...`).
#
# The session identity is available to this tool for free via
# ``context["session"]``, so we stamp it in code instead of trusting recall:
#
#   * ``written_by:`` is always set from the live session — a fact the model
#     never supplies and therefore cannot get wrong.
#   * ``written_at_turn:`` is the conversation's turn count at the instant of
#     writing, counted here from the JSONL. The orchestrator cannot derive turn
#     numbers (it sees a budgeted window, not the file), so this is the only
#     trustworthy turn anchor a self-written note can carry. It marks *when the
#     note was written*, which bounds the material it came from — it is not a
#     citation of the paragraphs' location.
#   * ``source:`` is filled in from the live session when the note omits it —
#     which is what the orchestrator is told to do, since "this conversation" is
#     the one provenance it never needs to recall. If present it is left alone,
#     unless it names a UUID that exists nowhere, which is rewritten to the real
#     one. Real UUIDs (a note digesting some *other* conversation), URLs and
#     ``curated (...)`` are preserved.

_UUID_RE = re.compile(
    r"\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
    r"[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b"
)


def _writing_session(context: dict[str, Any]) -> tuple[str | None, str | None]:
    """Return ``(session_uuid, title)`` for the session performing the write."""
    session = context.get("session")
    jsonl_path = getattr(session, "_jsonl_path", None) if session is not None else None
    if jsonl_path is None:
        return None, None
    uuid_str = Path(jsonl_path).stem
    title: str | None = None
    try:
        titles_file = Path(jsonl_path).parent / ".titles.json"
        if titles_file.is_file():
            value = json.loads(titles_file.read_text(encoding="utf-8")).get(uuid_str)
            if isinstance(value, str) and value.strip():
                title = value.strip()
    except Exception:  # a missing or malformed title file must never block a write
        logger.debug("could not read conversation title for %s", uuid_str, exc_info=True)
    return uuid_str, title


def _turn_count(jsonl_path: Path) -> int | None:
    """Count conversation turns in a session JSONL, as of right now.

    A *turn* is a ``user``/``assistant`` message entry, counted in file order —
    the same definition the memory wiki's paragraph source tags use. Tool calls,
    tool results, interruptions and metadata entries are not turns.

    The live session is still appending, so this is "the turn we are at as the
    note is written", which is the useful anchor: it bounds the material the
    note was digested from. Returns None if the file cannot be counted, which
    must never block a write.
    """
    try:
        count = 0
        with open(jsonl_path, encoding="utf-8", errors="replace") as fh:
            for line in fh:
                line = line.strip()
                if not line or '"user"' not in line and '"assistant"' not in line:
                    continue  # cheap pre-filter before paying for json.loads
                try:
                    entry = json.loads(line)
                except ValueError:
                    continue
                kind = entry.get("type")
                if kind in ("user", "assistant"):
                    count += 1
                else:
                    message = entry.get("message")
                    if isinstance(message, dict) and message.get("role") in ("user", "assistant"):
                        count += 1
        return count or None
    except Exception:
        logger.debug("could not count turns in %s", jsonl_path, exc_info=True)
        return None


def _conversation_exists(context_dir: Path, uuid_str: str) -> bool:
    """True if ``uuid_str`` names a conversation JSONL that exists on disk."""
    return (
        (context_dir / f"{uuid_str}.jsonl").is_file()
        or (context_dir / "chats" / f"{uuid_str}.jsonl").is_file()
    )


def _stamp_provenance(
    content: str, context: dict[str, Any], target: Path
) -> tuple[str, dict[str, Any]]:
    """Stamp the writing session into a memory note's frontmatter.

    Returns the (possibly rewritten) content plus a report of what changed, so
    the tool result can tell the model what was corrected.
    """
    report: dict[str, Any] = {}
    if "memory" not in target.parts or not content.startswith("---\n"):
        return content, report

    end = content.find("\n---", 4)
    if end == -1:
        return content, report
    head, body = content[4:end], content[end:]

    uuid_str, title = _writing_session(context)
    if not uuid_str:
        return content, report

    jsonl_path = getattr(context.get("session"), "_jsonl_path", None)
    context_dir = Path(jsonl_path).parent
    stamp = f'session {uuid_str}' + (f' ("{title}")' if title else "")

    # Replace UUIDs in `source:` that match no conversation on disk.
    def _fix_source(match: re.Match[str]) -> str:
        line = match.group(0)
        bogus = [
            u for u in _UUID_RE.findall(line)
            if not _conversation_exists(context_dir, u)
        ]
        if not bogus:
            return line
        report["source_corrected"] = bogus
        fixed = line
        for u in bogus:
            fixed = fixed.replace(u, uuid_str)
        return fixed

    new_head, n = re.subn(r"^source:.*$", _fix_source, head, count=1, flags=re.M)
    if n:
        head = new_head
    else:
        # No `source:` at all — the model is meant to omit it for a note written
        # out of this conversation, because this is the one fact we can state
        # with certainty. Fill it in.
        head = head.rstrip("\n") + f"\nsource: {stamp}"
        report["source_stamped"] = stamp

    # `written_by` and `written_at_turn` are owned by this tool: always rewrite.
    written_by = f"written_by: {stamp}"
    head, n = re.subn(r"^written_by:.*$", written_by, head, count=1, flags=re.M)
    if not n:
        head = head.rstrip("\n") + "\n" + written_by
    report["written_by"] = stamp

    turn = _turn_count(Path(jsonl_path))
    if turn is not None:
        written_at = f"written_at_turn: {turn}"
        head, n = re.subn(r"^written_at_turn:.*$", written_at, head, count=1, flags=re.M)
        if not n:
            head = head.rstrip("\n") + "\n" + written_at
        report["written_at_turn"] = turn

    return "---\n" + head + body, report


@registry.register(
    name="read_file",
    description=(
        "Read a file. Absolute paths read from anywhere on the host; relative "
        "paths resolve against the project directory. Optionally pass "
        "start_line/end_line (1-indexed, inclusive) to read just a slice — "
        "useful for large files. When the response gets truncated by the "
        "byte limit you'll see a marker like '[truncated at line X of Y total "
        "— call read_file again with start_line=X+1 to continue]' so you can "
        "page through the rest."
    ),
    input_schema={
        "type": "object",
        "properties": {
            "path": {
                "type": "string",
                "description": "Absolute path (e.g. '/etc/hosts', '~/notes.txt') or path relative to the project root (e.g. 'CLAUDE.md', 'context/memory/MEMORY.md').",
            },
            "start_line": {
                "type": "integer",
                "minimum": 1,
                "description": "1-indexed first line to return (inclusive). Default: 1 (start of file).",
            },
            "end_line": {
                "type": "integer",
                "minimum": 1,
                "description": "1-indexed last line to return (inclusive). Default: end of file. Clamped to total line count.",
            },
        },
        "required": ["path"],
    },
)
async def read_file(
    context: dict[str, Any],
    path: str,
    start_line: int | None = None,
    end_line: int | None = None,
) -> str:
    project_dir = context.get("project_dir", "")
    if not project_dir:
        return json.dumps({"error": "Project directory not configured"})

    target = _resolve_path(project_dir, path)

    if not target.is_file():
        return json.dumps({"error": f"File not found: {path}"})

    try:
        raw = target.read_text(encoding="utf-8")
    except Exception as e:
        return json.dumps({"error": f"Failed to read file: {e}"})

    lines = raw.splitlines(keepends=True)
    total_lines = len(lines)

    # Normalise the range. start_line defaults to 1; end_line defaults to EOF.
    # Both are clamped to [1, total_lines]; an inverted range yields empty.
    s = 1 if start_line is None else max(1, start_line)
    e = total_lines if end_line is None else min(total_lines, max(1, end_line))

    if total_lines == 0:
        # Empty file — preserve original behaviour (return empty content).
        return json.dumps({
            "path": str(target),
            "content": "",
            "start_line": 1,
            "end_line": 0,
            "total_lines": 0,
        })

    if s > total_lines:
        return json.dumps({
            "error": (
                f"start_line {s} is past end of file "
                f"(total {total_lines} lines)"
            ),
            "path": str(target),
            "total_lines": total_lines,
        })

    sliced = "".join(lines[s - 1:e])
    returned_end = e

    # Byte-size truncation — count whole lines so the marker can name a
    # line number the model can resume from.
    if len(sliced) > MAX_FILE_SIZE:
        kept: list[str] = []
        running = 0
        last_idx = s - 1
        for idx in range(s - 1, e):
            chunk = lines[idx]
            if running + len(chunk) > MAX_FILE_SIZE and kept:
                break
            kept.append(chunk)
            running += len(chunk)
            last_idx = idx
        sliced = "".join(kept)
        returned_end = last_idx + 1
        next_line = returned_end + 1
        marker = (
            f"\n... [truncated at line {returned_end} of {total_lines} total "
            f"— call read_file again with start_line={next_line} to continue]"
        )
        sliced += marker

    return json.dumps({
        "path": str(target),
        "content": sliced,
        "start_line": s,
        "end_line": returned_end,
        "total_lines": total_lines,
    })


@registry.register(
    name="write_file",
    description="Write content to a file. Absolute paths write anywhere on the host; relative paths resolve against the project directory. Creates parent directories if needed.",
    input_schema={
        "type": "object",
        "properties": {
            "path": {
                "type": "string",
                "description": "Absolute path or path relative to the project root.",
            },
            "content": {
                "type": "string",
                "description": "The content to write to the file.",
            },
        },
        "required": ["path", "content"],
    },
)
async def write_file(context: dict[str, Any], path: str, content: str) -> str:
    project_dir = context.get("project_dir", "")
    if not project_dir:
        return json.dumps({"error": "Project directory not configured"})

    target = _resolve_path(project_dir, path)

    content, provenance = _stamp_provenance(content, context, target)

    try:
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content, encoding="utf-8")
        result: dict[str, Any] = {
            "path": str(target), "status": "written", "bytes": len(content),
        }
        if provenance.get("written_by"):
            result["written_by"] = provenance["written_by"]
        if provenance.get("written_at_turn"):
            result["written_at_turn"] = provenance["written_at_turn"]
        if provenance.get("source_stamped"):
            result["source_stamped"] = provenance["source_stamped"]
        if provenance.get("source_corrected"):
            result["source_corrected"] = provenance["source_corrected"]
            result["note"] = (
                "The `source:` field named conversation id(s) that do not exist "
                "on disk; they were replaced with this conversation's real id. "
                "Never compose a session UUID — if you do not have one, write "
                "`curated (<reason>)`."
            )
        return json.dumps(result)
    except Exception as e:
        return json.dumps({"error": f"Failed to write file: {e}"})
