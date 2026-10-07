# specs/

Normative specifications of the client apps: what every client (web, Safari 12 compat, Android
main, Android lite) MUST do. Written during the 2026-10 rebuild
([projects/frontend-refactor](../projects/frontend-refactor/README.md)) and kept as the reference
for the apps as built — code comments cite them by section (e.g. "spec 12 §6.11a"). Where a spec
and the code disagree, the code wins and the spec should be fixed. The orientation docs for each
client are in [../clients/](../clients/INDEX.md).

- [11-information-architecture.md](11-information-architecture.md) — Screens, navigation and the settings hierarchy for web and Android (approved 2026-10-03).
- [12-client-protocol.md](12-client-protocol.md) — The client data layer: wire frames, the conversation reducer, stable ids and ordering invariants, voice signalling; conformance fixtures in `apps/protocol-fixtures/`.
- [13-web-architecture.md](13-web-architecture.md) — `apps/web` architecture: layers, stores, services, voice engine, the two builds, work packages W-01…15.
- [14-android-architecture.md](14-android-architecture.md) — `apps/android` architecture: `:app-main`, `:app-lite`, `core/*` and `feature/*` modules, work packages A/B/C/D.
