#!/bin/bash
# tv_setup.sh — provision a Fire TV / Android TV for Archie (spec 15 §8). Idempotent.
#
# Installs Archie TV + the TvServerHub launcher, grants their permissions and app-ops, enables the
# launcher's key watcher (accessibility, appended — never clobbers other services), gives the
# remote's mic button to Archie by disabling Alexa's voice UI (com.amazon.vizzini), compiles both
# apps with their profiles, and prints a verification table.
#
# Usage:
#   shared/scripts/tv_setup.sh [--serial IP:PORT] [--archie-apk PATH] [--hub-apk PATH]
#                              [--no-install] [--keep-alexa]
#   shared/scripts/tv_setup.sh --restore-alexa [--serial IP:PORT]   # give the mic button back to Alexa
#   shared/scripts/tv_setup.sh --check [--serial IP:PORT]           # verification table only
#
# The TV is found by --serial, else $TV_SERIAL, else the TvServerHub discovery script (by MAC).
# APKs default to the newest Archie TV release/debug build in this repo and TvServerHub's debug build.
set -u

REPO="$(cd "$(dirname "$(readlink -f "$0")")/../.." && pwd)"
ARCHIE_PKG=com.assistant.archie.tv
HUB_PKG=com.example.tvserverhub
HUB_A11Y="$HUB_PKG/.KeyWatchService"
HUB_A11Y_LONG="$HUB_PKG/$HUB_PKG.KeyWatchService"   # same service, the form Settings may write
PROBE_A11Y="com.assistant.tvprobe/.KeyWatchService"
PROBE_A11Y_LONG="com.assistant.tvprobe/com.assistant.tvprobe.KeyWatchService"
REVERSE_PORT=tcp:8777   # the only adb reverse this script cleans up (others belong to someone else)
ALEXA_UI=com.amazon.vizzini
DISCOVER="$HOME/AndroidStudioProjects/TvServerHub/scripts/discover-firetv.sh"
HUB_APK_DEFAULT="$HOME/AndroidStudioProjects/TvServerHub/app/build/outputs/apk/debug/app-debug.apk"

serial="${TV_SERIAL:-}"; archie_apk=""; hub_apk=""; install=1; alexa=disable; mode=setup
usage_error() { echo "$1 (see --help)" >&2; exit 2; }
while [ $# -gt 0 ]; do
  case "$1" in
    --serial|--archie-apk|--hub-apk)
      { [ $# -ge 2 ] && [ -n "$2" ] && [ "${2#--}" = "$2" ]; } || usage_error "$1 needs a value"
      case "$1" in
        --serial) serial="$2" ;;
        --archie-apk) archie_apk="$2" ;;
        --hub-apk) hub_apk="$2" ;;
      esac
      shift 2 ;;
    --no-install) install=0; shift ;;
    --keep-alexa) alexa=keep; shift ;;
    --restore-alexa) mode=restore; shift ;;
    --check) mode=check; shift ;;
    -h|--help) sed -n '2,17p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) usage_error "unknown option: $1" ;;
  esac
done

say() { printf '\033[1m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[33m!!\033[0m %s\n' "$*" >&2; }

# --- find and connect the TV ------------------------------------------------------------------
if [ -z "$serial" ]; then
  [ -x "$DISCOVER" ] || { echo "no --serial/TV_SERIAL and no discovery script at $DISCOVER" >&2; exit 2; }
  say "Discovering the TV by MAC"
  out="$("$DISCOVER" 2>&1)"; echo "$out" | tail -3
  ip="$(echo "$out" | grep -oE '([0-9]{1,3}\.){3}[0-9]{1,3}' | tail -1)"
  [ -n "$ip" ] || { echo "TV not found" >&2; exit 1; }
  serial="$ip:5555"
