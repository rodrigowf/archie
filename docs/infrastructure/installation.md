---
name: installation
category: archie/infrastructure
tags: [install, installer, setup-context, symlinks, venv, npm, migration, certificates, parallel-install, doctor, harness-versions, no-node, modelstudio]
created: 2026-04-15
modified: 2026-10-08
summary: What the installers do, the version pins, every per-harness symlink, install/doctor.sh, backend-only (no-Node) hosts, migrating to a new machine, certificates, and running a second install.
source: curated (consolidated from INSTALL.md, install/README.md, install/linux/install.sh, shared/scripts/setup-context.sh, shared/scripts/run.sh, start.sh, memory notes assistant/infrastructure/repo_layout_cutover_2026_10.md and a private parallel-installation note (generic mechanics only); verified against code 2026-10-06)
references:
  - topology.md
  - context-sync.md
  - deployment.md
  - ssh-remote-execution.md
  - ../overview/repo-layout.md
  - ../harnesses/registry.md
  - ../harnesses/gemini-cli.md
  - ../harnesses/qwen-code.md
  - ../harnesses/codex-cli.md
  - ../harnesses/claude-code.md
  - ../harnesses/model-studio.md
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
| `install-with-agent.sh` / `.ps1` | Conversational install: launches an agent CLI (claude / qwen / gemini / codex; it offers to `npm install -g` one at the pinned versions below if none is present) with `INSTALL.md` as instructions; the agent re-does each step and logs to `context/install.log` |
| `install/<os>/install-prerequisites.*` | Checks Python ≥ 3.11, Node ≥ `NODE_MIN_MAJOR` (22), npm, git (Linux prints package hints; macOS offers Homebrew; Windows offers winget); the bash ones take `--no-node` |
| `install/` (templates) | `AGENTS.md`, `MEMORY.md`, `context.env`, `assistant_config.json`, `manager.json`, `sync.env`, `cli-runtime/<cli>/`, `cli-runtime/codex-home/config.toml` — user-agnostic seeds, see `install/README.md` |
| `install/harness-versions.env` | The CLI pins (`QWEN_CLI_VERSION`, `GEMINI_CLI_VERSION`, `CODEX_CLI_VERSION`) and `NODE_MIN_MAJOR` — sourced by the bash installers, parsed by the PowerShell ones, compared by the doctor. The claude-agent-sdk pin stays in `backend/requirements-claude.txt`; `QWEN_CLI_VERSION` in `backend/manager/qwen/adapter.py` must match (the doctor warns) |
| `install/doctor.sh` | Per-harness check of an install; `--fix` / `--dry-run` repair symlinks and seed files only — see [The doctor](#the-doctor-installdoctorsh) |
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
| `--with-claude` / `--with-qwen` / `--with-gemini` / `--with-codex` / `--with-modelstudio` (and `--without-*`) | Session harnesses to set up ([registry.md](../harnesses/registry.md)); Windows: `-WithClaude`, `-WithCodex`, `-WithModelStudio`, … Model Studio installs no CLI: it needs `requirements-claude.txt`, the `.claude_config/` links and `DASHSCOPE_API_KEY` ([model-studio](../harnesses/model-studio.md)) |
| `--no-node` | Backend-only host (auto-detected when `node` is missing or does not start) — see [Backend-only hosts](#backend-only-hosts-no-nodejs); Linux / macOS only |
| `--with-anthropic` / `--with-openai` (and `--without-*`) | Orchestrator SDKs to install |
| `--qwen-only` | Qwen harness + OpenAI SDK only |
| `--dev` | `backend/requirements-dev.txt` |
| `--skip-prereqs`, `--skip-auth` | Skip the prerequisite check / the CLI install + login step |

Without flags it asks the two "axis" questions (harnesses, orchestrator SDKs) and whether to create
or import the context.

## What the Linux installer does (Step numbers match the script)

`install/apple/install.sh` is the same file apart from its header comment (its helpers —
`resolve_path`, `sed_inplace`, `ensure_dir_link` — are portable across GNU/BSD and bash 3.2; port a
change by copying the Linux file). `install/windows/install.ps1` mirrors the steps in PowerShell —
symlinks need Developer Mode or Administrator, else directory junctions and file copies; the Gemini
settings merge (3e) runs after the venv exists; no `--no-node` mode and no doctor (its verification
step lists every link instead); see [Pitfalls](#pitfalls).

Every link step follows one rule (`ensure_dir_link`, and the doctor's `--fix`): a correct link is
left alone; a link pointing elsewhere is reported and never clobbered; an empty real directory is
replaced; a real directory holding sessions is migrated (sessions copied into `context/` without
overwriting, the directory backed up / moved aside) and then linked.

| Step | Action |
|---|---|
| 0 | Detect a usable Node (`node -v` runs); without one: [backend-only](#backend-only-hosts-no-nodejs) — Qwen/Gemini skipped with a note |
| 1 | Prerequisite check |
| 2 | Context: keep an existing configured `context/` (or back it up to `context.bak/`); **new**: `mkdir context/{memory,skills,scripts,agents,secrets,certs}`, seed `context/memory/MEMORY.md`, `context/AGENTS.md`, `context/.env` from `install/` (uncommenting keys for the chosen axes: `OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, `DASHSCOPE_API_KEY` for Qwen, `GEMINI_API_KEY` for Gemini); **import**: `git clone`, add missing folders. Both create the `shared/` symlinks (below) |
| 3 | Claude **and Model Studio** (same bundled CLI, same `CLAUDE_CONFIG_DIR`): `.claude_config/projects/<mangled-cwd>` → `../../context` (migrating any JSONL from a real directory there; the key replaces every non-alphanumeric character with `-`, like the CLI), `.claude_config/skills` → `../context/skills`, `.claude_config/agents` → `../context/agents` (the CLI loads user agents from `$CLAUDE_CONFIG_DIR/agents`) |
| 3b | Qwen: `~/.qwen/projects/<mangled-cwd>` → `<repo>/context`, chats into `context/chats/` (JSONL only — since 0.25 `*.runtime.json` is a liveness marker, not session data); `~/.qwen/skills` → `context/skills` (an empty real dir is replaced) |
| 3c | Gemini: `~/.gemini/tmp/<label>` → `<repo>/context` (label from `~/.gemini/projects.json`, else the cwd basename); offers to copy `GEMINI_API_KEY` into `~/.gemini/.env` (mode 600) for hosts other machines reach over SSH |
| 3c2 | Codex: seed `~/.codex-archie/config.toml` from `install/cli-runtime/codex-home/` (never overwritten: `project_doc_max_bytes = 131072`, plugins/apps off), `~/.codex-archie/sessions` → `<repo>/context/codex/sessions` (a real directory there is copied in and moved aside). `auth.json` is never copied ([codex-cli](../harnesses/codex-cli.md)) |
| 3c3 | When Codex, Gemini or Qwen is enabled: `.agents/skills` → `../context/skills` — Codex 0.161 lists skills from `<repo>/.agents/skills` (verified with `codex debug prompt-input`), Gemini CLI reads it as its workspace-skills alias, Qwen as a project skill dir |
| 3d | `CLAUDE.md`, `QWEN.md`, `GEMINI.md` and `AGENTS.md` (read by Codex) at the repo root → `context/AGENTS.md` (committed; missing ones re-created; migrates a legacy root `AGENTS.md`/`CLAUDE.md`) |
| 3e | Seed `.claude/`, `.qwen/`, `.gemini/` from `install/cli-runtime/` without overwriting; then merge Archie's keys into an existing `.gemini/settings.json` (`backend/manager/gemini/workspace_settings.py`: session retention off, `context.fileFiltering`, API-key auth, thinking overrides — [gemini-cli](../harnesses/gemini-cli.md)) |
| 4–6 | `python3 -m venv .venv`, upgrade pip, `pip install -r backend/requirements.txt` (or `-dev`) plus `requirements-claude.txt` (claude or modelstudio) / `-anthropic.txt` / `-openai.txt` per axis |
| 7 | `npm install` in `apps/web` and `apps/design-tokens` (the web build's token gate needs the latter); skipped without Node |
| 7b | `npm install -g` each chosen CLI that is not on `PATH` yet, and check auth. Pins from `install/harness-versions.env`: `@qwen-code/qwen-code@0.25.0` (= `QWEN_CLI_VERSION`; needs Node 22+), `@google/gemini-cli@0.63.0`, `@openai/codex@0.161.0`; `@anthropic-ai/claude-code` is unpinned and optional (login only — the backend runs the CLI bundled with `claude-agent-sdk==0.2.164`). An already-installed CLI at another version only gets a warning with the `npm install -g …@<pin>` command. Without npm, Codex comes from its static GitHub release binary. Model Studio: nothing to install, `DASHSCOPE_API_KEY` checked. Login: Claude `CLAUDE_CODE_OAUTH_TOKEN` / `claude auth login` / `ANTHROPIC_API_KEY`; Qwen OAuth (`qwen`) or `DASHSCOPE_API_KEY`; Gemini **only** `GEMINI_API_KEY` (Google stopped serving Gemini CLI to personal-account OAuth on 2026-06-18 — [gemini-cli](../harnesses/gemini-cli.md)); Codex `CODEX_HOME=~/.codex-archie codex login --device-auth` (the shared `~/.codex` login also works; no API-key fallback) |
| 8 | `mkdir -p index logs` |
| 9 | Symlink `.claude_config/.credentials.json` → `~/.claude/.credentials.json` (so token refreshes by the interactive CLI are shared; a stale *copy* here causes 401s) |
| 10–11 | `assistant_config.json` from the template (`@@SCRIPT_DIR@@`; provider = the first installed harness in the order claude, qwen, gemini, codex, modelstudio; orchestrator model = `qwen3.6-plus` for a Qwen or Model Studio default, else Claude Sonnet) and `.manager.json` |
| 12 / 12b | Verify imports and the env keys of the chosen axes (`DASHSCOPE_API_KEY` for Qwen / Model Studio, `GEMINI_API_KEY` for Gemini), then `install/doctor.sh --harness <chosen>` prints the wiring table; probe Gemini Live backends and pick `default_voice_endpoint` |

The legacy web apps (`legacy/frontend*`) are optional; install their deps only to build them.

## The symlink model

```
context/skills/<name>   → ../../shared/skills/<name>     (general-purpose, public)
context/scripts/<name>  → ../../shared/scripts/<name>
context/agents/<name>   → ../../shared/agents/<name>
context/skills/<own>/                                    (personal, real folder)
.claude_config/projects/-home-rodrigo-assistant → ../../context   (JSONL lands in context/; claude + modelstudio)
.claude_config/skills   → ../context/skills
.claude_config/agents   → ../context/agents
~/.qwen/projects/<mangled-cwd>, ~/.gemini/tmp/<label> → <repo>/context
~/.qwen/skills           → <repo>/context/skills
~/.codex-archie/sessions → <repo>/context/codex/sessions
.agents/skills           → ../context/skills   (codex, gemini, qwen)
CLAUDE.md, QWEN.md, GEMINI.md, AGENTS.md → context/AGENTS.md   (committed; the installers re-create missing ones)
```

`.agents/skills` is not committed or gitignored yet — a fresh install creates it as an untracked
link (commit it like the root `*.md` links, or add `.agents/` to `.gitignore`).

Public, general-purpose tools live in `shared/`; personal ones live directly in `context/`; both are
reachable from `context/{skills,scripts,agents}/`. `shared/scripts/setup-context.sh` links every
file and folder in `shared/skills|scripts|agents` (skipping `__pycache__`) and never replaces an
existing entry, so personal items with the same name win. Re-run it after adding something to
`shared/` — new items are not linked until you do (links missing after the 2026-10-05 move came from
not re-running it). The installers' own link loops skip `__pycache__` the same way. Relative links
keep the tree portable. See
[skills.md](../integrations/skills.md) and [repo-layout.md](../overview/repo-layout.md).

## The doctor (`install/doctor.sh`)

`install/doctor.sh [--repo DIR] [--harness claude,modelstudio,qwen,gemini,codex|all] [--fix | --dry-run]`
prints one OK / WARN / FAIL / SKIP row per check and exits 1 if any row is FAIL:

| Area | Checks |
|---|---|
| common | `context/`, `context/AGENTS.md`, `context/.env`; Node vs `NODE_MIN_MAJOR`; the four root `*.md` links; every `shared/{skills,scripts,agents}` entry linked from `context/` (and dangling links there) |
| claude | venv `claude-agent-sdk` version vs `requirements-claude.txt` (from its `dist-info`, nothing imported), the bundled CLI, auth (`CLAUDE_CODE_OAUTH_TOKEN`, `.claude_config/.credentials.json` link or copy, or `ANTHROPIC_API_KEY`), the three `.claude_config` links |
| modelstudio | the same SDK + `DASHSCOPE_API_KEY` |
| qwen | `qwen --version` vs pin (also `QWEN_CLI_PATH`), the backend's `QWEN_CLI_VERSION` vs the pins file, Node 22+, auth (`DASHSCOPE_API_KEY` or `~/.qwen/oauth_creds.json`), both `~/.qwen` links, `.qwen/settings.json` memory keys false |
| gemini | `gemini --version` vs pin, `GEMINI_API_KEY`, `~/.gemini/.env` key + mode 600 (WARN: only SSH-remote hosts need it), the `projects.json` label, the `tmp/<label>` link, `.gemini/settings.json` = `workspace_settings.merge_archie_settings()` of itself |
| codex | `codex --version` vs pin (also `CODEX_CLI_PATH`), auth (`ARCHIE_CODEX_HOME`, `~/.codex-archie/auth.json`, else WARN for the shared `~/.codex`), `config.toml`, the `sessions` link, `.agents/skills` |

Env keys and auth are checked by name / file existence only; no value is printed. By default a
harness whose CLI is not on this host is a SKIP row (with the no-Node note when Node is missing);
`--harness` makes the listed ones required. `--fix` repairs only symlinks and seed files with the
installers' rule above (a migrated real directory is moved to `<name>.bak-<timestamp>`); it never
installs packages or touches auth, and `--dry-run` prints what it would do. The script is bash 3.2
compatible (macOS) and self-contained — copy it to another host and point `--repo` at the checkout
(it falls back to built-in pins when that checkout has no `install/harness-versions.env`).

## Backend-only hosts (no Node.js)

A host without a usable Node — missing, or unable to start, like Node 22 on the Jetson's glibc 2.27 —
still runs the backend. The Linux / macOS installer detects it (or `--no-node`): no web-app `npm
install` (build `apps/web` elsewhere and copy `dist/` + `dist-compat/` — [web](../clients/web.md)),
Qwen and Gemini skipped (use them through SSH working directories on a machine that has them —
[ssh-remote-execution](ssh-remote-execution.md)), Claude and Model Studio unaffected (bundled CLI),
and Codex installed from `codex-<arch>-unknown-linux-musl.tar.gz` (`apple-darwin` on macOS) of
GitHub release `rust-v<CODEX_CLI_VERSION>` into `/usr/local/bin` (writable or sudo) or
`~/.local/bin` — then set `CODEX_CLI_PATH`, since a systemd service's `PATH` rarely includes it.

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

- "Symlinks point at the wrong place after copying `context/`": `install/doctor.sh --dry-run`
  shows which; `--fix`, `./install.sh` or `shared/scripts/setup-context.sh` re-create missing ones
  (none of them replaces a link that points elsewhere — remove it first).
- A CLI installed under nvm may be invisible to non-interactive shells; locally set `QWEN_CLI_PATH` /
  `GEMINI_CLI_PATH` in `context/.env` (read by `backend/manager/{qwen,gemini}/session.py`) or
  `CODEX_CLI_PATH` (`backend/manager/codex/home.py`), and for SSH remotes see
  [ssh-remote-execution.md](ssh-remote-execution.md#the-nvm-exit-127-trap).
- Windows: symlinks need Developer Mode or Administrator, otherwise junctions + copies; path mangling
  replaces every non-alphanumeric character (`\`, `:`) with `-`, and Qwen lower-cases the path first. A checkout without git symlink support turns the committed
  root `*.md` links into text stubs (`context/AGENTS.md`); `install.ps1` replaces them. Codex's home is
  `%USERPROFILE%\.codex-archie` (no `chmod 700` equivalent), and the backend's Codex binary
  resolution has no Windows entry, so the Codex harness on Windows is untested.
