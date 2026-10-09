#!/usr/bin/env bash
# context-sync.sh — Bidirectional real-time sync for the assistant context folder.
#
# Uses inotifywait to detect file changes and rsync over SSH to push them to
# the remote machine immediately. Handles the remote being offline gracefully.
#
# Usage: context-sync.sh [--config /path/to/config]
# Normally started by the systemd service (context-sync.service).

set -euo pipefail

# ── Load config ──────────────────────────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONFIG_FILE="${1:-${SCRIPT_DIR}/config.env}"

if [[ ! -f "$CONFIG_FILE" ]]; then
  echo "ERROR: Config file not found: $CONFIG_FILE" >&2
  echo "Copy install/sync.env to infra/sync/config.env and fill in your values." >&2
  exit 1
fi

# shellcheck source=/dev/null
source "$CONFIG_FILE"

# ── Required config variables ────────────────────────────────────────────────
: "${LOCAL_DIR:?config: LOCAL_DIR must be set}"
: "${REMOTE_HOST:?config: REMOTE_HOST must be set}"
: "${REMOTE_USER:?config: REMOTE_USER must be set}"
: "${REMOTE_DIR:?config: REMOTE_DIR must be set}"
: "${SSH_KEY:?config: SSH_KEY must be set}"

# ── Defaults ─────────────────────────────────────────────────────────────────
DEBOUNCE_SECONDS="${DEBOUNCE_SECONDS:-2}"
RETRY_INTERVAL="${RETRY_INTERVAL:-30}"
# How long a deleted path stays "tombstoned": pushes skip it and a stale copy
# that comes back (older than the delete) is removed again. See "Deletions".
TOMBSTONE_SECONDS="${TOMBSTONE_SECONDS:-120}"
# Pause before exiting when inotifywait can't watch a directory, so a host that
# is still out of watches restarts every ~45 s instead of every 15 s.
WATCH_FAIL_DELAY="${WATCH_FAIL_DELAY:-30}"
# Tombstone files: ours, and the remote's (relative to the remote $HOME).
STATE_DIR="${STATE_DIR:-$HOME/.local/state/context-sync}"
REMOTE_STATE_DIR="${REMOTE_STATE_DIR:-.local/state/context-sync}"
LOG_TAG="${LOG_TAG:-context-sync}"
SCRIPT_PID=$$

TOMBSTONES="$STATE_DIR/tombstones"
mkdir -p "$STATE_DIR"
touch "$TOMBSTONES"

# ── Helpers ──────────────────────────────────────────────────────────────────
log() { echo "[$(date '+%H:%M:%S')] $*" | systemd-cat -t "$LOG_TAG" -p info 2>/dev/null || echo "[$(date '+%H:%M:%S')] $*"; }
err() { echo "[$(date '+%H:%M:%S')] ERROR: $*" | systemd-cat -t "$LOG_TAG" -p err 2>/dev/null || echo "[$(date '+%H:%M:%S')] ERROR: $*" >&2; }

SSH_OPTS="-o StrictHostKeyChecking=no -o ConnectTimeout=5 -o BatchMode=yes -i $SSH_KEY"

# Files/patterns to sync (exclude git internals, temp files, conflict files)
RSYNC_EXCLUDES=(
  --exclude='.git/'
  --exclude='.stfolder'
  --exclude='.stignore'
  --exclude='.stversions/'
  --exclude='*.sync-conflict-*'
  --exclude='.syncthing.*.tmp'
  --exclude='*.tmp'
  --exclude='.DS_Store'
)

