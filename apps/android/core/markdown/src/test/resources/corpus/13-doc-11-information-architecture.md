## 3. Expanded layout (desktop web, Android tablets landscape)

```
┌──────┬────────────────────┬──────────────────────────────────────────────────────────┐
│ RAIL │ LIST PANE (320dp,  │ WORKSPACE                                                │
│      │ collapsible)       │ ┌─ Top app bar (same surface as content, no divider) ──┐ │
│ [＋] │ ┌Search──────────┐ │ │ [◉ Archie] [● Agent: refactor ×] [Memory: x.md ×] [⌄]│ │
│      │ Open now           │ │                                     status · ⋮      │ │
│ Chats│  ● Archie          │ └──────────────────────────────────────────────────────┘ │
│Memory│  ● refactor (Claude)│                                                         │
│Visual│ Today              │   messages (max width 840dp, centered)                   │
│      │  …                 │                                                          │
│      │ Yesterday          │                                                          │
│  ⚙   │  …                 │ ┌ Composer ────────────────────────────────────────────┐ │
│      │                    │ │ ＋  Message Archie…            ◔ 42%   🎙   (●voice) │ │
└──────┴────────────────────┴─┴──────────────────────────────────────────────────────┴─┘
```

- **Navigation rail** (80 dp): New (extended FAB-style "＋" → menu: *New Archie conversation*,
  *New agent session*), destinations **Chats / Memory / Visuals**, **Settings** pinned at bottom.
  Rail destination drives the list pane content.
- **List pane**: Chats → search, *Open now* section (pool sessions, status dot), then history
  grouped Today / Yesterday / Previous 7 days / Earlier. Memory → tree with search. Visuals →
  list with thumbnails-less cards (title, project, age). Collapsible (toggle in top bar; state
  remembered per device).
- **Tab strip integrated in the top app bar** (fixes R3): 40 dp tall tabs (M3 secondary-tab
  style), min touch target 48 dp, leading type icon (Archie / agent provider / memory / visual),
  live status indicator (spinner while working, dot when idle, warning when disconnected),
  title, close button **always visible on the active tab and on hover**, middle-click closes,
  double-click / context menu → Rename, drag to reorder (Archie stays first),
  overflow chevron **⌄ "All tabs" menu** with search when tabs overflow,
  keyboard: Ctrl+Tab / Ctrl+Shift+Tab, Ctrl+W, Ctrl+1…9.
- Right of tabs: compact status text ("Thinking…", "Using Bash…", turns · cost on hover) and
  ⋮ session menu (Rename, Session settings, Compact context, Fork, Close, Delete).
- **No colored bars**: rail, list pane, top app bar and composer sit on surface /
  surface-container tones only; separation by tone and spacing, not by borders or tinted strips (fixes R1).

