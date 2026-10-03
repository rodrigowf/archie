## 5. Compact layout (Android phone app, web on phones)

```
┌──────────────────────────────────────┐
│ ☰   Archie ⌄              🔊  ⋮      │  ← top app bar, same surface as chat; title opens
│      Thinking…                        │     the session switcher; subtitle = live status
├──────────────────────────────────────┤
│  messages                             │
│                                       │
│  [permission card / stall card here]  │
│ ┌───────────────────────────────────┐ │
│ │ ＋  Message Archie…      🎙  (◉)   │ │  ← single floating composer, no strip behind it
│ └───────────────────────────────────┘ │
└──────────────────────────────────────┘
```

- **No bottom navigation bar.** Modern assistant pattern: the conversation is the home; everything
  else is one swipe/tap away. (Removes the two stacked tinted strips — R1/A11.)
- **Top app bar**: ☰ opens the **navigation drawer**; the title (session name + ⌄) opens the
  **session switcher** bottom sheet; trailing actions: voice/speaker state, ⋮ session menu.
- **Session switcher sheet** = the phone equivalent of tabs: *Open now* list with status, each
  closable (swipe or ×), plus "New Archie conversation" / "New agent session".
  Horizontal swipe on the top app bar title switches between open sessions (Android; optional on web).
- **Navigation drawer** (modal, M3): header with the Archie mark + connection status;
  search; *Open now*; history grouped by date; footer entries **Memory**, **Visuals**, **Settings**.
- Memory and Visuals are full screens with their own top app bar (back arrow); documents open
  as detail screens (not tabs).

