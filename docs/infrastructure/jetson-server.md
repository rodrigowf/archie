---
name: jetson-server
category: archie/infrastructure
tags: [jetson, server, systemd, nginx, power-mode, thermal, baseline-image, watchdog, security, fb0-blank, memory-baseline]
created: 2026-02-23
modified: 2026-10-06
summary: The Jetson Nano 24/7 server — hardware, services, power/thermal setup, restore images, crash guardrails, security, resource budget.
source: curated (consolidated from memory notes assistant/infrastructure/server_hub_project.md, project_jetson_crash_2026_04_20.md, project_jetson_thermal_optimum_2026_05_15.md, reference_jetson_access.md, feedback_use_systemd_service_for_jetson_backend.md, project_indexer_full_reembed_fix_2026_06_17.md; verified against code 2026-10-06)
references:
  - topology.md
  - deployment.md
  - context-sync.md
  - ssh-remote-execution.md
  - ../operations/troubleshooting.md
  - ../operations/debugging.md
  - ../architecture/memory-and-search.md
  - ../architecture/backend.md
---

# Jetson Nano server

The Jetson Nano is Archie's always-on server: it runs the backend 24/7 and every peripheral connects
to it. This doc describes the box as it is configured; the deploy procedure is in
[deployment.md](deployment.md) and the two-machine picture in [topology.md](topology.md).

The server-side state described here (systemd units under `/etc/systemd/system/`, nginx config,
watchdog scripts) lives **on the Jetson only** — none of it is in the repo except the context-sync
units in `infra/sync/`.

## Hardware and OS

| Item | Value |
|---|---|
| Board | NVIDIA Jetson Nano Developer Kit (Tegra210, aarch64), **SD-card boot** (OS on `/dev/mmcblk0p1`, not eMMC) |
| OS | L4T R32.7.1 / JetPack 4.6.1 (Ubuntu 18.04), kernel 4.9.201-tegra |
| RAM | 4 GB (≈3.9 GB usable) |
| Storage | 59.5 GB SD card (~21 GB used after the 2026-04-21 rebuild) |
| Network | `192.168.0.200`, static, LAN |
| Known damage | USB ports/hub are non-functional (no USB boot fallback) |
| Fan | 40 mm 5 V PWM; residual noise after the software fixes is bearing wear (audible even at PWM 0) |

Old system git: no `git branch --show-current` — use `git rev-parse --abbrev-ref HEAD`.

## Access