# "a, b, c (+N more)" for log lines.
summarize() {
  local -a items=("$@")
  local n=${#items[@]} shown
  (( n == 0 )) && return 0
  shown=$(printf '%s, ' "${items[@]:0:3}")
  shown=${shown%, }
  (( n > 3 )) && shown+=" (+$((n - 3)) more)"
  printf '%s' "$shown"
}

# ── Tombstones ───────────────────────────────────────────────────────────────
# One "<epoch>\t<relative path>" line per deleted path. Written by this side when
# it deletes something, and by the other side when it deletes something here.

# Drop expired tombstones. Called once per batch (not on every read), so it
# rarely overlaps with the other side appending to the file.
prune_tombstones() {
  local cutoff=$(( $(date +%s) - TOMBSTONE_SECONDS )) tmp="$TOMBSTONES.$$"
  awk -F'\t' -v c="$cutoff" '$1 >= c' "$TOMBSTONES" > "$tmp" 2>/dev/null && mv -f "$tmp" "$TOMBSTONES"
}

# Print "<epoch>\t<path>" for tombstones younger than TOMBSTONE_SECONDS.
active_tombstones() {
  local cutoff=$(( $(date +%s) - TOMBSTONE_SECONDS ))
  awk -F'\t' -v c="$cutoff" '$1 >= c' "$TOMBSTONES" 2>/dev/null || true
}

add_tombstones() {
  local now p
  now=$(date +%s)
  for p in "$@"; do printf '%s\t%s\n' "$now" "$p"; done >> "$TOMBSTONES"
}

# Epoch of the youngest tombstone covering a path (the path itself or a parent
# directory), or nothing.
tombstone_time() {
  local rel="$1"
  active_tombstones | awk -F'\t' -v p="$rel" \
    'p == $2 || index(p, $2 "/") == 1 { if ($1 > t) t = $1 } END { if (t) print t }'
}

# rsync exclude patterns for the active tombstones (anchored, wildcards
# escaped). A path re-created here since its delete (mtime at or after the
# tombstone) is a new file and is sent.
tombstone_excludes() {
  local t p mtime
  while IFS=$'\t' read -r t p; do
    [[ -z "$p" ]] && continue
    mtime=$(stat -c %Y -- "${LOCAL_DIR%/}/$p" 2>/dev/null || echo 0)
    (( mtime >= t )) && continue
    printf '/%s\n' "$(sed 's/[][*?\\]/\\&/g' <<< "$p")"
  done < <(active_tombstones)
}

# ── rsync / ssh ──────────────────────────────────────────────────────────────
PUSHED=()

rsync_to_remote() {
  # Push file contents only. Deletions are handled out-of-band by
  # remote_delete_paths so we never use rsync's --delete (which would race with
  # files the other side just created and not yet pushed to us). Tombstoned
  # paths are skipped so a file deleted on the other side moments ago is not
  # sent back. Sets PUSHED to the files rsync transferred.
  local excludes out rc
  excludes=$(mktemp)
  tombstone_excludes > "$excludes"
  set +e
  out=$(rsync -az --update --out-format='%n' \
    "${RSYNC_EXCLUDES[@]}" \
    --exclude-from="$excludes" \
    -e "ssh $SSH_OPTS" \
    "$LOCAL_DIR/" \
    "${REMOTE_USER}@${REMOTE_HOST}:${REMOTE_DIR}/" 2>&1)
  rc=$?
  set -e
  rm -f "$excludes"
  if (( rc != 0 )); then
    err "rsync failed (exit $rc): $(tail -n 2 <<< "$out" | tr '\n' ' ')"
    return "$rc"
  fi
  mapfile -t PUSHED < <(grep -v '/$' <<< "$out" | grep -v '^$' || true)
}

# Delete a specific set of paths on the remote. Each entry is relative to
# $LOCAL_DIR / $REMOTE_DIR. The remote first records them as tombstones (so its
# own pushes skip them and it doesn't echo the delete back), then rm -rfs each
# one, which handles both files and directories (inotify reports ISDIR on
# directory deletes/renames).
remote_delete_paths() {
  local -a paths=("$@")
  [[ ${#paths[@]} -eq 0 ]] && return 0
  local now p
  now=$(date +%s)
  for p in "${paths[@]}"; do printf '%s\t%s\n' "$now" "$p"; done | \
    ssh $SSH_OPTS "${REMOTE_USER}@${REMOTE_HOST}" \
      "mkdir -p ~/'${REMOTE_STATE_DIR}' && cat >> ~/'${REMOTE_STATE_DIR}'/tombstones"
  # NUL-delimited list into a remote xargs: tolerates spaces/newlines in paths;
  # the cd guarantees we never rm outside $REMOTE_DIR even if a path somehow got
  # through as absolute.
  printf '%s\0' "${paths[@]}" | \
    ssh $SSH_OPTS "${REMOTE_USER}@${REMOTE_HOST}" \
      "cd '${REMOTE_DIR}' && xargs -0 -r -I{} rm -rf -- './{}'"
}

remote_reachable() {
  ssh $SSH_OPTS "${REMOTE_USER}@${REMOTE_HOST}" true 2>/dev/null
}

# ── Initial sync on startup ───────────────────────────────────────────────────
log "Starting context-sync: $LOCAL_DIR → ${REMOTE_USER}@${REMOTE_HOST}:${REMOTE_DIR}"
log "Debounce: ${DEBOUNCE_SECONDS}s, Retry interval: ${RETRY_INTERVAL}s, tombstones: ${TOMBSTONE_SECONDS}s"

# Push everything once when the service starts (catches the offline period).
# Content only: the other machine is live (its backend writes all the time), so
# an rsync --delete here would remove whatever it created that we haven't
# received yet. A delete made while this service was down is not replayed.
while ! remote_reachable; do
  log "Remote not reachable, waiting ${RETRY_INTERVAL}s..."
  sleep "$RETRY_INTERVAL"
done
log "Initial sync..."
if rsync_to_remote; then
  log "Initial sync complete (${#PUSHED[@]} file(s) pushed$([[ ${#PUSHED[@]} -gt 0 ]] && printf ': %s' "$(summarize "${PUSHED[@]}")"))."
else
  err "Initial sync failed, continuing anyway."
fi

# ── Watch loop ────────────────────────────────────────────────────────────────
# inotifywait monitors recursively and outputs one event per line. It honours only the LAST
# --exclude, so all exclusions are one alternation.
# We batch events with a debounce: wait DEBOUNCE_SECONDS after the last event
# before triggering rsync (avoids syncing mid-write during streaming responses).
#
# Deletions: instead of running rsync --delete (which races with files the
# other side just created and not yet pushed to us), we collect the exact set of
# paths that were deleted locally during the debounce window and rm only those
# on the remote after the content push. Each deleted path is also tombstoned on
# both sides for TOMBSTONE_SECONDS:
#   - pushes skip tombstoned paths, so neither side sends a deleted file back;
#   - a tombstoned path that reappears here with an mtime older than the delete
#     is a stale copy from a push that was already in flight; it is removed again;
#   - a local delete of a path the remote already tombstoned (the remote deleted
#     it here) is not echoed back.
#
# Watch failures: inotifywait never retries a directory it couldn't watch (for
# example when the user's inotify watches ran out), so that directory would stay
# blind until a restart. On such an error we exit with a failure and let systemd
# restart us with a fresh, complete set of watches.

# Parse one inotify line of form "<timestamp> <EVENT[,EVENT...]> <fullpath>".
# Sets parse_event / parse_path / parse_isdir as globals. The path may contain
# spaces, so we extract fields 1 and 2 with parameter expansion and treat the
# remainder as the path.
parse_event_line() {
  local rest="$1"
  rest="${rest#* }"           # drop timestamp
  parse_event="${rest%% *}"   # event field
  parse_path="${rest#* }"     # everything after the event field
  case ",${parse_event}," in
    *,ISDIR,*|*ISDIR,*|*,ISDIR*) parse_isdir=1 ;;
    *) parse_isdir=0 ;;
  esac
}

# Returns 0 if the event field contains DELETE or MOVED_FROM.
is_delete_event() {
  case "$1" in
    *DELETE*|*MOVED_FROM*) return 0 ;;
    *) return 1 ;;
  esac
}

