"""Speech-to-text for every client: one interface over several providers.

The backend owns the API keys, so transcription is a backend service rather than
something each client arranges for itself. Two shapes of the same job live here:

* **Streaming** — audio arrives in ~100 ms chunks and text comes back while the
  speaker is still talking. Used by composer dictation and the voice-notes
  canvas, both over ``WS /api/transcribe/stream``.
* **Batch** — a whole recording goes out in one request and the text comes back
  once. Used by ``POST /api/transcribe`` (the TV, the Safari 12 compat build)
  and by streaming clients that picked a model which cannot stream.

Both shapes implement :class:`~transcription.base.Transcriber`, so the route
code never branches on which provider is configured — it feeds PCM in and reads
:class:`~transcription.base.Committed` / :class:`~transcription.base.Partial`
events out. The providers differ sharply underneath (OpenAI appends word-sized
deltas to one growing item; ElevenLabs replaces a partial until it commits a
segment) and :mod:`transcription.base` is where that difference is normalised.

:mod:`transcription.registry` is the catalogue — which models exist, which have
a key on this server, which can stream, and what an hour of talking costs.
"""
