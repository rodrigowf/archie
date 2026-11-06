"""Voice notes on disk: the writer, the store and the REST surface.

The ffmpeg call is faked in most tests (it is an external binary and a slow one); one test lets
it fail for real to prove the promise that matters — a failed conversion costs the *format*,
never the recording.
"""

from __future__ import annotations

import json

import pytest
from fastapi import FastAPI
from starlette.testclient import TestClient

from api.routes import transcribe
from transcription import notes

LOCAL = ("127.0.0.1", 50000)
PCM = b"\x01\x02" * 16000  # 32000 bytes = 16000 samples = 1 s at 16 kHz


@pytest.fixture
def notes_dir(tmp_path, monkeypatch):
    d = tmp_path / "recordings" / "voice-notes"
    monkeypatch.setattr(notes, "notes_dir", lambda: d)
    return d


@pytest.fixture
def no_ffmpeg(monkeypatch):
    """Pretend the conversion worked, without running ffmpeg."""
    async def fake(directory):
        (directory / notes.MP3_NAME).write_bytes(b"ID3fake-mp3")
        (directory / notes.WAV_NAME).unlink(missing_ok=True)
        return notes.MP3_NAME

    monkeypatch.setattr(notes, "_to_mp3", fake)


@pytest.fixture
def app() -> FastAPI:
    a = FastAPI()
    a.include_router(transcribe.router)
    return a


async def _write_note(title="Tarot daily draw", text="So the idea is…", seconds=2):
    w = notes.create(title, sample_rate=16000)
    for _ in range(seconds):
        w.write_audio(PCM)
    w.set_text(text)
    return w, await w.finalize(provider="openai", model="gpt-live-transcribe")


# ---------------------------------------------------------------- the writer


@pytest.mark.asyncio
async def test_a_note_keeps_audio_transcript_and_metadata(notes_dir, no_ffmpeg):
    w, meta = await _write_note()
    assert meta["id"].endswith("-tarot-daily-draw")
    assert meta["duration_ms"] == 2000
    assert meta["audio"] == notes.MP3_NAME
    assert meta["provider"] == "openai"

    folder = notes_dir / meta["id"]
    assert (folder / notes.MP3_NAME).exists()
    assert not (folder / notes.WAV_NAME).exists(), "the WAV is dropped once converted"
    assert json.loads((folder / notes.META_NAME).read_text())["title"] == "Tarot daily draw"
    body = (folder / notes.TRANSCRIPT_NAME).read_text()
    assert body.startswith("# Tarot daily draw")
    assert "0:02" in body, "the heading carries the final duration"
    assert "So the idea is…" in body


@pytest.mark.asyncio
async def test_audio_is_on_disk_before_the_note_is_finished(notes_dir, no_ffmpeg):
    """The whole point of streaming to disk: a crash mid-note still leaves the recording."""
    w = notes.create("Half a note", sample_rate=16000)
    w.write_audio(PCM)
    w.set_text("the half I said")
    wav = w.dir / notes.WAV_NAME
    assert wav.exists() and wav.stat().st_size > 1000
    assert "the half I said" in (w.dir / notes.TRANSCRIPT_NAME).read_text()
    w.discard()


@pytest.mark.asyncio
async def test_discard_removes_the_folder(notes_dir, no_ffmpeg):
    w = notes.create("Oops", sample_rate=16000)
    w.write_audio(PCM)
    folder = w.dir
    w.discard()
    assert not folder.exists()
    assert notes.list_notes() == []


@pytest.mark.asyncio
async def test_two_notes_in_the_same_second_get_distinct_ids(notes_dir, no_ffmpeg):
    a = notes.create("Same", sample_rate=16000)
    b = notes.create("Same", sample_rate=16000)
    assert a.id != b.id
    a.discard()
    b.discard()


@pytest.mark.asyncio
async def test_a_failed_conversion_keeps_the_wav(notes_dir, monkeypatch):
    """ffmpeg missing must cost the format, never the recording."""
    async def boom(*a, **k):
        raise FileNotFoundError("no ffmpeg here")

    monkeypatch.setattr("asyncio.create_subprocess_exec", boom)
    w, meta = await _write_note(title="No ffmpeg")
    assert meta["audio"] == notes.WAV_NAME
    assert (notes_dir / meta["id"] / notes.WAV_NAME).exists()
    assert notes.audio_file(meta["id"]) is not None


@pytest.mark.asyncio
async def test_an_empty_title_still_produces_a_usable_id(notes_dir, no_ffmpeg):
    w, meta = await _write_note(title="")
    assert notes.NOTE_ID_RE.match(meta["id"])
    assert meta["title"]


# ---------------------------------------------------------------- the store


@pytest.mark.asyncio
async def test_list_is_newest_first_and_skips_junk(notes_dir, no_ffmpeg):
    _, first = await _write_note(title="First")
    _, second = await _write_note(title="Second")
    (notes_dir / "not-a-note").mkdir()
    (notes_dir / "20260101T010101-no-meta").mkdir()

    listed = notes.list_notes()
    assert [n["id"] for n in listed][:2] == sorted([first["id"], second["id"]], reverse=True)
    assert len(listed) == 2, "folders without a meta.json are not notes"
    assert listed[0]["text"], "the transcript is read back without its heading"


@pytest.mark.asyncio
async def test_rename_keeps_the_id_and_rewrites_the_heading(notes_dir, no_ffmpeg):
    _, meta = await _write_note(title="Before")
    renamed = notes.rename_note(meta["id"], "After")
    assert renamed is not None and renamed["title"] == "After"
    assert renamed["id"] == meta["id"], "the folder never moves"
    assert (notes_dir / meta["id"] / notes.TRANSCRIPT_NAME).read_text().startswith("# After")
    assert notes.get_note(meta["id"])["title"] == "After"


