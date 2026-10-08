#!/usr/bin/env bash
# install-with-agent.sh — conversational installer.
#
# This is the alternate install entry point.  Instead of running the
# deterministic ./install.sh, it launches one of the agent CLIs (Claude Code,
# Qwen Code, Gemini CLI or Codex CLI) and hands it INSTALL.md as instructions.  The
# agent then walks the user through the install conversationally, executing
# each step itself with its file and Bash tools.
#
# What this script does:
#   1. Detects which agent CLIs are installed (claude / qwen / gemini / codex).
#   2. If none are installed, asks which ones to install and runs `npm i -g`.
#      If multiple are now available, asks which one should drive the install.
#   3. Ensures the chosen driver CLI is authenticated (or that the user knows
#      to log in before proceeding).
#   4. Sets the appropriate per-CLI config env vars and execs the agent with
#      INSTALL.md as its prompt.
#
# From the moment the agent starts, this script is done — the agent handles
# everything else and the user interacts with the agent directly.
#
# This script does NOT replace ./install.sh — it sits in front of it.  The
# agent reads ./install.sh as the canonical recipe and follows it step by
# step.  Users who prefer a deterministic install should run ./install.sh
# directly.
set -euo pipefail

# This script lives at install/apple/install-with-agent.sh.  Project root is
# two dirs up.  cd into the project root so all subsequent paths (INSTALL.md,
# context/, etc.) resolve the way the install agent expects.
INSTALLER_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT_DIR="$(cd "$INSTALLER_DIR/../.." && pwd)"
cd "$SCRIPT_DIR"

# ─────────────────────────────────────────────────────────────────────────────
# Output helpers (matched to install.sh's style)
# ─────────────────────────────────────────────────────────────────────────────
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

info()  { echo -e "${GREEN}✓${NC} $1"; }
step()  { echo -e "${BLUE}→${NC} $1"; }
warn()  { echo -e "${YELLOW}!${NC} $1"; }
error() { echo -e "${RED}✗${NC} $1"; exit 1; }
ask()   { echo -e "${CYAN}?${NC} $1"; }

is_interactive() { [ -t 0 ]; }

# ─────────────────────────────────────────────────────────────────────────────
# Header + safety notice
# ─────────────────────────────────────────────────────────────────────────────
clear
echo -e "${BOLD}"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "       Personal Assistant — Conversational Installer"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo -e "${NC}"
echo "This is the conversational install path.  It will launch an agent CLI"
echo "(Claude Code, Qwen Code, Gemini CLI or Codex CLI) and let that agent walk you"
echo "through the install — asking you questions, executing each step, and"
echo "logging progress to context/install.log."
echo ""
echo -e "${YELLOW}Heads up:${NC} the agent will run with broad file + Bash permissions"
echo -e "inside this directory.  It follows ${BOLD}./install.sh${NC} as a recipe and writes"
echo "files into the project root.  If you'd rather see every action as plain"
echo -e "bash, exit now and run ${BOLD}./install.sh${NC} instead."
echo ""

if ! is_interactive; then
    error "This script needs an interactive terminal.  Use ./install.sh for non-interactive installs."
fi

ask "Proceed with the conversational install? [Y/n] "
read -r ANS
if [[ "${ANS:-Y}" =~ ^[Nn]$ ]]; then
    info "Cancelled.  Run ./install.sh whenever you're ready."
    exit 0
fi
echo ""

# ─────────────────────────────────────────────────────────────────────────────
# Step 1: Detect installed harnesses
# ─────────────────────────────────────────────────────────────────────────────
# After this block we have an array INSTALLED of the CLIs that are on PATH,
# and the script branches on its size.
step "Detecting installed agent CLIs..."

ALL_CLIS=(claude qwen gemini codex)
# Qwen, Gemini and Codex are pinned to the versions Archie's harnesses are
# verified against — install/harness-versions.env, shared with install.sh.
# (Plain functions instead of associative arrays: macOS ships bash 3.2.)
# shellcheck disable=SC1091
. "$INSTALLER_DIR/../harness-versions.env"
cli_pkg() {
    case "$1" in
        claude) echo "@anthropic-ai/claude-code" ;;
        qwen)   echo "@qwen-code/qwen-code@$QWEN_CLI_VERSION" ;;
        gemini) echo "@google/gemini-cli@$GEMINI_CLI_VERSION" ;;
        codex)  echo "@openai/codex@$CODEX_CLI_VERSION" ;;
    esac
}
cli_label() {
    case "$1" in
        claude) echo "Claude Code (Anthropic)" ;;
        qwen)   echo "Qwen Code (Alibaba)" ;;
        gemini) echo "Gemini CLI (Google — needs GEMINI_API_KEY)" ;;
        codex)  echo "Codex CLI (OpenAI — ChatGPT login)" ;;
    esac
}

INSTALLED=()
for cli in "${ALL_CLIS[@]}"; do
    if command -v "$cli" &>/dev/null; then
        INSTALLED+=("$cli")
        info "Found $cli at $(command -v "$cli")"
    fi
