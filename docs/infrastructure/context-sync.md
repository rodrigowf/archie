---
name: context-sync
category: archie/infrastructure
tags: [context-sync, rsync, inotifywait, systemd, git, delete-gating, tombstones, inotify, vscode, large-files]
created: 2026-04-17
modified: 2026-10-09
summary: The context-sync service that mirrors context/ between laptop and Jetson — design, install, delete gating, git rule, pitfalls.
source: curated (consolidated from memory notes assistant/infrastructure/server_hub_project.md, project_context_sync_delete_gating.md, feedback_jetson_main_repo_normal_git.md, assistant/utilities/review_artifacts_location.md, projects/video-editing/large_assets_outside_context.md, assistant/infrastructure/repo_layout_cutover_2026_10.md; verified against infra/sync/ 2026-10-06)
references:
  - topology.md
  - deployment.md
  - jetson-server.md
  - installation.md
  - ../operations/troubleshooting.md
  - ../operations/working-rules.md
---

# context-sync

`context-sync` keeps the private `context/` folder (conversations, memory, skills, scripts, `.env`,
secrets, `public/`) identical on the laptop and the Jetson in near real time. It is a bash script
run as a **systemd user service on both machines**; each side watches its own `context/` and pushes
changes to the other. Code is not synced this way — see [topology.md](topology.md).

| File | Role |
|---|---|
| `infra/sync/context-sync.sh` | The service: initial sync, then inotify watch loop |
| `infra/sync/context-sync.service` | User unit, the same on both machines (`ExecStart=%h/assistant/infra/sync/context-sync.sh %h/assistant/infra/sync/config.env`, `Restart=on-failure`, `RestartSec=10s`, `ExecStartPre=/bin/sleep 5`) |
| `infra/sync/install.sh` | Checks deps, requires `config.env`, copies the unit to `~/.config/systemd/user/` with `%h` expanded, enables and starts it |
| `install/sync.env` | Template for `config.env` |
| `infra/sync/config.env` | **Gitignored**, per machine: which machine is the remote |
| `infra/sync/config.jetson.env` | Tracked Jetson-direction config (copy to `config.env` on the Jetson) |
| `infra/sync/README.md` | Operator README |

## Configuration

`config.env` variables (all required except the last two):

| Variable | Meaning |
|---|---|
| `LOCAL_DIR` | Local `context/` (absolute, no trailing slash) |
| `REMOTE_HOST`, `REMOTE_USER` | The other machine (laptop config → `192.168.0.200`; Jetson config → `192.168.0.28`) |
| `REMOTE_DIR` | Remote `context/` — same path on both machines |
| `SSH_KEY` | Passphrase-less private key authorized on the other side |
| `DEBOUNCE_SECONDS` | Default `2` |
| `RETRY_INTERVAL` | Default `30` — wait between reachability checks at startup |
| `TOMBSTONE_SECONDS` | Default `120` — how long a deleted path stays tombstoned (see step 5) |
| `WATCH_FAIL_DELAY` | Default `30` — pause before exiting when a directory can't be watched |
| `STATE_DIR`, `REMOTE_STATE_DIR` | Tombstone files; default `~/.local/state/context-sync` on both sides |
| `LOG_TAG` | Journal tag, default `context-sync` (tests use another tag) |

## How it works

1. **Startup.** Waits until the remote answers (`ssh … true`, retrying every `RETRY_INTERVAL`), then
   pushes everything once with `rsync -az --update` to catch up on anything missed while offline.
   **No `--delete`, not even here** (since 2026-10-09): the other machine is live — the Jetson's
   backend writes all the time — so a startup `--delete` would remove whatever it created that this
   side hadn't received yet. A delete made while the service was down is therefore not replayed.
2. **Watch.** `inotifywait --monitor --recursive` on `LOCAL_DIR` for
   `close_write,moved_to,moved_from,delete,create`, excluding `/.git/`, sync-conflict and Syncthing
   files, `.stfolder` and `*.tmp`. inotifywait honours only its last `--exclude`, so all exclusions
   are one regex alternation.
3. **Debounce.** After the first event it keeps draining events until `DEBOUNCE_SECONDS` pass with
   none, so a streaming JSONL write becomes one sync.
4. **Push content.** `rsync -az --update` **without** `--delete` (rsync excludes: `.git/`,
   Syncthing artifacts, `*.tmp`, `.DS_Store`, and the active tombstones). `--update` skips files that
   are newer on the receiver, which together with both sides pushing gives last-write-wins. The log
   line names what was pushed (`Synced after change: pushed 2 (a.md, b.json)`).