- SSH: `rodrigo@192.168.0.200` (the laptop's key is authorized). Scripts use `sshpass`.
- Credentials: `context/.env` holds `SERVER_USERNAME` and `SERVER_PASSWORD` (the sudo password).
  Load them without printing: `set -a; . /home/rodrigo/assistant/context/.env; set +a`, then use
  `"$SERVER_PASSWORD"`; for sudo, `echo "$SERVER_PASSWORD" | sudo -S -p '' <cmd>`.
- `curl … | sudo tee` does not work with `sudo -S` (stdin is taken by the password); download to a
  temp file and `sudo -S cp` it.
- Off-LAN access goes through a Tailscale tailnet (node `jetson-server`); treat tailnet peers as
  inside the LAN trust zone.
- Runbook skill: `/server-management` (`context/skills/server-management/SKILL.md`) — status, deploy,
  sync, restart, logs, power.

## Software layout

| Path | What |
|---|---|
| `/home/rodrigo/assistant/` | Main repo (normal git repo; see [deployment.md](deployment.md)) |
| `/home/rodrigo/assistant/context/` | Private context repo, kept in sync with the laptop by [context-sync](context-sync.md) |
| `/home/rodrigo/assistant/.venv/` | Python 3.11 venv, created from the conda `assistant` env |
| `/home/rodrigo/miniconda3/envs/assistant/` | Conda env (Python 3.11) |
| `apps/web/dist`, `apps/web/dist-compat`, `legacy/frontend/dist`, `legacy/frontend-compat/dist` | Web builds rsynced from the laptop, served by the backend |
| `/home/rodrigo/nginx-server.conf` | nginx config |
| `/home/rodrigo/ssl/` | Self-signed certificate + key for nginx |
| `/home/rodrigo/server.conf`, `/home/rodrigo/server/` | copyparty file-server config and shared files |
| `/home/rodrigo/logs/` | nginx access/error logs |

**Do not build the web app here.** The system glibc is too old for Node 20+; web builds are made on
the laptop and rsynced (see [deployment.md](deployment.md)).

## Services at boot

| Unit | Bus | Role and hardening |
|---|---|---|
| `agentic-backend.service` | system | The backend. `WorkingDirectory=/home/rodrigo/assistant`, `ExecStart=…/context/scripts/run.sh -m uvicorn api.app:create_app --factory --host 127.0.0.1 --port 8765`. `StartLimitBurst=5`, `MemoryMax=2G`, `CPUQuota=300%`, `TasksMax=256`. Supervises the search server and the CLI subprocesses |
| `nginx-server.service` | system | nginx with `-c /home/rodrigo/nginx-server.conf`: **pure reverse proxy** for ports 80/443 → `127.0.0.1:8765`, TLS termination. Serves no static files. `StartLimitBurst=3` |
| `copyparty.service` | system | File sharing on port 3923. `MemoryMax=512M`, `TasksMax=128` |
| `jetson-watchdog.timer` | system | Every 60 s runs `/usr/local/sbin/jetson-watchdog.sh`: detects read-only root FS, available memory < 200 MB, > 10 concurrent user sessions. Logs to the journal |
| `fb0-blank.timer` | system | Display re-blanking safety net (see below) |
| `context-sync.service` | **user** | Needs lingering (`Linger=yes` for `rodrigo`, `/var/lib/systemd/linger/rodrigo`); re-enable with `sudo loginctl enable-linger rodrigo` |
| `nvpmodel.service`, `nvphs.service` | system | Power mode at boot; `nvphs` is the thermal fan control — **do not disable** |
| `tailscaled.service` | system | Off-LAN access |

Pitfalls:

- nginx is **`nginx-server.service`**, not `nginx.service`. The packaged `nginx.service` is
  intentionally disabled; an audit that checks only `nginx.service` wrongly concludes nginx is down.
- `assistant-backend.service` (old app, port 8000) is disabled — never re-enable it.
- Restart the backend **only** with `sudo systemctl restart agentic-backend.service`. Killing the
  PID by hand leaves orphaned children and risks double-binding the port.
- Detached SSH launches (`setsid`/`nohup`/`& disown`) survive a cancelled tool call; verify on the
  remote what actually ran.

Disabled on purpose (re-enable only with a reason): GUI (`gdm`/`gdm3`/`lightdm` are **masked** to
`/dev/null`), Docker/containerd, `nvargus-daemon` (see display fix), `packagekit`,
`accounts-daemon`, `colord`, `bolt`, `rtkit-daemon`, `upower`, serial gettys, avahi, bluetooth,
ModemManager, snapd, rpcbind, crash reporters, gpsd, and the unattended timers `apt-daily.timer`,
`anacron.timer`, `motd-news.timer`. Kept: `apt-daily-upgrade.timer` (security upgrades),
`fstrim.timer` (SD TRIM), `systemd-tmpfiles-clean.timer`. MongoDB, PostgreSQL and Jellyfin are not
installed.

Log rotation (`/etc/logrotate.d/rodrigo-services`): copyparty daily/7, nginx daily/14,
`assistant/logs/*.log` weekly/4.

## Power mode and thermal baseline

Custom nvpmodel mode **MAXN-SAFE (ID 2)** is the boot default (`PM_CONFIG DEFAULT=2` in
`/etc/nvpmodel.conf`; original saved as `/etc/nvpmodel.conf.backup`).

| ID | Name | Cores | CPU max | Use |
|---|---|---|---|---|
| 0 | MAXN | 4 | 1.43 GHz | Full performance |
| 1 | 5W | 2 | 918 MHz | Original default |
| 2 | MAXN-SAFE | 4 | 1.2 GHz (GPU 614 MHz) | **Current** — safe for 24/7 |

Switch with `sudo nvpmodel -m <id>`, query with `sudo nvpmodel -q`. Temperatures:
`cat /sys/devices/virtual/thermal/thermal_zone*/temp`.

Before changing power management, display, kernel boot parameters or service-spawn behaviour, note
the current fan/thermal state so a regression is detectable.

## Headless display blanking (fb0)

With no HDMI cable the hotplug-detect pin floats; every few hours the kernel sees a false "plugged"
event and the display controller starts a ~135 Hz scanout. While it runs, IRQ 74 (`tegradc.0`)
fires ~117/s, the `irq/74-tegradc.` kernel thread uses ~5 % CPU, the cores never reach deep idle,
and the fan stays loud. Three layers, all calling `/usr/local/sbin/fb0-blank-on-hotplug.sh`:

1. Kernel cmdline `video=HDMI-A-1:d` in `/boot/extlinux/extlinux.conf` (backup
   `extlinux.conf.pre-video-disable-2026-04-23`). Reduces but does not fully stop false triggers. A
   physical monitor won't light up until it is removed.
2. udev rule `/etc/udev/rules.d/99-fb0-blank-hotplug.rules` — reacts within milliseconds.
3. `fb0-blank.timer` + `.service` — every 60 s, the reliable floor.

The script samples IRQ 74 over 200 ms; a delta ≥ 5 means real scanout, and it writes `4` to
`/sys/class/graphics/fb0/blank` and logs under tag `fb0-blank`. It uses the IRQ rate because the
sysfs `blank` attribute reports the last written value, not the hardware state. `nvargus-daemon` is
disabled because, with no camera, it only held `/dev/fb0` open.

Checks: `grep '74:' /proc/interrupts` twice 5 s apart (> 10/s = scanout);
`sudo journalctl -t fb0-blank --since today`; `sudo journalctl -k | grep tegradc`;
`sudo fuser -v /dev/fb*`; `systemctl is-active nvargus-daemon` (must be inactive); CPU frequencies
stuck at 1224 MHz = display awake. Force-blank: `sudo sh -c 'echo 4 > /sys/class/graphics/fb0/blank'`.
General lesson: for unexplained heat or fan noise, sort processes by **accumulated** CPU time
(`ps -eo pid,comm,time --sort=-time`) — kernel IRQ threads never show high instantaneous `%CPU`.

## Restore images

Both images are on the external drive `HDPORTATIL`:

| Image | Location | Notes |
|---|---|---|
| **2026-05-15 — gold restore point** | `/media/rodrigo/HDPORTATIL/jetson-baseline-2026-05-15/` | 4 split parts (~13 GB, raw gzip streams without `.gz`), full 59.5 GB card. Includes everything below. The fan stayed silent for days in this state: keep it as the thermal reference even if a newer image is made |
| 2026-04-21 — fallback | `/media/rodrigo/HDPORTATIL/jetson-baseline-2026-04-21/` | Immediate post-rebuild state; re-apply SSH-churn hardening, credentials, disabled timers and the fb0 fix after restoring |

Restore: `cat baseline.img.part-* | gunzip | sudo dd of=/dev/sdX bs=4M status=progress` (~45 min,
then ~45 s boot to full stack). After restoring, pull the current code
([deployment.md](deployment.md)) and check Claude authentication.

## The 2026-04-20 crash and its guardrails

On 2026-04-20 systemd spawned and killed user-manager sessions in a tight loop (~550 sessions in
under 3 minutes). The churn's writes overwhelmed the SD card, the kernel remounted `/` read-only
(`errors=remount-ro`), and the box became unreachable. The card read fine in the laptop, so the
fault was load on the Jetson's SD path, not bad sectors. `kern.log` had nothing (the FS was already
read-only); the evidence was in the rescued `syslog`. The likely trigger was SSH session churn —
concurrent session starts opening many SSH handshakes. The box was rebuilt fresh on 2026-04-21.

