# Archie TV — Project Charter

Branch: `feat/tv` (integration; per-agent branches `feat/tv-*` merge into it) · Started: 2026-10-16 ·
Owner: Rodrigo · Coordinator: Claude

Build a native TV client for Archie (Fire TV first, any Android TV), let agents and the orchestrator
open pages on it without adb, and rework the TvServerHub launcher/screensaver.
Normative spec: [`docs/specs/15-tv-app.md`](../../specs/15-tv-app.md).

## Rodrigo's request (2026-10-16)

1. A new, modern TV app inspired by the existing aesthetics, easy to drive with the plain Fire TV
   remote, that shows everything (any conversation, visualizations, memory) and matches the other
   apps in functionality. Plan the design carefully first, with TV best practices.
2. Runs on any modern TV, especially his Fire TV.
3. Use the Fire TV remote's microphone to send voice messages to the orchestrator, or to hold a
   realtime voice conversation.
4. Agents and the orchestrator open any visualization or web page on the TV, fully navigable inside
   the app, through a direct call (a script used by the skill), not adb.
5. TvServerHub stays as screensaver/launcher, with only two cards: Archie and YouTube.
6. Blank (black, not video-off) screen after 15 min of inactivity, configurable; the behaviour can be
   switched in a settings screen opened by the remote's settings/menu button or a small gear icon at
   the top right; any key on the black screen returns to the launcher.

## Decisions log

| # | Date | Decision | By |
|---|------|----------|----|
| TV-1…TV-11 | 2026-10-16 | See spec 15 §0 | Claude (from research + probe) |
| — | 2026-10-16 | Disable `com.amazon.vizzini` (Alexa voice UI) on the living-room TV so the mic button belongs to Archie; reversible | Rodrigo approved the test; kept disabled pending final confirmation |

## Document map

| Path | Content | Status |
|------|---------|--------|
| [`../../specs/15-tv-app.md`](../../specs/15-tv-app.md) | Spec: decisions, platform facts, probe, product design, voice, control channel, architecture, launcher, setup, tests, WPs | draft v1 |
| `mockups/archie-tv-mockups.html` | High-fidelity mockups of every TV screen | in progress |
| `progress.md` | Progress log, WP → commit table, open issues | live |
| `pending-context/` | Exact new contents of files under the private `context/` repo (skills, ORCHESTRATOR_SCRIPTS), applied at deploy time | from WP B2 |

## Research inputs (2026-10-16)

- Fire TV platform and remote: verified on the AFTKM over adb (spec §1).
- Remote mic: `:app-tvprobe` field test with Rodrigo, three rounds, Whisper transcripts (spec §2).
- 10-ft UX guidance: Android TV design guidelines (overscan 48/27 dp, 960×540 dp canvas), Compose for
  TV (`tv-material` 1.1.0, regular Lazy lists, `focusRestorer`), Amazon Fire TV design and remote-input
  docs (mic and Home keys are system events; Menu reaches native apps).
- Existing code: shared Android core reuse map, TvServerHub analysis, backend browser-hub pattern.
