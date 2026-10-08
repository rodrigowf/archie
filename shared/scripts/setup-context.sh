#!/usr/bin/env bash
# Usage: shared/scripts/setup-context.sh [--force]
# Description: Set up the context folder structure and SDK symlinks.
#
# This script:
#   1. Creates the context folder structure if it doesn't exist
#   2. Creates symlinks to shared/skills, shared/scripts, shared/agents
#      (files and folders alike; existing entries are never overwritten —
#      re-run after adding something to shared/ to link it)
#   3. Sets up the Claude SDK compatibility symlink
#   4. Creates a template .env file if none exists
#
# Options:
#   --force    Recreate symlinks even if they exist
#   -h, --help Show this help message
set -euo pipefail

# Resolve to project root (works whether called directly or via symlink)
SCRIPT_PATH="$(readlink -f "${BASH_SOURCE[0]}")"
SCRIPT_DIR="$(dirname "$SCRIPT_PATH")"
PROJECT_DIR="$(dirname "$(dirname "$SCRIPT_DIR")")"  # shared/scripts/ → repo root

cd "$PROJECT_DIR"

# Colors
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

info() { echo -e "${GREEN}✓${NC} $1"; }
warn() { echo -e "${YELLOW}!${NC} $1"; }
step() { echo -e "${BLUE}→${NC} $1"; }

# Parse arguments
FORCE=false
for arg in "$@"; do
    case $arg in
        --force)
            FORCE=true
            ;;
        -h|--help)
            echo "Usage: shared/scripts/setup-context.sh [--force]"
            echo ""
            echo "Set up the context folder structure and SDK symlinks."
            echo ""
            echo "Options:"
            echo "  --force    Recreate symlinks even if they exist"
            echo "  -h, --help Show this help message"
            exit 0
            ;;
    esac
done

echo "Setting up context for: $PROJECT_DIR"
echo ""

# ─────────────────────────────────────────────────────────────────────────────
# Step 1: Create context folder structure
# ─────────────────────────────────────────────────────────────────────────────
step "Creating context folder structure..."

mkdir -p context/{memory,skills,scripts,agents,secrets,certs}
info "Created context subdirectories"

# ─────────────────────────────────────────────────────────────────────────────
# Step 2: Create symlinks to shared/skills
# ─────────────────────────────────────────────────────────────────────────────
step "Creating skill symlinks..."

