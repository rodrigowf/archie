"""Server-side dictation: ``POST /api/transcribe`` (spec 15 §4.1, §5.3).

A client posts a short WAV recording and gets the text back, transcribed by
OpenAI ``whisper-1`` with the **server's** ``OPENAI_API_KEY``. Built for the
Archie TV's dictation into agent conversations (the TV never holds API keys),
usable by any client. Same LAN trust model as the rest of the REST API.

    POST /api/transcribe   multipart: audio=<wav ≤ 2 MB>, language=<ISO code>?
    → 200 {"text": "..."}
      400 empty upload · 413 too large · 415 not a WAV
      503 no OpenAI key on the server · 502 OpenAI error · 504 OpenAI timeout
"""

from __future__ import annotations

import logging

import httpx
from fastapi import APIRouter, File, Form, HTTPException, UploadFile

from api.routes.config import _resolve_openai_key

logger = logging.getLogger(__name__)
router = APIRouter(tags=["transcribe"])

OPENAI_TRANSCRIPTIONS_URL = "https://api.openai.com/v1/audio/transcriptions"
TRANSCRIBE_MODEL = "whisper-1"
TRANSCRIBE_TIMEOUT_S = 25.0
# 2 MB of 16 kHz mono PCM16 is ~65 s — above the TV's 60 s burst cap.
MAX_AUDIO_BYTES = 2 * 1024 * 1024


def _make_client() -> httpx.AsyncClient:
    """The HTTP client for the OpenAI call (a seam for tests)."""
    return httpx.AsyncClient(timeout=TRANSCRIBE_TIMEOUT_S)


def _is_wav(data: bytes) -> bool:
    return len(data) >= 12 and data[:4] == b"RIFF" and data[8:12] == b"WAVE"


@router.post("/api/transcribe")
async def transcribe(
    audio: UploadFile = File(...),
    language: str | None = Form(None),
) -> dict[str, str]:
    # Read one byte past the cap so an oversized upload is detected without
    # pulling the whole thing into memory.
    data = await audio.read(MAX_AUDIO_BYTES + 1)
    if not data:
        raise HTTPException(status_code=400, detail="audio is empty")
    if len(data) > MAX_AUDIO_BYTES:
        raise HTTPException(
            status_code=413,
            detail=f"audio too large (max {MAX_AUDIO_BYTES // (1024 * 1024)} MB)",
        )
    if not _is_wav(data):
        raise HTTPException(status_code=415, detail="audio must be a WAV file")

    key = _resolve_openai_key()
    if not key:
        raise HTTPException(
            status_code=503, detail="OPENAI_API_KEY is not configured on the backend",
        )

    form = {"model": TRANSCRIBE_MODEL, "response_format": "json"}
    lang = (language or "").strip()
    if lang:
        form["language"] = lang

    try:
        async with _make_client() as client:
            resp = await client.post(
                OPENAI_TRANSCRIPTIONS_URL,
                headers={"Authorization": f"Bearer {key}"},
                data=form,
                files={"file": ("audio.wav", data, "audio/wav")},
            )
    except httpx.TimeoutException:
        logger.warning("Whisper transcription timed out after %ss", TRANSCRIBE_TIMEOUT_S)
        raise HTTPException(status_code=504, detail="transcription timed out") from None
    except httpx.HTTPError as e:
        logger.warning("Whisper transcription request failed: %s", e)
        raise HTTPException(status_code=502, detail=f"transcription failed: {e}") from None

    if resp.status_code != 200:
        logger.warning("Whisper transcription error %s: %s", resp.status_code, resp.text[:500])
        raise HTTPException(
            status_code=502, detail=f"OpenAI transcription error: {resp.status_code}",
        )
    try:
        text = resp.json().get("text")
    except ValueError:
        text = None
    if not isinstance(text, str):
        raise HTTPException(status_code=502, detail="OpenAI returned no text")
    return {"text": text.strip()}
