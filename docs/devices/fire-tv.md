---
name: fire-tv
category: archie/devices
tags: [fire-tv, tvserverhub, adb, launcher, screensaver, webview, visualizations, show-on-tv]
created: 2026-02-23
modified: 2026-10-09
summary: Fire TV integration — TvServerHub launcher, its screensaver service, ADB connection, the TV skills and "Show on TV".
source: curated (consolidated from memory notes assistant/devices/television_integration_project.md, assistant/infrastructure/features_and_integrations_summary.md §6; verified against code 2026-10-06)
references:
  - devices.md
  - ../integrations/visualizations-and-sharing.md
  - ../integrations/skills.md
  - ../integrations/youtube.md
  - ../clients/web.md
  - ../clients/android.md
---

# Fire TV

The living-room TV is an **Amazon Fire TV Stick (AFTKM, Fire OS / Android 11)** controlled
entirely over ADB. It runs a custom launcher, **TvServerHub**, that shows web apps in a
full-screen WebView, so anything Archie can render as a web page — visualizations, dashboards,
the web app itself — can be put on the TV.

| Fact | Value |
|---|---|
| ADB address | `192.168.0.16:5555` (DHCP — can change; rediscovered by MAC address) |
| Launcher package | `com.example.tvserverhub` (`.MainActivity`, `.WebPageViewActivity`, `.ScreensaverMonitorService`, `.BootReceiver`) |
| Launcher source | A separate Android Studio project outside this repo (`~/AndroidStudioProjects/TvServerHub` on the laptop, with its own `CLAUDE.md` and `DEBUGGING.md`) |
| Discovery script | `~/AndroidStudioProjects/TvServerHub/scripts/discover-firetv.sh` |

## TvServerHub

Reworked to **v2.0** on 2026-10-16 (spec [15-tv-app.md](../specs/15-tv-app.md) §7): a two-card
launcher, a D-pad settings screen and an idle engine that blanks the screen.

