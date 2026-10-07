---
name: deployment
category: archie/infrastructure
tags: [deploy, jetson, rsync, dist, git, systemd, oauth, credentials, heredoc, server-management]
created: 2026-02-23
modified: 2026-10-06
summary: How to deploy code and web builds from the laptop to the Jetson, verify it, and keep Claude auth working there.
source: curated (consolidated from memory notes assistant/infrastructure/server_hub_project.md, assistant/infrastructure/repo_layout_cutover_2026_10.md, feedback_always_build_both_frontends.md, feedback_stale_web_build_voice_symptom.md, feedback_jetson_main_repo_normal_git.md, feedback_jetson_oauth_token_expiry.md, feedback_verify_auth_under_backend_config_dir.md, feedback_use_systemd_service_for_jetson_backend.md, reference_jetson_access.md; verified against code 2026-10-06)
references:
  - topology.md
  - jetson-server.md
  - context-sync.md
  - ssh-remote-execution.md
  - ../clients/web.md
  - ../clients/legacy-apps.md
  - ../operations/troubleshooting.md
  - ../operations/working-rules.md
---

# Deploying to the Jetson

Code goes to the Jetson through **git**; web builds go through **rsync from the laptop**; the backend
picks up Python changes after a **systemd restart**. `context/` needs no deploy — it is mirrored
continuously by [context-sync](context-sync.md). The runbook with ready-to-paste commands is the
`/server-management` skill (`context/skills/server-management/SKILL.md`); this doc explains the
procedure and the rules behind it.

## What the backend serves

`backend/api/app.py` `_spa_dirs()` maps URLs to build directories (tests:
`backend/tests/test_spa_routes.py`):

| URL | Directory | Built by (on the laptop) |
|---|---|---|
| `/` | `apps/web/dist` | `cd apps/web && npm run build` |
| `/compat/` | `apps/web/dist-compat` | the same `npm run build` (Safari 12 / iOS 12) |
| `/legacy/` | `legacy/frontend/dist` | `cd legacy/frontend && npx vite build` |
| `/legacy_compat/` | `legacy/frontend-compat/dist` | `cd legacy/frontend-compat && npx vite build` |
| `/next/`, `/next-compat/` | — | 307 to `/` and `/compat/` |

The dists are gitignored build outputs: `git pull` on the Jetson never updates them. nginx only
proxies; the backend serves them, and `index.html` is not cached, so a dist update needs no restart —
clients do a hard reload.

## Procedure

Load credentials first, never echo them: `set -a; . /home/rodrigo/assistant/context/.env; set +a`.

### 1. Code via git

Commit and push the branch from the laptop (normally `local`, or the feature branch both machines
are on). Then update the Jetson's main repo. It is a **normal git repo** — nothing rsyncs it — so use
a fast-forward pull on a clean tree, or `fetch` + `reset --hard` when the tree is dirty with nothing
worth keeping:

```bash
sshpass -p "$SERVER_PASSWORD" ssh -o StrictHostKeyChecking=no rodrigo@192.168.0.200 << 'REMOTE'
cd /home/rodrigo/assistant || { echo "NO DIR"; exit 1; }
echo "pwd: $(pwd)"
git rev-parse --abbrev-ref HEAD
git pull --ff-only origin local        # or: git fetch -q origin && git reset --hard origin/<branch>
git status --short && git rev-parse --short HEAD    # expect a clean tree
REMOTE
```

If `backend/requirements*.txt` changed: `.venv/bin/pip install -r backend/requirements.txt` (and the
provider files in use) on the Jetson.

### 2. Web builds via rsync

Build on the laptop (the Jetson cannot run Node 20+). One `npm run build` writes both `dist` and
`dist-compat`; **always deploy the two together**, even if only one client changed. Rebuild and
deploy the legacy apps only when code under `legacy/` changed. On the laptop, take the npm lock and
lower the priority (see [working-rules.md](../operations/working-rules.md)):