done
echo ""

# ─────────────────────────────────────────────────────────────────────────────
# Step 2: If nothing is installed, offer to install one or more
# ─────────────────────────────────────────────────────────────────────────────
# A user landing here on a totally fresh machine needs at least one CLI to
# drive the install.  We let them pick which one(s) to install; the rest can
# be installed later by the agent during the conversational walkthrough.
if [ "${#INSTALLED[@]}" -eq 0 ]; then
    warn "No agent CLI is installed yet — one is required to drive this install."
    echo ""
    echo "Available agent CLIs:"
    for cli in "${ALL_CLIS[@]}"; do
        echo "  • $cli  ($(cli_label "$cli")) — npm install -g $(cli_pkg "$cli")"
    done
    echo ""
    echo "You can install one or more now.  The first one you install becomes"
    echo "the install driver; the others (if any) can be added later by the"
    echo "agent during the conversational install."
    echo ""

    # Check npm is available before offering installs.
    if ! command -v npm &>/dev/null; then
        echo ""
        error "npm not found — install Node.js 20+ first, then re-run this script."
    fi

    for cli in "${ALL_CLIS[@]}"; do
        ask "Install $cli ($(cli_label "$cli"))? [y/N] "
        read -r ANS
        if [[ "${ANS:-N}" =~ ^[Yy]$ ]]; then
            step "Installing $(cli_pkg "$cli") globally..."
            if npm install -g "$(cli_pkg "$cli")"; then
                info "$cli installed"
                INSTALLED+=("$cli")
            else
                warn "$cli install failed — skipping"
            fi
        fi
    done
    echo ""

    if [ "${#INSTALLED[@]}" -eq 0 ]; then
        error "No CLI was installed — can't proceed.  Install one manually (npm install -g <pkg>) and re-run."
    fi
fi

# ─────────────────────────────────────────────────────────────────────────────
# Step 3: Pick which CLI drives the install
# ─────────────────────────────────────────────────────────────────────────────
# If multiple are available, ask.  If only one, use it.
if [ "${#INSTALLED[@]}" -eq 1 ]; then
    DRIVER="${INSTALLED[0]}"
    info "Using $DRIVER to drive the install"
else
    echo -e "${BOLD}── Which CLI should drive the install? ──${NC}"
    echo ""
    i=1
    for cli in "${INSTALLED[@]}"; do
        echo -e "  ${BOLD}${i})${NC} $cli  ($(cli_label "$cli"))"
        i=$((i + 1))
    done
    echo ""
    ask "Choice [1-${#INSTALLED[@]}] (default 1): "
    read -r CHOICE
    CHOICE="${CHOICE:-1}"
    if ! [[ "$CHOICE" =~ ^[0-9]+$ ]] || [ "$CHOICE" -lt 1 ] || [ "$CHOICE" -gt "${#INSTALLED[@]}" ]; then
        error "Invalid choice: $CHOICE"
    fi
    DRIVER="${INSTALLED[$((CHOICE - 1))]}"
    info "Using $DRIVER to drive the install"
fi
echo ""

# ─────────────────────────────────────────────────────────────────────────────
# Step 4: Ensure the driver CLI is authenticated
# ─────────────────────────────────────────────────────────────────────────────
# We re-use the same detection logic install.sh's Step 7b uses, but only for
# the driver CLI (the others can be authed by the agent during the install).
check_driver_auth() {
    case "$DRIVER" in
        claude)
            claude auth status 2>/dev/null | grep -q '"loggedIn": true'
            ;;
        qwen)
            # Qwen has no clean status command — check OAuth file or
            # DashScope key as a proxy.
            [ -f "$HOME/.qwen/oauth_creds.json" ] || \
                grep -q "^DASHSCOPE_API_KEY=.\+" context/.env 2>/dev/null || \
                [ -n "${DASHSCOPE_API_KEY:-}" ]
            ;;
        gemini)
            # Google stopped serving Gemini CLI to personal Google logins
            # (oauth-personal) on 2026-06-18 — only an API key counts.
            grep -q "^GEMINI_API_KEY=.\+" context/.env 2>/dev/null || \
                [ -n "${GEMINI_API_KEY:-}" ]
            ;;
        codex)
            # The driver runs with the default CODEX_HOME (~/.codex), not
            # Archie's ~/.codex-archie — that one is set up by install.sh.
            [ -f "${CODEX_HOME:-$HOME/.codex}/auth.json" ]
            ;;
    esac
}

login_cmd_for() {
    case "$1" in
        claude) echo "claude auth login" ;;
        qwen)   echo "qwen" ;;
        gemini) echo "export GEMINI_API_KEY=<key from https://aistudio.google.com/apikey>   (or put it in context/.env)" ;;
        codex)  echo "codex login --device-auth" ;;
    esac
}

step "Checking authentication state for $DRIVER..."
if check_driver_auth; then
    info "$DRIVER is authenticated"
