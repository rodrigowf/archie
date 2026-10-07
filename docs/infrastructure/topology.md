---
name: topology
category: archie/infrastructure
tags: [topology, laptop, jetson, dual-machine, git, context-sync, branches, per-machine-state]
created: 2026-02-23
modified: 2026-10-06
summary: The two machines (laptop + Jetson), what runs where, and how code, context and per-machine state travel between them.
source: curated (consolidated from memory notes assistant/infrastructure/server_hub_project.md, assistant/infrastructure/ssh-remote-execution.md, assistant/infrastructure/repo_layout_cutover_2026_10.md, assistant/architecture/refactor_methodology.md, context/AGENTS.md "Dual-Machine Setup"; verified against code 2026-10-06)
references:
  - jetson-server.md
  - deployment.md
  - context-sync.md
  - ssh-remote-execution.md
  - installation.md
  - ../operations/working-rules.md
  - ../devices/devices.md
  - ../overview/repo-layout.md
---

# Topology: laptop + Jetson

The reference deployment of Archie runs on **two Linux machines with the same codebase at the same
path**. A single machine is enough to run Archie; the second machine is optional and is wired in by
[context-sync](context-sync.md) and, optionally, [SSH remote execution](ssh-remote-execution.md).

| Machine | Hostname role | LAN IP | Role |
|---|---|---|---|
| **Laptop** (desktop) | development | `192.168.0.28` | Primary development, builds (web dists, Android APKs), heavier workloads, emulators |
| **Jetson Nano** (server) | 24/7 server | `192.168.0.200` | Runs `agentic-backend.service` around the clock; every peripheral (phones, A300M, iPad, Fire TV) talks to it. See [jetson-server.md](jetson-server.md) |

Both machines run the backend from `/home/rodrigo/assistant/`. The peripherals in
[devices.md](../devices/devices.md) point at the Jetson (`https://192.168.0.200/`); the laptop's
backend (`./start.sh`, port 8765, bound to `0.0.0.0`) is used for development and on-device test
passes so the Jetson stays untouched.

## Why the install path is identical on both machines

Several harness mechanisms derive identifiers from the **absolute working directory**:

- Claude Code stores session JSONL under `$CLAUDE_CONFIG_DIR/projects/<mangled-cwd>/`, where the
  mangled cwd is the path with `/` replaced by `-` (`/home/rodrigo/assistant` →
  `-home-rodrigo-assistant`). On both machines that directory is a symlink to `../../context`.
- Qwen Code keys its project directory the same way (`~/.qwen/projects/<mangled-cwd>` → `context/`),
  and Gemini CLI uses a label derived from the cwd (`~/.gemini/tmp/<label>` → `context/`).
- Resume uses the SDK session id plus that project key. Same path on both sides means a session
  created on one machine **resumes cleanly on the other**: the JSONL lands in `context/` (which is
  synced) under the same project key the other machine will look in.

A different path on one machine would put its sessions in a different project bucket and break
cross-machine resume and SSH-remote sessions (see the cwd mechanics in
[ssh-remote-execution.md](ssh-remote-execution.md)).

## How things travel between machines

| What | Mechanism | Notes |
|---|---|---|
| Code (`backend/`, `apps/`, `shared/`, `infra/`, `docs/`, …) | **git** — push on one machine, pull on the other | See [deployment.md](deployment.md) for the Jetson procedure |
| `context/` (conversations, memory, skills, scripts, `.env`, secrets, `public/`) | **context-sync** (inotifywait + rsync over SSH, ~2 s, both directions) | `context/` is also its own git repo for versioning; see [context-sync.md](context-sync.md) |
| Built web dists (`apps/web/dist`, `apps/web/dist-compat`, legacy dists) | **rsync from the laptop** | Gitignored build outputs; the Jetson cannot build them |
| Android APKs | built on the laptop, installed with `adb` | Not deployed to the Jetson at all |

### Branches