Guardrails that came out of it:

- `StartLimitBurst` / `StartLimitIntervalSec` on every restart-always unit, plus `MemoryMax`,
  `CPUQuota`, `TasksMax` on the backend.
- `jetson-watchdog.timer` (read-only FS, low memory, > 10 user sessions).
- In the backend: `SessionPool._host_create_locks` serializes session creation per SSH host
  (`backend/api/pool.py`); an ICMP pre-probe refuses SSH to an unreachable host
  (`probe_host_reachable` in `backend/manager/_ssh.py`); the resolved remote CLI path is cached so
  concurrent starts share one probe; the "No conversation found" resume fallback retries once with
  backoff. See [ssh-remote-execution.md](ssh-remote-execution.md).
- Don't blindly restore old units, PAM/logind config, the full dpkg list or cron jobs onto a fresh
  image — review each.

## Resource budget

| State | Figures |
|---|---|
| Cold idle (2026-04-23, before the embedding model loads) | ~315 MB used, load ~0.02, 21–23 °C, CPUs 300–800 MHz, 23 running services |
| Warm steady state (2026-06-18, search server loaded) | ~2.1 GB used, ~1.5 GB **available**, swap ~270 MB stable, 33 °C, fan PWM 0 |
| Boot to full stack | ~36–48 s |

