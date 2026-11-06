"""Voice notes on disk: one folder per note, written while you are still talking.

    context/recordings/voice-notes/20261105T214500-tarot-daily-draw/
        audio.mp3        the recording (WAV during capture, converted on stop)
        transcript.md    what was said, rewritten as segments commit
        meta.json        id, title, timing, which transcriber produced the text

Audio is appended **as it arrives** rather than uploaded in one piece at the
end. That is what makes an unbounded recording safe: nothing is held in the
browser, a 40-minute note costs the backend one open file handle, and a tab
that crashes mid-note still leaves the audio and the transcript behind.

The folder sits under ``context/recordings/``, which the context repo already
gitignores (audio is far too big for git) and which context-sync mirrors to the
other machine out of band. The transcript travels with its audio for the same
reason: a note is one unit, and anything worth keeping gets digested into the
memory wiki later anyway.
"""

from __future__ import annotations

import asyncio
import json
import logging
import re
import shutil
import time
import wave
from datetime import datetime
from pathlib import Path
from typing import Any

from utils.paths import get_context_dir

logger = logging.getLogger(__name__)

NOTES_DIRNAME = "voice-notes"
TRANSCRIPT_NAME = "transcript.md"
META_NAME = "meta.json"
WAV_NAME = "audio.wav"
MP3_NAME = "audio.mp3"

#: ``<YYYYMMDD>T<HHMMSS>-<slug>`` — sorts chronologically, reads as a date.
NOTE_ID_RE = re.compile(r"^\d{8}T\d{6}-[a-z0-9-]{1,60}$")

FFMPEG_TIMEOUT_S = 120.0


def notes_dir() -> Path:
    return get_context_dir() / "recordings" / NOTES_DIRNAME


def _slug(title: str) -> str:
    slug = re.sub(r"[^a-z0-9]+", "-", (title or "").lower()).strip("-")
    return (slug[:60].rstrip("-")) or "note"


def _note_path(note_id: str) -> Path | None:
    """The folder for *note_id*, or None if the id is not one we wrote.

    The id reaches here from a URL, so it is validated against the exact shape
    :func:`create` produces — no separators, no dots, nothing to walk out of
    the notes directory with.
    """
    if not NOTE_ID_RE.match(note_id or ""):
        return None
    path = notes_dir() / note_id
    if path.parent != notes_dir():  # belt and braces
        return None
    return path


def _fmt_duration(ms: int) -> str:
    total = max(0, ms) // 1000
    return f"{total // 60}:{total % 60:02d}"


class NoteWriter:
    """An open recording. Feed it audio and text; finish with :meth:`finalize`."""

    def __init__(self, note_id: str, directory: Path, sample_rate: int, title: str) -> None:
        self.id = note_id
        self.dir = directory
        self.sample_rate = sample_rate
        self.title = title
        self.started_at = time.time()
        self._wav = wave.open(str(directory / WAV_NAME), "wb")
        self._wav.setnchannels(1)
        self._wav.setsampwidth(2)
        self._wav.setframerate(sample_rate)
        self._frames = 0
        self._text = ""
        # Two different "done"s: the WAV handle is closed before ffmpeg runs,
        # but the transcript is rewritten once more after that with the final
        # duration — so audio and text stop accepting writes at different moments.
        self._wav_closed = False
        self.finalized = False

    @property
    def duration_ms(self) -> int:
        return int(self._frames * 1000 / self.sample_rate) if self.sample_rate else 0

    def write_audio(self, pcm: bytes) -> None:
        if self._wav_closed or not pcm:
            return
        self._wav.writeframes(pcm)
        self._frames += len(pcm) // 2

    def set_text(self, text: str) -> None:
        """Replace the transcript. Cheap enough to call on every commit."""
        if self.finalized:
            return
        self._text = text
        try:
            (self.dir / TRANSCRIPT_NAME).write_text(self._render(), encoding="utf-8")
        except OSError:
            logger.exception("Could not write the transcript for note %s", self.id)

    def _render(self) -> str:
        when = datetime.fromtimestamp(self.started_at).strftime("%Y-%m-%d %H:%M")
        return (
            f"# {self.title}\n\n"
            f"*Recorded {when} · {_fmt_duration(self.duration_ms)}*\n\n"
            f"{self._text.strip()}\n"
        )

    def _close_wav(self) -> None:
        if not self._wav_closed:
            self._wav_closed = True
            try:
                self._wav.close()
            except Exception:
                logger.exception("Could not close the WAV for note %s", self.id)

    async def finalize(self, *, provider: str = "", model: str = "", language: str = "") -> dict[str, Any]:
        """Close the recording, convert it to MP3 and write ``meta.json``."""
        duration_ms = self.duration_ms
        self._close_wav()
        audio_name = await _to_mp3(self.dir)
        meta = {
            "id": self.id,
            "title": self.title,
            "created": datetime.fromtimestamp(self.started_at).isoformat(timespec="seconds"),
            "created_ts": self.started_at,
            "duration_ms": duration_ms,
            "sample_rate": self.sample_rate,
            "audio": audio_name,
            "provider": provider,
            "model": model,
            "language": language,
        }
        try:
            (self.dir / META_NAME).write_text(json.dumps(meta, indent=2), encoding="utf-8")
        except OSError:
            logger.exception("Could not write meta.json for note %s", self.id)
        self.set_text(self._text)  # re-render now that the duration is final
        self.finalized = True
        meta["text"] = self._text.strip()
        try:
            meta["bytes"] = (self.dir / audio_name).stat().st_size
        except OSError:
            meta["bytes"] = 0
        return meta

    def discard(self) -> None:
        """Throw the whole note away (the user cancelled)."""
        self.finalized = True
        self._close_wav()
        shutil.rmtree(self.dir, ignore_errors=True)


