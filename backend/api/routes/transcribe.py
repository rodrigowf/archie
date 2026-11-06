"""Transcription for every client: one batch POST, one streaming socket, and voice notes.

    POST /api/transcribe              a finished WAV in, the text out (spec 15 §4.1, §5.3)
    GET  /api/transcribe/providers    the catalogue, with availability and cost
    WS   /api/transcribe/stream       live dictation: PCM in, text out while you speak
    GET  /api/transcribe/notes        voice notes, newest first
    GET  /api/transcribe/notes/{id}   one note, with its transcript
    GET  /api/transcribe/notes/{id}/audio
    PATCH/DELETE /api/transcribe/notes/{id}

Every call here spends the **server's** API keys, so all of it — the socket
included — is held to the same two rules the batch endpoint has always had:

* **Home network only.** The caller (the peer and, behind nginx, ``X-Real-IP``
  — ``api.net.is_from_home_network``) must be loopback, a private LAN range or
  Tailscale; anything else is refused, so an accidentally exposed port is not a
  free transcription proxy. For the socket this happens *before* ``accept``.
* **Size checked before the body is parsed.** FastAPI's ``File()`` parameters
  would have Starlette spool the whole multipart body to disk before the
  handler runs, so the handler reads the raw body itself.

The socket adds caps of its own (:data:`MAX_LIVE_SOCKETS`,
:data:`DICTATION_LIMIT_S`, :data:`NOTE_LIMIT_S`, :data:`IDLE_TIMEOUT_S`),
because unlike the POST it has no natural end: a client that opens one and
walks away would otherwise bill audio forever.

Which model does the work is a Settings choice (``dictation_provider`` /
``dictation_model``), resolved through :mod:`transcription.registry` so a saved
choice that later loses its key degrades instead of breaking.
"""

from __future__ import annotations

import asyncio
import base64
import binascii
import contextlib
import logging
import time
from typing import Any

from fastapi import APIRouter, HTTPException, Request, WebSocket, WebSocketDisconnect
from fastapi.responses import FileResponse
from pydantic import BaseModel
from starlette.datastructures import UploadFile

from api.net import is_from_home_network, real_client_address
from transcription import notes as notes_store
from transcription import registry
from transcription.base import Committed, Failed, Partial, TranscriberError

logger = logging.getLogger(__name__)
router = APIRouter(tags=["transcribe"])

TRANSCRIBE_TIMEOUT_S = 25.0
# 2 MB of 16 kHz mono PCM16 is ~65 s — above the TV's 60 s burst cap.
MAX_AUDIO_BYTES = 2 * 1024 * 1024
# The whole multipart body: the audio plus boundaries, part headers and the
# language field.
MAX_BODY_BYTES = MAX_AUDIO_BYTES + 64 * 1024

# ---- streaming caps -------------------------------------------------------
#: Concurrent live transcriptions across all clients. Each one holds an upstream
#: WebSocket open and bills by the second.
MAX_LIVE_SOCKETS = 4
#: A composer dictation is a sentence or two; anything near this is a stuck tab.
DICTATION_LIMIT_S = 10 * 60
#: A voice note is meant to be long, but not unbounded.
NOTE_LIMIT_S = 2 * 60 * 60
#: No audio for this long with the socket still open: the client is gone.
IDLE_TIMEOUT_S = 90.0
#: One decoded PCM chunk. The web client sends 100 ms (~4.8 KB at 24 kHz).
MAX_CHUNK_BYTES = 1024 * 1024

_live_sockets = 0
_live_lock = asyncio.Lock()


def _resolve_spec(provider: str | None, model: str | None, *, streaming: bool | None = None):
    """The transcriber to use, falling back to the configured default."""
    if not provider and not model:
        from api.routes.config import _load_config
        config = _load_config()
        provider = config.get("dictation_provider")
        model = config.get("dictation_model")
    return registry.resolve(provider, model, streaming=streaming)


def _config_language() -> str:
    from api.routes.config import _load_config
    value = _load_config().get("dictation_language")
    return value if isinstance(value, str) else ""


# ===========================================================================
# POST /api/transcribe — a finished WAV
# ===========================================================================

def _is_wav(data: bytes) -> bool:
    return len(data) >= 12 and data[:4] == b"RIFF" and data[8:12] == b"WAVE"


def _too_large() -> HTTPException:
    return HTTPException(
        status_code=413,
        detail=f"audio too large (max {MAX_AUDIO_BYTES // (1024 * 1024)} MB)",
    )


