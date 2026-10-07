---
name: installation
category: archie/infrastructure
tags: [install, installer, setup-context, symlinks, venv, npm, migration, certificates, parallel-install]
created: 2026-04-15
modified: 2026-10-07
summary: What the installers do, the context/ symlink model, migrating to a new machine, certificates, and running a second install.
source: curated (consolidated from INSTALL.md, install/README.md, install/linux/install.sh, shared/scripts/setup-context.sh, shared/scripts/run.sh, start.sh, memory notes assistant/infrastructure/repo_layout_cutover_2026_10.md and a private parallel-installation note (generic mechanics only); verified against code 2026-10-06)
references:
  - topology.md
  - context-sync.md
  - deployment.md
  - ssh-remote-execution.md
  - ../overview/repo-layout.md
  - ../harnesses/registry.md
  - ../integrations/skills.md
  - ../clients/web.md
---

# Installation

Archie installs into a git checkout of the public repo. The installer creates the Python venv,
installs Node deps for the web app, creates (or imports) the private `context/` folder, and wires the
symlinks that let each agent CLI read and write inside `context/`. The step-by-step human guide is
`INSTALL.md` at the repo root; this doc explains what the pieces do and how to move an install.

## Entry points

| File | What it does |
|---|---|
| `install.sh` | OS dispatcher: execs `install/linux/install.sh` or `install/apple/install.sh`; on Git Bash/MSYS it tells you to use PowerShell |
| `install.ps1` | Windows entry → `install/windows/install.ps1` |
| `install-with-agent.sh` / `.ps1` | Conversational install: launches an agent CLI with `INSTALL.md` as instructions; the agent re-does each step and logs to `context/install.log` |
| `install/<os>/install-prerequisites.*` | Checks Python ≥ 3.11, Node, npm, git (Linux prints package hints; macOS offers Homebrew; Windows offers winget) |
| `install/` (templates) | `AGENTS.md`, `MEMORY.md`, `context.env`, `assistant_config.json`, `manager.json`, `sync.env`, `cli-runtime/<cli>/` — user-agnostic seeds, see `install/README.md` |
| `shared/scripts/setup-context.sh` | Standalone, idempotent (re)creation of the `context/` structure and symlinks; `--force` relinks |

