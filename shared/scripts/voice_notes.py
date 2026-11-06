"""List and read the voice notes recorded from the Voice notes canvas.

Notes live in ``context/recordings/voice-notes/<id>/`` (audio + transcript + metadata); this
script is the read path for agents and the orchestrator, which cannot open the canvas.

Usage::

    context/scripts/run.sh shared/scripts/voice_notes.py list [--limit N] [--full]
    context/scripts/run.sh shared/scripts/voice_notes.py read <id>
    context/scripts/run.sh shared/scripts/voice_notes.py latest [--full]
    context/scripts/run.sh shared/scripts/voice_notes.py delete <id>

Everything prints JSON on stdout. ``list`` truncates each transcript to a short excerpt unless
``--full`` is given, so a long history stays readable in a tool result.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

# Run from anywhere: point at the repo root so `transcription` imports.
_PROJECT_ROOT = Path(__file__).resolve().parents[2]  # shared/scripts/ → repo root
if str(_PROJECT_ROOT / "backend") not in sys.path:
    sys.path.insert(0, str(_PROJECT_ROOT / "backend"))

from transcription import notes  # noqa: E402

EXCERPT = 280


def _trim(note: dict, full: bool) -> dict:
    out = dict(note)
    text = out.get("text") or ""
    if not full and len(text) > EXCERPT:
        out["text"] = text[:EXCERPT].rstrip() + "…"
        out["truncated"] = True
    out["path"] = str(notes.notes_dir() / out["id"])
    return out


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)

    p_list = sub.add_parser("list", help="every note, newest first")
    p_list.add_argument("--limit", type=int, default=20)
    p_list.add_argument("--full", action="store_true", help="whole transcripts, not excerpts")

    p_read = sub.add_parser("read", help="one note with its full transcript")
    p_read.add_argument("id")

    p_latest = sub.add_parser("latest", help="the most recent note")
    p_latest.add_argument("--full", action="store_true")

    p_delete = sub.add_parser("delete", help="delete a note and its audio")
    p_delete.add_argument("id")

    args = parser.parse_args()

    if args.command == "list":
        found = notes.list_notes(limit=max(1, args.limit))
        print(json.dumps({"notes": [_trim(n, args.full) for n in found], "count": len(found)}, indent=2))
    elif args.command == "read":
        note = notes.get_note(args.id)
        if note is None:
            print(json.dumps({"error": f"no note {args.id!r}"}))
            return 1
        print(json.dumps(_trim(note, True), indent=2))
    elif args.command == "latest":
        found = notes.list_notes(limit=1)
        if not found:
            print(json.dumps({"error": "no voice notes yet"}))
            return 1
        print(json.dumps(_trim(found[0], args.full), indent=2))
    elif args.command == "delete":
        ok = notes.delete_note(args.id)
        print(json.dumps({"deleted": ok, "id": args.id}))
        return 0 if ok else 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
