# Transcription: dictation and voice notes

Speech to text for every client. The backend owns the API keys, so transcription is a **backend
service** rather than something each client arranges: a client sends PCM and gets text back,
without knowing or caring which vendor did the work.

Two features sit on it today:

- **Composer dictation** — an empty composer on an agent session shows a mic instead of a dead
  disabled Send. Speaking streams punctuated text into the field; Stop leaves it there to edit,
  Send stops and sends in one press. Design board: `context/public/composer-dictation/`.
- **Voice notes** — the `/voice-notes/` canvas: record for as long as you like, watch the
  transcript appear, and keep every note on disk with its audio.

Both talk to the same socket, so a third consumer (Android, the TV) needs no new backend work.

## Where the code is

| Piece | File |
|---|---|
| Catalogue: which models exist, which have a key, what each costs | `backend/transcription/registry.py` |
| The one interface, and the event vocabulary | `backend/transcription/base.py` |
| OpenAI realtime (`gpt-live-transcribe`) | `backend/transcription/openai_realtime.py` |
| ElevenLabs Scribe v2 realtime | `backend/transcription/elevenlabs_realtime.py` |
| Batch (both vendors), used by `POST /api/transcribe` | `backend/transcription/batch.py` |
| Notes on disk | `backend/transcription/notes.py` |
| Routes | `backend/api/routes/transcribe.py` |
| Web client (the socket + the mic) | `apps/web/src/voice/dictation.ts` (lazy entry `@/voice/dictation`) |
| Web client, no AudioWorklet (Safari 12) | `apps/web/src/features/composer/audio/dictationFallback.ts` |
| The canvas | `context/public/voice-notes/` |
| Read notes from an agent or the orchestrator | `shared/scripts/voice_notes.py` |

## The models

Settings → Dictation picks one. The list is rendered from `GET /api/transcribe/providers`;
entries with no API key on this server are shown **greyed with the reason** rather than hidden.

| Model | Live? | Rate | Key | Cost |
|---|---|---|---|---|
| `openai/gpt-live-transcribe` *(default)* | yes, word by word | 24 kHz | `OPENAI_API_KEY` | ≈ $0.36/h |
| `elevenlabs/scribe_v2_realtime` | yes, 150 ms partials, 90+ languages | 16 kHz | `ELEVENLABS_API_KEY` | $0.39/h |
| `openai/gpt-transcribe` | no, on stop | 16 kHz | `OPENAI_API_KEY` | ≈ $0.36/h |
| `openai/gpt-4o-mini-transcribe` | no, on stop | 16 kHz | `OPENAI_API_KEY` | ≈ $0.18/h |
| `elevenlabs/scribe_v1` | no, on stop | 16 kHz | `ELEVENLABS_API_KEY` | ≈ $0.22/h |

`whisper-1` is **gone**: OpenAI retires it on 2027-02-26, and `gpt-transcribe` is both better and
cheaper. `POST /api/transcribe` (the TV's dictation, the compat build's) now defaults to it.

`registry.resolve()` never raises. An explicit choice that exists is returned as-is even with no
key — the caller then reports `no_api_key`, which is honest; silently billing a different vendor
would be worse. Everything else is a fallback, and fallbacks prefer a provider that works.

## Two provider shapes, one stream

This is the part worth understanding before touching the provider modules. The two streaming
APIs disagree about what a piece of text means:

- **OpenAI** sends append-only deltas — one word or one punctuation mark at a time, each already
  final. It emits only `Committed`, never a `Partial`.
- **ElevenLabs** re-sends the **whole current segment** as a `partial_transcript`, rewriting
  earlier words as it hears more, until the segment closes with a `committed_transcript` and the
  next partial starts empty. It emits a run of `Partial` then one `Committed` per segment.

`transcription/base.py` normalises both onto `Committed` / `Partial`, and the socket sends
`{append, partial}`: append what is final, replace the volatile tail. A client that renders
`committed + partial` is correct for both without knowing which is configured.

`Committed` text is appended **verbatim**, so each provider keeps whatever joining whitespace its
own tokens carry (OpenAI's transcripts open with a leading space; ElevenLabs' segments carry
none, so that module inserts the space itself).

## Protocol quirks, verified against the live APIs (2026-11-05)

Each of these costs an afternoon if you learn it the hard way:

**OpenAI** — `wss://api.openai.com/v1/realtime?intent=transcription`

1. **Send no beta header.** `OpenAI-Beta: realtime=v1`, which every older example uses, is now
   refused with `beta_api_shape_disabled` and the socket closes.
