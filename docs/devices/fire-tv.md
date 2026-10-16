---
name: fire-tv
category: archie/devices
tags: [fire-tv, tvserverhub, adb, launcher, screensaver, webview, visualizations, show-on-tv, archie-tv, tv-control]
created: 2026-02-23
modified: 2026-10-16
summary: Fire TV integration — the Archie TV control channel (tv_cmd.py, /api/tv/ws), TvServerHub launcher and screensaver, ADB connection (fallback), the TV skills and "Show on TV".
source: curated (consolidated from memory notes assistant/devices/television_integration_project.md, assistant/infrastructure/features_and_integrations_summary.md §6; verified against code 2026-10-06)
references:
  - devices.md
  - ../specs/15-tv-app.md
  - ../specs/12-client-protocol.md
  - ../architecture/backend.md
  - ../integrations/visualizations-and-sharing.md
  - ../integrations/skills.md
  - ../integrations/youtube.md
  - ../clients/web.md
  - ../clients/android.md
---

# Fire TV

The living-room TV is an **Amazon Fire TV Stick (AFTKM, Fire OS / Android 11)**. It runs a
custom launcher, **TvServerHub**, and — with spec [15](../specs/15-tv-app.md) — the native
**Archie TV** app, which keeps its own control connection to the backend so agents can put
pages, visualizations, memory notes and conversations on the screen without adb. ADB remains
the fallback and the route for everything else (other apps, key events, screenshots, installs).