- `local` is the **default trunk** both machines come back to after parallel feature work.
- Feature work happens on feature branches (for example `frontend-refactory`, `permissions`), and
  **either machine may be on a feature branch at any moment** — they are not guaranteed to match.
- Always check before assuming: `git branch --show-current` on the laptop. The Jetson's git is too
  old for `--show-current`; use `git rev-parse --abbrev-ref HEAD` there.
- Before deploying, reconcile: the Jetson should normally be on the same branch as the laptop so
  file paths, protocol and code stay aligned.

## What is per-machine and does NOT travel

These files are gitignored and outside `context/`, so neither git nor context-sync moves them. Each
machine has its own copy, and they drift.

| Path | What | Consequence |
|---|---|---|
| `.claude_config/` (except the symlinks into `context/`) | Claude Code config dir used by the backend (`CLAUDE_CONFIG_DIR`), incl. `.credentials.json`, `.claude.json` (MCP config) | OAuth credentials drift per machine — the cause of the Jetson 401s; see [deployment.md](deployment.md#claude-code-authentication-on-the-jetson) |
| `assistant_config.json` | Working directories (incl. SSH entries), MCPs, default models, voice settings | Each machine lists the *other* machine as an SSH working directory; edit it on the machine whose backend should use it |
| `.manager.json` | Session-manager defaults | Per machine |
| `infra/sync/config.env` | context-sync direction (who is the remote) | Laptop points at the Jetson, Jetson at the laptop |
| `index/` | Search indexes (`history.sqlite3`, `memory.sqlite3`, `session_summaries.sqlite3`) | Each backend indexes locally; the SQLite files are portable and can be copied (build on the laptop, copy to the Jetson) |
| `apps/web/dist*`, `legacy/*/dist` | Build outputs | Must be rsynced to the Jetson on every web deploy |
| `.venv/`, `node_modules/` | Environments | Installed per machine |
| `logs/` | Backend logs, `remote_console.log`, `logs/voice/` | Per machine — look on the machine that served the request |
| `~/.claude/` | Interactive Claude Code's own config | Distinct from `.claude_config/`; sessions started from a terminal or VS Code land here and never sync |
| `projects/` (repo root) | Laptop working dirs for large media projects | Laptop only — the Jetson cannot see files there |

Because `projects/` and other laptop-only paths are invisible to the Jetson, anything Rodrigo needs
to review must be copied under `context/` (see
[working-rules.md](../operations/working-rules.md#review-artifacts-go-where-rodrigo-can-reach-them)).

## How to tell which machine you are on

Check the hostname or the IP, never the path (the path is the same by design):

```bash
hostname
hostname -I     # 192.168.0.28 = laptop, 192.168.0.200 = Jetson
uname -m        # x86_64 = laptop, aarch64 = Jetson
```

## Pitfalls

- **Don't assume branch state.** The two machines may be on different branches; check both before a
  deploy or a cross-machine comparison.
- **A `git pull` never brings `infra/sync/config.env`, `assistant_config.json` or the dists.** After
  moving or renaming anything they reference, fix them by hand on each machine.
- **Conversations started outside the wrapper don't sync.** A bare `claude` in a terminal writes to
  `~/.claude/projects/...`, not `context/`.
- **Two copies of the same OAuth grant break each other** (rotating refresh tokens). Never copy
  Claude credentials between the machines as a fix; see
  [deployment.md](deployment.md#claude-code-authentication-on-the-jetson).

## History

- 2026-02: the Jetson became the 24/7 server; the laptop stays the development box.
- 2026-04-17: SSH remote execution let the Jetson run sessions on the laptop.
- Earlier, `context/` was shared with Syncthing (the rsync excludes still list its artifacts); it
  was replaced by the inotifywait + rsync `context-sync` service.
- 2026-10-05: repo reorganized (`apps/`, `backend/`, `shared/`, `legacy/`, `infra/sync/`); paths
  inside both installs changed, the install path did not.
