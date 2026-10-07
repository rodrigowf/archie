# architecture/

How Archie's backend is built: the components and the paths a message takes through them, the
FastAPI app and its routes, the agent-session layer that wraps the coding-agent CLIs, the
orchestrator agent with its tools and background runner, and the memory wiki with the search stack
over memory and conversation history. Voice internals live in `../voice/`, harness specifics in
`../harnesses/`, clients in `../clients/`.

- [system-overview.md](system-overview.md) — Components, the two agent systems, data flows (agent chat, orchestrator text, orchestrator voice), where state lives.
- [backend.md](backend.md) — FastAPI app factory and startup tasks, every route module, SPA/static/memory serving, config files, `paths.py`, running and testing.
- [agent-sessions.md](agent-sessions.md) — SessionPool, session managers, `local_id` vs `sdk_session_id`, lifecycle hardening, permission gating, stall and loop watchdogs, per-session config, SDK version.
- [orchestrator.md](orchestrator.md) — OrchestratorSession vs OrchestratorAgent, model providers, system prompt assembly, the 24 tools, fire-and-forget runner and wake callback, `run_script`, JSONL entries.
- [memory-and-search.md](memory-and-search.md) — The memory wiki model (two-level index, frontmatter, add/update flow), SQLite hybrid search indexes, warm search server, navigation tools, indexing cadence, open ideas.
