## 1. Architecture of the client data layer

Both platforms MUST implement the same five layers. Only layer 5 (UI) and the audio engines differ per platform.

```
 ┌───────────────────────────────────────────────────────────────────────────┐
 │ 5  UI (React / Compose) — reads immutable snapshots, dispatches actions   │
 ├───────────────────────────────────────────────────────────────────────────┤
 │ 4  Stores: SessionDirectory (list + pool), Config, Visualizations, Memory │
 ├───────────────────────────────────────────────────────────────────────────┤
 │ 3  Conversation reducer — PURE function (state, input) -> state  (§4, §5) │
 ├───────────────────────────────────────────────────────────────────────────┤
 │ 2  Connection managers: one per open conversation (§3)                    │
 │    seq dedupe, start/re-start, replay, pre-start buffer, reconnect        │
 ├───────────────────────────────────────────────────────────────────────────┤
 │ 1  Transport: WebSocket (binary in / text out), REST client                │
 └───────────────────────────────────────────────────────────────────────────┘
```

Rules:

- **L-1.** The conversation reducer MUST be a pure, synchronous, deterministic function with no I/O, no timers and no clock reads. Everything it needs (REST pages, data-channel events, user actions) arrives as an *input*. This is what makes the fixtures (§11) runnable on both platforms. The web implements it in TypeScript; Android implements it in pure Kotlin in the shared `:core` module.
- **L-2.** All inputs for one conversation MUST be applied in a single ordered stream (one queue, one consumer). The transport → reducer path MUST be lossless: no bounded buffer that drops on overflow. *Rationale:* Android's `MutableSharedFlow(extraBufferCapacity=64)` + `tryEmit` silently drops text deltas under load (A-3.3).
- **L-3.** High-rate audio frames (`voice_audio_out`, `voice_audio_in`) MUST NOT go through the conversation input queue. The connection manager routes them directly to the audio engine.
- **L-4.** The reducer state MUST be exposed to the UI as immutable snapshots, or as observable state with structural sharing. Mutations from more than one thread are forbidden (A-4.4.7).

---

