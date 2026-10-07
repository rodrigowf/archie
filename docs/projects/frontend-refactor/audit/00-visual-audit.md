# Visual audit — current frontends (baseline, 2026-10-03)

Captured against the live Jetson backend (`https://192.168.0.200`), web via a local Vite
dev server proxied to the Jetson, Android via an API 36 emulator profiled as the POCO X7
(1220×2712 @ 440 dpi). Screenshots live in `screenshots/` (gitignored — they contain
private conversation content).

## Web (`frontend/`)

| Shot | What it shows |
|------|---------------|
| `web-01-desktop-home.png` | Empty state, sidebar with Sessions / Memory / Visuals sub-tabs |
| `web-02-desktop-chat.png` | Session with tool calls, status line, input row |
| `web-03-desktop-memory.png` | Memory tree in sidebar |
| `web-04-desktop-visuals.png` | Visualizations list in sidebar |
| `web-05-desktop-config.png` | Configuration modal (orchestrator text/summarizer/voice, voice tuning sliders) |
| `web-06-mobile-home.png` | 412×915 phone viewport, chat |

Findings:

- **W1 Tab bar is small and detached.** A single 160-px tab chip floats at the top-left;
  low contrast, small close target, no overflow strategy. Replace with a proper M3
  top app bar + scrollable primary tabs (desktop) / session switcher (mobile).
- **W2 Sidebar sub-tabs** (Sessions/Memory/Visuals) are tiny pill buttons with count
  badges; header "SESSIONS" label is near-invisible (very low contrast).
- **W3 Session metadata badges** (`C`, `Q`, `orch`) are cryptic single letters.
- **W4 Status row** ("● Ready … Connected") floats between messages and input with
  two unrelated items far apart.
- **W5 Input row**: unlabeled `?` button, a separate send button, and a gear — three
  equal-weight square buttons next to the field. Voice button not visible in this state.
- **W6 Mobile**: scroll-to-bottom FAB overlaps message text; truncated single tab;
  hamburger only.
- **W7 Config modal**: dense; slider descriptions are monospace and truncated with
  ellipsis (`Range 0.15–0.50. De…`), raw RST-style backticks (```` ``listening`` ````) leak.
- **W8 Sessions list recency** shows "35d ago" at top while visuals show "1h ago" — verify
  whether the sessions list ordering/timestamps are right (may be backend data; check in
  inventory).
- Positive: dark theme, typography and tool-call cards read well; user bubble right-aligned
  with outline is clean. Keep this overall feel.

## Android (`android/`, current build)

| Shot | What it shows |
|------|---------------|
| `android-01-launch.png` | Conversations list (light theme), FAB, bottom nav (chat / history / settings) |
| `android-02-chat.png` | Chat tab on launch — completely blank |
| `android-03..05-settings*.png` | App settings: server, audio sliders, output routing, wake word, appearance |
| `android-06-system.png` | System settings: orchestrator provider/model/voice |
| `android-07-session-chat.png` | Session chat with tool calls and markdown |

Findings:

- **A1 Theme mismatch**: light lavender M3-baseline palette vs the web's dark neutral
  palette — the two products look unrelated.
- **A2 Assistant text is washed-out gray**, and **bold markdown spans are nearly
  invisible** ("Tweet:", "Posted:", "URL:") — markdown renderer color bug.
- **A3 Inline code** renders as a black slab with white text on a light background —
  visually heavy, inconsistent with the web.
- **A4 Empty message rows** rendered with only a `⋮` menu (blank assistant entries).
- **A5 No top app bar in chat** — no session title, no session switcher, no back/context.
- **A6 Blank chat tab on launch** — no orchestrator history or empty state.
- **A7 Settings: "No servers yet" shown while "Connected"** — contradictory state.
- **A8 Settings layout**: one long scroll of heavy tonal cards; mixed slider styles
  (ticked sliders with 5–20 tick dots), long helper paragraphs, audio-routing icon row
  without labels; System tab duplicates web config with different grouping.
- **A9 Tool-call card**: title in monospace orange, truncated; "done" chip crowds the title.
- **A10 Missing features vs web**: Visualizations and Memory (to be added — user request).
- **A11 Bottom bar + input bar** are two stacked tinted strips with differing colors —
  the "color separation between bars" the user wants removed.