async def _read_capped_body(request: Request) -> bytes:
    """The raw request body, refusing anything over ``MAX_BODY_BYTES``
    before (declared length) or while (chunked) it is read."""
    declared = request.headers.get("content-length")
    if declared is not None:
        try:
            if int(declared) > MAX_BODY_BYTES:
                raise _too_large()
        except ValueError:
            raise HTTPException(status_code=400, detail="bad Content-Length") from None
    chunks: list[bytes] = []
    total = 0
    async for chunk in request.stream():
        total += len(chunk)
        if total > MAX_BODY_BYTES:
            raise _too_large()
        chunks.append(chunk)
    return b"".join(chunks)


#: TranscriberError code → HTTP status for the batch endpoint.
_ERROR_STATUS = {
    "timeout": 504,
    "http_error": 502,
    "provider_error": 502,
    "no_text": 502,
    "no_api_key": 503,
    "unknown_provider": 400,
}


@router.post("/api/transcribe")
async def transcribe(request: Request) -> dict[str, str]:
    if not is_from_home_network(request):
        logger.warning("Refusing transcription for %s (not the home network)",
                       real_client_address(request))
        raise HTTPException(
            status_code=403, detail="transcription is only served to the home network",
        )

    body = await _read_capped_body(request)

    async def replay() -> dict:
        return {"type": "http.request", "body": body, "more_body": False}

    # Parse the already-capped bytes (in memory: ≤ MAX_BODY_BYTES).
    try:
        async with Request(request.scope, replay).form(max_files=1, max_fields=6) as form:
            audio = form.get("audio")
            language = form.get("language")
            provider = form.get("provider")
            model = form.get("model")
            if not isinstance(audio, UploadFile):
                raise HTTPException(status_code=422, detail="'audio' file field is required")
            # Read one byte past the cap: the body cap leaves room for
            # multipart overhead, the audio itself must still fit.
            data = await audio.read(MAX_AUDIO_BYTES + 1)
    except HTTPException:
        raise
    except Exception as e:  # MultiPartException, malformed form
        raise HTTPException(status_code=400, detail=f"unreadable form: {e}") from None
    if not data:
        raise HTTPException(status_code=400, detail="audio is empty")
    if len(data) > MAX_AUDIO_BYTES:
        raise _too_large()
    if not _is_wav(data):
        raise HTTPException(status_code=415, detail="audio must be a WAV file")

    # A caller that names no model gets the batch default rather than whatever
    # is configured for dictation: this endpoint is handed finished audio, so a
    # streaming model here would only be a slower way to the same answer.
    spec = registry.resolve(
        provider if isinstance(provider, str) and provider else registry.BATCH_DEFAULT_PROVIDER,
        model if isinstance(model, str) and model else registry.BATCH_DEFAULT_MODEL,
        streaming=False,
    )
    key = registry.api_key(spec)
    if not key:
        raise HTTPException(
            status_code=503, detail=f"{spec.env_key} is not configured on the backend",
        )

    from transcription.batch import transcribe_wav
    lang = language.strip() if isinstance(language, str) else ""
    try:
        text = await transcribe_wav(spec, key, data, lang)
    except TranscriberError as e:
        raise HTTPException(
            status_code=_ERROR_STATUS.get(e.code, 502), detail=e.detail or e.code,
        ) from None
    return {"text": text}


# ===========================================================================
# GET /api/transcribe/providers
# ===========================================================================

@router.get("/api/transcribe/providers")
async def list_providers() -> dict[str, Any]:
    """The transcriber catalogue, plus what dictation is currently set to.

    The settings travel with the catalogue so a client needs **one** cheap call to answer both
    "may I show the mic?" and "what are the choices?". ``/api/config`` is not broadcast (G-27)
    and is only fetched when Settings opens, which is far too late for the composer.
    """
    from api.routes.config import _load_config

    config = _load_config()
    spec = registry.resolve(config.get("dictation_provider"), config.get("dictation_model"))
    enabled = config.get("dictation_enabled") is not False and bool(registry.api_key(spec))
    return {
        "transcribers": registry.list_transcribers(),
        "languages": [{"id": code, "label": label} for code, label in registry.LANGUAGES],
        "default": f"{registry.DEFAULT_PROVIDER}/{registry.DEFAULT_MODEL}",
        "dictation": {
            # False when switched off in Settings *or* when the chosen model has
            # no key here — either way the composer must not offer the mic.
            "enabled": enabled,
            "provider": spec.provider,
            "model": spec.model,
            "streaming": spec.streaming,
            "language": config.get("dictation_language") or "",
        },
    }


# ===========================================================================
# WS /api/transcribe/stream — live dictation
# ===========================================================================

