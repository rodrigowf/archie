"""The one interface every transcriber implements, and its event vocabulary.

A :class:`Transcriber` is **one dictation**: open it, feed PCM16 mono at
:attr:`~Transcriber.sample_rate`, then either :meth:`~Transcriber.finish` (flush
and get the last words) or :meth:`~Transcriber.aclose` (throw it away). Text
comes back by iterating :meth:`~Transcriber.events` concurrently with feeding.

Three events, and the split between the first two is the whole point of this
module::

    Partial("and keep the disabled")   # volatile — replaced by the next Partial
    Committed(" and keep the disabled send")  # final — append to the transcript
    Failed("provider_closed", "...")   # terminal

Providers disagree about which they emit. OpenAI's realtime transcription sends
append-only deltas, so every delta is already final: it emits only ``Committed``
and never a ``Partial``. ElevenLabs sends a ``partial_transcript`` that is
re-sent and rewritten until the segment closes with a ``committed_transcript``:
it emits a run of ``Partial`` then one ``Committed`` per segment. A client that
renders ``committed + partial`` is correct for both without knowing which it
is talking to — which is exactly what ``WS /api/transcribe/stream`` does.

``Committed`` text is appended verbatim, so a provider must keep whatever
leading space its own tokens carry; the route does not re-join words.
"""

from __future__ import annotations

import abc
import asyncio
import io
import logging
import wave
from dataclasses import dataclass
from typing import AsyncIterator

logger = logging.getLogger(__name__)

# How long finish() waits for a provider to flush its last words before giving
# up on them. Generous: the OpenAI realtime API can take a few seconds to turn
# the tail of a long utterance into deltas.
FLUSH_TIMEOUT_S = 20.0


@dataclass(frozen=True)
class Partial:
    """Volatile text that replaces the previous ``Partial`` of this segment."""

    text: str


@dataclass(frozen=True)
class Committed:
    """Final text, to be appended to everything committed before it."""

    text: str


@dataclass(frozen=True)
class Failed:
    """The transcription ended badly. Terminal: no events follow."""

    code: str
    detail: str = ""


TranscriptEvent = Partial | Committed | Failed


class TranscriberError(RuntimeError):
    """Raised by :meth:`Transcriber.start` when the session cannot be opened."""

    def __init__(self, code: str, detail: str = "") -> None:
        super().__init__(detail or code)
        self.code = code
        self.detail = detail


class Transcriber(abc.ABC):
    """One dictation against one provider.

    Subclasses implement :meth:`_start`, :meth:`_feed`, :meth:`_finish` and
    :meth:`_close`, and call :meth:`_emit` whenever the provider produces text.
    This class owns the event queue and the end-of-stream bookkeeping, so no
    provider has to reimplement them.
    """

    #: PCM16 mono sample rate this provider expects, in Hz.
    sample_rate: int = 24000

    def __init__(self) -> None:
        self._events: asyncio.Queue[TranscriptEvent | None] = asyncio.Queue()
        self._done = False
        self._started = False

    # ---- lifecycle ----------------------------------------------------

    async def start(self) -> None:
        """Open the provider session. Raises :class:`TranscriberError`."""
        if self._started:
            return
        self._started = True
        await self._start()

    async def feed(self, pcm: bytes) -> None:
        """Hand over the next PCM16 mono chunk. Ignored once finished."""
        if self._done or not pcm:
            return
        await self._feed(pcm)

    async def finish(self) -> None:
        """Say there is no more audio and wait for the provider's last words.

        Always ends the event stream, even on timeout or provider error, so a
        caller iterating :meth:`events` is never left hanging.
        """
        if self._done:
            return
        try:
            await asyncio.wait_for(self._finish(), FLUSH_TIMEOUT_S)
        except asyncio.TimeoutError:
            logger.warning("%s did not flush within %ss", type(self).__name__, FLUSH_TIMEOUT_S)
        except Exception as e:  # a provider that dies on flush loses only its tail
            logger.warning("%s failed while flushing: %s", type(self).__name__, e)
            self._emit(Failed("flush_failed", str(e)))
        finally:
            self._end()
            await self._close()

    async def aclose(self) -> None:
        """Abandon the dictation. Safe to call twice, and after :meth:`finish`."""
        self._end()
        await self._close()

    # ---- events -------------------------------------------------------

    async def events(self) -> AsyncIterator[TranscriptEvent]:
        """Yield text as it arrives, ending when the dictation does."""
        while True:
            ev = await self._events.get()
            if ev is None:
                return
            yield ev
            if isinstance(ev, Failed):
                return

    def _emit(self, event: TranscriptEvent) -> None:
        """Publish an event (called by subclasses, from any task)."""
        if not self._done:
            self._events.put_nowait(event)

    def _end(self) -> None:
        """Close the event stream exactly once."""
        if not self._done:
            self._done = True
            self._events.put_nowait(None)

    # ---- for subclasses ------------------------------------------------

    @abc.abstractmethod
    async def _start(self) -> None: ...

    @abc.abstractmethod
    async def _feed(self, pcm: bytes) -> None: ...

    @abc.abstractmethod
    async def _finish(self) -> None: ...

    async def _close(self) -> None:
        """Release provider resources. Must tolerate being called twice."""
        return None


def wav_bytes(pcm: bytes, sample_rate: int) -> bytes:
    """Wrap raw PCM16 mono in a WAV container (what the batch APIs accept)."""
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(sample_rate)
        w.writeframes(pcm)
    return buf.getvalue()
