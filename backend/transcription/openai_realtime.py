"""OpenAI realtime transcription — ``gpt-live-transcribe`` over a WebSocket.

    wss://api.openai.com/v1/realtime?intent=transcription

Three things about this endpoint cost an afternoon if you learn them the hard
way, all verified against the live API on 2026-11-05:

1. **No beta header.** Sending ``OpenAI-Beta: realtime=v1`` — which every older
   example and the previous generation of this code does — is now refused with
   ``beta_api_shape_disabled`` and the socket is closed. The GA shape takes a
   plain ``Authorization`` header.
2. **``turn_detection`` must be null** for this model. Leaving the default
   (``server_vad``) is rejected with "Turn detection is not supported for this
   transcription model", and because the rejection arrives as an ``error``
   event rather than a close, the session stays open and silently transcribes
   nothing: ``session.audio.input.transcription`` is left ``null``.
3. **The deltas are already final.** ``conversation.item.input_audio_transcription.delta``
   carries one word (or one punctuation mark) of append-only text, so each one
   is a :class:`~transcription.base.Committed` — this provider never emits a
   ``Partial``. The ``.completed`` event that follows a commit repeats the
   whole transcript, so it is used only to recover a tail we somehow missed,
   never appended wholesale.
"""

from __future__ import annotations

import asyncio
import base64
import json
import logging

import websockets

from transcription.base import Committed, Failed, Transcriber, TranscriberError

logger = logging.getLogger(__name__)

REALTIME_URL = "wss://api.openai.com/v1/realtime?intent=transcription"
OPEN_TIMEOUT_S = 15.0

_DELTA = "conversation.item.input_audio_transcription.delta"
_COMPLETED = "conversation.item.input_audio_transcription.completed"


class OpenAIRealtimeTranscriber(Transcriber):
    def __init__(self, api_key: str, model: str, *, language: str = "", sample_rate: int = 24000) -> None:
        super().__init__()
        self.sample_rate = sample_rate
        self._key = api_key
        self._model = model
        self._language = language
        self._ws: websockets.ClientConnection | None = None
        self._reader: asyncio.Task | None = None
        # Text already emitted per transcription item, so `.completed` can tell
        # whether it is repeating us or telling us something new.
        self._emitted: dict[str, str] = {}
        self._completed = asyncio.Event()

    async def _start(self) -> None:
        try:
            self._ws = await websockets.connect(
                REALTIME_URL,
                additional_headers={"Authorization": f"Bearer {self._key}"},
                open_timeout=OPEN_TIMEOUT_S,
                max_size=2**24,
            )
        except Exception as e:
            raise TranscriberError("connect_failed", f"OpenAI realtime: {e}") from None

        transcription: dict[str, object] = {"model": self._model}
        if self._language:
            transcription["language"] = self._language
        await self._send({
            "type": "session.update",
            "session": {
                "type": "transcription",
                "audio": {
                    "input": {
                        "format": {"type": "audio/pcm", "rate": self.sample_rate},
                        "transcription": transcription,
                        # Load-bearing: see the module docstring.
                        "turn_detection": None,
                    },
                },
            },
        })
        self._reader = asyncio.create_task(self._read(), name="openai-transcribe-read")

    async def _feed(self, pcm: bytes) -> None:
        await self._send({
            "type": "input_audio_buffer.append",
            "audio": base64.b64encode(pcm).decode("ascii"),
        })

    async def _finish(self) -> None:
        # Commit what is buffered so the model turns the tail into deltas and
        # closes the item; `.completed` then tells us it is done.
        await self._send({"type": "input_audio_buffer.commit"})
        await self._completed.wait()

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
                    event = json.loads(raw)
                except (TypeError, ValueError):
                    continue
                self._handle(event)
        except asyncio.CancelledError:
            raise
        except websockets.ConnectionClosed as e:
            # A close during the flush is normal once we have the transcript.
            if not self._completed.is_set():
                self._emit(Failed("provider_closed", f"OpenAI closed the stream: {e}"))
            self._completed.set()
        except Exception as e:
            logger.warning("OpenAI realtime transcription read failed: %s", e)
            self._emit(Failed("read_failed", str(e)))
            self._completed.set()

    def _handle(self, event: dict) -> None:
        kind = event.get("type")
        if kind == _DELTA:
            delta = event.get("delta")
            if isinstance(delta, str) and delta:
                item = str(event.get("item_id") or "")
                self._emitted[item] = self._emitted.get(item, "") + delta
                self._emit(Committed(delta))
        elif kind == _COMPLETED:
            item = str(event.get("item_id") or "")
            full = event.get("transcript")
            if isinstance(full, str):
                seen = self._emitted.get(item, "")
                # Normally `full` is exactly what the deltas already said. Emit
                # only a tail we are sure we missed; never the whole thing, or
                # the transcript doubles.
                if not seen:
                    if full:
                        self._emit(Committed(full))
                elif full.startswith(seen) and len(full) > len(seen):
                    self._emit(Committed(full[len(seen):]))
                self._emitted[item] = full
            self._completed.set()
        elif kind == "error":
            err = event.get("error") or {}
            code = str(err.get("code") or "provider_error")
            detail = str(err.get("message") or "")
            logger.warning("OpenAI realtime transcription error %s: %s", code, detail)
            self._emit(Failed(code, detail))
            self._completed.set()
