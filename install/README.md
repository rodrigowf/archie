# install/ — Fresh Install Templates

This directory holds the **template files** that `install.sh` copies into a
fresh checkout to bootstrap a new installation. Each file is a clean,
user-agnostic starting point — no secrets, no personal context, no machine-
specific paths baked in.

If you're setting up the assistant manually (without `install.sh`), copy these
files into place yourself and edit them. The install script is the
recommended path though — it handles symlinks, axis selection, and
substitutions for you.

## Files

| Template | Copied to | Purpose |
|----------|-----------|---------|
| `AGENTS.md` | `context/AGENTS.md` | Project instructions read by Claude Code (via the `CLAUDE.md` symlink at the project root), Qwen Code (`QWEN.md`), Gemini CLI (`GEMINI.md`) and Codex (`AGENTS.md`). |
| `MEMORY.md` | `context/memory/MEMORY.md` | The shared memory index. Topic files referenced from here live alongside it. |
| `ORCHESTRATOR_MEMORY.md` | `context/memory/ORCHESTRATOR_MEMORY.md` | The orchestrator's private memory, loaded into every orchestrator prompt. On a new install it gives Archie its identity and a summary of how it is built, and tells it to learn about the user over the first conversations (who they are, how Archie should behave, what they want to do with it, their devices, their boundaries). Archie records the answers in this file and in `people/`, then deletes the onboarding section. |
| `ORCHESTRATOR_SCRIPTS.md` | `context/memory/ORCHESTRATOR_SCRIPTS.md` | The orchestrator's `run_script` allowlist. It starts empty and only explains the entry format. Never write a line that starts with `path:` outside an entry: every such line is treated as an allowed script. |
| `context.env` | `context/.env` | API keys and runtime configuration. Comments explain which axis each key belongs to; uncomment and fill in what you need. |
| `assistant_config.json` | `assistant_config.json` (repo root) | Default working directory, provider, and model picked up by the API on first run. Placeholders `@@SCRIPT_DIR@@`, `@@DEFAULT_PROVIDER@@`, `@@DEFAULT_MODEL@@` are substituted at install time. |
| `manager.json` | `.manager.json` (repo root) | Session-manager defaults (model, permission mode, budget caps). |
| `sync.env` | `infra/sync/config.env` | Optional. Configures the `context-sync` systemd service for two-machine deployments. |
| `cli-runtime/<cli>/*` | `.<cli>/*` | Seeds the per-CLI runtime dirs (`.claude/`, `.qwen/`, `.gemini/`) at the project root with default `settings.json` and any other starter files. These dirs are gitignored on disk — the templates here are what gets dropped in on first install. Only the dirs for harnesses the user opts into are seeded. Existing files are never overwritten. `.qwen/settings.json` turns Qwen's managed auto-memory / auto-dream / auto-skill off (its project dir is `context/`, so auto-memory would write into the memory wiki). |
| `cli-runtime/codex-home/config.toml` | `~/.codex-archie/config.toml` | Archie's Codex home config (`project_doc_max_bytes = 131072`, plugins/apps off). Seeded only when missing. |

## Shared tooling (not copied anywhere)

| File | Purpose |
|------|---------|
| `harness-versions.env` | The agent-CLI version pins (`QWEN_CLI_VERSION`, `GEMINI_CLI_VERSION`, `CODEX_CLI_VERSION`) and `NODE_MIN_MAJOR`. Sourced by the bash installers, parsed by the PowerShell ones, compared by `doctor.sh`. Bump a pin here (plus `QWEN_CLI_VERSION` in `backend/manager/qwen/adapter.py` for Qwen). The claude-agent-sdk pin stays in `backend/requirements-claude.txt`. |
| `doctor.sh` | Checks an install per harness (claude, modelstudio, qwen, gemini, codex): CLI present + version vs pin, env keys (names only), auth files (existence only), every symlink below (including `context/memory/archie`), `context/memory/ORCHESTRATOR_MEMORY.md`, the Qwen/Gemini seed settings, the root instruction links and the `context/{skills,scripts,agents}` → `shared/` links. Prints an OK/WARN/FAIL table, exit 1 on any FAIL. `--fix` repairs only symlinks and seed files (installer rules: correct link kept, wrong link warned about, real directory migrated and moved aside to `<name>.bak-<ts>`); `--dry-run` lists what `--fix` would do; `--harness claude,codex` (or `all`) makes those required; `--repo DIR` checks another checkout (copy the script anywhere). Never installs packages or touches auth. bash 3.2+, macOS-safe. The bash installers run it as their last verification step. |

