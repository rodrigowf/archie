#!/usr/bin/env bash
# Usage: install/linux/install-prerequisites.sh [--no-node]
# Description: Check and install system prerequisites for the assistant.
#
# This script checks for required system dependencies and provides
# installation instructions for missing ones.
#
#   --no-node   Backend-only host (install.sh passes it when no usable Node.js
#               is found): Node.js / npm become optional.
set -euo pipefail

NO_NODE=false
[ "${1:-}" = "--no-node" ] && NO_NODE=true

# Minimum Node major (apps/web engines, qwen-code) from the shared pins file.
NODE_MIN_MAJOR=22
PINS="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/harness-versions.env"
# shellcheck disable=SC1090
[ -f "$PINS" ] && . "$PINS"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

info() { echo -e "${GREEN}✓${NC} $1"; }
warn() { echo -e "${YELLOW}!${NC} $1"; }
error() { echo -e "${RED}✗${NC} $1"; }

MISSING=()

echo "Checking system prerequisites..."
echo

# Check Python version
if command -v python3 &> /dev/null; then
    PY_VERSION=$(python3 -c 'import sys; print(f"{sys.version_info.major}.{sys.version_info.minor}")')
    PY_MAJOR=$(echo "$PY_VERSION" | cut -d. -f1)
    PY_MINOR=$(echo "$PY_VERSION" | cut -d. -f2)

    if [ "$PY_MAJOR" -ge 3 ] && [ "$PY_MINOR" -ge 11 ]; then
        info "Python $PY_VERSION"
    else
        error "Python $PY_VERSION (need 3.11+)"
        MISSING+=("python")
    fi
else
    error "Python not found"
    MISSING+=("python")
fi

# Check Node.js version (the web toolchain and the Qwen / Gemini CLIs need it)
if [ "$NO_NODE" = true ]; then
    warn "Node.js skipped (--no-node: backend-only host; build the web app elsewhere)"
elif command -v node &> /dev/null; then
    NODE_VERSION=$(node -v | sed 's/v//')
    NODE_MAJOR=$(echo "$NODE_VERSION" | cut -d. -f1)

    if [ "$NODE_MAJOR" -ge "$NODE_MIN_MAJOR" ]; then
        info "Node.js $NODE_VERSION"
    else
        error "Node.js $NODE_VERSION (need $NODE_MIN_MAJOR+; or re-run install.sh with --no-node for a backend-only host)"
        MISSING+=("node")
    fi
else
    error "Node.js not found (or re-run install.sh with --no-node for a backend-only host)"
    MISSING+=("node")
fi

# Check npm
if [ "$NO_NODE" = true ]; then
    :
elif command -v npm &> /dev/null; then
    NPM_VERSION=$(npm -v)
    info "npm $NPM_VERSION"
else
    error "npm not found"
    MISSING+=("npm")
fi

# Claude Code CLI — optional: the claude / modelstudio harnesses run the CLI
# bundled with claude-agent-sdk; the global one is only used to log in.
if command -v claude &> /dev/null; then
    # Try to get version, but don't fail if it doesn't work
    if CLAUDE_VERSION=$(claude --version 2>/dev/null | head -1); then
        info "Claude Code CLI ($CLAUDE_VERSION)"
    else
        info "Claude Code CLI (installed)"
    fi
else
    warn "Claude Code CLI not on PATH (optional — install.sh offers it for the claude harness; the backend uses the CLI bundled with claude-agent-sdk)"
fi

# Check git (optional but recommended)
if command -v git &> /dev/null; then
    GIT_VERSION=$(git --version | sed 's/git version //')
    info "Git $GIT_VERSION"
else
    warn "Git not found (optional)"
fi

echo

# If anything is missing, show installation instructions
if [ ${#MISSING[@]} -gt 0 ]; then
    echo "Missing prerequisites. Installation instructions:"
    echo

    # Detect OS
    if [[ "$OSTYPE" == "darwin"* ]]; then
        OS="macos"
    elif [[ -f /etc/debian_version ]]; then
        OS="debian"
    elif [[ -f /etc/fedora-release ]]; then
        OS="fedora"
    elif [[ -f /etc/arch-release ]]; then
        OS="arch"
    else
        OS="unknown"
    fi

    for item in "${MISSING[@]}"; do
        case $item in
            python)
                echo "  Python 3.11+ (3.12 recommended):"
                case $OS in
                    macos)  echo "    brew install python@3.12" ;;
                    debian) echo "    sudo apt install python3.12 python3.12-venv" ;;
                    fedora) echo "    sudo dnf install python3.12" ;;
                    arch)   echo "    sudo pacman -S python" ;;
                    *)      echo "    Download from https://www.python.org/downloads/" ;;
                esac
                echo
                ;;
            node|npm)
                echo "  Node.js $NODE_MIN_MAJOR+:"
                case $OS in
                    macos)  echo "    brew install node@$NODE_MIN_MAJOR" ;;
                    debian) echo "    curl -fsSL https://deb.nodesource.com/setup_$NODE_MIN_MAJOR.x | sudo -E bash -"
                            echo "    sudo apt install nodejs" ;;
                    fedora) echo "    sudo dnf install nodejs$NODE_MIN_MAJOR" ;;
                    arch)   echo "    sudo pacman -S nodejs npm" ;;
                    *)      echo "    Download from https://nodejs.org/" ;;
                esac
                echo "    (glibc older than 2.28, e.g. Ubuntu 18.04 / Jetson: Node $NODE_MIN_MAJOR cannot run —"
                echo "     use install.sh --no-node for a backend-only install)"
                echo
                ;;
        esac
    done

    exit 1
else
    info "All prerequisites satisfied!"
    echo
    echo "You can now run: ./install.sh"
fi
