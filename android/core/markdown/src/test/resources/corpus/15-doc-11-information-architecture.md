## 7. Settings (fixes R5) — identical hierarchy on both platforms

Settings home is a grouped list; each row opens a detail page. Expanded: two-pane list/detail.

```
Settings
├── THIS DEVICE
│   ├── Connection        servers (saved + discovered), scan, add, auto-connect, status   [Android]
│   ├── Audio             mic level, speaker level, echo ducking, output route (labeled)    [Android]
│   ├── Wake word & triggers  enable, talk phrases, wake phrases, sensitivity, auto-stop,
│   │                         assist gesture / recents trigger, notification actions        [Android]
│   └── Appearance        theme (System / Dark / Light), text size, reduce motion           [both]
├── ARCHIE (SERVER)       ← header shows server name + connection; disabled when offline
│   ├── Conversation model     text provider + model, history summarizer provider + model
│   ├── Voice                  provider, (Google backend), model, voice, language, recording
│   ├── Voice tuning           VAD threshold, min silence, server mic gain
│   ├── Agent sessions         default provider, harness model, Chrome flag
│   ├── Working directories    full list CRUD incl. SSH host/user (now on Android too)
│   ├── MCP servers            per-server switches with "all enabled" semantics shown correctly
│   └── Account                Claude sign-in / credentials (AuthGate flows)
└── ABOUT                  app version (real), backend version/host, remote logging, licenses
```

Rules: sliders commit on release; every save shows a snackbar ("Saved" / server error message
verbatim + Retry); helper text is one short line (details behind an ⓘ); controls are labeled
(no unlabeled icon rows); device-vs-server scope is always visible.

**Session settings** (per session, from ⋮): side sheet (Expanded) / bottom sheet (Compact):
working directory, MCPs, skills & agents, "Save and restart".