## Links every install needs

| Link | Target | Harness |
|------|--------|---------|
| `CLAUDE.md`, `QWEN.md`, `GEMINI.md`, `AGENTS.md` (repo root) | `context/AGENTS.md` | all (committed in git; re-created if missing) |
| `.claude_config/projects/<path with non-alphanumerics → ->` | `../../context` | claude, modelstudio |
| `.claude_config/skills`, `.claude_config/agents` | `../context/skills`, `../context/agents` | claude, modelstudio |
| `~/.qwen/projects/<same key>`, `~/.qwen/skills` | `<repo>/context`, `<repo>/context/skills` | qwen |
| `~/.gemini/tmp/<label from ~/.gemini/projects.json, else folder name>` | `<repo>/context` | gemini |
| `~/.codex-archie/sessions` | `<repo>/context/codex/sessions` | codex |
| `.agents/skills` (repo) | `../context/skills` | codex (verified on 0.161), gemini, qwen |
| `context/{skills,scripts,agents}/<name>` | `../../shared/<kind>/<name>` | all (`shared/scripts/setup-context.sh`) |
| `context/memory/archie` | `../../docs` | all (new, imported and kept contexts; a junction on Windows without symlink rights) |

## How install.sh uses these

`install.sh` reads the templates from this directory at the appropriate steps:

- **Context bootstrap (new install path)** — `MEMORY.md`, `ORCHESTRATOR_MEMORY.md` and `ORCHESTRATOR_SCRIPTS.md` are copied into `context/memory/`, and `AGENTS.md` into `context/`. An imported context keeps its own files. `context.env` is copied into `context/.env`, and the keys for the axes the user opted into get uncommented so they show up as required.
- **AGENTS.md migration (existing-install path)** — If `context/AGENTS.md` already exists (e.g. legacy `AGENTS.md` at the repo root, or a real `CLAUDE.md` at root), the install script normalises it into `context/AGENTS.md`. The template here is only used for *fresh* installs, never to overwrite.
- **Config files** — `assistant_config.json` and `.manager.json` are copied into the repo root with placeholders substituted (`@@SCRIPT_DIR@@`, `@@DEFAULT_PROVIDER@@`, `@@DEFAULT_MODEL@@`).
- **CLI runtime seeds (Step 3e)** — For each enabled harness (`--with-claude` / `--with-qwen` / `--with-gemini`), files under `cli-runtime/<cli>/` are copied into `.<cli>/` at the project root. Drop additional starter files in here (default permission allowlists, file-filter carve-outs, ignore files) and they'll land on every fresh install. Codex's home config comes from `cli-runtime/codex-home/` (Step 3c2).
- **Agent CLI install + login (Step 7b)** — After npm + Python deps are in, `install.sh` installs each enabled harness CLI via `npm install -g <pkg>@<pin from harness-versions.env>` (if missing), then pauses to walk the user through the first interactive login. `--with-modelstudio` installs no CLI (it runs the Claude Code CLI bundled with claude-agent-sdk) and only checks `DASHSCOPE_API_KEY`. On a host without Node (`--no-node`, auto-detected) Qwen/Gemini are skipped and Codex comes from its static GitHub release binary. `--skip-auth` bypasses this entire step; non-interactive shells (no TTY) skip the login prompt silently but still install the CLI.
- **Verification (Step 12)** — runs `doctor.sh --harness <the chosen ones>` and shows its table.
- **Sync** — `sync.env` stays an opt-in step; copy it manually to `infra/sync/config.env` if you want the systemd sync service.

Any file in this directory is safe to edit if you want to change the default a
fresh install lands on. Keep this directory user-agnostic — personal content
belongs in `context/` (which is gitignored), not here.