class _Session:
    """One live transcription, and the transcript it has accumulated.

    ``committed`` is text the provider will not revise; ``partial`` is the tail
    it is still thinking about. Clients render ``committed + partial`` — see
    :mod:`transcription.base` for why the two providers need that split.
    """

    def __init__(self) -> None:
        self.committed = ""
        self.partial = ""

    @property
    def text(self) -> str:
        return (self.committed + self.partial).strip()


async def _send(ws: WebSocket, message: dict) -> None:
    with contextlib.suppress(Exception):
        await ws.send_json(message)


@router.websocket("/api/transcribe/stream")
async def transcribe_stream(ws: WebSocket) -> None:
    global _live_sockets

    if not is_from_home_network(ws):
        logger.warning("Refusing a transcription socket for %s (not the home network)",
                       real_client_address(ws))
        await ws.close(code=1008, reason="not the home network")
        return

    await ws.accept()

    # --- start --------------------------------------------------------
    try:
        start = await asyncio.wait_for(ws.receive_json(), 20.0)
    except (asyncio.TimeoutError, WebSocketDisconnect):
        await ws.close(code=1008, reason="no start message")
        return
    except Exception:
        await _send(ws, {"type": "error", "error": "invalid_json"})
        await ws.close(code=1008, reason="invalid json")
        return

    if not isinstance(start, dict) or start.get("type") != "start":
        await _send(ws, {"type": "error", "error": "expected_start"})
        await ws.close(code=1008, reason="expected start")
        return

    save = start.get("save") if isinstance(start.get("save"), dict) else None
    limit_s = NOTE_LIMIT_S if save else DICTATION_LIMIT_S

    async with _live_lock:
        if _live_sockets >= MAX_LIVE_SOCKETS:
            await _send(ws, {
                "type": "error", "error": "busy",
                "detail": f"{MAX_LIVE_SOCKETS} transcriptions are already running",
            })
            await ws.close(code=1013, reason="busy")
            return
        _live_sockets += 1

    writer: notes_store.NoteWriter | None = None
    transcriber = None
    try:
        spec = _resolve_spec(start.get("provider"), start.get("model"), streaming=True)
        language = start.get("language")
        if not isinstance(language, str):
            language = _config_language()
        rate = start.get("sample_rate")
        sample_rate = rate if isinstance(rate, int) and 8000 <= rate <= 48000 else spec.sample_rate

        try:
            transcriber = registry.create(spec, language=language, sample_rate=sample_rate)
            await transcriber.start()
        except TranscriberError as e:
            await _send(ws, {"type": "error", "error": e.code, "detail": e.detail})
            await ws.close(code=1011, reason=e.code)
            return

        if save:
            title = save.get("title")
            writer = notes_store.create(
                title if isinstance(title, str) else "", sample_rate=sample_rate,
            )

        await _send(ws, {
            "type": "ready",
            "provider": spec.provider,
            "model": spec.model,
            "streaming": spec.streaming,
            "sample_rate": sample_rate,
            "language": language,
            "note_id": writer.id if writer else None,
        })

        session = _Session()
        await _pump(ws, transcriber, session, writer, limit_s, spec, language)
    finally:
        if transcriber is not None:
            with contextlib.suppress(Exception):
                await transcriber.aclose()
        if writer is not None and not writer.finalized:  # crashed before the note was closed
            writer.discard()
        async with _live_lock:
            _live_sockets -= 1


