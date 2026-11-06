"""Batch transcription: a whole recording in, the text out.

Two callers:

* ``POST /api/transcribe`` hands over a finished WAV (the TV's dictation, the
  Safari 12 compat build, anything that cannot hold a WebSocket open).
* :class:`BatchTranscriber` makes the streaming interface available to models
  that have no streaming API at all — it buffers the PCM and transcribes once
  on :meth:`~transcription.base.Transcriber.finish`. The client sees the same
  protocol, just with no text until it stops.

This is also where ``whisper-1`` finally goes away: OpenAI retires it on
2026-02-26 and the replacement, ``gpt-transcribe``, is both better and cheaper.
"""

from __future__ import annotations

import logging

import httpx

from transcription.base import Committed, Failed, Transcriber, TranscriberError, wav_bytes
from transcription.registry import TranscriberSpec

logger = logging.getLogger(__name__)

OPENAI_TRANSCRIPTIONS_URL = "https://api.openai.com/v1/audio/transcriptions"
ELEVENLABS_TRANSCRIPTIONS_URL = "https://api.elevenlabs.io/v1/speech-to-text"
TRANSCRIBE_TIMEOUT_S = 25.0

#: Buffered-audio ceiling for the streaming-shaped wrapper, in PCM16 bytes.
#: 25 MB is ~13 min at 16 kHz — past the point where a batch model is the wrong
#: tool, and far below anything that would trouble the backend's memory.
MAX_BUFFERED_BYTES = 25 * 1024 * 1024


def _make_client() -> httpx.AsyncClient:
    """The HTTP client for the provider call (a seam for tests)."""
    return httpx.AsyncClient(timeout=TRANSCRIBE_TIMEOUT_S)


async def transcribe_wav(spec: TranscriberSpec, key: str, wav: bytes, language: str = "") -> str:
    """Transcribe a WAV with *spec*. Raises :class:`TranscriberError`."""
    if spec.provider == "openai":
        url = OPENAI_TRANSCRIPTIONS_URL
        headers = {"Authorization": f"Bearer {key}"}
        data: dict[str, str] = {"model": spec.model, "response_format": "json"}
        if language:
            data["language"] = language
    elif spec.provider == "elevenlabs":
        url = ELEVENLABS_TRANSCRIPTIONS_URL
        headers = {"xi-api-key": key}
        data = {"model_id": spec.model}
        if language:
            data["language_code"] = language
    else:
        raise TranscriberError("unknown_provider", f"no batch transcriber for {spec.id}")

    try:
        async with _make_client() as client:
            resp = await client.post(
                url,
                headers=headers,
                data=data,
                files={"file": ("audio.wav", wav, "audio/wav")},
            )
    except httpx.TimeoutException:
        logger.warning("%s transcription timed out after %ss", spec.id, TRANSCRIBE_TIMEOUT_S)
        raise TranscriberError("timeout", "transcription timed out") from None
    except httpx.HTTPError as e:
        logger.warning("%s transcription request failed: %s", spec.id, e)
        raise TranscriberError("http_error", f"transcription failed: {e}") from None

    if resp.status_code != 200:
        logger.warning("%s transcription error %s: %s", spec.id, resp.status_code, resp.text[:500])
        raise TranscriberError(
            "provider_error", f"{spec.provider} transcription error: {resp.status_code}",
        )
    try:
        text = resp.json().get("text")
    except ValueError:
        text = None
    if not isinstance(text, str):
        raise TranscriberError("no_text", f"{spec.provider} returned no text")
    return text.strip()


class BatchTranscriber(Transcriber):
    """A non-streaming model behind the streaming interface."""

    def __init__(self, api_key: str, spec: TranscriberSpec, *, language: str = "", sample_rate: int = 16000) -> None:
        super().__init__()
        self.sample_rate = sample_rate
        self._key = api_key
        self._spec = spec
        self._language = language
        self._chunks: list[bytes] = []
        self._buffered = 0
        self._overflowed = False

    async def _start(self) -> None:
        return None

    async def _feed(self, pcm: bytes) -> None:
        if self._overflowed:
            return
        self._buffered += len(pcm)
        if self._buffered > MAX_BUFFERED_BYTES:
            self._overflowed = True
            self._chunks.clear()
            self._emit(Failed(
                "too_long",
                f"{self._spec.label} transcribes on stop and cannot hold more than "
                f"{MAX_BUFFERED_BYTES // (1024 * 1024)} MB of audio — pick a live model for long recordings",
            ))
            return
        self._chunks.append(pcm)

    async def _finish(self) -> None:
        if self._overflowed or not self._chunks:
            return
        pcm = b"".join(self._chunks)
        self._chunks.clear()
        try:
            text = await transcribe_wav(
                self._spec, self._key, wav_bytes(pcm, self.sample_rate), self._language,
            )
        except TranscriberError as e:
            self._emit(Failed(e.code, e.detail))
            return
        if text:
            self._emit(Committed(text))