Prerequisites per `INSTALL.md`: Python 3.11+ (3.12 recommended; the backend has no 3.12-only syntax
or stdlib use — the whole of `backend/` and `shared/scripts/` compiles under 3.11 — and the
prerequisite checkers accept 3.11 since 2026-10-07), Node.js 22.12+ (the web toolchain's `engines`), npm,
git; plus credentials for whichever harnesses/providers you choose. (The Jetson runs Python 3.11 from
a conda env and builds nothing with Node — see [jetson-server.md](jetson-server.md).)

## Flags (`install/linux/install.sh --help`)

| Flag | Effect |
|---|---|
| `--new-context` | Create a fresh `context/` non-interactively |
| `--import-context URL` | `git clone URL context` (your private context repo) |
| `--with-claude` / `--with-qwen` / `--with-gemini` (and `--without-*`) | Session harnesses to set up ([registry.md](../harnesses/registry.md)) |
| `--with-anthropic` / `--with-openai` (and `--without-*`) | Orchestrator SDKs to install |
| `--qwen-only` | Qwen harness + OpenAI SDK only |
| `--dev` | `backend/requirements-dev.txt` |
| `--skip-prereqs`, `--skip-auth` | Skip the prerequisite check / the CLI install + login step |

Without flags it asks the two "axis" questions (harnesses, orchestrator SDKs) and whether to create
or import the context.

## What the Linux installer does (Step numbers match the script)

| Step | Action |
|---|---|
| 1 | Prerequisite check |
| 2 | Context: keep an existing configured `context/` (or back it up to `context.bak/`); **new**: `mkdir context/{memory,skills,scripts,agents,secrets,certs}`, seed `context/memory/MEMORY.md`, `context/AGENTS.md`, `context/.env` from `install/` (uncommenting keys for the chosen axes); **import**: `git clone`, add missing folders. Both create the `shared/` symlinks (below) |
| 3 | Claude: `.claude_config/projects/<mangled-cwd>` → `../../context` (migrating any JSONL from a real directory there), `.claude_config/skills` → `../context/skills`, `.claude_config/agents` → `../context/agents` (the CLI loads user agents from `$CLAUDE_CONFIG_DIR/agents`; the installers create this link since 2026-10-07) |
| 3b | Qwen: `~/.qwen/projects/<mangled-cwd>` → `<repo>/context`, chats into `context/chats/`; `~/.qwen/skills` → `context/skills` if free |
| 3c | Gemini: `~/.gemini/tmp/<label>` → `<repo>/context` (label from `~/.gemini/projects.json`, else the cwd basename) |
| 3d | `CLAUDE.md` and `QWEN.md` at the repo root → `context/AGENTS.md` (migrates a legacy root `AGENTS.md`/`CLAUDE.md`) |
| 3e | Seed `.claude/`, `.qwen/`, `.gemini/` from `install/cli-runtime/` without overwriting |
| 4–6 | `python3 -m venv .venv`, upgrade pip, `pip install -r backend/requirements.txt` (or `-dev`) plus `requirements-claude.txt` / `-anthropic.txt` / `-openai.txt` per axis |
| 7 | `npm install` in `apps/web` and `apps/design-tokens` (the web build's token gate needs the latter) |
| 7b | `npm install -g` the chosen CLIs (`@anthropic-ai/claude-code`, `@qwen-code/qwen-code`, `@google/gemini-cli`) and prompt for login unless an API key is in `context/.env` |
| 8 | `mkdir -p index logs` |
| 9 | Symlink `.claude_config/.credentials.json` → `~/.claude/.credentials.json` (so token refreshes by the interactive CLI are shared; a stale *copy* here causes 401s) |
| 10–11 | `assistant_config.json` from the template (`@@SCRIPT_DIR@@`; provider = the first installed harness in the order claude, qwen, gemini; orchestrator model = `qwen3.6-plus` for a Qwen default, else Claude Sonnet) and `.manager.json` |
| 12 / 12b | Verify imports and env keys; probe Gemini Live backends and pick `default_voice_endpoint` |

The legacy web apps (`legacy/frontend*`) are optional; install their deps only to build them.

## The symlink model

```
context/skills/<name>   → ../../shared/skills/<name>     (general-purpose, public)
context/scripts/<name>  → ../../shared/scripts/<name>
context/agents/<name>   → ../../shared/agents/<name>
context/skills/<own>/                                    (personal, real folder)
.claude_config/projects/-home-rodrigo-assistant → ../../context   (JSONL lands in context/)
.claude_config/skills   → ../context/skills
.claude_config/agents   → ../context/agents
CLAUDE.md, QWEN.md, GEMINI.md → context/AGENTS.md
```

Public, general-purpose tools live in `shared/`; personal ones live directly in `context/`; both are
reachable from `context/{skills,scripts,agents}/`. `shared/scripts/setup-context.sh` links every
file and folder in `shared/skills|scripts|agents` (skipping `__pycache__`) and never replaces an
existing entry, so personal items with the same name win. Re-run it after adding something to
`shared/` — new items are not linked until you do (links missing after the 2026-10-05 move came from
not re-running it). The installers' own link loops skip `__pycache__` the same way. Relative links
keep the tree portable. See
[skills.md](../integrations/skills.md) and [repo-layout.md](../overview/repo-layout.md).

`shared/scripts/run.sh` (reached as `context/scripts/run.sh`) is how everything runs Python: it
exports `PYTHONPATH=<repo>/backend`, `CLAUDE_CONFIG_DIR=<repo>/.claude_config`, sources
`context/.env` with `set -a`, sets `LD_PRELOAD=libgomp.so.1` on aarch64, and execs `.venv/bin/python`.

## Running

```bash
context/scripts/run.sh -m uvicorn api.app:create_app --factory --host 0.0.0.0 --port 8765
./start.sh     # same, detached with setsid, output in logs/api_<timestamp>.log
cd apps/web && npm run dev        # dev server 5450 (compat: npm run dev:compat, 5451)
```

Without `run.sh`: `.venv/bin/python -m uvicorn api.app:create_app --factory --app-dir backend --port 8765`.

## Certificates

- `context/certs/cert.pem` + `key.pem`: when present, the Vite dev server (`apps/web/vite.shared.ts`)
  serves HTTPS (`https://localhost:5450`); otherwise plain HTTP. HTTPS matters for browser mic access
  (voice) from other devices.
- The Jetson's public HTTPS is nginx with a self-signed certificate in `/home/rodrigo/ssl/`, not
  `context/certs/` ([jetson-server.md](jetson-server.md)).

## Migrating to a new machine

1. Clone the public repo to the **same absolute path** if the new machine will share sessions with an
   existing one (project keys derive from the path — [topology.md](topology.md)).
2. Run `./install.sh --import-context <your private context repo>`.
3. Bring over what the context repo may not carry. The reference context repo is private and tracks
   `context/.env`, `context/secrets/` and `context/certs/`; if yours doesn't, copy those three by
   hand (they hold API keys, OAuth tokens/client secrets and dev TLS keys).
4. Recreate per-machine state that never travels: `assistant_config.json` (working directories, SSH
   entries), `.manager.json`, Claude credentials (`claude auth login`, or the
   `CLAUDE_CODE_OAUTH_TOKEN` in `context/.env`), `infra/sync/config.env` if syncing.
5. The search index (`index/`) rebuilds from `context/`; the SQLite index files can also be copied.

Swapping the whole `context/` (cloning a different context repo in its place) is the supported way
to switch an installation to a different user/environment.

## A second, parallel installation

Two installs can coexist on one machine in different directories (e.g. `~/assistant` and
`~/other/assistant`), each with its own `context/` (history, memory, keys). What differs:

- **Port.** Both default to 8765; run the second backend with `--port <other>` and point its web dev
  server at it with `ARCHIE_BACKEND=http://localhost:<other> npm run dev` (Vite ports 5450/5451 are
  strict, so run one dev server at a time or change the ports). Clients (Android apps) can connect to
  either backend by changing the server URL in their settings.
- **Project keys.** The different path gives a different mangled cwd, so `.claude_config/projects/…`
  and `~/.qwen/projects/…` don't collide. Gemini's default label is the directory basename, so two
  dirs both named `assistant` collide in `~/.gemini/tmp/assistant`; the installer leaves an existing
  link alone and warns. `~/.qwen/skills` is global and stays pointed at the first install.
- **Keys and MCPs** are per install (`context/.env`, `.claude_config/.claude.json`) — a fresh one
  needs its own.
- **context-sync**: the context-sync unit (`context-sync.service`, the only one) hard-codes
  `~/assistant/infra/sync/` and the unit name `context-sync`; don't install the sync service from a
  second install without giving it its own unit.

## Pitfalls

- "Symlinks point at the wrong place after copying `context/`": re-run `./install.sh` or
  `shared/scripts/setup-context.sh` — both are idempotent.
- A CLI installed under nvm may be invisible to non-interactive shells; locally set `QWEN_CLI_PATH` /
  `GEMINI_CLI_PATH` in `context/.env` (read by `backend/manager/{qwen,gemini}/session.py`), and for SSH remotes see
  [ssh-remote-execution.md](ssh-remote-execution.md#the-nvm-exit-127-trap).
- Windows: symlinks need Developer Mode or Administrator, otherwise junctions + copies; path mangling
  replaces both `\` and `:` with `-`.