def create(title: str = "", *, sample_rate: int = 16000) -> NoteWriter:
    """Start a new note folder and return its writer."""
    base = notes_dir()
    base.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now().strftime("%Y%m%dT%H%M%S")
    note_id = f"{stamp}-{_slug(title)}"
    directory = base / note_id
    suffix = 2
    while directory.exists():  # two notes started in the same second
        note_id = f"{stamp}-{_slug(title)}-{suffix}"
        directory = base / note_id
        suffix += 1
    directory.mkdir(parents=True)
    return NoteWriter(note_id, directory, sample_rate, title.strip() or f"Note {stamp}")


async def _to_mp3(directory: Path) -> str:
    """Convert ``audio.wav`` to ``audio.mp3`` and drop the WAV.

    Returns the file name that ended up holding the audio: if ffmpeg is missing
    or fails, the WAV stays and the note is still perfectly playable — a failed
    conversion must never cost the recording.
    """
    wav = directory / WAV_NAME
    if not wav.exists():
        return WAV_NAME
    mp3 = directory / MP3_NAME
    try:
        proc = await asyncio.create_subprocess_exec(
            "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
            "-i", str(wav), "-codec:a", "libmp3lame", "-q:a", "5", "-ac", "1", str(mp3),
            stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.PIPE,
        )
        _, err = await asyncio.wait_for(proc.communicate(), FFMPEG_TIMEOUT_S)
    except (FileNotFoundError, asyncio.TimeoutError, OSError) as e:
        logger.warning("Keeping %s as WAV: ffmpeg unavailable (%s)", directory.name, e)
        return WAV_NAME
    if proc.returncode != 0 or not mp3.exists() or mp3.stat().st_size == 0:
        logger.warning("Keeping %s as WAV: ffmpeg failed (%s)", directory.name,
                       (err or b"").decode("utf-8", "replace")[:200])
        mp3.unlink(missing_ok=True)
        return WAV_NAME
    wav.unlink(missing_ok=True)
    return MP3_NAME


def _read_note(directory: Path) -> dict[str, Any] | None:
    try:
        meta = json.loads((directory / META_NAME).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    if not isinstance(meta, dict):
        return None
    audio = meta.get("audio") or MP3_NAME
    audio_path = directory / audio
    meta["id"] = directory.name
    meta["has_audio"] = audio_path.exists()
    try:
        meta["bytes"] = audio_path.stat().st_size
    except OSError:
        meta["bytes"] = 0
    try:
        body = (directory / TRANSCRIPT_NAME).read_text(encoding="utf-8")
        # Drop the heading and the italic timing line the writer added back.
        meta["text"] = body.split("\n\n", 2)[-1].strip() if "\n\n" in body else body.strip()
    except OSError:
        meta["text"] = ""
    return meta


def list_notes(limit: int | None = None) -> list[dict[str, Any]]:
    """Every note, newest first. Folders without a ``meta.json`` are skipped."""
    base = notes_dir()
    if not base.is_dir():
        return []
    out: list[dict[str, Any]] = []
    for directory in sorted(base.iterdir(), reverse=True):
        if not directory.is_dir() or not NOTE_ID_RE.match(directory.name):
            continue
        note = _read_note(directory)
        if note is not None:
            out.append(note)
        if limit is not None and len(out) >= limit:
            break
    return out


def get_note(note_id: str) -> dict[str, Any] | None:
    directory = _note_path(note_id)
    if directory is None or not directory.is_dir():
        return None
    return _read_note(directory)


def audio_file(note_id: str) -> Path | None:
    note = get_note(note_id)
    if not note:
        return None
    directory = _note_path(note_id)
    if directory is None:
        return None
    path = directory / (note.get("audio") or MP3_NAME)
    return path if path.is_file() else None


def rename_note(note_id: str, title: str) -> dict[str, Any] | None:
    """Change a note's title in place (the folder id never moves)."""
    directory = _note_path(note_id)
    if directory is None or not directory.is_dir():
        return None
    note = _read_note(directory)
    if note is None:
        return None
    note["title"] = title.strip() or note.get("title") or note_id
    meta = {k: v for k, v in note.items() if k not in ("text", "has_audio", "bytes")}
    try:
        (directory / META_NAME).write_text(json.dumps(meta, indent=2), encoding="utf-8")
        body = (directory / TRANSCRIPT_NAME).read_text(encoding="utf-8").split("\n", 1)
        rest = body[1] if len(body) > 1 else ""
        (directory / TRANSCRIPT_NAME).write_text(f"# {note['title']}\n{rest}", encoding="utf-8")
    except OSError:
        logger.exception("Could not rename note %s", note_id)
        return None
    return note


def delete_note(note_id: str) -> bool:
    directory = _note_path(note_id)
    if directory is None or not directory.is_dir():
        return False
    shutil.rmtree(directory, ignore_errors=True)
    return not directory.exists()