5. **Apply deletions per path, with tombstones.** Every `DELETE` / `MOVED_FROM` path seen during the
   window is collected; after the window, only paths that are **really gone locally** are kept (an
   atomic rename fires `MOVED_FROM` for a file that reappears under the same name), minus rsync's own
   temp files (`.<name>.XXXXXX` whose `<name>` now exists). Each kept path is written as a
   *tombstone* (`<epoch>\t<path>` in `~/.local/state/context-sync/tombstones`) on **both** machines,
   then removed with `rm -rf` under `REMOTE_DIR`. For `TOMBSTONE_SECONDS`:
   - pushes on either side skip tombstoned paths, so neither machine sends a deleted file back;
   - a tombstoned path that reappears with an mtime **older** than its delete is a stale copy from a
     push that was already in flight; it is removed again (`Removed 1 stale cop(ies) …`);
   - a local delete of a path the other side already tombstoned (it deleted it here) is not echoed
     back, which ended a delete ping-pong between the machines;
   - a path re-created on purpose (mtime at or after the delete) is a new file and syncs normally.
6. **Remote offline.** The change is skipped and logged; the next event or restart pushes it.
7. **Watch failures restart the service.** `inotifywait` never retries a directory it couldn't watch
   (out of inotify watches, permissions), so that directory stayed blind — no syncs triggered from
   it, and deletes inside it never reached the other machine. Now any `Couldn't watch …` /
   `upper limit on inotify watches` line is logged with "A directory is not being watched;
   restarting in 30s", and the script exits with a failure (SIGUSR1) so systemd
   (`Restart=on-failure`) starts it again with a complete set of watches.

`inotifywait`'s own stderr (other than the "Setting up watches" banners) goes to the journal as
`inotifywait: …` errors, so a watch-limit failure is visible in `journalctl -t context-sync`.

### Why deletes are per-path (never `rsync --delete` on incremental syncs)

With `--delete` on every event both sides race: A creates `foo.md` and starts pushing; meanwhile any
event on B triggers B → A with `--delete`, which removes `foo.md` on A before B ever received it —
new files vanished. Gating `--delete` on seeing a delete event did not help, because Claude Code's
JSONL writer and most editors write via `tmp → rename`, so almost every burst contains a
`MOVED_FROM`. The fix (2026-05-19) dropped `--delete` semantics entirely: a path is deleted remotely
only because *this* machine saw it deleted, so a file the other side just created can never be on
the list. Trade-off: a deletion made while the service is down propagates only at the next restart's
full sync — resurrected old files are far less harmful than vanished new ones. **Don't "simplify"
this back to `rsync --delete`.**

## Install

On each machine (`sudo apt install inotify-tools rsync openssh-client` first; passwordless SSH must
work in both directions):

```bash
cd ~/assistant
cp install/sync.env infra/sync/config.env      # laptop: fill in; Jetson: cp infra/sync/config.jetson.env infra/sync/config.env
bash infra/sync/install.sh
```

User units only start at boot with lingering enabled (`sudo loginctl enable-linger rodrigo`). After
moving `infra/sync/` (as in the 2026-10-05 reorganization), copy `config.env` into the new folder
and re-run `install.sh` on both machines so the installed unit points at the new path — `git pull`
does not bring `config.env`.

## Operating it

```bash
systemctl --user status context-sync
journalctl --user -u context-sync -f          # "Synced after change (rm N path(s) on remote)."
systemctl --user restart context-sync
systemctl --user stop context-sync            # e.g. during a large render inside context/
```

On the Jetson, run the same commands over SSH (`ssh rodrigo@192.168.0.200 "systemctl --user status context-sync"`).

## Git on the Jetson's context repo

`context/` is also a git repo (`assistant-context`, branch `main`) for versioning. Commit and push
from the laptop. **On the Jetson, never `git pull` the context repo**: rsync keeps its working tree
current but not its `.git/`, so the tree always looks dirty to git; `pull` refuses, and
stash/checkout/pull-rebase rewrite files that rsync then echoes back to the laptop. Use a
metadata-only reset:

```bash
sshpass -p "$SERVER_PASSWORD" ssh -o StrictHostKeyChecking=no rodrigo@192.168.0.200 << 'REMOTE'
cd /home/rodrigo/assistant/context || { echo "NO DIR"; exit 1; }
git fetch -q origin && git reset --mixed origin/main
git status --short
REMOTE
```

