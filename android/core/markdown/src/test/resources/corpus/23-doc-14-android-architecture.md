## 8. Risk register

| # | Risk | Mitigation | Owner WP |
|---|---|---|---|
| X1 | AndroidX dropped API 21. A transitive bump silently breaks the lite build or the shared core. | Two-tier catalog + tier check + manifest-merger guard (§1.4–§1.5). | A-01 |
| X2 | Robolectric can't run API 21/22, so Lollipop paths are untested on the JVM. | `sdkInt` policies tested on the JVM + instrumented tests on the A300M AVD (§6.1, §6.5). | A-04/A-05 |
| X3 | Native libs are 4 KB-aligned and fail on 16 KB-page devices. | Alignment check; decision Q4 to upgrade the main app to WebRTC 1.3.10 / Vosk 0.3.75 after the gate, re-run F1/F8/F19/F22. | A-01, D-01 |
| X4 | The patched `libvosk.so` loses to the AAR copy in the merged APK. | `pickFirsts` + `verifyPatchedVosk` (§1.9). | A-01/C-01 |
| X5 | R8 strips JNA/WebRTC/Vosk members (release has never been tested). | Consumer rules + release smoke on both AVDs and devices (§1.10). | A-06/A-07/G-01 |
| X6 | Signing key mismatch or loss blocks updating the lite app in place. | Verify the device certificate; back the key up to `context/secrets`; explicit signing config (§1.8). | A-01 |
| X7 | versionCode ≤ 10 → downgrade error; a non-debuggable release blocks rollback. | versionCode 100; ship debug until L-F2 passes (§1.8, Q3). | C-01 |
| X8 | Changed component names lose the accessibility grant or the assist selection. | Keep FQCNs (§1.7); L-F2 checks; one-time reselect documented. | C-01 |
| X9 | Android 14+ FGS microphone rules; a sticky restart can't re-acquire the mic. | Type policy + degraded state + Resume action (§2.6). | A-08/B-09 |
| X10 | HyperOS kills the service. | Reliability checklist page; M-F2 measures it. | B-08/D-01 |
| X11 | No background activity launch on wake (UX expects the screen to show the app). | Headless voice + notification; optional full-screen intent (§2.6). | B-09 |
| X12 | Mic contention: two wake-word services on one phone (old app + main). | Field precondition; no debug id suffix (§1.6–§1.7). | D-01 |
| X13 | Spec 12 not final → reducer churn. | Fixture-driven reducer; A-02 M2 waits for the spec-12 freeze; the runner is format-agnostic. | A-02 |
| X14 | Tokens.kt symbol names unknown; no XML output for Views. | `TokenAdapter.kt` single seam; request XML output; C-01 fallback (§5.2). | B-01/C-01 |
| X15 | Markdown block splitter edge cases (lists, ref links, nested fences). | Streaming-equivalence test over 40 real messages; documented limitations (§3.2). | B-02 |
| X16 | Viz JS can call any backend API (no auth), including the OpenAI key. | Same as the web; flagged for a backend decision (§4.2). | — |
| X17 | Cleartext LAN traffic incl. the raw OpenAI key (inv04 R1). | TrustStore modes; decision Q5. | A-03 |
| X18 | The x86 API 21 AVD can't load unpatched Vosk → falls back to SR → masks bugs. | Patch the x86 lib for emulator builds (§1.9). | A-01/C-01 |
| X19 | Laptop overload (8 cores / 15 GB) from emulator + Gradle + browser. | One-environment rule, worker cap, emulator flags (§6.5). | all |
| X20 | AGP 9.4 / Kotlin 2.4 are new; plugins (Roborazzi) may lag. | A-01 verifies on day 1; documented fallback to the newest compatible AGP 9.x. | A-01 |
| X21 | nginx 1 MiB body limit makes most shared files fail. | Clear error now; backend fix (`client_max_body_size`) is inv01 §8.2's call. | B-09 |
| X22 | Navigation 3 adaptive scenes are young. | Fallback to navigation-compose 2.10 + `NavigableListDetailPaneScaffold`; only the shell changes. | B-03 |
| X23 | targetSdk 37 local-network permission would block LAN traffic. | Stay on 36 (Q6). | A-01 |
| X24 | Moving voice ownership into the service changes timing (inv04 §8 risk 3). | G-01 AVD script + F1/F3/F6/F7 logcat markers. | G-01/D-01 |
| X25 | Bumping OkHttp/WebRTC/coroutines alters tuned keepalive and ADM behaviour. | OkHttp 4.12 and WebRTC 1.1.1 pinned; coroutines bump is covered by the virtual-time parity suite. | A-03/A-06 |
| X26 | The A300M's API level is ambiguous (5.0.2 = 21 vs inventory "22"). | `getprop` check before creating the AVD (§6.5). | A-01 |
| X28 | The cast endpoint is never approved, or its shape changes. | Capability probe hides the action; only `ArchieApi.castVisualization` and the probe change. | B-07 |
| X27 | `ButtonAccessibilityService` keeps the package "alive" for the watchdog while the FGS is dead. | FGS restarted on every process start (§5.7). | C-01 |

---

