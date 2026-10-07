---
name: repo-layout
category: archie/overview
tags: [repo-layout, public-private, context, shared, legacy, paths, symlinks, migration]
created: 2026-02-23
modified: 2026-10-07
summary: The repo tree today, the public framework vs private context split, the symlink model, and old→new path mapping.
source: curated (consolidated from memory notes assistant/infrastructure/repo_layout_cutover_2026_10.md, assistant/architecture/project-overview.md, context/AGENTS.md; verified against the tree 2026-10-07)
references:
  - archie.md
  - ../architecture/system-overview.md
  - ../architecture/backend.md
  - ../architecture/memory-and-search.md
  - ../clients/web.md
  - ../clients/android.md
  - ../clients/legacy-apps.md
  - ../integrations/skills.md
  - ../infrastructure/installation.md
  - ../infrastructure/context-sync.md
  - ../infrastructure/topology.md
---

# Repo layout

Archie is two git repos on disk: the **public framework** (this repo, `rodrigowf/archie`, cloned at
`~/assistant/`) and the **private context** (`context/`, a separate repo that the framework's
`.gitignore` excludes). Code reaches every path through `backend/utils/paths.py` (`PROJECT_ROOT` =
the repo root).

## The tree

```
~/assistant/                      PUBLIC (rodrigowf/archie)
├── backend/                      Python backend; on PYTHONPATH via run.sh (modules import as api, manager, orchestrator, utils)
│   ├── api/                      FastAPI app, routes, SessionPool, indexers
│   ├── manager/                  Agent-CLI session managers (claude/, qwen/, gemini/), registry, store
│   ├── orchestrator/             Orchestrator agent, tools/, providers/, voice_*.py
│   ├── utils/                    paths.py, search indexes, search service
│   ├── tests/                    pytest suite (reads backend/pyproject.toml)
│   ├── vendor/                   Silero VAD model
│   └── requirements*.txt, pyproject.toml
├── apps/                         Clients
│   ├── web/                      React + Vite web app; builds dist/ (/) and dist-compat/ (/compat/, Safari 12)
│   ├── android/                  Gradle multi-module: :app-main (com.assistant.archie), :app-lite (com.assistant.peripheral), core/*, feature/*
│   ├── android-device/           Companion app com.assistant.device (A300M watchdog, boot, WiFi ADB)
│   ├── browser-extension/        Chrome MV3 extension behind /browser-control (loaded unpacked)
│   ├── design-tokens/            One token source → CSS variables, Kotlin theme, Android XML
│   └── protocol-fixtures/        Client-protocol conformance fixtures (web + Android tests)
├── shared/                       General-purpose, shareable tooling
│   ├── skills/                   e.g. recall, browser-control, debug-app, scaffold-skill, scaffold-agent, wrapper-guide
│   ├── scripts/                  run.sh, setup-context.sh, search.py, search-server.py, index-memory.py, browser_cmd.py, …
│   └── agents/                   Subagent definitions
├── docs/                         These docs (also context/memory/archie/): topic folders + specs/, projects/, history/
├── legacy/                       Pre-2026-10 apps, frozen: frontend/ (/legacy/), frontend-compat/ (/legacy_compat/), android/, notes/
├── infra/sync/                   context-sync service (script, systemd units, installer)
├── install/  install.sh  install.ps1  install-with-agent.*   Installers + templates for a new context
├── start.sh                      Starts the backend in the background (logs to logs/)
├── CLAUDE.md QWEN.md GEMINI.md   Symlinks → context/AGENTS.md (each CLI reads its own name)
├── .claude_config/               Claude CLI config dir (gitignored, per machine); skills → ../context/skills
├── .vscode/settings.json         Keeps VS Code's watcher off heavy dirs (inotify budget)
├── assistant_config.json, .manager.json   Per-machine config (gitignored)
├── index/                        Search indexes, *.sqlite3 (gitignored, rebuildable)
├── logs/                         Backend logs + remote_console.log (gitignored)
├── projects/, plugins/           Claude runtime + heavy media working dirs (gitignored)
└── context/                      PRIVATE — separate git repo (see below)
```

## Public framework vs private context

