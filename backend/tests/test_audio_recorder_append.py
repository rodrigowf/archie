"""Every voice call of a conversation appends to ONE recording.

Regression (2026-10-13): ``AudioRecorder.start`` reopened ``audio.pcm`` with
``"wb"``; the recording dir is keyed on the conversation's tab id, so each
new call erased the earlier calls' audio and the JSONL's
``[voice, recording: … ms]`` references pointed at nothing.
"""

from __future__ import annotations

import base64
import json
from pathlib import Path

import pytest

from orchestrator import audio_recorder as ar


@pytest.fixture(autouse=True)
def recordings(tmp_path: Path, monkeypatch) -> Path:
    monkeypatch.setattr(ar, "RECORDINGS_DIR", tmp_path)
    return tmp_path


def _b64(n: int, byte: bytes = b"\x01") -> str:
    return base64.b64encode(byte * n).decode()


def _call(provider: str, user: bytes, assistant: bytes) -> dict:
    rec = ar.AudioRecorder("conv", provider=provider, model="m", voice="v")
    rec.start()
    rec.write_user_audio(_b64(480, user))
    user_seg = rec.mark_user_turn_end("hi")
    rec.write_assistant_audio(_b64(960, assistant))
    asst_seg = rec.mark_assistant_turn_end("hello")
    rec.stop()
    return {"user": user_seg, "assistant": asst_seg}


def _index(recordings: Path) -> dict:
    return json.loads((recordings / "conv" / "index.json").read_text())


def test_second_call_appends_and_keeps_first_calls_audio(recordings: Path):
    first = _call("google", b"\x01", b"\x02")
    second = _call("openai", b"\x03", b"\x04")

    audio = (recordings / "conv" / "audio.pcm").read_bytes()
    assert audio == b"\x01" * 480 + b"\x02" * 960 + b"\x03" * 480 + b"\x04" * 960

    idx = _index(recordings)
    assert idx["total_bytes"] == len(audio)
    assert len(idx["chunks"]) == 4 and len(idx["segments"]) == 4
    assert [c["provider"] for c in idx["calls"]] == ["google", "openai"]
    assert all(c["ended_at"] for c in idx["calls"])
    # The second call continues the timeline after the first one.
    assert second["user"]["start_ms"] > first["assistant"]["end_ms"]
    assert idx["calls"][1]["offset_ms"] > first["assistant"]["end_ms"]
    assert second["user"]["turn_index"] == first["assistant"]["turn_index"] + 1


def test_first_calls_segment_still_plays_back_after_second_call(recordings: Path):
    first = _call("google", b"\x01", b"\x02")
    _call("google", b"\x03", b"\x04")
    # The first call's range (both speakers) is the first call's audio, untouched
    # by the second call (which lives later on the timeline).
    got = ar.get_recording_audio_by_wall_clock(
        "conv", first["user"]["start_ms"], first["assistant"]["end_ms"] + 1,
    )
    assert got == b"\x01" * 480 + b"\x02" * 960


def test_unreadable_index_is_moved_aside_not_overwritten(recordings: Path):
    d = recordings / "conv"
    d.mkdir()
    (d / "audio.pcm").write_bytes(b"\x09" * 100)
    (d / "index.json").write_text("{not json")
    _call("google", b"\x01", b"\x02")
    kept = sorted(p.name for p in d.iterdir() if p.name not in ("audio.pcm", "index.json"))
    assert any(n.startswith("audio.") and n.endswith(".pcm") for n in kept)
    assert any(n.startswith("index.") and n.endswith(".json") for n in kept)
    aside = next(p for p in d.iterdir() if p.name.startswith("audio.") and p.name != "audio.pcm")
    assert aside.read_bytes() == b"\x09" * 100
    assert (d / "audio.pcm").read_bytes() == b"\x01" * 480 + b"\x02" * 960


def test_failed_call_with_no_audio_keeps_earlier_recording(recordings: Path):
    """The 2026-10-13 case: a call that dies at once must not empty the index."""
    _call("google", b"\x01", b"\x02")
    rec = ar.AudioRecorder("conv", provider="openai", model="gpt-realtime", voice="cedar")
    rec.start()
    rec.stop()
    idx = _index(recordings)
    assert len(idx["chunks"]) == 2 and len(idx["segments"]) == 2
    assert (recordings / "conv" / "audio.pcm").stat().st_size == 1440
