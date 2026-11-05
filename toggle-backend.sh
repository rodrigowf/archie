#!/usr/bin/env bash
# Usage: ./toggle-backend.sh
# Description: Toggle this install's backend: stop it if it is running, otherwise start it
# detached (logs in logs/api_<timestamp>.log). Meant for a desktop launcher or a keyboard
# shortcut. Several installs can run side by side: each one only ever touches its own backend,
# and ARCHIE_PORT (default 8765) gives each its own port.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

PORT="${ARCHIE_PORT:-8765}"
PIDFILE="$SCRIPT_DIR/logs/backend.pid"
mkdir -p "$SCRIPT_DIR/logs"

notify() {
    local title="$1"
    local body="$2"
    local urgency="${3:-normal}"
    if command -v notify-send >/dev/null 2>&1; then
        notify-send -u "$urgency" -i "$SCRIPT_DIR/apps/web/public-main/icon-192.png" \
            "$title" "$body" || true
    fi
    echo "[$title] $body"
}

# Detect a running backend. Prefer the PID file; fall back to port + process match.
detect_running_pid() {
    if [ -f "$PIDFILE" ]; then
        local pid
        pid="$(cat "$PIDFILE" 2>/dev/null || true)"
        if [ -n "${pid:-}" ] && kill -0 "$pid" 2>/dev/null; then
            echo "$pid"
            return 0
        fi
        rm -f "$PIDFILE"
    fi
    # Fall back: a uvicorn api.app process of this user running from this install's folder
    # (another install's backend has its own working directory). Only Python processes: a shell
    # whose command line merely mentions the pattern is not a backend.
    local pid
    for pid in $(pgrep -u "$USER" -f 'uvicorn api.app:create_app' || true); do
        case "$(ps -o comm= -p "$pid" 2>/dev/null)" in python*) ;; *) continue ;; esac
        if [ "$(readlink "/proc/$pid/cwd" 2>/dev/null)" = "$SCRIPT_DIR" ]; then
            echo "$pid"
            return 0
        fi
    done
    return 1
}

stop_backend() {
    local pid="$1"
    notify "Archie" "Stopping backend (pid $pid)…"
    # Kill the whole process group so child workers exit too.
    local pgid
    pgid="$(ps -o pgid= "$pid" 2>/dev/null | tr -d ' ')"
    if [ -n "${pgid:-}" ]; then
        kill -TERM -"$pgid" 2>/dev/null || kill -TERM "$pid" 2>/dev/null || true
    else
        kill -TERM "$pid" 2>/dev/null || true
    fi
    # Wait up to 8s for graceful shutdown.
    for _ in $(seq 1 16); do
        if ! kill -0 "$pid" 2>/dev/null; then break; fi
        sleep 0.5
    done
    if kill -0 "$pid" 2>/dev/null; then
        kill -KILL "$pid" 2>/dev/null || true
        sleep 0.5
    fi
    rm -f "$PIDFILE"
    notify "Archie" "Backend stopped."
}

start_backend() {
    [ -s "$HOME/.nvm/nvm.sh" ] && source "$HOME/.nvm/nvm.sh"
    local timestamp
    timestamp="$(date +%Y%m%d_%H%M%S)"
    local logfile="$SCRIPT_DIR/logs/api_${timestamp}.log"
    notify "Archie" "Starting backend on port $PORT…"
    # Detach fully so closing any UI doesn't kill it.
    setsid nohup "$SCRIPT_DIR/shared/scripts/run.sh" \
        -m uvicorn api.app:create_app --factory --host 0.0.0.0 --port "$PORT" \
        >"$logfile" 2>&1 < /dev/null &
    local pid=$!
    echo "$pid" > "$PIDFILE"
    disown "$pid" 2>/dev/null || true
    # Wait for the port to be reachable (up to ~15s).
    local ok=0
    for _ in $(seq 1 30); do
        if (echo > /dev/tcp/127.0.0.1/"$PORT") 2>/dev/null; then
            ok=1
            break
        fi
        if ! kill -0 "$pid" 2>/dev/null; then break; fi
        sleep 0.5
    done
    if [ "$ok" = "1" ]; then
        notify "Archie" "Backend ready at http://localhost:$PORT"
    else
        notify "Archie" "Backend did not come up — see $logfile" critical
    fi
}

if pid="$(detect_running_pid)"; then
    stop_backend "$pid"
else
    start_backend
fi