| Public (this repo) | Private (`context/`) |
|---|---|
| All code: `backend/`, `apps/`, `infra/`, `install/` | Conversations: `*.jsonl` (Claude Code + orchestrator), `chats/` (Qwen, Gemini), `<uuid>/` SDK state, `.titles.json`, `*.config.json` per-session config, `trash/` |
| General skills, scripts, agents: `shared/` | Personal skills, scripts, agents (directly in `context/skills/`, `scripts/`, `agents/`) plus symlinks to `shared/*` |
| Archie's documentation: `docs/` | The memory wiki: `memory/` (people, projects, references, orchestrator memory) |
| | `AGENTS.md` (the project instructions all three CLIs read), `.env`, `secrets/`, `certs/` |
| | `public/` (served at the URL root), `uploads/`, `recordings/`, `evals/` |

Rules of thumb:

- If it would be useful to anyone running Archie, it goes in the public repo (`shared/` for skills,
  scripts and agents; `docs/` for knowledge about Archie itself).
- If it is about Rodrigo, other people, his accounts, credentials or conversations, it goes in
  `context/`. **`docs/` is public** — no secrets, no personal details, no links into `context/`.
- To migrate to a new machine, clone both repos and copy the secrets (`context/.env`,
  `context/secrets/`, `context/certs/`), which are not in git. See
  [installation](../infrastructure/installation.md).

## Symlinks that tie the two together

| Link | Target | Why |
|---|---|---|
| `context/skills/<name>`, `context/scripts/<name>`, `context/agents/<name>` | `../../shared/<kind>/<name>` | One place to find every skill/script/agent; made by `shared/scripts/setup-context.sh` |
| `.claude_config/skills` | `../context/skills` | Claude Code SDK skill discovery |
| `.claude_config/projects/<mangled-path>` | `context/` | The SDK writes session JSONL straight into `context/` |
| `CLAUDE.md`, `QWEN.md`, `GEMINI.md` | `context/AGENTS.md` | One instructions file, three CLIs |
| `context/memory/archie` | `../../docs` | These docs are part of the memory wiki (indexed, searchable, browsable, served at `/memory/archie/…`) while staying versioned with the code |

`context/scripts/run.sh` → `shared/scripts/run.sh` is how every Python script and the backend run:
it runs the venv's Python (`.venv/bin/python`), exports `PYTHONPATH=<repo>/backend` and
`CLAUDE_CONFIG_DIR=<repo>/.claude_config`, and on aarch64 preloads `libgomp.so.1`.

Because `context/` is gitignored by the framework repo, **ripgrep (and the Grep tool) skip it when
searching from the repo root.** Pass an explicit path under `context/` when searching memory,
history or personal skills.

## Old → new paths

The repo was reorganized three times on 2026-10-05. Notes and commits from before then use old
paths:

| Old path | Now |
|---|---|
| `api/`, `manager/`, `orchestrator/`, `utils/`, `tests/`, `vendor/`, `requirements*.txt`, `pyproject.toml` (root) | `backend/<same>` (module names unchanged) |
| `manager/session.py` (before the multi-harness split) | `backend/manager/claude/session.py` |
| `frontend/`, `frontend-compat/`, `android/` written **before** 2026-10-05 (old apps) | `legacy/frontend/`, `legacy/frontend-compat/`, `legacy/android/` |
| `frontend-next/`, `android-next/` (side-by-side rebuild) and `frontend/`, `android/` written **on** 2026-10-05 | `apps/web/`, `apps/android/` |
| `_old/` | `legacy/` |
| `default-skills/`, `default-scripts/`, `default-agents/` | `shared/skills/`, `shared/scripts/`, `shared/agents/` |
| `android-device/`, `browser-extension/` | `apps/android-device/`, `apps/browser-extension/` |
| `design/tokens/`, `shared/protocol-fixtures/` | `apps/design-tokens/`, `apps/protocol-fixtures/` |
| `sync/` | `infra/sync/` |
| `remote_console.log` (root) | `logs/remote_console.log` |
| `HANDOFF-2026-08-25.md`, `PLAN-sync-wakeword-fixes.md` (root), `assets/logo.svg` | `docs/history/`, `docs/assets/` |
| `index/chroma/` | retired 2026-10-06; indexes are `index/*.sqlite3` |
| `docs/frontend-refactor/spec/` | `docs/specs/` (2026-10-07) |
| `docs/frontend-refactor/`, `docs/history-search/` | `docs/projects/frontend-refactor/`, `docs/projects/history-search/` (2026-10-07) |
| Memory notes under `context/memory/assistant/…` | consolidated into `docs/` on 2026-10-07 (old notes recoverable from the context repo's git history) |

URLs did not change in any of the moves (`/`, `/compat/`, `/legacy/`, `/legacy_compat/`).
