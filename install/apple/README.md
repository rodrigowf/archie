# install/apple/ — macOS installer

Per-OS implementation of the Personal Assistant installer for macOS
(Intel and Apple Silicon).

You normally don't run these scripts directly — the top-level
`./install.sh` and `./install-with-agent.sh` at the project root dispatch
here when they detect `OSTYPE=darwin*`.

## Files

| File | Purpose |
|------|---------|
| `install.sh` | Deterministic installer. **Identical to `install/linux/install.sh` apart from its header comment** — every helper (`resolve_path`, `sed_inplace`, `ensure_dir_link`, the static-Codex download) is portable across GNU/BSD userlands and bash 3.2, so port a change by copying the Linux file. |
| `install-with-agent.sh` | Conversational installer. Launches one of the agent CLIs and hands it `INSTALL.md`. bash 3.2-safe (no associative arrays). |
| `install-prerequisites.sh` | Bootstraps Homebrew (if missing), then installs Python 3.12 and Node `NODE_MIN_MAJOR`+ (22, from `install/harness-versions.env`) via brew (Python 3.11+ accepted if already present). Handles both Apple Silicon (`/opt/homebrew`) and Intel (`/usr/local`) prefixes. `--no-node` makes Node optional. |

Shared with every OS: `install/harness-versions.env` (CLI version pins) and
`install/doctor.sh` (harness check / symlink repair — bash 3.2 compatible,
runs on macOS as is).

## macOS-specific notes

- **Homebrew**: the prereq installer offers to install Homebrew via the
  official one-liner if it's not on `$PATH`.  After install, you'll want to
  add `eval "$(brew shellenv)"` to your `~/.zprofile` so brew persists
  across shell sessions.
- **node@22**: the prereq installer uses the `node@22` keg-only formula
  (the major comes from `NODE_MIN_MAJOR`).  The script adds it to `$PATH`
  for the remainder of the prereq run, but `brew link --overwrite --force
  node@22` (or your shell init) is what makes it persist — and what
  `install.sh` itself needs to see it.
- **BSD sed**: macOS ships BSD sed, which needs a backup-suffix argument
  after `-i`.  The installer's `sed_inplace` uses `sed -i.sedbak` and
  deletes the backup, which works on both BSD and GNU sed.
- **bash 3.2**: `/bin/bash` on macOS is 3.2; the installers and
  `doctor.sh` avoid bash-4 features (associative arrays, `mapfile`, `${x,,}`).
- **Codex without npm**: the `--no-node` path installs Codex from the
  `codex-<arch>-apple-darwin.tar.gz` GitHub release asset, like the Linux
  musl build.
- **readlink -f**: pre-Big Sur macOS ships a BSD readlink without `-f`.
  The installer's `resolve_path` helper falls back to `realpath` then
  `python3 -c 'os.path.realpath(...)'` so symlink resolution works
  everywhere.
- **Symlinks**: macOS supports POSIX symlinks natively (same as Linux).
  No special setup required.
- **Xcode Command Line Tools**: provides `git`, `make`, `clang`, etc.
  Install with `xcode-select --install` if missing.

For the full step-by-step recipe, see `INSTALL.md` at the project root.
