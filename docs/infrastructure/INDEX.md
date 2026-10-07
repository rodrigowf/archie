# infrastructure/

Where Archie runs and how it gets there: the two-machine reference deployment (a development laptop
and a 24/7 Jetson Nano server), the server itself, deploying code and web builds, the `context/`
sync service, installing on a new machine, and running agent sessions on another machine over SSH.

- [topology.md](topology.md) — laptop + Jetson, same install path and why, how code vs `context/` travel, per-machine state, how to tell which machine you are on.
- [jetson-server.md](jetson-server.md) — Jetson hardware/OS, services at boot, power mode and thermal setup, fb0 blanking, restore images, the 2026-04-20 crash guardrails, resource budget, security.
- [deployment.md](deployment.md) — laptop → Jetson deploy: git for code, rsync for both web dists, systemd restart, verification, heredoc rule, Claude auth on the Jetson.
- [context-sync.md](context-sync.md) — `infra/sync/` service: inotifywait + rsync, per-path delete gating, install/config, the `reset --mixed` rule for the Jetson's context repo, inotify budget, large files.
- [installation.md](installation.md) — installers and flags, what each step does, the `shared/` ↔ `context/` symlink model, certificates, migrating to a new machine, a parallel second install.
- [ssh-remote-execution.md](ssh-remote-execution.md) — remote Claude/Qwen/Gemini sessions: config, wrapper script, ControlMaster, the SSH quoting bug, cwd/project keys, the nvm exit-127 trap, debugging.
