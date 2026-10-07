# Harnesses

The agent CLIs that run Archie's chat sessions. Each harness plugs into the backend through one
`HarnessSpec` in `backend/manager/registry.py` plus a subpackage under `backend/manager/<name>/`
(JSONL adapter + session manager); everything else — the pool, the chat WebSocket, the history list,
the settings pickers — goes through the registry. Start with `registry.md`; the per-harness docs
cover storage layout, CLI flags, JSONL quirks and landmines.

- [registry.md](registry.md) — HarnessRegistry / HarnessSpec, ProviderAdapter, normalized events, SessionStore across harnesses, dispatch sites, carve-outs, how to add a harness (incl. Codex recon notes)
- [claude-code.md](claude-code.md) — Claude Code over claude-agent-sdk: `.claude_config/` layout, session options, JSONL, SDK pin and upgrade stages, auth, Chrome flag
- [qwen-code.md](qwen-code.md) — Qwen Code: spawn-per-turn stream-json CLI, `~/.qwen` symlinks into `context/`, JSONL normalization, model catalog, lazy SDKs, landmines
- [gemini-cli.md](gemini-cli.md) — Gemini CLI: `--prompt` per turn, `--skip-trust`, storage symlink into `context/chats/`, glob resolver, JSONL quirks, landmines
