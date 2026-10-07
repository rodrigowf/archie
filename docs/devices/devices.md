---
name: devices
category: archie/devices
tags: [devices, reference-deployment, jetson, laptop, samsung-a300m, poco, ipad, fire-tv, iphone, adb, lan]
created: 2026-04-14
modified: 2026-10-06
summary: The reference deployment — every machine and device, its role, address, client app, access path and driving skill.
source: curated (consolidated from memory notes assistant/devices/peripheral_devices.md, assistant/infrastructure/features_and_integrations_summary.md (device table), assistant/infrastructure/repo_layout_cutover_2026_10.md, assistant/android/android_device_project.md, ORCHESTRATOR_MEMORY.md (Devices); verified against code 2026-10-06)
references:
  - ../infrastructure/topology.md
  - ../infrastructure/jetson-server.md
  - ../infrastructure/ssh-remote-execution.md
  - ../voice/architecture.md
  - ../clients/web.md
  - ../clients/android.md
  - ../clients/android-device.md
  - ../clients/browser-extension.md
  - ../clients/legacy-apps.md
  - fire-tv.md
  - photo-servers.md
  - ../voice/wake-word.md
  - ../voice/lifecycle.md
  - ../integrations/visualizations-and-sharing.md
---

# Devices — the reference deployment

Archie is one backend (normally on the Jetson) with several client surfaces around the house.
Every client talks to the backend over the LAN (`192.168.0.0/24`); nothing is exposed outside it.
This page is the single table of what runs where in the reference deployment; machine-level
detail for the two computers is in [topology.md](../infrastructure/topology.md) and
[jetson-server.md](../infrastructure/jetson-server.md).

| Device | Role | OS | LAN address | Runs | Reached by | Skill |
|---|---|---|---|---|---|---|
| **Jetson Nano** | 24/7 server hub: backend, orchestrator, web app, search | Linux for Tegra (aarch64), 4 GB RAM | `192.168.0.200` (static) | `agentic-backend.service` (uvicorn on 127.0.0.1:8765) behind nginx on 80/443 | `ssh rodrigo@192.168.0.200`; `https://192.168.0.200/` | `/server-management` |
| **Laptop** | Development, builds (web dists, APKs), heavier workloads; Chrome host for browser control | Linux | `192.168.0.28` | Same repo at `~/assistant`; local backend on 8765 when needed; `browser_daemon.py` on 127.0.0.1:8766 | local; `ssh rodrigo@192.168.0.28` | `/debug-app`, `/android-dev`, `/browser-control` |
| **Samsung Galaxy A300M** (SM-A300M) | Dedicated always-on voice terminal ("Archie lite") in the living room | Android 5.0.2 (API 21), 888 MB RAM | `192.168.0.225` (static) | Lite app `com.assistant.peripheral` ([android.md](../clients/android.md)) + companion `com.assistant.device` ([android-device.md](../clients/android-device.md)) | adb USB serial `06e4f224` or WiFi `192.168.0.225:5555` | `/android-dev` |
| **POCO X7 Pro** | Rodrigo's personal phone: Archie on the go | Android 15 / HyperOS 2 | DHCP | Main app `com.assistant.archie`; the old `com.assistant.peripheral` v1.0.9 still installed (wake word off) | adb USB serial `HAOF4P8XLJ9DQCPJ` | `/android-dev` |
| **iPad mini 2** | Stationary visual peripheral: watch sessions and the orchestrator, chat | iOS 12 / Safari 12 | DHCP | Web compat build ([web.md](../clients/web.md)) | browser at `http://192.168.0.200/compat/`; console via `logs/remote_console.log` | — |
| **Fire TV Stick** (AFTKM) | Large-screen display: visualizations, web pages, media apps | Fire OS (Android 11) | `192.168.0.16` (DHCP, may change; found by MAC) | TvServerHub launcher `com.example.tvserverhub` ([fire-tv.md](fire-tv.md)) | adb `192.168.0.16:5555` | `/connect-tv`, `/tv-remote`, `/tv-dev`, `/create-viz` |
| **iPhone 11** | Media capture; photo library served over the LAN | iOS (latest) | DHCP (last seen `192.168.0.34`) | Pythonista 3 photo server on port 3691 ([photo-servers.md](photo-servers.md)) | `http://<iphone-ip>:3691` | `/iphone-photos` |

The skills are agent runbooks in `context/skills/<name>/SKILL.md` (private repo).

## Per-device notes

**Jetson Nano.** Runs the backend under systemd; restart only with
`sudo systemctl restart agentic-backend.service` (never by killing the PID). It has no Node, so
web dists and APKs are built on the laptop. Code arrives by `git pull`, `context/` by the
context-sync service. Details: [jetson-server.md](../infrastructure/jetson-server.md).