fi
adb connect "$serial" >/dev/null 2>&1
adb -s "$serial" get-state >/dev/null 2>&1 || { echo "adb cannot reach $serial (TV off, or accept the debugging prompt on screen)" >&2; exit 1; }
A() { adb -s "$serial" shell "$@" 2>/dev/null | tr -d '\r'; }
installed() { A pm list packages "$1" | grep -qx "package:$1"; }
failed=0

# Prints the enabled accessibility services ("" when none). Fails — and prints nothing — when adb
# fails or the answer is not "null" or a colon-separated list of components, so a bad read can
# never be written back over the user's other services.
read_a11y() {
  local out rc
  out="$(adb -s "$serial" shell settings get secure enabled_accessibility_services 2>/dev/null)"; rc=$?
  out="$(printf '%s' "$out" | tr -d '\r')"
  [ "$rc" = 0 ] || return 1
  [ "$out" = "null" ] && { printf ''; return 0; }
  [ -z "$out" ] && return 0
  printf '%s' "$out" | grep -qxE '[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+(:[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+)*' || return 1
  printf '%s' "$out"
}

check() {
  echo
  printf '%-34s %s\n' "Check" "State"
  printf '%-34s %s\n' "----------------------------------" "-----"
  printf '%-34s %s\n' "TV" "$(A getprop ro.product.model) · $(A getprop ro.build.version.name 2>/dev/null || A getprop ro.build.version.release) · $serial"
  for p in $ARCHIE_PKG $HUB_PKG; do
    v="$(A dumpsys package "$p" | sed -n 's/.*versionName=//p' | head -1)"
    printf '%-34s %s\n' "$p" "${v:-NOT INSTALLED}"
  done
  printf '%-34s %s\n' "Archie TV mic permission" "$(A dumpsys package $ARCHIE_PKG | grep -q 'android.permission.RECORD_AUDIO: granted=true' && echo granted || echo MISSING)"
  op() { A appops get "$1" "$2" | sed -n 's/^[^:]*: \([a-z]*\).*/\1/p' | head -1; }
  printf '%-34s %s\n' "Archie TV overlay app-op" "$(op $ARCHIE_PKG SYSTEM_ALERT_WINDOW | grep . || echo default)"
  printf '%-34s %s\n' "Launcher usage-stats app-op" "$(op $HUB_PKG GET_USAGE_STATS | grep . || echo default)"
  printf '%-34s %s\n' "Launcher overlay app-op" "$(op $HUB_PKG SYSTEM_ALERT_WINDOW | grep . || echo default)"
  printf '%-34s %s\n' "Launcher may re-enable watcher" "$(A dumpsys package $HUB_PKG | grep -q 'WRITE_SECURE_SETTINGS: granted=true' && echo yes || echo NO)"
  a11y="$(A settings get secure enabled_accessibility_services)"
  printf '%-34s %s\n' "Launcher key watcher" "$(echo "$a11y" | tr ':' '\n' | grep -qxF -e "$HUB_A11Y" -e "$HUB_A11Y_LONG" && echo enabled || echo DISABLED)"
  printf '%-34s %s\n' "Launcher idle service" "$(A dumpsys activity services $HUB_PKG | grep -q ScreensaverMonitorService && echo running || echo 'not running (starts with the launcher)')"
  if installed $ALEXA_UI; then
    st="$(A pm list packages -d $ALEXA_UI | grep -q "$ALEXA_UI" && echo "disabled — mic button belongs to Archie" || echo "enabled — Alexa also reacts to the mic button")"
  else st="not present"; fi
  printf '%-34s %s\n' "Alexa voice UI ($ALEXA_UI)" "$st"
  printf '%-34s %s\n' "In front" "$(A dumpsys window | sed -n 's/.*mCurrentFocus=Window{[^ ]* [^ ]* \([^}]*\)}.*/\1/p' | head -1)"
}

