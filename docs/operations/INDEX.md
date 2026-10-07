# operations/

How to work on Archie day to day: the standing rules every agent follows (with the incident behind
each), where the logs are and how to probe a running system, a symptom → cause → fix table for every
known incident, and the working agreement for refactoring fine-tuned subsystems.

- [working-rules.md](working-rules.md) — rules for agents: observe first, logs before patches, hands-on device checks via adb, no git writes in subagents, parallelism and resource caps, paid-API cost checks, experiment isolation, review-artifact locations, verifying with explicit `context/` paths, multi-channel input.
- [debugging.md](debugging.md) — every log location (journald, `logs/`, `remote_console.log`, `logs/voice/`, logcat tags), the direct WebSocket probe, reading JSONL, voice deferred/drain signals, Android and browser playbooks.
- [troubleshooting.md](troubleshooting.md) — symptom → cause → fix for deploy, Jetson, context-sync, SSH, search, voice, client and agent-tooling incidents, each linked to its detail doc.
- [refactor-methodology.md](refactor-methodology.md) — source fidelity, test-first, parity tests before the change, on-device verification, public-surface preservation, detours, done; how D1 applied it to the 2026-10 rebuild.