The search server (`shared/scripts/search-server.py`, PyTorch + sentence-transformers) is the
dominant consumer, ~1.8 GB RSS in the 2026-06 measurement (taken before the 2026-10-06 move from
Chroma to SQLite indexes — re-baseline when auditing); the backend ~100–130 MB; copyparty ~10–40 MB.
Red flags worth digging into: `Available` under 400 MB while swap climbs past 500 MB; the search
server above ~2.2 GB; uvicorn above 250 MB sustained (use `py-spy dump --pid`); any new process over
100 MB; search-server CPU high with no queries (see the indexer row in
[troubleshooting.md](../operations/troubleshooting.md)). Disk: ~36 % of the card used.

## Security notes

- Exposure is LAN (+ tailnet) only — no router port forwards. LAN listeners: 22 (SSH), 80/443
  (nginx, self-signed cert), 3923 (copyparty). Loopback only: 8765 (backend), so `:8765` URLs work
  only on the Jetson itself; peripherals use `https://192.168.0.200/`.
- Secrets on disk: `.claude_config/.credentials.json` (mode 600), `context/.env`,
  `context/secrets/`, `~/ssl/`. No disk encryption — the SD card and the restore images must stay
  physically controlled.
- The backend can run shell commands and SSH into the laptop (SSH working directories), so a
  compromised Jetson means a compromised laptop account.
- Known gaps: SSH password auth is still enabled; no `from=` restriction on the laptop's
  authorized key for the Jetson; no fail2ban/auditd.
- Quick audit commands: `sudo ss -tlnp`, `sudo ss -tn state established`, SSH source IPs from
  `/var/log/auth.log`, crontabs for `rodrigo` and `root`, recently modified files in
  `/usr/local/{bin,sbin}`, `/tmp`, `/var/tmp`, `authorized_keys`.

## History

- 2026-02: Jetson set up as the home server hub.
- 2026-04-20: session-churn crash, read-only SD. 2026-04-21: fresh rebuild with hardened units;
  baseline image taken.
- 2026-04-22/23: unattended timers disabled; fb0 three-layer fix; `nvargus-daemon` disabled.
- 2026-05-15: new baseline image (thermal optimum).
- 2026-05-23/24: uvicorn pinned a core for ~20 h — `MemoryWatcher` (`backend/api/indexer.py`) used
  `watchfiles.awatch`, whose Rust thread ignores asyncio cancellation, so anyio's
  `_deliver_cancellation` busy-looped. Fixed in `be8514e` (set `_stop_event` in `finally`). Pattern:
  whoever owns an uncancellable thread must signal it in `finally`; `except Exception` does not
  catch cancellation.
- 2026-06-17: indexer re-embed loop fixed (`6e98e2b`); 2026-10-06 the indexes moved to SQLite.