```bash
mkdir -p /tmp/archie-locks && touch /tmp/archie-locks/npm.lock
cd /home/rodrigo/assistant/apps/web && flock /tmp/archie-locks/npm.lock nice -n 10 npm run build
# (the build's gate:tokens step needs apps/design-tokens deps: npm install there once)

cd /home/rodrigo/assistant
for d in apps/web/dist apps/web/dist-compat; do      # add legacy/frontend/dist legacy/frontend-compat/dist only if changed
  sshpass -p "$SERVER_PASSWORD" rsync -az --delete -e "ssh -o StrictHostKeyChecking=no" \
    "$d/" "rodrigo@192.168.0.200:/home/rodrigo/assistant/$d/"
done
```

`--delete` is applied **per dist directory** (removes stale hashed assets) — never on a parent
directory. Pull the code first so the target directories exist on the Jetson.

### 3. Restart (only if backend code changed)

```bash
sshpass -p "$SERVER_PASSWORD" ssh -o StrictHostKeyChecking=no rodrigo@192.168.0.200 \
  "echo \"$SERVER_PASSWORD\" | sudo -S systemctl restart agentic-backend.service"
```

Needed for changes under `backend/` or its requirements. Not needed for dist-only or Android-only
changes. Never kill the uvicorn PID by hand.

### 4. Verify

- **HEAD moved *and* the files changed.** `git status --short` must be clean; grep a working-tree
  file for the new code. Don't trust `git log` alone.
- **Dist is current.** Compare `ls apps/web/dist/assets/ apps/web/dist-compat/assets/` on both
  machines (hashed filenames differ = stale), or grep the deployed bundle for a string only the new
  code contains, e.g. `grep -l voice_owner_active /home/rodrigo/assistant/apps/web/dist/assets/*.js`.
  Compare `dist/index.html` mtime with the newest source commit.
- **Backend up.** `sudo systemctl status agentic-backend.service --no-pager`, then
  `journalctl -u agentic-backend.service -n 50 --no-pager`.
- Smoke-test `/`, `/compat/` (and `/legacy/`, `/legacy_compat/` if touched) at
  `https://192.168.0.200/`.

## Rules and why