| Fact | Value |
|---|---|
| ADB address | DHCP — `192.168.0.15:5555` as of 2026-10-16 (it was `.16` until 2026-10-14 and another device has `.16` now); always rediscover it by MAC address |
| Archie TV app | `com.assistant.archie.tv`, module `apps/android/app-tv`, control socket `/api/tv/ws` ([below](#archie-tv-control-channel)) |
| Launcher package | `com.example.tvserverhub` (`.MainActivity`, `.WebPageViewActivity`, `.ScreensaverMonitorService`, `.BootReceiver`) |
| Launcher source | A separate Android Studio project outside this repo (`~/AndroidStudioProjects/TvServerHub` on the laptop, with its own `CLAUDE.md` and `DEBUGGING.md`) |
| Discovery script | `~/AndroidStudioProjects/TvServerHub/scripts/discover-firetv.sh` |

## Archie TV control channel

The Archie TV app connects `WS /api/tv/ws` (`backend/api/routes/tv.py`) whenever it is connected
to the server, next to its orchestrator socket. The backend's `TvHub` keeps one connection per
`device_id` and relays commands to it; the wire and the command list are spec 12
[§9.5](../specs/12-client-protocol.md) (RD-1..6) and spec 15 §5.

- **Agents and the orchestrator** use `shared/scripts/tv_cmd.py` (symlinked into
  `context/scripts/`, allowlisted for the orchestrator's `run_script`):
  `viz <path>`, `open <url|/path>`, `memory <path>`, `conversation <id>`, `home`, `back`,
  `reload`, `scroll <dir>`, `media <action>`, `notify <text>`, `state`, `status`; `--device`,
  `--timeout`; JSON output; exit codes 0 ok · 1 TV error/timeout · 2 no TV (or no backend) ·
  3 usage (several TVs: pass `--device`).
- It calls `POST /api/tv/command {command, params, device?, timeout?}`, which is
  **loopback-only** (refused through nginx), so it must run on the machine the TV is connected
  to. On another machine set `TV_HOST_NAME` / `TV_HOST_SSH` in `context/.env` and it re-runs
  itself there over SSH (the same scheme as `browser_cmd.py`).
- `GET /api/tv/status` lists the connected TVs with the last `state` each reported (screen, URL,
  title, conversation, voice) — `tv_cmd.py state` asks the TV directly.
- Auth: optional `TV_CONTROL_TOKEN` (env or `context/.env`). When set, the app's hello must carry
  it; when unset, any LAN client may register as a TV (it can only *receive* URLs; a reconnect
  with the same `device_id` replaces the old socket with close code 4409).
- A command is a user action on the TV (the FOCUS-1 exemption): the app comes to the front,
  navigates, and wakes the launcher's blank screen.

Tests: `backend/tests/test_api_tv.py`, `backend/tests/test_tv_cmd.py`.

## TvServerHub

- **Launcher** (`MainActivity.kt`, `AppLauncher.kt`): a Compose app grid with tiles for
  services on the Jetson (an "Agentic" tile, Jellyfin on `:8096`, Copyparty on `:3923`) and an
  animated gradient background. On start, `initializeEnvironment()` starts the screensaver
  service, sets `screensaver_enabled=0`, `screen_off_timeout=2147483647` and
  `stay_on_while_plugged_in=7`, and launches YouTube in the background for casting.
- **`WebPageViewActivity`** opens any URL: JavaScript on, hardware-accelerated, camera/microphone
  permissions auto-granted, remote WebView debugging on, Back navigates history, `singleTask`
  (reused for each new URL). Certificate errors are accepted for the local server.

```bash
adb -s 192.168.0.16:5555 shell am start -n com.example.tvserverhub/.WebPageViewActivity -e url "https://example.com"
```

### ScreensaverMonitorService

TvServerHub replaces Android's screensaver with its own:

1. Native screensaver off, screen timeout at maximum.
2. `ScreensaverMonitorService` runs as a **foreground service**, checking every
   **10 s** (`CHECK_INTERVAL = 10000L`).
3. It tracks app switches (`UsageStatsManager`, fallback `getRunningTasks`), media playback
   (`AudioManager.isMusicActive`) and remote input (DPAD / gamepad / keyboard sources).
4. After **80 s** idle (`IDLE_TIMEOUT = 80`) it brings `MainActivity` back to the front.

It needs three app-ops, granted after every fresh install:

```bash
adb -s 192.168.0.16:5555 shell "appops set com.example.tvserverhub android:get_usage_stats allow"
adb -s 192.168.0.16:5555 shell "appops set com.example.tvserverhub SYSTEM_ALERT_WINDOW allow"
adb -s 192.168.0.16:5555 shell "appops get com.example.tvserverhub"   # expect GET_USAGE_STATS, SYSTEM_ALERT_WINDOW, START_FOREGROUND: allow
```

**Pitfall: the service can be killed by Android under memory pressure.** It is `START_STICKY`
but does not reliably come back. Symptom: the TV no longer returns to the launcher after
inactivity, and there are no screensaver logs (the launcher process may still be running).

```bash
adb -s 192.168.0.16:5555 shell "dumpsys activity services com.example.tvserverhub" | grep -A 10 ScreensaverMonitorService
# no output → not running. Restarting the activity re-runs initializeEnvironment():
adb -s 192.168.0.16:5555 shell "am force-stop com.example.tvserverhub && sleep 1 && am start -n com.example.tvserverhub/.MainActivity"
```

## Connecting (ADB)

ADB is needed for the fallback cast, other apps, key events, screenshots and installing the apps.
`/connect-tv` runs the discovery script: it looks up the Fire TV's MAC address in the ARP cache,
ping-sweeps the subnet if needed, then `adb connect <ip>:5555`. If it fails, the TV may be off,
on another network, or waiting for the user to accept the ADB pairing dialog on screen.
The skills resolve the address first and use it as `$TV`; never assume a fixed IP (on
2026-10-14 the TV moved `.16` → `.15` and another device took `.16`). Check that the device is the
TV (`getprop ro.product.manufacturer` → `Amazon`) before sending anything.

## Skills

| Skill | Use for |
|---|---|
| `/connect-tv` | (Re)establish ADB after a power cycle or IP change |
| `/tv-remote` | Show content through the Archie TV app (`tv_cmd.py`) first; remote-control actions over adb: launch/close apps (YouTube, Netflix, Prime Video, TvServerHub), open URLs in `WebPageViewActivity`, media keys (85 play/pause, 126/127, 87/88, 89/90), D-pad (19–23), back/home (4/3), volume (24/25/164), screenshots (`exec-out screencap -p`), current app (`dumpsys activity activities \| grep mResumedActivity`) |
| `/tv-dev` | Build, deploy and debug the TV apps — Archie TV (`:app-tv`, logcat `ArchieTv`, `shared/scripts/tv_setup.sh`) and TvServerHub: WebView DevTools via `adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>`, logcat tags `TvServerHub`, `WebViewScreen`, `ScreensaverMonitor`, screensaver troubleshooting, changing the timeout or launcher tiles |
| `/create-viz` | Build interactive HTML canvases under `context/public/`; show one on the TV with `tv_cmd.py viz`, "Show on TV" or `POST /api/visualizations/cast` |

Skills live in `context/skills/<name>/SKILL.md`. When delegating TV work to a session, load
`/tv-remote` (or `/tv-dev` for app debugging). YouTube results can be played on the TV through
the same remote commands ([youtube.md](../integrations/youtube.md)).

## Showing visualizations on the TV

Files in `context/public/` are served by the backend at the URL root (see
[visualizations-and-sharing.md](../integrations/visualizations-and-sharing.md)), so a
visualization is a URL the TV's WebView can open:

- On the Jetson: `https://192.168.0.200/visualizations/<name>.html` (nginx on 443).
- On a dev machine: `http://<lan-ip>:8765/visualizations/<name>.html` (the backend directly).
  The old Vite dev port 5432 no longer serves visualizations, and the new dev servers
  (5450/5451) do not proxy `/visualizations`.

`context/scripts/create_visualization.py` and `get_visualization_url.py` print the right URL
for the machine they run on. `create_visualization.py --display-tv` then calls the backend's
"Show on TV" endpoint (below); only when no backend answers does it run the adb intent itself.

**"Show on TV"** in the web and Android apps' Visuals screen uses the backend endpoints in
`backend/api/routes/visualizations.py`. Two routes, the app first:

1. **Archie TV app** — when a TV is attached to the hub, the backend sends it
   `open_visualization {path}` (8 s timeout) and the app opens its Viewer.
2. **adb** — when no TV app is connected, or it timed out or reported an error: the connected adb
   device whose `ro.product.manufacturer` is Amazon (so a phone on adb is never targeted), or the
   one pinned by `FIRE_TV_ADB_SERIAL`, gets
   `adb -s <serial> shell am start -n com.example.tvserverhub/.WebPageViewActivity -e url <url>`
   with a 10 s timeout. The base URL is `https://<this machine's LAN IP>`, or `VIZ_CAST_BASE_URL`
   when set (needed on dev machines without nginx).

- `GET /api/visualizations/cast` → `{available, via, reason}`: available when either route
  works; `via` is `app`, `adb` or null, and `reason` names the route (or says why neither works).
- `POST /api/visualizations/cast {path}` → `{ok, via, message}` — validates that `path` is an
  `.html` file under `context/public/` (no `..`, no symlink escape) before trying either route.
  It never raises on TV problems; failures come back as `{ok: false, via, message}`. With several
  TVs connected it does not guess (`ok: false`, `via: app`).

Tests: `backend/tests/test_api_visualizations_cast.py`.

## Pitfalls

- The TV's IP is DHCP-assigned; when adb commands fail with "device offline/not found", run
  `/connect-tv` before anything else. `tv_cmd.py` does not depend on the IP (the app dials the
  server).
- After reinstalling TvServerHub, re-grant the app-ops above or the screensaver silently stops
  working.
- Pages for the TV should be self-contained (inline CSS/JS), sized for 1920×1080 and navigable
  with the D-pad (arrow keys + Enter); a mouse-only page is unusable from the remote.
- The launcher's "Agentic" tile points at `https://192.168.0.200/agentic/voice/`, a path from
  the pre-Archie setup. It still works: the backend's SPA catch-all answers any unknown path with
  the web app's `index.html` (absolute `/assets/…` URLs, hash routing), so the tile opens the
  Archie app — the same bytes as `https://192.168.0.200/`. Don't point it at `/` without also
  changing `WebViewScreen.kt`: its Menu-key "Agentic Options" dialog (clear cache, storage and
  cookies, then reload) is enabled by `url.contains("agentic")`.

## History

- 2026-02: TvServerHub and the ADB-based control skills built; visualization system added
  (templates `basic`, `chart`, `dashboard`, `tv-remote`).
- Later: the screensaver idle timeout was doubled from 40 s to 80 s and the check interval from 5 s to 10 s.
- 2026-10: "Show on TV" (BX-2) added to the rebuilt web and Android apps.
- 2026-10-14: the TV's DHCP address moved from `.16` to `.15`; the skills stopped hardcoding it.
- 2026-10-16: the Archie TV control channel (`/api/tv/ws`, `tv_cmd.py`); "Show on TV" prefers the
  TV app and keeps adb as the fallback (spec 15).