- **Launcher** (`MainActivity.kt`, `AppLauncher.kt`): two centered cards over the animated
  gradient — **Archie** (launches Archie TV, `com.assistant.archie.tv`; toast "Archie TV is not
  installed" when it is missing) and **YouTube** (`com.amazon.firetv.youtube/dev.cobalt.app.MainActivity`)
  — plus a **gear** in the top-right corner (D-pad Up from the cards). **Menu** (82) and
  **Settings** (176) also open Settings. The old in-launcher WebView and its Agentic / Jellyfin /
  Copyparty tiles are gone.
- **Settings** (`SettingsActivity.kt`), D-pad only — Left/Right/OK change a value, Back saves and
  closes: *When idle* (**Black screen** / Turn display off (sleep) / Do nothing), *Idle time before
  blanking* (5–60 min, **15**), *Never blank while media plays* (**on**), *Return to launcher when
  idle* (Off / **80 s** / 2 / 5 / 10 min), *Test: blank now*, and status rows (key watcher, usage
  access, overlay permission, monitor service + current idle time), each with its adb fix.
- **Once per boot** (keyed by `BOOT_COUNT`; the v1 `initialized` flag was set once and never
  reset): `screensaver_enabled=0`, `screen_off_timeout=2147483647`, `stay_on_while_plugged_in=7`,
  and YouTube launched in the background for casting (by `BootReceiver`, whose YouTube component
  name is fixed in v2.0).
- **`WebPageViewActivity`** opens any URL — the adb fallback for "Show on TV": JavaScript on,
  hardware-accelerated, camera/microphone permissions auto-granted, remote WebView debugging on,
  Back navigates history, `singleTask` (reused for each new URL). Certificate errors are accepted
  for the local server.

```bash
adb -s 192.168.0.15:5555 shell am start -n com.example.tvserverhub/.WebPageViewActivity -e url "https://example.com"
```

### ScreensaverMonitorService (idle engine)

A foreground service ticks every **10 s**, reads the settings, detects the foreground app
(`UsageEvents` foreground events, fallback usage stats / `getRunningTasks`) and media
(`AudioManager.isMusicActive`), and asks the pure `IdleLogic.decide()` (unit-tested):

- **Key presses** come from `KeyWatchService`, an AccessibilityService with
  `flagRequestFilterKeyEvents` that sees every remote key in every app and **never consumes** one
  (v1 counted input *devices*, so it never saw a press). HOME and the Alexa/mic key never reach it.
  Without it, only app switches and media count as activity.
- `lastActivity` = latest of key press, switch to another app, `KEEP_AWAKE` hold-off, and media
  playing (when *never blank while media plays* is on).
- **Return to launcher**: idle ≥ 80 s in any app except TvServerHub and **Archie TV**, not while media plays.
- **Blank**: idle ≥ 15 min in any app (Archie TV included), not while media plays → `BlankActivity`:
  pure black, keeps the screen on, immersive; the first key opens the launcher; it closes itself
  when anything else comes to the front (e.g. Archie TV showing pushed content).
- **Sleep** (`GLOBAL_ACTION_LOCK_SCREEN`) is implemented but **untested on the living-room
  display** — it may not come back on; Settings shows a warning.
- **`KEEP_AWAKE`** — exported broadcast `com.example.tvserverhub.action.KEEP_AWAKE`, optional int
  extra `seconds` (hold idle off that long). Archie TV sends it on pushed content and every 60 s
  while voice is live, with `setPackage("com.example.tvserverhub")`.

Setup after a fresh install (Archie's `tv_setup.sh` does it): two app-ops and the key watcher.

```bash
adb -s 192.168.0.15:5555 shell "appops set com.example.tvserverhub android:get_usage_stats allow"
adb -s 192.168.0.15:5555 shell "appops set com.example.tvserverhub SYSTEM_ALERT_WINDOW allow"
adb -s 192.168.0.15:5555 shell settings put secure enabled_accessibility_services com.example.tvserverhub/.KeyWatchService   # append if others are enabled
adb -s 192.168.0.15:5555 shell settings put secure accessibility_enabled 1
adb -s 192.168.0.15:5555 shell am broadcast -a com.example.tvserverhub.action.KEEP_AWAKE -p com.example.tvserverhub --ei seconds 300   # test KEEP_AWAKE
```

**Pitfall: the service is not running after a reinstall** (the update kills the process) and can
be killed under memory pressure. It is started again by the key watcher when the system rebinds
it, by the launcher (`onCreate`/`onResume`) and at boot. Symptom: no return to the launcher, no black screen, no `ScreensaverMonitor` status line
(one per minute) in logcat; Settings → Status shows "Stopped".

```bash
adb -s 192.168.0.15:5555 shell "dumpsys activity services com.example.tvserverhub" | grep -A 3 ScreensaverMonitorService
# no output → not running. Opening the launcher starts it:
adb -s 192.168.0.15:5555 shell am start -n com.example.tvserverhub/.MainActivity
```

## Connecting

`/connect-tv` runs the discovery script: it looks up the Fire TV's MAC address in the ARP cache,
ping-sweeps the subnet if needed, then `adb connect <ip>:5555`. If it fails, the TV may be off,
on another network, or waiting for the user to accept the ADB pairing dialog on screen.
Commands in the skills assume `192.168.0.16:5555`; if the IP changed, discover it first.

## Skills

| Skill | Use for |
|---|---|
| `/connect-tv` | (Re)establish ADB after a power cycle or IP change |
| `/tv-remote` | Remote-control actions: launch/close apps (YouTube, Netflix, Prime Video, TvServerHub), open URLs in `WebPageViewActivity`, media keys (85 play/pause, 126/127, 87/88, 89/90), D-pad (19–23), back/home (4/3), volume (24/25/164), screenshots (`exec-out screencap -p`), current app (`dumpsys activity activities \| grep mResumedActivity`) |
| `/tv-dev` | Build, deploy and debug TvServerHub itself: WebView DevTools via `adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>`, logcat tags `TvServerHub`, `WebViewScreen`, `ScreensaverMonitor`, screensaver troubleshooting, changing the timeout or launcher tiles |
| `/create-viz` | Build interactive HTML canvases under `context/public/`; show one on the TV with "Show on TV" or `POST /api/visualizations/cast` |

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
for the machine they run on. `create_visualization.py --display-tv` then opens it on the TV the
same way "Show on TV" does (below): same device selection, same `WebPageViewActivity` intent.

**"Show on TV"** in the web and Android apps' Visuals screen uses the backend endpoints in
`backend/api/routes/visualizations.py`:

- `GET /api/visualizations/cast` → `{available, reason}` — the capability probe. It looks for a
  connected adb device whose `ro.product.manufacturer` is Amazon (so a phone on adb is never
  targeted), or the device pinned by `FIRE_TV_ADB_SERIAL`.
- `POST /api/visualizations/cast {path}` → `{ok, message}` — validates that `path` is an `.html`
  file under `context/public/` (no `..`, no symlink escape), then runs
  `adb -s <serial> shell am start -n com.example.tvserverhub/.WebPageViewActivity -e url <url>`
  with a 10 s timeout. The base URL is `https://<this machine's LAN IP>`, or `VIZ_CAST_BASE_URL`
  when set (needed on dev machines without nginx). It never raises on TV problems; failures come
  back as `{ok: false, message}`.

So "Show on TV" only works on a machine where adb is installed and connected to the TV.
Tests: `backend/tests/test_api_visualizations_cast.py`.

## Pitfalls

- The TV's IP is DHCP-assigned; when commands fail with "device offline/not found", run
  `/connect-tv` before anything else.
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