# Convert an absolute path under $LOCAL_DIR into a path relative to $LOCAL_DIR.
# Echoes nothing if the path is not under $LOCAL_DIR (shouldn't happen).
relative_to_local() {
  local abs="$1"
  local base="${LOCAL_DIR%/}/"
  if [[ "$abs" == "$base"* ]]; then
    printf '%s' "${abs#"$base"}"
  fi
}

# True when rel is the temp file of a finished rsync transfer: rsync writes
# "<dir>/.<name>.XXXXXX" and renames it to "<dir>/<name>", so the MOVED_FROM of
# the temp name is not a deletion worth replicating.
is_rsync_temp() {
  local rel="$1" dir base
  base="${rel##*/}"
  [[ "$rel" == */* ]] && dir="${rel%/*}/" || dir=""
  [[ "$base" =~ ^\.(.+)\.[A-Za-z0-9]{6}$ ]] || return 1
  [[ -e "${LOCAL_DIR%/}/${dir}${BASH_REMATCH[1]}" ]]
}

# Record one event in the batch arrays.
collect_event() {
  local rel
  parse_event_line "$1"
  rel=$(relative_to_local "$parse_path")
  [[ -z "$rel" ]] && return 0
  if is_delete_event "$parse_event"; then
    DELETED_PATHS+=("$rel")
  else
    CHANGED_PATHS+=("$rel")
  fi
}

# stderr of inotifywait: log it; a directory it couldn't watch means a restart.
watch_errors() {
  local e restarting=0
  while IFS= read -r e; do
    case "$e" in
      "Setting up watches"*|"Watches established"*) continue ;;
    esac
    err "inotifywait: $e"
    if (( ! restarting )) && [[ "$e" == *"Couldn't watch"* || "$e" == *"upper limit on inotify watches"* ]]; then
      restarting=1
      err "A directory is not being watched; restarting in ${WATCH_FAIL_DELAY}s (check fs.inotify.max_user_watches)."
      # SIGUSR1's default action ends the script with a failure status, so
      # systemd (Restart=on-failure) starts it again and kills the leftovers.
      ( sleep "$WATCH_FAIL_DELAY"; kill -USR1 "$SCRIPT_PID" ) &
    fi
  done
}

set +e
inotifywait \
  --monitor \
  --recursive \
  --format '%T %e %w%f' \
  --timefmt '%s' \
  --event close_write,moved_to,moved_from,delete,create \
  --exclude '(/\.git/|\.sync-conflict-|\.syncthing\.|\.stfolder|\.tmp$)' \
  "$LOCAL_DIR" 2> >(watch_errors) | \
while IFS= read -r line; do
  prune_tombstones
  DELETED_PATHS=()
  CHANGED_PATHS=()
  collect_event "$line"

  # Drain any additional queued events within the debounce window.
  while IFS= read -r -t "$DEBOUNCE_SECONDS" extra; do
    collect_event "$extra"
  done

  # 1) Stale copies: a tombstoned path that reappeared with an mtime older than
  #    its delete came from a push that was already in flight. Remove it again.
  RESURRECTED=()
  for rel in "${CHANGED_PATHS[@]}"; do
    abs="${LOCAL_DIR%/}/$rel"
    [[ -e "$abs" ]] || continue
    t=$(tombstone_time "$rel")
    [[ -z "$t" ]] && continue
    mtime=$(stat -c %Y "$abs" 2>/dev/null || echo 0)
    if (( mtime < t )); then
      rm -rf -- "$abs" && RESURRECTED+=("$rel")
    fi
  done
  (( ${#RESURRECTED[@]} > 0 )) && log "Removed ${#RESURRECTED[@]} stale cop(ies) of deleted path(s): $(summarize "${RESURRECTED[@]}")"

  # 2) Confirm deletions against the live filesystem. A MOVED_FROM during an
  #    atomic-rename (e.g. recorder rotating tempfiles) looks identical to a
  #    delete but the path reappears under the same name once the rename
  #    completes; replicating that as a remote rm races with the rsync push
  #    and can wipe the just-renamed file on the remote. Only keep paths that
  #    are genuinely gone locally after the debounce window closes, that aren't
  #    rsync temp files, and that the remote didn't delete itself (tombstoned
  #    before our delete: don't echo it back).
  CONFIRMED_DELETES=()
  for rel in "${DELETED_PATHS[@]}"; do
    [[ -e "${LOCAL_DIR%/}/$rel" ]] && continue
    is_rsync_temp "$rel" && continue
    [[ -n "$(tombstone_time "$rel")" ]] && continue
    CONFIRMED_DELETES+=("$rel")
  done
  (( ${#CONFIRMED_DELETES[@]} > 0 )) && add_tombstones "${CONFIRMED_DELETES[@]}"

  if remote_reachable; then
    # 3) Push content first (no --delete). A file the remote already has but we
    #    just modified gets updated; new files get created.
    if ! rsync_to_remote; then
      err "Sync failed after change."
      continue
    fi
    msg="Synced after change"
    (( ${#PUSHED[@]} > 0 )) && msg+=": pushed ${#PUSHED[@]} ($(summarize "${PUSHED[@]}"))"
    # 4) Then apply the per-path deletions we actually observed locally.
    if (( ${#CONFIRMED_DELETES[@]} > 0 )); then
      if remote_delete_paths "${CONFIRMED_DELETES[@]}" 2>/dev/null; then
        log "$msg; removed on remote: $(summarize "${CONFIRMED_DELETES[@]}")."
      else
        err "$msg, but remote deletion failed for: $(summarize "${CONFIRMED_DELETES[@]}")."
      fi
    else
      log "$msg."
    fi
  else
    log "Remote offline, skipping sync (will retry on next event or restart)."
  fi
done
rc=$?
set -e

# inotifywait only exits on a fatal error (typically: out of inotify watches at
# startup). Pause, then fail so systemd restarts us.
err "inotifywait stopped (exit $rc); restarting in ${WATCH_FAIL_DELAY}s."
sleep "$WATCH_FAIL_DELAY"
exit 1
