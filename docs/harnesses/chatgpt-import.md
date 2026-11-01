# ChatGPT import (`harness: "chatgpt"`)

Conversations exported from **ChatGPT** (OpenAI's own product, not a harness Archie can run),
converted into Archie's per-conversation JSONL so they are searchable and readable alongside every
other harness's history.

This is the one **import-only, read-only** harness value. There is no `HarnessSpec`, no
`backend/manager/chatgpt/`, no session manager and no CLI: nothing is spawned, resumed or
continued. `harness: "chatgpt"` exists only so the history index, `search_history` and the UI can
tell these conversations apart from conversations Archie actually ran.

- Converter: `shared/scripts/chatgpt_import.py`
- Output: `context/chatgpt-history/` (private; the export is personal data)
- Index integration: `backend/utils/history_index.py`
- Resume exclusion: `backend/orchestrator/tools/agent_sessions.py`

## Usage

```
context/scripts/run.sh shared/scripts/chatgpt_import.py [--root DIR] [--export DIR]
                                                        [--dry-run] [--hardlink]
```

1. Request an export from ChatGPT (Settings → Data controls → Export data) and unzip it.
2. Put the unzipped directory under `context/chatgpt-history/raw/`. The converter finds it by
   looking for `conversations-000.json` — first under `<root>/raw/`, then directly under `<root>`.
3. Run the converter. `--dry-run` reports what it would do and writes nothing.
4. The next index pass (every ~5 min, or on demand) makes the conversations searchable.

`--root` defaults to `context/chatgpt-history`; `--hardlink` hard-links attachments instead of
copying them (same filesystem only), which avoids duplicating the blobs.

**Reruns are safe and deterministic.** `jsonl/` and `attachments/` are derived: the converter
clears and rebuilds them on every run, conversations sorted by `create_time` then `id`. Two runs
over the same export produce byte-identical output (only `MANIFEST.generated_at` moves). `raw/` is
never written to.

## Layout

```
context/chatgpt-history/
├── raw/<export-dir>/              the original export, untouched
├── jsonl/<conversation_id>.jsonl  one file per conversation
├── attachments/<conversation_id>/<file_id>.<ext>
├── index.csv                      one row per conversation
├── attachments.csv                one row per asset reference
└── MANIFEST.json                  provenance: shard sha256s, export timestamp, counts
```

The file name is the ChatGPT `conversation_id`, so `session_id_for()` returns it unchanged and the
session id in the index *is* the ChatGPT id. Conversation ids and titles are preserved verbatim;
nothing is re-keyed.

**Titles.** Archie's own conversations are titled in `context/.titles.json`; an imported one carries
its title in its header instead, and `extract_session` reads it into `SessionDoc.title`. That
file-carried title **beats the LLM auto-title** in `sessions.auto_title` (the way a `.titles.json`
rename does), so search results and `read_conversation` show the title Rodrigo saw in ChatGPT.
`SessionDoc.title` is `None` for every other harness, so nothing else changes.

## JSONL format

Line 1 is a header; every following line is one turn of the canonical thread.

```jsonc
{"type":"chatgpt_meta","harness":"chatgpt","schema_version":1,"read_only":true,
 "session_id":"67684acc-…","conversation_id":"67684acc-…","title":"Câmera ou microfone?",
 "create_time":1734888166.957915,"timestamp":"2024-12-22T17:22:46.957915+00:00",
 "started_at":"…","ended_at":"…","default_model_slug":"gpt-4o","models":[],"voice":"orbit",
 "source":{"export":"ed8aa578…","shard":"conversations-000.json","origin":"chatgpt_export"},
 "ui":{"label":"ChatGPT","icon":"chatgpt-mark","resumable":false},
 "counts":{"turns":32,"nodes":33,"nodes_off_spine":0,"voice_turns":32,"audio_files_present":32,…}}
```

```jsonc
{"type":"user","create_time":1734888167.169888,"timestamp":"2024-12-22T17:22:47.169888+00:00",
 "node_id":"eef74ef4-…","parent_id":"bbb23c42-…","voice":true,
 "message":{"role":"user","content":[{"type":"text","text":"Agora ele está usando a câmera…"}]},
 "attachments":[{"kind":"audio","file_id":"file_c8eb…","present":true,
                 "path":"attachments/67684acc-…/file_c8eb….wav","duration_s":6.02}],
 "audio":{"available":true,"direction":"in","format":"wav","path":"attachments/…","duration_s":6.02}}
```

`message.content` is a list of typed blocks **mirroring Claude's shape on purpose**, so
`_blocks_text()` parses it with no extra code. All provenance (model slug, node ids, voice and
audio) stays outside `message`, so the conversational payload is clean.

### Design rules

| Rule | Why |
|---|---|
| **SPINE-1** — the thread is `current_node` walked upward to its root, reversed | A ChatGPT conversation is a tree; this export carries only `parent` pointers (no `children` arrays), so the children map is inverted by the converter. Abandoned edit/regeneration branches are discarded. |
| **TIME-1** — every line carries both `create_time` (float epoch, canonical) and `timestamp` (ISO-8601 UTC, derived) | The index only accepts string timestamps (`Turn.ts`, gated by `isinstance(ts, str)`), and reads dates by slicing the string. A float-only line would index with **no date at all**, silently. |
| **VOICE-1** — the transcription *is* the content, always written as text | Most audio blobs expire server-side. The text is what makes a voice conversation searchable; audio is an enrichment. |
| **VOICE-2** — a missing blob gets `audio.available: false` and never a path | A path that doesn't resolve is worse than an explicit absence. |
| **REASON-1** — `thoughts` and `reasoning_recap` are kept, flagged `reasoning: true` with `reasoning_mode: "thoughts"\|"recap"` | They are real model output; dropping them loses the reasoning trail. Flagged so a consumer can skip them, as the Qwen branch already does with `skip_thoughts`. |
| **ASSET-1** — assets live outside the JSONL, under `attachments/<conversation_id>/`, extension sniffed from magic bytes | The export names every blob `.dat` regardless of type. No asset is shared between conversations, so ownership is unambiguous. |
| **ASSET-2** — blobs reachable only from abandoned branches are still copied, as *loose* attachments (`on_spine=false`) referenced by no turn | Discarding a branch should not silently discard a file. |
| **READ-ONLY-1** — `harness: "chatgpt"` is never resumable | There is no process behind an imported conversation. See *Read-only* below. |

### Excluded from the pipeline

Kept in `raw/`, recorded in `MANIFEST.source.excluded_from_pipeline`:

- **`user.json`** — account identity (email, phone). Never copied into the JSONL; the header has no
  user-identity field.
- **`codex.json`** — Codex IDE tasks in a completely different schema (`{id, title, turns:[…]}`).
  Archie has a real Codex harness with its own rollout format; see [codex-cli.md](codex-cli.md).
- **`chat.html`** — a redundant human-readable render of the same conversations. Not a
  visualization path.

## Read-only

An imported conversation is searchable and readable, never resumable. Three guards, all keyed on
`READ_ONLY_HARNESSES` / `is_read_only_session()` in `backend/utils/history_index.py`:

1. `_kind_matches("agent", "chatgpt")` is **False** — `kind="agent"` means "anything resumable", so
   imported conversations never appear as resume candidates.
2. `resume_conversation` refuses with a message pointing at `read_conversation`.
3. `open_agent_session(resume_sdk_id=…)` refuses the same way, so the invariant holds whichever
   entry point is used.

`search_history`, `read_conversation` and `grep_conversation` work normally — reading is the point.
`find_session_file()` resolves `chatgpt-history/jsonl/<id>.jsonl` alongside `context/*.jsonl` and
`context/chats/*.jsonl`, which is what makes the two read tools work on an imported id.

A fourth guard covers the clients: the chat WebSocket's `start` handler refuses any
`resume_sdk_id` that is an imported conversation with `error{read_only_session}`
(`backend/api/routes/chat.py`). The web app never sends `start` for one — it opens the view
read-only — but a stale tab or an older client would otherwise create an empty live session under
the imported id.

## Listing and opening (the clients)

`manager/chatgpt/adapter.py` registers a `ProviderAdapter` and **no** `HarnessSpec`, which is the
whole trick: `SessionStore` can list and read these conversations, while `chatgpt` never appears as
a harness the user can pick for a new session.

- `SessionStore.list_sessions()` scans `context/chatgpt-history/jsonl/*.jsonl` (stem = session id,
  as for Claude), and `_locate_jsonl()` resolves that directory too.
- `SessionInfo.read_only` / `SessionInfoResponse.read_only` carry the flag out through
  `GET /api/sessions`, so every client can tell an imported conversation apart without
  special-casing the provider string.
- The web app opens it with the existing read-only view (spec 12 **H-3**: REST only, no chat
  socket, no composer). `openFromHistory` passes `readOnly` straight from the row.
- `ReadOnlyBar` offers **no Resume** for an import — the session store's `resumable` flag is false
  for providers in `IMPORTED_PROVIDERS` (`SessionRuntime.ts`) — and says "Imported from ChatGPT,
  read only." A normal past conversation still gets its Resume button.
- History rows show the ChatGPT mark and a `· ChatGPT · read-only ·` tooltip.

**Detection is order-independent.** The turn lines are Claude-shaped, so `ClaudeAdapter`'s
heuristic would otherwise claim an imported file; it rejects a `chatgpt_meta` first line
explicitly rather than relying on adapter registration order.

**Android** does the same, through one flag rather than a parallel view:

- `SessionRef.readOnly` carries it (`SessionInfoDto.read_only` → `SessionSummary.readOnly` →
  `OpenSessionsRepository.openSession`), labelled `ChatGPT` via `HarnessLabels.SHORT`.
- `ConversationRepository.AgentHandle.begin()` takes a REST-only path for a read-only ref: it runs
  `coldOpen()` and **never acquires a socket**. `socket` staying uninitialised is itself the test —
  `sendFrame` / `reconnectNow` / `onForeground` / `onBackground` / `park` / `unpark` all check
  `::socket.isInitialized` and no-op, so nothing can dial, send or reconnect. For a live view every
  one of those paths is unchanged.
- The composer reuses the existing disabled-with-reason mechanism (`ComposerUi.enabled = false`,
  `disabledReason = "Imported from ChatGPT — read-only"`), so there is no second screen to maintain.
- `statusOf` / `detailOf` answer for a read-only ref **first** — `IDLE` and "Imported from ChatGPT ·
  read-only". Without that the tab sat on "Connecting…" forever, since it never dials.
- History rows, open items and the switcher show the ChatGPT mark instead of the terminal glyph:
  `ChatGptMark` (`core/design`, from `res/drawable/ic_chatgpt_mark.xml` — the same single path as
  the web asset, tinted by `Icon`). Unlike the decorative icons beside it the mark carries a
  content description, because a one-line row has no supporting text to name the provider.

`HarnessProvider.imported` is the one place that knows which providers are imports — the Kotlin
counterpart of the web's `IMPORTED_PROVIDERS`.

`SessionsController.openFromHistory` needs no special case — an imported row opens like any other.

## UI

`search_history` results carry `kind` (= harness, from `history_nav.py`). The web app badges
`kind === 'chatgpt'` rows with the ChatGPT mark and appends `read-only` to the meta line
(`apps/web/src/features/tools/renderers/history.tsx`).

The mark is a **standalone brand asset**, deliberately outside the generated Material Symbols
pipeline (`apps/web/src/ui/icons/`, `npm run icons`), which only carries Material glyphs:

- `apps/web/src/ui/primitives/ChatGptMark/ChatGptMark.tsx` — exported from `@/ui/primitives`
- `apps/web/public-main/chatgpt-mark.svg`, `apps/web/public-compat/chatgpt-mark.svg` — the same
  single path as a static file, for non-React surfaces

Monochrome via `fill="currentColor"`, so one asset works in both light and dark schemes. Source:
Wikimedia Commons `File:ChatGPT-Logo.svg`, public domain (simple geometry) but an **OpenAI
trademark** — used only to identify imported conversations, never to brand Archie itself.

## Landmines

- **The `.dat` extension means nothing.** Sniff magic bytes. In the reference export the 135 blobs
  were 103 images (PNG/JPEG/WebP) and 32 WAVs (24 kHz mono), all named `.dat`.
- **Node ids may be the empty string.** Test `is None`, never truthiness, when walking parents.
- **A conversation may have two roots.** The reference export had one (`6817fdcf…`, 63 nodes) whose
  canonical thread runs *through* the irregular root. Terminate the walk on `parent is None` *or* a
  parent missing from `mapping`, and cap iterations at `len(mapping)` against cycles.
- **`sites/export_manifest.json` reports no omissions even when assets are missing.** In the
  reference export 3,680 of 3,712 audio pointers had no file. Expect dangling pointers and never
  trust that manifest to tell you.
- **Shards are not globally sorted by date.** Each `conversations-NNN.json` spans the whole history;
  treat the four as one unsorted pool.
- **`user.json` is unreliable** (`birth_year: 1837`, `chatgpt_plus_user: false` despite o3/gpt-5
  usage) — and it is PII. Don't read fields from it.
- **Grepping the output needs an explicit path.** `context/` is gitignored from the repo root, so a
  root-rooted ripgrep silently skips all of `context/chatgpt-history/`. See
  [../operations/working-rules.md](../operations/working-rules.md).

## Summaries say "Archie"

Session summaries (`backend/utils/session_summaries.py`) come from a prompt that hardcodes *his AI
assistant "Archie"*, so an imported conversation is summarised as though Rodrigo had been talking
to Archie. This is **left as is on purpose**: Rodrigo's reading is that his personal ChatGPT
account *was* the previous iteration of Archie — the same assistant under an earlier name — so the
phrasing is accurate rather than a defect. Nothing to fix; don't "correct" it later.

## Tests

`backend/tests/test_chatgpt_import.py` — detection (including that a turn line alone is *not*
misdetected), extraction (turns, merged roles, voice text, dates surviving, and a guard documenting
that a float-only timestamp is dropped), source discovery, and every read-only guard.
`apps/web/src/features/tools/renderers/history.test.tsx` covers the badge.

## Reference export

The first import (2026-10-15 export, converted 2026-10-28): 390 conversations, 8,528 turns,
2023-03-08 → 2026-10-03, 28 model slugs. 3,975 voice turns with transcriptions (32 audio files
surviving, all in one conversation), 683 reasoning turns, 96 on-spine images, 13 loose attachments,
538 off-spine nodes discarded across 149 branch points.
