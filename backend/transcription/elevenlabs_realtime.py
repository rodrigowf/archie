"""ElevenLabs Scribe v2 realtime over a WebSocket.

    wss://api.elevenlabs.io/v1/speech-to-text/realtime?model_id=scribe_v2_realtime

Opposite shape to OpenAI's (see :mod:`transcription.openai_realtime`): instead
of append-only deltas, this one re-sends the **whole current segment** as a
``partial_transcript`` — rewriting earlier words as it hears more — until the
segment closes with a ``committed_transcript`` and the next partial starts
empty again. So it emits a run of :class:`~transcription.base.Partial` followed
by one :class:`~transcription.base.Committed` per segment.

Two findings from probing the live API on 2026-11-05:

* **``commit_strategy=vad`` is not the default** and matters enormously. Left
  at ``manual``, nothing is ever committed until the client says so, so a
  30-minute note arrives as one ever-growing partial and the caller can never
  bank any of it. With ``vad`` the segments close on natural pauses.
* **Segments carry no joining whitespace.** "Hey, buddy. We're back." is
  followed by "And we should have…", so this module inserts the space; the
  :class:`~transcription.base.Committed` contract is append-verbatim.

The audio rate travels on each chunk (``sample_rate``), which the service
honours even though ``session_started`` reports its own default back.
"""

from __future__ import annotations

import asyncio
import base64
import json
import logging
from urllib.parse import urlencode

import websockets

from transcription.base import Committed, Failed, Partial, Transcriber, TranscriberError

logger = logging.getLogger(__name__)

REALTIME_URL = "wss://api.elevenlabs.io/v1/speech-to-text/realtime"
OPEN_TIMEOUT_S = 15.0
# The final commit drains until the service goes quiet rather than until the
# first ``committed_transcript``: one commit can close *several* pending
# segments, and stopping at the first one truncates the note. (It does, in
# practice, whenever audio arrives faster than real time.)
QUIET_AFTER_COMMIT_S = 1.5
MAX_DRAIN_S = 15.0
# Close a segment after this much silence. Shorter than the 1.5 s default so
# dictated text lands while you are still speaking the next sentence.
VAD_SILENCE_S = 1.0


class ElevenLabsRealtimeTranscriber(Transcriber):
    def __init__(self, api_key: str, model: str, *, language: str = "", sample_rate: int = 16000) -> None:
        super().__init__()
        self.sample_rate = sample_rate
        self._key = api_key
        self._model = model
        self._language = language
        self._ws: websockets.ClientConnection | None = None
        self._reader: asyncio.Task | None = None
        self._committed_any = False
        # The partial in flight, so a stream that ends without a final commit
        # still banks the words the user actually said.
        self._partial = ""
        self._commit_seen = asyncio.Event()
        self._closing = False
        self._last_message_at = 0.0

    def _url(self) -> str:
        params = {
            "model_id": self._model,
            "commit_strategy": "vad",
            "vad_silence_threshold_secs": str(VAD_SILENCE_S),
            "sample_rate": str(self.sample_rate),
        }
        if self._language:
            params["language_code"] = self._language
        return f"{REALTIME_URL}?{urlencode(params)}"

    async def _start(self) -> None:
        try:
            self._ws = await websockets.connect(
                self._url(),
                additional_headers={"xi-api-key": self._key},
                open_timeout=OPEN_TIMEOUT_S,
                max_size=2**24,
            )
        except Exception as e:
            raise TranscriberError("connect_failed", f"ElevenLabs realtime: {e}") from None
        self._reader = asyncio.create_task(self._read(), name="elevenlabs-transcribe-read")

    async def _feed(self, pcm: bytes) -> None:
        await self._send({
            "message_type": "input_audio_chunk",
            "audio_base_64": base64.b64encode(pcm).decode("ascii"),
            "commit": False,
            "sample_rate": self.sample_rate,
        })

    async def _finish(self) -> None:
        self._commit_seen.clear()
        await self._send({
            "message_type": "input_audio_chunk",
            "audio_base_64": "",
            "commit": True,
            "sample_rate": self.sample_rate,
        })
        loop = asyncio.get_running_loop()
        self._last_message_at = loop.time()
        deadline = loop.time() + MAX_DRAIN_S
        while loop.time() < deadline:
            await asyncio.sleep(0.2)
            if self._closing or loop.time() - self._last_message_at >= QUIET_AFTER_COMMIT_S:
                break
        # Whatever is still only a partial is the last thing the user said;
        # bank it rather than dropping it.
        self._flush_partial()

    async def _close(self) -> None:
        if self._reader is not None:
            self._reader.cancel()
            self._reader = None
        ws, self._ws = self._ws, None
        if ws is not None:
            try:
                await ws.close()
            except Exception:
                pass

    # ---- internals ----------------------------------------------------

    async def _send(self, message: dict) -> None:
        ws = self._ws
        if ws is None:
            return
        await ws.send(json.dumps(message))

    async def _read(self) -> None:
        ws = self._ws
        if ws is None:
            return
        try:
            async for raw in ws:
                try:
                    message = json.loads(raw)
                except (TypeError, ValueError):
                    continue
                self._last_message_at = asyncio.get_running_loop().time()
                self._handle(message)
        except asyncio.CancelledError:
            raise
        except websockets.ConnectionClosed as e:
            self._closing = True
            if not self._commit_seen.is_set():
                self._flush_partial()
                self._emit(Failed("provider_closed", f"ElevenLabs closed the stream: {e}"))
            self._commit_seen.set()
        except Exception as e:
            self._closing = True
            logger.warning("ElevenLabs realtime transcription read failed: %s", e)
            self._emit(Failed("read_failed", str(e)))
            self._commit_seen.set()

    def _handle(self, message: dict) -> None:
        kind = message.get("message_type")
        if kind == "partial_transcript":
            text = message.get("text")
            if isinstance(text, str):
                self._partial = text
                self._emit(Partial(text))
        elif kind in ("committed_transcript", "committed_transcript_with_timestamps"):
            text = message.get("text")
            if isinstance(text, str):
                self._partial = ""
                self._commit(text)
            self._commit_seen.set()
        elif kind in ("input_error", "invalid_request"):
            detail = str(message.get("error") or message.get("message") or "")
            logger.warning("ElevenLabs realtime transcription error: %s", detail)
            self._emit(Failed("provider_error", detail))
            self._closing = True
            self._commit_seen.set()

    def _commit(self, text: str) -> None:
        if not text:
            return
        if self._committed_any and not text[:1].isspace():
            text = " " + text
        self._committed_any = True
        self._emit(Committed(text))

    def _flush_partial(self) -> None:
        pending, self._partial = self._partial, ""
        if pending:
            self._commit(pending)