# Use relative paths for portability
cd context/skills
for skill_dir in ../../shared/skills/*/; do
    skill_name=$(basename "$skill_dir")

    if [ "$FORCE" = true ] && [ -L "$skill_name" ]; then
        rm "$skill_name"
    fi

    if [ ! -e "$skill_name" ]; then
        ln -s "../../shared/skills/$skill_name" "$skill_name"
        info "Linked $skill_name"
    fi
done
cd ../..

# ─────────────────────────────────────────────────────────────────────────────
# Step 3: Create symlinks to shared/scripts
# ─────────────────────────────────────────────────────────────────────────────
step "Creating script symlinks..."

# Use relative paths for portability
cd context/scripts
for script_file in ../../shared/scripts/*; do
    script_name=$(basename "$script_file")
    [ "$script_name" = "__pycache__" ] && continue  # bytecode cache, not a script

    if [ "$FORCE" = true ] && [ -L "$script_name" ]; then
        rm "$script_name"
    fi

    if [ ! -e "$script_name" ]; then
        ln -s "../../shared/scripts/$script_name" "$script_name"
        info "Linked $script_name"
    fi
done
cd ../..

# ─────────────────────────────────────────────────────────────────────────────
# Step 4: Create symlinks to shared/agents
# ─────────────────────────────────────────────────────────────────────────────
step "Creating agent symlinks..."

# Use relative paths for portability
cd context/agents
for agent_file in ../../shared/agents/*; do
    agent_name=$(basename "$agent_file")

    if [ "$FORCE" = true ] && [ -L "$agent_name" ]; then
        rm "$agent_name"
    fi

    if [ ! -e "$agent_name" ]; then
        ln -s "../../shared/agents/$agent_name" "$agent_name"
        info "Linked $agent_name"
    fi
done
cd ../..

# ─────────────────────────────────────────────────────────────────────────────
# Step 5: Set up Claude SDK compatibility symlink
# ─────────────────────────────────────────────────────────────────────────────
step "Setting up Claude SDK symlink..."

# Create .claude_config structure
mkdir -p .claude_config/projects

# Mangled path name: the CLI replaces every character that is not a letter or
# digit with "-" (/home/me/my.repo → -home-me-my-repo), same as the installers.
MANGLED=$(printf '%s' "$PROJECT_DIR" | sed 's/[^A-Za-z0-9]/-/g')
SYMLINK_PATH=".claude_config/projects/$MANGLED"

if [ "$FORCE" = true ] && [ -L "$SYMLINK_PATH" ]; then
    rm "$SYMLINK_PATH"
fi

if [ -L "$SYMLINK_PATH" ]; then
    info "SDK symlink already exists"
elif [ -e "$SYMLINK_PATH" ]; then
    warn "$SYMLINK_PATH exists but is not a symlink - skipping"
else
    ln -s "../../context" "$SYMLINK_PATH"
    info "Created SDK symlink: $SYMLINK_PATH -> ../../context"
fi

# Create skills symlink for SDK discovery
if [ "$FORCE" = true ] && [ -L ".claude_config/skills" ]; then
    rm ".claude_config/skills"
fi

if [ ! -L ".claude_config/skills" ]; then
    ln -sf "../context/skills" ".claude_config/skills"
    info "Created skills discovery symlink"
fi

# The bundled CLI loads user agents from $CLAUDE_CONFIG_DIR/agents.
if [ "$FORCE" = true ] && [ -L ".claude_config/agents" ]; then
    rm ".claude_config/agents"
fi

if [ ! -L ".claude_config/agents" ] && [ ! -e ".claude_config/agents" ]; then
    ln -sf "../context/agents" ".claude_config/agents"
    info "Created agents discovery symlink"
fi

# Archie's documentation (docs/, versioned with the code) is part of the memory wiki
# as context/memory/archie — indexed, searchable and browsable like any other notes.
mkdir -p "context/memory"
if [ -L "context/memory/archie" ]; then
    info "Docs symlink already exists"
elif [ -e "context/memory/archie" ]; then
    warn "context/memory/archie exists but is not a symlink - skipping"
else
    ln -s "../../docs" "context/memory/archie"
    info "Created docs symlink: context/memory/archie -> ../../docs"
fi

# ─────────────────────────────────────────────────────────────────────────────
# Step 6: Create template files if missing
# ─────────────────────────────────────────────────────────────────────────────
step "Checking template files..."

# Seed template files from install/ if missing.  install/ holds the
# canonical fresh-install templates — see install/README.md.
if [ ! -f "context/memory/MEMORY.md" ] && [ -f "install/MEMORY.md" ]; then
    cp install/MEMORY.md context/memory/MEMORY.md
    info "Created context/memory/MEMORY.md from install/MEMORY.md"
fi

if [ ! -f "context/.env" ] && [ -f "install/context.env" ]; then
    cp install/context.env context/.env
    info "Created context/.env from install/context.env"
    warn "Remember to edit context/.env with your API keys!"
fi

if [ ! -f "context/AGENTS.md" ] && [ -f "install/AGENTS.md" ]; then
    cp install/AGENTS.md context/AGENTS.md
    info "Created context/AGENTS.md from install/AGENTS.md"
fi

# The orchestrator's private memory (its identity, and how it gets to know a
# new user) and its run_script allowlist.
for seed in ORCHESTRATOR_MEMORY.md ORCHESTRATOR_SCRIPTS.md; do
    if [ ! -f "context/memory/$seed" ] && [ -f "install/$seed" ]; then
        cp "install/$seed" "context/memory/$seed"
        info "Created context/memory/$seed from install/$seed"
    fi
done

# ─────────────────────────────────────────────────────────────────────────────
# Done
# ─────────────────────────────────────────────────────────────────────────────
echo ""
info "Context setup complete!"
echo ""
echo "Structure:"
echo "  context/"
echo "  ├── *.jsonl           <- Session files"
echo "  ├── memory/           <- Memory markdown files"
echo "  │   ├── MEMORY.md     <- Memory index"
echo "  │   ├── ORCHESTRATOR_MEMORY.md  <- Orchestrator's private memory"
echo "  │   └── archie -> ../../docs"
echo "  ├── skills/           <- Skill folders (symlinks + custom)"
echo "  ├── scripts/          <- Script files (symlinks + custom)"
echo "  ├── agents/           <- Agent definitions (symlinks + custom)"
echo "  ├── secrets/          <- OAuth credentials"
echo "  ├── certs/            <- SSL certificates"
echo "  └── .env              <- Environment variables"
echo ""
echo "  .claude_config/"
echo "  ├── skills -> ../context/skills"
echo "  ├── agents -> ../context/agents"
echo "  └── projects/$MANGLED -> ../../context"
echo ""
