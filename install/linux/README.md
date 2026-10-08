# install/linux/ — Linux installer

Per-OS implementation of the Personal Assistant installer for Linux
(Debian / Ubuntu / Fedora / Arch / Jetson Linux).

You normally don't run these scripts directly — the top-level
`./install.sh` and `./install-with-agent.sh` at the project root dispatch
here based on `$OSTYPE`.

## Files

| File | Purpose |
|------|---------|
| `install.sh` | Deterministic installer. Asks two axis questions (session harness + orchestrator backends), then runs every step automatically. |
| `install-with-agent.sh` | Conversational installer. Launches one of the agent CLIs and hands it `INSTALL.md` as instructions; the agent walks the user through the install. |
| `install-prerequisites.sh` | Verifies Python 3.11+, Node 22+ (`NODE_MIN_MAJOR`), npm, and git are present; prints platform-specific install commands for what's missing. Called by `install.sh` Step 1 (with `--no-node` on a backend-only host). |

The shared templates (`AGENTS.md`, `MEMORY.md`, `context.env`,
`assistant_config.json`, `manager.json`, `sync.env`, `cli-runtime/`), the CLI
version pins (`harness-versions.env`) and `doctor.sh` live one level up in
`install/` and are reused across every OS. `install/apple/install.sh` is a copy
of `install.sh` here apart from its header — keep them identical.

## Linux-specific notes

- Symlinks: native, no special setup.
- Package managers supported by the prereq installer's install hints: `apt`
  (Debian / Ubuntu), `dnf` (Fedora), `pacman` (Arch).  Other distros fall
  through to "Download from upstream" messages.
- Node: the prereq installer suggests NodeSource (`deb.nodesource.com`) on
  Debian/Ubuntu.  Using `nvm` / `volta` / `mise` works too — just make sure
  `node` and `npm` are on `$PATH` when `./install.sh` runs.
- No usable Node (missing, or glibc < 2.28 like the Jetson's Ubuntu 18.04,
  where Node 22 cannot run): `install.sh` detects it (or `--no-node`) and does
  a backend-only install — no web-app `npm install` (build `apps/web` elsewhere
  and copy `dist/` + `dist-compat/`), Qwen and Gemini skipped (use them through
  SSH working directories), Codex installed from the static
  `codex-<arch>-unknown-linux-musl.tar.gz` of GitHub release `rust-v<pin>`
  into `/usr/local/bin` (or `~/.local/bin` + `CODEX_CLI_PATH`).

For the full step-by-step recipe (every step `install.sh` performs), see
`INSTALL.md` at the project root.