`reset --mixed` writes only `.git/HEAD` and `.git/index`; `.git/` is excluded from both inotify and
rsync, so the reset causes no sync traffic, and the working tree (already matching via rsync) is
untouched. After a long offline gap, first hash-compare each dirty file against `origin/main`
(`sha256sum "$f"` vs `git show origin/main:"$f" | sha256sum`); any difference means rsync is still
propagating or the Jetson has real local edits — investigate before resetting.

**This rule is for `context/` only.** The main `~/assistant` repo is not rsynced and uses normal git
([deployment.md](deployment.md#rules-and-why)); `reset --mixed` there leaves stale code on disk.

## Pitfalls

- **inotify budget.** Watches are a per-user limit (`fs.inotify.max_user_watches`) shared by every
  watcher. The laptop's is raised to 524,288 in `/etc/sysctl.d/60-inotify.conf` (2026-10-09; it
  was 65,536 and ran out again when a VS Code instance with a large JS project open held ~51k
  watches — five `context/` directories created meanwhile went unwatched; global
  `files.watcherExclude` for `node_modules`/build dirs is set in the user's VS Code settings).
  Earlier: On 2026-10-05 VS Code, open on the repo, used almost all of it
  (node_modules, Gradle build dirs, the index, `legacy/`), so `inotifywait --recursive` could not
  start and the service crash-looped for ~3.5 hours (`Initial sync complete.` then
  `status=1/FAILURE` every ~15 s). The checked-in `.vscode/settings.json` sets
  `files.watcherExclude` for those dirs and the bulky `context/` subtrees (`*.jsonl`, `chats/`,
  `trash/`, `recordings/`, `projects/`); reload the VS Code window after changing it. Raising the
  sysctl is the alternative. Count watches per process:
  `for p in /proc/[0-9]*; do n=$(cat $p/fdinfo/* 2>/dev/null | grep -c '^inotify'); [ "$n" -gt 0 ] && echo "$n $(cat $p/comm)"; done | sort -rn | head`
  — a healthy `inotifywait` on `context/` holds a few hundred.
- **Large binaries.** Everything under `context/` is mirrored, including paths the context repo
  gitignores (`recordings/`, `/projects/`, media subfolders of `memory/projects/<name>/`,
  `public/memory/`). Gitignoring a folder keeps it out of git, not out of rsync. Multi-GB asset
  projects belong in the repo-root `projects/<name>/` (laptop only, not synced): on 2026-06-12 a
  12 GB video project under `context/projects/` filled the Jetson disk with rsync partials
  (contributing to a crash), aborted ffmpeg `+faststart` encodes mid-rename, and replaced hardlinks
  with symlinks in a Remotion `public/` folder. If you must work on large files inside `context/`,
  stop the service for the duration. Review artifacts (renders, previews) should be small —
  downscale to tens of MB before copying into `context/`.
- **Hardlinks don't survive.** rsync without `-H` turns a hardlinked pair into two copies on the
  other machine (disk doubles there) — fine for small files only.
- **Conflicts.** Last write wins; there is no merge. Avoid editing the same file on both machines
  within a couple of seconds. In practice only one machine writes a given session at a time.
- **The JSONL symlink.** On both machines `.claude_config/projects/-home-rodrigo-assistant` is a
  symlink to `../../context`, which is what makes wrapper session JSONLs land in the synced tree.

## History

- 2026-04: `inotifywait` + `rsync` service introduced (replacing Syncthing), running on both machines.
- 2026-05-17: first delete-gating attempt (gate `--delete` on delete events) — did not work.
- 2026-05-19: per-path observed deletes; incremental pushes never use `--delete`.
- 2026-06-06: `reset --mixed` procedure for the Jetson's context repo confirmed (9- and 1-commit gaps,
  zero rsync echo).
- 2026-10-05: moved from `sync/` to `infra/sync/`; VS Code inotify crash-loop and the
  `.vscode/settings.json` excludes; inotifywait errors now reach the journal.
- 2026-10-07: removed the unused duplicate `context-sync.jetson.service` (both machines install
  `context-sync.service`); the README no longer claims `config.env` ships configured.
- 2026-10-09: no `--delete` at startup; tombstones (no resurrected deletes, no delete ping-pong);
  restart on unwatched directories; rsync temp names ignored; pushed/deleted paths logged; laptop
  `max_user_watches` raised to 524,288.