- **Main repo: normal git. `context/`: `fetch` + `reset --mixed`.** The metadata-only
  `git reset --mixed origin/main` is correct **only** for `context/`, whose working tree rsync has
  already updated ([context-sync.md](context-sync.md#git-on-the-jetsons-context-repo)). On the main
  repo `--mixed` moves HEAD but leaves the old files on disk, and the backend keeps running them. On
  2026-07-20/21 `orchestrator/token_budget.py`, an Android provider file and `orchestrator/prompt.py`
  each survived a `reset --mixed` unchanged; the same habit had left the Jetson's main tree full of
  a stale snapshot (~35 files, 2113 stale deletions) plus an abandoned stash, which a stray
  `git add -A && commit` would have pushed. It was cleaned with `reset --hard` + `stash drop`.
- **Keep the Jetson main tree clean.** If it is unexpectedly dirty, investigate before
  `reset --hard`: a file whose `git hash-object` matches a blob from an old commit is a stale deploy
  snapshot (safe to discard); anything else may be real work. If you scp'd a file in before pulling
  its commit, `git pull` refuses ("local changes would be overwritten") — stash, pull, drop.
- **Compose remote commands as a quoted heredoc.** An inline `ssh host "cd ~/assistant && git …"`
  assembled through several quoting layers can silently lose the `cd`; git then runs in `$HOME`,
  fails with `fatal: not a git repository`, and an `&&` chain swallows it (five attempts in a row on
  2026-07-20). Use `<< 'REMOTE'` (quoted delimiter so `$VAR` and `$(…)` expand remotely), an
  absolute path, a loud `|| { echo NO DIR; exit 1; }` guard, and `echo "pwd: $(pwd)"`. For one-liners
  `git -C /home/rodrigo/assistant …` avoids `cd` entirely.
- **Never build on the Jetson.** Old glibc; and a missing build step there is invisible because the
  Android app keeps working against the same backend. "Voice fails from the web but works from the
  phone" is the signature of a stale `apps/web/dist` (2026-07-25 incident).
- **Old dist paths are dead.** `frontend/dist*` and `_old/…/dist` may linger on the Jetson from
  before 2026-10-05; they are not served and can be deleted.
- Laptop-side heavy builds are serialized with `flock` and `nice`; a reboot wipes `/tmp`, and
  `flock` on a missing lock file does nothing useful — `touch` it first.

## Claude Code authentication on the Jetson

The backend runs `claude` with `CLAUDE_CONFIG_DIR=<repo>/.claude_config` (set by
`shared/scripts/run.sh`). Two credential sources exist:

1. **`CLAUDE_CODE_OAUTH_TOKEN` in `context/.env`** — the current setup. A long-lived token minted
   with `claude setup-token`; the env var takes precedence over any `.credentials.json`. `run.sh`
   exports `context/.env` with `set -a`, so the bundled CLI and the SDK see it; because `context/.env`
   is synced, both machines get it; and `ClaudeSessionManager._write_ssh_wrapper`
   (`backend/manager/claude/session.py`) forwards it to SSH-remote sessions. No refresh, no per-machine
   drift.
2. **`.claude_config/.credentials.json`** — per machine, gitignored, outside `context/`, never
   synced. Separate from `~/.claude/.credentials.json` (the interactive CLI's store, which the
   running CLI keeps refreshed). The Linux installer symlinks the project file to the user-level one
   (`install/linux/install.sh` Step 9); a real copy there goes stale.

Symptom of a credential problem: sessions fail with **401 "Invalid authentication credentials"**,
"OAuth session expired and could not be refreshed", or "Not logged in · Please run /login".

Diagnose:

- Verify **under the backend's config dir**, never with a bare `claude` from a Bash tool (that reads
  the healthy `~/.claude` store and passes while every wrapper session fails):
  `CLAUDE_CONFIG_DIR=/home/rodrigo/assistant/.claude_config claude -p 'reply with exactly: OK' < /dev/null`
  — run it through `run.sh`'s environment (or with `context/.env` sourced) to include the token.
- Check that `CLAUDE_CODE_OAUTH_TOKEN` is set (name only) in `context/.env` on the machine.
- Compare `expiresAt` / `refreshTokenExpiresAt` in both credential files.

Fix:

- Preferred: make sure the token in `context/.env` is valid (re-mint with `claude setup-token` if it
  was revoked or leaked; edit `.env` with an editor tool, never a shell heredoc that echoes the
  secret).
- Stopgap only: back up the target's `.claude_config/.credentials.json`, copy a fresh
  `~/.claude/.credentials.json` from the same machine (or scp from the laptop), `chmod 600`, verify as
  above. **Copying one grant between machines re-arms the failure**: Anthropic OAuth rotates refresh
  tokens, so whichever machine refreshes second holds a dead token — this caused ~7 recurrences
  between 2026-07-20 and 2026-08-24. Each machine should have its own grant or use the env token.
- Restart the backend afterwards (`sudo systemctl restart agentic-backend.service`) — it caches the
  token in memory. Upgrade a badly outdated CLI on the Jetson at the same time.
- Don't `pkill -f "claude setup-token"` over SSH: the pattern matches your own SSH command and kills
  the session (exit 255).

## History

- Before 2026-10-05 the web apps were `frontend/` and `frontend-compat/`, built separately; since the
  cutover one `apps/web` project builds both dists.
- 2026-07-20/21: the `reset --mixed`-on-main-repo incidents and the heredoc rule.
- 2026-07-20 → 2026-08-24: recurring Jetson 401s from copied credentials; 2026-08-24 the long-lived
  `CLAUDE_CODE_OAUTH_TOKEN` in `context/.env` replaced copying.