case "$mode" in
  check) check; exit 0 ;;
  restore)
    say "Re-enabling Alexa's voice UI"
    A pm enable "$ALEXA_UI" >/dev/null && echo "enabled $ALEXA_UI (the mic button talks to Alexa again; Archie TV still hears it too)"
    check; exit 0 ;;
esac

# --- install ----------------------------------------------------------------------------------
if [ "$install" = 1 ]; then
  if [ -z "$archie_apk" ]; then
    archie_apk="$(ls -t "$REPO"/apps/android/app-tv/build/outputs/apk/release/*.apk \
                         "$REPO"/apps/android/app-tv/build/outputs/apk/debug/*.apk 2>/dev/null | head -1)"
  fi
  [ -z "$hub_apk" ] && hub_apk="$HUB_APK_DEFAULT"
  for apk in "$archie_apk" "$hub_apk"; do
    if [ -n "$apk" ] && [ -f "$apk" ]; then
      say "Installing $(basename "$apk")"
      adb -s "$serial" install -r "$apk" | tail -1
    else
      warn "APK not found (${apk:-none}); build it first or pass --archie-apk/--hub-apk. Skipping."
    fi
  done
fi

# --- permissions and app-ops --------------------------------------------------------------------
if installed $ARCHIE_PKG; then
  say "Archie TV: microphone permission + overlay app-op (lets agent commands bring it to the front)"
  A pm grant $ARCHIE_PKG android.permission.RECORD_AUDIO
  A appops set $ARCHIE_PKG SYSTEM_ALERT_WINDOW allow
fi
if installed $HUB_PKG; then
  say "Launcher: usage access + overlay app-ops, key watcher"
  A appops set $HUB_PKG android:get_usage_stats allow
  A appops set $HUB_PKG SYSTEM_ALERT_WINDOW allow
  # Lets the launcher re-enable its own key watcher: Android drops an app's accessibility
  # services from the enabled list whenever the app is force-stopped.
  A pm grant $HUB_PKG android.permission.WRITE_SECURE_SETTINGS
  if cur="$(read_a11y)"; then
    # Drop the probe's watcher and empty entries; append the launcher's once (in either form).
    new="$(printf '%s\n' "$cur" | tr ':' '\n' | grep -vxF -e '' -e "$PROBE_A11Y" -e "$PROBE_A11Y_LONG" | paste -sd: -)"
    if ! printf '%s\n' "$new" | tr ':' '\n' | grep -qxF -e "$HUB_A11Y" -e "$HUB_A11Y_LONG"; then
      new="${new:+$new:}$HUB_A11Y"
    fi
    # Single-quoted for the TV's shell: adb joins the arguments into one remote command line, so
    # an unquoted "$" in a component name (Outer$Inner) would be expanded there.
    [ "$new" != "$cur" ] && A settings put secure enabled_accessibility_services "'$new'"
    A settings put secure accessibility_enabled 1
  else
    warn "Could not read the TV's enabled accessibility services (adb failed or the answer was unexpected)."
    warn "Skipped enabling the launcher's key watcher so the other services are not overwritten; re-run when adb is stable."
    failed=1
  fi
fi

# --- Alexa ------------------------------------------------------------------------------------
if [ "$alexa" = disable ] && installed $ALEXA_UI; then
  say "Giving the mic button to Archie: disabling $ALEXA_UI (undo: $0 --restore-alexa)"
  A pm disable-user --user 0 $ALEXA_UI | tail -1
fi

# --- performance + cleanup --------------------------------------------------------------------
for p in $ARCHIE_PKG $HUB_PKG; do
  installed "$p" && { say "Compiling $p (speed-profile)"; A cmd package compile -f -m speed-profile "$p" | tail -1; }
done
if adb -s "$serial" reverse --list 2>/dev/null | awk '{print $2}' | grep -qxF "$REVERSE_PORT"; then
  adb -s "$serial" reverse --remove "$REVERSE_PORT" >/dev/null 2>&1 || true
fi

check
exit "$failed"