**Laptop.** Same install path (`~/assistant`) as the Jetson, so session cwds and paths are
identical on both and sessions resume on either. Heavy jobs (Gradle, npm builds, emulators) run
one at a time behind `/tmp/archie-locks/*.lock` with `nice -n 15` — see
[android.md](../clients/android.md). The Jetson can spawn Claude sessions here over SSH
([ssh-remote-execution.md](../infrastructure/ssh-remote-execution.md)), which is also how
browser-control commands reach the laptop's Chrome ([browser-extension.md](../clients/browser-extension.md)).

**Samsung A300M.** The permanent "face" of Archie: wake word ("wake up" → realtime voice;
"hello my friend, …" → one-shot voice message, see [wake-word.md](../voice/wake-word.md)),
voice in/out, the last exchange on screen. The companion keeps it alive (watchdog, launch on
boot, DND, WiFi ADB). Its static IP was set on the device and chosen high (`.225`) so a fresh
DHCP lease never collides with it; WiFi ADB still needs `adb tcpip 5555` over USB after every
reboot, and its USB port is flaky, so WiFi is the preferred deploy path. Low RAM drives most
design rules of the lite app ([android.md](../clients/android.md), "A300M constraints").

**POCO X7 Pro.** Personal device with personal data and social-media apps. Any automation on it
— especially social-media apps — happens only on Rodrigo's explicit request; check the
foreground app before screenshots and ask before device tests. No companion app: no watchdog or
boot launch; after a reboot or app update, wake word resumes once the app is opened.

**iPad mini 2.** Safari 12 cannot run the main build, so it uses `/compat/` (the same app,
compiled for Safari 12 with low-end mode always on). No voice on the iPad — chat and visual
monitoring only. With no DevTools, the page reports errors to `POST /api/debug/log` →
`logs/remote_console.log` on the server. Documents are shared to it as links under
`context/public/` ([visualizations-and-sharing.md](../integrations/visualizations-and-sharing.md)).

**Fire TV.** Custom launcher (TvServerHub) with a WebView that can open any URL, plus a
self-managed "screensaver" that returns to the launcher after 80 s idle. The web and Android
apps' "Show on TV" button and the `/create-viz` skill display visualizations there
([fire-tv.md](fire-tv.md)).

**iPhone.** A single-file Python server run inside Pythonista exposes the photo library as a
REST API; it works only while Pythonista is in the foreground ([photo-servers.md](photo-servers.md)).

## The living-room voice setup

In daily use Rodrigo speaks to the A300M across the living room, watches the multi-tab sessions
on the iPad, and develops on the laptop.

- The A300M drives **wired external speakers and a wired microphone**, tested up to about
  **7 m** from the speaker.
- At that distance quieter speech and longer pauses made the server-side VAD cut turns short;
  the backend's Silero VAD waits **2.5 s** of silence before ending a turn
  (`backend/orchestrator/voice_vad.py`, `min_silence_duration_ms = 2500`). Distance still makes
  cut-offs more likely than at arm's length.
- The assistant hearing itself through the speakers is handled by echo ducking on the device
  (mic gain reduced while the assistant speaks) and drain-then-restore logic on the server — see
  [voice/architecture.md](../voice/architecture.md) and [voice/lifecycle.md](../voice/lifecycle.md).
- The A300M's Samsung audio HAL needs `MODE_NORMAL` (not `MODE_IN_COMMUNICATION`, which routes
  to the earpiece even with wired speakers attached) and cues on `STREAM_MUSIC`.

## Network notes

- Clients reach the Jetson by LAN IP. There is no remote access path in the reference
  deployment; using Archie outside the LAN would need a VPN.
- The Jetson's HTTPS certificate is signed by a private CA. Browsers accept it after a
  click-through exception, but contexts with no UI (an extension service worker's `wss://`, some
  WebViews) cannot, which is why some integrations use plain HTTP on loopback or accept the
  server's certificate explicitly.
- Microphone and WebRTC in a browser require a secure context: open the web app over `https://`
  (or `localhost`) on devices that use voice.

## History

- 2026-04: the Jetson became the 24/7 server; the A300M became the dedicated terminal with the
  old peripheral app and the companion; the iPad got the old compat build.
- 2026-07-22: the A300M got its static IP `192.168.0.225`.
- 2026-10-05: the two phones started running different apps — the A300M the lite app (installed
  in place, same package), the POCO the new main app — and the iPad's `/compat/` URL started
  serving the new web app's compat build ([legacy-apps.md](../clients/legacy-apps.md)).