async def _pump(ws, transcriber, session, writer, limit_s, spec, language) -> None:
    """Shuttle audio up and text down until the client stops or a cap trips."""
    stop_reason: list[str] = []

    async def forward_text() -> None:
        """Provider events → client frames (and the note's transcript)."""
        async for event in transcriber.events():
            if isinstance(event, Committed):
                session.committed += event.text
                session.partial = ""
                await _send(ws, {"type": "text", "append": event.text, "partial": ""})
                if writer is not None:
                    writer.set_text(session.text)
            elif isinstance(event, Partial):
                session.partial = event.text
                await _send(ws, {"type": "text", "append": "", "partial": event.text})
            elif isinstance(event, Failed):
                await _send(ws, {"type": "error", "error": event.code, "detail": event.detail})
                stop_reason.append(event.code)
                return

    pump = asyncio.create_task(forward_text(), name="transcribe-forward")
    started = time.monotonic()
    cancelled = False
    receive: asyncio.Task | None = None

    try:
        while True:
            if time.monotonic() - started > limit_s:
                await _send(ws, {
                    "type": "error", "error": "too_long",
                    "detail": f"recording stopped at the {limit_s // 60} minute limit",
                })
                break
            if stop_reason:
                break
            # Wait on the client *and* the transcriber at once. Waiting only on
            # the client would hang here for the whole idle timeout whenever the
            # provider died, because a client that just got an error has nothing
            # left to send.
            receive = asyncio.create_task(ws.receive_json(), name="transcribe-receive")
            done, _ = await asyncio.wait(
                {receive, pump}, timeout=IDLE_TIMEOUT_S, return_when=asyncio.FIRST_COMPLETED,
            )
            if not done:
                receive.cancel()
                await _send(ws, {"type": "error", "error": "idle_timeout"})
                break
            if pump in done:
                receive.cancel()
                with contextlib.suppress(asyncio.CancelledError, Exception):
                    await receive
                break
            try:
                message = receive.result()
            except (WebSocketDisconnect, RuntimeError):
                # The tab went away. A note with words in it is still worth
                # keeping — that is the point of writing to disk as we go —
                # but an empty one is just litter.
                cancelled = writer is not None and not session.text
                break
            except Exception:
                await _send(ws, {"type": "error", "error": "invalid_json"})
                break

            kind = message.get("type") if isinstance(message, dict) else None
            if kind == "audio":
                raw = message.get("audio")
                if not isinstance(raw, str) or not raw:
                    continue
                try:
                    pcm = base64.b64decode(raw, validate=True)
                except (binascii.Error, ValueError):
                    await _send(ws, {"type": "error", "error": "bad_audio"})
                    break
                if len(pcm) > MAX_CHUNK_BYTES:
                    await _send(ws, {"type": "error", "error": "chunk_too_large"})
                    break
                await transcriber.feed(pcm)
                if writer is not None:
                    writer.write_audio(pcm)
            elif kind == "stop":
                break
            elif kind == "cancel":
                cancelled = True
                break
            # Anything else is ignored: an unknown message must not kill a
            # recording that is going fine.
    finally:
        # Every exit except an explicit cancel flushes: a stop, a dropped tab,
        # the idle timeout and the duration cap all keep what was said.
        if not cancelled:
            with contextlib.suppress(Exception):
                await transcriber.finish()
            with contextlib.suppress(asyncio.TimeoutError, asyncio.CancelledError):
                await asyncio.wait_for(asyncio.shield(pump), timeout=5.0)
        pump.cancel()
        with contextlib.suppress(asyncio.CancelledError):
            await pump

    if cancelled:
        if writer is not None:
            writer.discard()
        await _send(ws, {"type": "cancelled"})
    else:
        note = None
        if writer is not None:
            writer.set_text(session.text)
            note = await writer.finalize(
                provider=spec.provider, model=spec.model, language=language,
            )
        await _send(ws, {"type": "final", "text": session.text, "note": note})
    with contextlib.suppress(Exception):
        await ws.close()


# ===========================================================================
# Voice notes
# ===========================================================================

class NotePatch(BaseModel):
    title: str


def _require_home(request: Request) -> None:
    if not is_from_home_network(request):
        raise HTTPException(
            status_code=403, detail="voice notes are only served to the home network",
        )


@router.get("/api/transcribe/notes")
async def get_notes(request: Request, limit: int = 100) -> dict[str, Any]:
    _require_home(request)
    return {"notes": notes_store.list_notes(limit=max(1, min(limit, 500)))}


@router.get("/api/transcribe/notes/{note_id}")
async def get_one_note(request: Request, note_id: str) -> dict[str, Any]:
    _require_home(request)
    note = notes_store.get_note(note_id)
    if note is None:
        raise HTTPException(status_code=404, detail="no such note")
    return note


@router.get("/api/transcribe/notes/{note_id}/audio")
async def get_note_audio(request: Request, note_id: str) -> FileResponse:
    _require_home(request)
    path = notes_store.audio_file(note_id)
    if path is None:
        raise HTTPException(status_code=404, detail="no audio for that note")
    media = "audio/mpeg" if path.suffix == ".mp3" else "audio/wav"
    return FileResponse(path, media_type=media, filename=f"{note_id}{path.suffix}")


@router.patch("/api/transcribe/notes/{note_id}")
async def patch_note(request: Request, note_id: str, patch: NotePatch) -> dict[str, Any]:
    _require_home(request)
    note = notes_store.rename_note(note_id, patch.title)
    if note is None:
        raise HTTPException(status_code=404, detail="no such note")
    return note


@router.delete("/api/transcribe/notes/{note_id}")
async def remove_note(request: Request, note_id: str) -> dict[str, bool]:
    _require_home(request)
    if not notes_store.delete_note(note_id):
        raise HTTPException(status_code=404, detail="no such note")
    return {"deleted": True}
