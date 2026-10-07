# Clients

The applications people use to talk to Archie, all speaking the same backend WebSocket/REST API:
the web app (main build plus a Safari 12 build for the iPad), the two Android apps (the main
phone app and the voice-first lite app for the dedicated terminal), the Android companion that
keeps the terminal alive, the Chrome extension that lets agent sessions drive a real logged-in
browser, and the frozen pre-2026-10 apps under `legacy/`. Design detail for the web and Android
apps lives in [`../specs/`](../specs/INDEX.md); these docs are the orientation maps and the rules.

- [web.md](web.md) — `apps/web`: React 18 + Vite + zustand, source layout, main (`/`) and Safari 12 (`/compat/`) builds, compat gates and pitfalls, remote console, low-end mode, deploy rule
- [android.md](android.md) — `apps/android`: `:app-main` (`com.assistant.archie`) and `:app-lite` (`com.assistant.peripheral`), modules, build/lock rules, signing, in-place install, A300M constraints, debugging
- [android-device.md](android-device.md) — `apps/android-device`: companion `com.assistant.device` (watchdog, boot launch, WiFi ADB, DND) and its hard-coded dependency on `com.assistant.peripheral`
- [browser-extension.md](browser-extension.md) — `apps/browser-extension`: Chrome MV3 extension + local daemon behind `/browser-control`; architecture, security, look-first workflow, pitfalls
- [legacy-apps.md](legacy-apps.md) — `legacy/`: old web (`/legacy/`), old compat app (`/legacy_compat/`) and its shim lessons, old Android peripheral app and its ViewModel architecture