2. **`turn_detection` must be `null`** for this model. The default (`server_vad`) is rejected —
   and because the rejection arrives as an `error` *event* rather than a close, the session stays
   open and silently transcribes nothing (`transcription` is left `null`).
3. The `.completed` event repeats the whole transcript, so it is used only to recover a tail that
   was somehow missed, never appended wholesale.

**ElevenLabs** — `wss://api.elevenlabs.io/v1/speech-to-text/realtime`

1. **`commit_strategy=vad` is not the default** and matters enormously. Left at `manual`, nothing
   is ever committed until the client says so, so a 30-minute note arrives as one ever-growing
   partial and nothing can be banked.
2. **The final commit must drain until the service goes quiet**, not until the first
   `committed_transcript`: one commit can close several pending segments, and stopping at the
   first truncates the note. (It does, in practice, whenever audio arrives faster than real time.)
3. The audio rate travels on each chunk (`sample_rate`), which the service honours even though
   `session_started` reports its own default back.

## The socket

`WS /api/transcribe/stream` — home-network only (`api.net.is_from_home_network`, checked *before*
`accept`), like every other route that spends the server's keys.

```
→ {"type":"start","provider":?,"model":?,"language":?,"sample_rate":?,"save":{"title":?}?}
→ {"type":"audio","audio":"<base64 pcm16 mono>"}      ~100 ms chunks
→ {"type":"stop"} | {"type":"cancel"}
← {"type":"ready","provider":…,"model":…,"streaming":…,"sample_rate":…,"note_id":…}
← {"type":"text","append":"newly final","partial":"volatile tail"}
← {"type":"final","text":"…","note":{…}?}
← {"type":"cancelled"}
← {"type":"error","error":"<code>","detail":"…"}
```

Omit `save` for dictation; include it to keep a voice note. Unknown message types are **ignored**
(the canvas sends `{"type":"ping"}` while paused — any message resets the idle timer).

Caps, because unlike a POST a socket has no natural end: 4 concurrent, 10 min per dictation, 2 h
per note, 90 s idle, 1 MB per chunk.

**Every exit except an explicit cancel flushes.** A stop, a dropped tab, the idle timeout and the
duration cap all keep what was said — a dropped socket must never cost the sentence you just spoke.

## Voice notes on disk

```
context/recordings/voice-notes/20261105T214500-tarot-daily-draw/
    audio.mp3        the recording (WAV during capture, converted on stop)
    transcript.md    rewritten as segments commit
    meta.json        id, title, timing, which transcriber produced the text
```

Audio is appended **as it arrives** rather than uploaded in one piece at the end. That is what
makes an unbounded recording safe: nothing is held in the browser, a 40-minute note costs the
backend one open file handle, and a tab that crashes mid-note still leaves the audio and the
transcript behind.

`context/recordings/` is already gitignored by the context repo (audio is far too big for git) and
context-sync mirrors it out of band. A failed ffmpeg conversion keeps the WAV: the format is
expendable, the recording is not.

REST: `GET /api/transcribe/notes`, `GET …/{id}`, `GET …/{id}/audio`, `PATCH …/{id}` (title),
`DELETE …/{id}`. Note ids from a URL are matched against the exact shape the writer produces, so
there is nothing to walk out of the notes directory with.

## Settings

Four server keys (`backend/api/routes/config.py`): `dictation_enabled` (default **true**),
`dictation_provider`, `dictation_model`, `dictation_language` (`""` = let the provider detect it,
which is what you want when switching between Portuguese and English mid-sentence).

`GET /api/transcribe/providers` returns the catalogue **and** the current settings, so a client
needs one cheap call to answer both "may I show the mic?" and "what are the choices?" —
`/api/config` is not broadcast (G-27) and is only fetched when Settings opens, far too late for
the composer. `enabled` is false when the switch is off *or* the chosen model has no key here.

## Testing

```
context/scripts/run.sh -m pytest backend/tests/test_transcription_registry.py \
    backend/tests/test_transcription_stream.py backend/tests/test_voice_notes.py \
    backend/tests/test_api_transcribe.py -v
```

The provider modules themselves are only exercised against the live APIs (they are thin
translation layers over a vendor socket, and a mock of a vendor socket tests the mock). To check a
provider after an API change, stream a WAV through the layer:

```python
from transcription import registry
spec = registry.find("openai", "gpt-live-transcribe")
t = registry.create(spec, sample_rate=24000)
await t.start(); await t.feed(pcm); await t.finish()
# then read t.events()
```