@pytest.mark.asyncio
async def test_delete_removes_everything(notes_dir, no_ffmpeg):
    _, meta = await _write_note()
    assert notes.delete_note(meta["id"]) is True
    assert notes.get_note(meta["id"]) is None
    assert notes.delete_note(meta["id"]) is False


@pytest.mark.parametrize("bad", [
    "../../../etc/passwd",
    "..",
    "/etc/passwd",
    "20261105T214500-ok/../../secrets",
    "not-an-id",
    "",
])
def test_ids_from_a_url_cannot_walk_out_of_the_notes_directory(notes_dir, bad):
    assert notes.get_note(bad) is None
    assert notes.audio_file(bad) is None
    assert notes.delete_note(bad) is False
    assert notes.rename_note(bad, "x") is None


# ---------------------------------------------------------------- REST


@pytest.mark.asyncio
async def test_rest_lists_reads_renames_and_deletes(app, notes_dir, no_ffmpeg):
    _, meta = await _write_note(title="Picnic shot list", text="Wide of the blanket first")

    with TestClient(app, client=LOCAL) as client:
        listed = client.get("/api/transcribe/notes").json()["notes"]
        assert [n["id"] for n in listed] == [meta["id"]]
        assert listed[0]["has_audio"] is True

        one = client.get(f"/api/transcribe/notes/{meta['id']}").json()
        assert one["text"] == "Wide of the blanket first"

        audio = client.get(f"/api/transcribe/notes/{meta['id']}/audio")
        assert audio.status_code == 200
        assert audio.headers["content-type"] == "audio/mpeg"

        patched = client.patch(f"/api/transcribe/notes/{meta['id']}", json={"title": "Picnic"})
        assert patched.json()["title"] == "Picnic"

        assert client.delete(f"/api/transcribe/notes/{meta['id']}").json() == {"deleted": True}
        assert client.get("/api/transcribe/notes").json()["notes"] == []


def test_rest_404s_for_an_unknown_note(app, notes_dir):
    with TestClient(app, client=LOCAL) as client:
        assert client.get("/api/transcribe/notes/20261105T214500-nope").status_code == 404
        assert client.get("/api/transcribe/notes/20261105T214500-nope/audio").status_code == 404
        assert client.delete("/api/transcribe/notes/20261105T214500-nope").status_code == 404


@pytest.mark.asyncio
async def test_notes_are_only_served_to_the_home_network(app, notes_dir, no_ffmpeg):
    _, meta = await _write_note()
    with TestClient(app, client=("8.8.8.8", 1)) as client:
        assert client.get("/api/transcribe/notes").status_code == 403
        assert client.get(f"/api/transcribe/notes/{meta['id']}").status_code == 403
        assert client.get(f"/api/transcribe/notes/{meta['id']}/audio").status_code == 403
        assert client.delete(f"/api/transcribe/notes/{meta['id']}").status_code == 403


# ---------------------------------------------------------------- the socket writes one


@pytest.mark.asyncio
async def test_the_socket_saves_a_note_when_asked(app, notes_dir, no_ffmpeg, monkeypatch):
    """`save` on the start frame is what turns a dictation into a kept recording."""
    import base64

    from transcription import registry
    from transcription.base import Committed
    from tests.test_transcription_stream import FakeTranscriber

    monkeypatch.setattr(registry, "api_key", lambda spec: "k-test")
    monkeypatch.setattr(
        registry, "create",
        lambda spec, language="", sample_rate=None: FakeTranscriber(on_feed=[Committed("Three cards, one sentence each.")]),
    )
    import api.routes.config as config_module

    monkeypatch.setattr(config_module, "_load_config", lambda: {
        "dictation_enabled": True, "dictation_provider": "openai",
        "dictation_model": "gpt-live-transcribe", "dictation_language": "",
    })

    with TestClient(app, client=LOCAL) as client:
        with client.websocket_connect("/api/transcribe/stream") as ws:
            ws.send_json({"type": "start", "save": {"title": "Tarot"}, "sample_rate": 16000})
            ready = ws.receive_json()
            assert ready["note_id"]
            ws.send_json({"type": "audio", "audio": base64.b64encode(PCM).decode()})
            ws.send_json({"type": "stop"})
            while True:
                m = ws.receive_json()
                if m["type"] == "final":
                    break

    assert m["note"]["id"] == ready["note_id"]
    assert m["note"]["duration_ms"] == 1000
    saved = notes.get_note(ready["note_id"])
    assert saved["text"] == "Three cards, one sentence each."
    assert saved["title"] == "Tarot"


@pytest.mark.asyncio
async def test_a_cancelled_recording_leaves_nothing_behind(app, notes_dir, no_ffmpeg, monkeypatch):
    import base64

    from transcription import registry
    from tests.test_transcription_stream import FakeTranscriber

    monkeypatch.setattr(registry, "api_key", lambda spec: "k-test")
    monkeypatch.setattr(registry, "create", lambda spec, language="", sample_rate=None: FakeTranscriber())
    import api.routes.config as config_module

    monkeypatch.setattr(config_module, "_load_config", lambda: {
        "dictation_enabled": True, "dictation_provider": "openai",
        "dictation_model": "gpt-live-transcribe", "dictation_language": "",
    })

    with TestClient(app, client=LOCAL) as client:
        with client.websocket_connect("/api/transcribe/stream") as ws:
            ws.send_json({"type": "start", "save": {"title": "Throwaway"}, "sample_rate": 16000})
            ws.receive_json()
            ws.send_json({"type": "audio", "audio": base64.b64encode(PCM).decode()})
            ws.send_json({"type": "cancel"})
            while ws.receive_json()["type"] != "cancelled":
                pass

    assert notes.list_notes() == []