else
    warn "$DRIVER is not authenticated yet."
    echo ""
    echo "    Open a separate terminal in this directory and run:"
    echo -e "      ${BLUE}$(login_cmd_for "$DRIVER")${NC}"
    echo "    Complete the login flow, then return here."
    echo ""
    ask "Press Enter once login completes: "
    read -r _
    if ! check_driver_auth; then
        warn "$DRIVER still doesn't look authenticated.  The agent may not be able to start."
        ask "Continue anyway? [y/N] "
        read -r ANS
        if [[ ! "${ANS:-N}" =~ ^[Yy]$ ]]; then
            info "Cancelled.  Finish authentication and re-run this script."
            exit 0
        fi
    else
        info "$DRIVER authenticated"
    fi
fi
echo ""

# ─────────────────────────────────────────────────────────────────────────────
# Step 5: Ensure context/install.log exists (and the dir for it)
# ─────────────────────────────────────────────────────────────────────────────
# The agent will append progress here.  We create the dir so the very first
# append doesn't trip on a missing parent.  We don't pre-create the log
# itself — the agent does that as its first action.
mkdir -p context
echo ""

# ─────────────────────────────────────────────────────────────────────────────
# Step 6: Build the agent prompt and launch
# ─────────────────────────────────────────────────────────────────────────────
# INSTALL.md is the boot prompt.  We prepend a short framing wrapper so the
# agent knows it's being launched as the install agent (not just reading
# INSTALL.md for reference) and feeds the user's chosen driver/start time.
START_TS="$(date '+%Y-%m-%d %H:%M:%S')"
PROMPT=$(cat <<EOF
You are the install agent for the Personal Assistant project, launched by
install-with-agent.sh at ${START_TS}.  You are running inside the project root
($(pwd)).  The driver CLI you are running on is: ${DRIVER}.

Your instructions are in INSTALL.md — read it now (the section marked
"For the install agent (boot prompt)") and follow it carefully.  Treat
install/apple/install.sh as the canonical recipe; do the steps yourself
with your file and Bash tools so you can adapt to the user's answers and
recover from errors.  (The top-level ./install.sh is just a dispatcher
that execs the per-OS script — don't read that, read the macOS one.  The
macOS variant differs from the Linux one in BSD sed / readlink handling
and uses Homebrew via install/apple/install-prerequisites.sh.)

Begin by:
  1. Reading INSTALL.md end-to-end.
  2. Reading install/apple/install.sh end-to-end.
  3. Reading install/README.md and install/apple/README.md.
  4. Creating context/install.log if it doesn't exist and appending a
     timestamped "install agent started" line.
  5. Greeting the user briefly and asking the first axis question
     (session harness — claude / qwen / gemini / codex / modelstudio,
     any subset; modelstudio installs no CLI — it needs the claude SDK
     and DASHSCOPE_API_KEY).

Important context for this session:
  - The user already has ${DRIVER} installed and authenticated (it's
    driving this conversation).  Do NOT re-install or re-authenticate
    ${DRIVER}.
  - Other harnesses (the ones not in the list above) may need to be
    installed if the user picks them.  Step 7b in install/apple/install.sh
    has the detection + npm install + login-prompt logic — follow that.
  - Finish with install/doctor.sh (check-only): it prints an OK/WARN/FAIL
    table of every harness's binary, pins, env keys, auth files, symlinks
    and seed settings.  Fix what it reports; --dry-run shows what --fix
    would repair (symlinks and seed files only).
  - When the install is complete, ask the user if they'd like the backend
    and/or frontend started in the background, then exit cleanly.

Begin.
EOF
)

step "Launching $DRIVER with the install prompt..."
echo ""

# Per-CLI launch.  Each one takes the prompt slightly differently:
#   claude — supports passing a prompt as a positional arg (defaults to
#            interactive mode after it processes the prompt).
#   qwen   — same as claude (Qwen Code is forked from Gemini CLI which is
#            in turn similar enough to claude's CLI).
#   gemini — same idiom.  Launched with GEMINI_CLI_AUTH_OVERRIDE so a stale
#            oauth-personal choice in ~/.gemini/settings.json (dead since
#            2026-06-18) doesn't beat the API key.
#   codex  — takes the prompt as a positional arg, like claude.
#
# CLAUDE_CONFIG_DIR / GEMINI_DIR aren't set here intentionally: the agent
# will discover whether they need to be set as part of executing the
# install.  We use the user's normal home-dir config for the driver CLI.
case "$DRIVER" in
    claude)
        exec claude "$PROMPT"
        ;;
    qwen)
        exec qwen --prompt-interactive "$PROMPT"
        ;;
    gemini)
        if [ -z "${GEMINI_API_KEY:-}" ] && [ -f context/.env ]; then
            GEMINI_API_KEY="$(grep -m1 "^GEMINI_API_KEY=" context/.env | cut -d= -f2-)"
            export GEMINI_API_KEY
        fi
        GEMINI_CLI_AUTH_OVERRIDE=gemini-api-key exec gemini --prompt-interactive "$PROMPT"
        ;;
    codex)
        exec codex "$PROMPT"
        ;;
    *)
        error "Internal error: unknown driver $DRIVER"
        ;;
esac
