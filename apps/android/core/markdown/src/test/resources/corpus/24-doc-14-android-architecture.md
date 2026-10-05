## 9. Decisions for Rodrigo

| # | Question | Recommendation |
|---|---|---|
| ~~Q1~~ | Main app applicationId | **Decided:** `com.assistant.archie`, launcher name "Archie" (IA §9.5) |
| Q2 | Main app signing key: reuse the existing debug key, or a new `archie-release.jks`? (Both kept in `context/secrets/android/`.) | Reuse the existing key (one key to guard). The lite app has no choice (§1.8). |
| Q3 | Lite app on the A300M: debuggable build or minified release? | Debug until L-F2/L-F4 pass (rollback possible), then release for RAM. |
| Q4 | Native versions: stay on WebRTC 1.1.1 / Vosk 0.3.47 (4 KB-aligned) or upgrade the **main** app to 1.3.10 / 0.3.75 (16 KB-aligned) after G-01? | Pinned first. Upgrade only if `getconf PAGE_SIZE` on the POCO is 16384, or before the next Android major update. |
| Q5 | TLS: LAN cleartext (today), TOFU-pinned self-signed HTTPS, Tailscale certs, or a private CA on nginx? | Keep cleartext on the LAN for v1; add Tailscale HTTPS for off-LAN use. The pinning code ships either way. |
| Q6 | targetSdk 36 now, 37 later? | Yes. |
| Q7 | Main app: no boot receiver (wake word resumes on first open after reboot)? Optional "Stay connected in background" (needed for permission notifications)? | No boot receiver. "Stay connected" off by default. |
| Q8 | Lite triggers: drop the `/dev/input` recents monitor (B5) and the VIS (B6) unless field evidence says they're needed? | Drop both, pending the A-08 and L-F5 evidence. |
| ~~Q9~~ | IA open questions 2–4 | **Decided** (IA §9): tabs on Expanded; Show on TV via the pending cast endpoint (capability-gated); "N steps" grouping, expanded while live. Still open for the backend owner: approve `POST /api/visualizations/cast`. |
| Q10 | Ask the token owner for an Android XML resource output for the Views-based lite app? | Yes. |
| Q11 | During the transition on the POCO: uninstall the old app, or keep it with wake word disabled? | Keep it with wake word disabled until M-F1 passes, then uninstall. |
| Q12 | Screenshot goldens: plain git or Git LFS? | Plain git with PNG-optimized goldens (~150 images × ~60 KB ≈ 9 MB); revisit if they exceed 50 MB. |

