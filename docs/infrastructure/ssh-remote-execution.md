---
name: ssh-remote-execution
category: archie/infrastructure
tags: [ssh, remote-execution, wrapper, controlmaster, quoting, cwd, project-key, nvm, exit-127]
created: 2026-04-17
modified: 2026-10-06
summary: How a backend runs Claude/Qwen/Gemini sessions on another machine over SSH — wrapper design, quoting fix, cwd, nvm trap, debugging.
source: curated (consolidated from memory notes assistant/infrastructure/ssh-remote-execution.md, feedback_ssh_remote_cli_nvm_path.md, assistant/infrastructure/server_hub_project.md (SSH churn hardening, cd gotcha), reference_jetson_access.md; verified against backend/manager/_ssh.py and callers 2026-10-06)
references:
  - topology.md
  - jetson-server.md
  - deployment.md
  - context-sync.md
  - ../architecture/agent-sessions.md
  - ../harnesses/registry.md
  - ../harnesses/claude-code.md
  - ../harnesses/qwen-code.md
  - ../harnesses/gemini-cli.md
  - ../operations/troubleshooting.md
---

# SSH remote execution

A backend can run an agent session's CLI (`claude`, `qwen` or `gemini`) **on another machine over
SSH** while the session itself — WebSocket, pool, event stream — lives in the local backend. In the
reference deployment the Jetson backend runs sessions on the laptop (heavier tools, laptop-only
files), and the laptop backend can run them on the Jetson. To the browser such a session looks
exactly like a local one, because skills, memory and JSONL history are shared through
[context-sync](context-sync.md) at the same path on both machines ([topology.md](topology.md)).

## Code map

| Piece | Where |
|---|---|
| SSH primitives: ICMP pre-probe, argv builder, remote CLI path probe + cache, `RemoteCommand`, wrapper script | `backend/manager/_ssh.py` |
| Claude: wrapper script passed to the SDK as `cli_path` | `backend/manager/claude/session.py` `ClaudeSessionManager._build_options()` / `_write_ssh_wrapper()`; `_pre_start_check()` |
| Qwen / Gemini: argv that starts with `ssh` | `backend/manager/qwen/session.py` and `backend/manager/gemini/session.py` `_maybe_wrap_with_ssh()` |
| Working-directory entry → `ManagerConfig` | `backend/api/session_factory.py` (`ssh_host`, `ssh_user`, `ssh_key`, `claude_config_dir` → `ssh_claude_config_dir`) |
| Entry storage, id and `claude_config_dir` defaulting | `backend/api/routes/config.py` |
| Per-host create lock, resume fallback | `backend/api/pool.py` (`_host_create_locks`, "No conversation found" retry) |

## Configuration

A working directory in `assistant_config.json` (`working_directory_history`) with `ssh_host` set is a
remote one. Pick it as the active working directory (globally or per session in the session config
panel) and new sessions run there.

```json
{
  "id": "192.168.0.28:/home/rodrigo/assistant",
  "path": "/home/rodrigo/assistant",
  "label": "Laptop",
  "ssh_host": "192.168.0.28",
  "ssh_user": "rodrigo",
  "ssh_key": null,
  "claude_config_dir": "/home/rodrigo/assistant/.claude_config"
}
```

| Field | Meaning |
|---|---|
| `ssh_host`, `ssh_user` | Target and login |
| `ssh_key` | Private key on the **local** machine; `null` = SSH agent / default keys |
| `path` | Absolute project dir **on the remote** (the remote cwd) |
| `claude_config_dir` | `CLAUDE_CONFIG_DIR` on the remote; auto-derived as `<path>/.claude_config` when blank (correct for standard installs) |
| `id` | Defaults to `<ssh_host>:<path>` |

`assistant_config.json` is gitignored and per machine: each backend lists the *other* machine.

## How it works

### Shared primitives (`_ssh.py`)

- **`probe_host_reachable(host, 2.0)`** — one ICMP ping before any SSH. An unreachable host raises
  `RemoteHostUnreachableError` at session start instead of hanging ~30 s in TCP retransmit (which
  pinned the Jetson CPU while the laptop hibernated).
- **`build_ssh_argv(SshTarget)`** — `ssh -T -o BatchMode=yes -o StrictHostKeyChecking=accept-new
  -o ControlMaster=auto -o ControlPersist=60s -o ControlPath=/tmp/<prefix>-ssh-<host>-%r [-i key]
  user@host`. ControlMaster multiplexes the burst of connections at session start and per turn (Qwen
  and Gemini spawn a process per turn). The prefix is per provider (`claude`, `qwen`, `gemini`) so
  one provider's ControlPersist timeout can't tear down another's socket.
- **`resolve_remote_cli_path(cli, target)`** — runs
  `bash -c '. ~/.profile; . ~/.bashrc; which <cli> || ls -t <search paths> | head -1'` once and
  caches the result in-process per `(cli, host, user, key)`. Concurrent starts share the cached
  probe instead of opening N handshakes (one of the 2026-04-20 crash lessons). If the host is
  unreachable it returns the bare name **without** caching, so the next attempt re-probes.
- **`default_cli_search_paths(cli)`** — `~/.local/bin/<cli>`, `/usr/local/bin/<cli>`,
  `/usr/bin/<cli>`, and the glob `~/.nvm/versions/node/*/bin/<cli>`.
- **`RemoteCommand.render_shell()`** — `cd '<path>' && PATH=<cli dir>:$PATH KEY='v' exec '<cli>'`.
  Env vars are an **inline prefix**, never `export`: `export` inside `bash -c` over SSH from a Python
  subprocess dumps `declare -x …` on stdout and corrupts the JSON stream. The CLI's own directory is
  prepended to `PATH` for the `node` shebang (see the nvm trap).

### Claude: the wrapper script

The Claude SDK takes a `cli_path` and runs `<cli_path> --output-format stream-json …` with flags it
builds at runtime, so Python can't touch that argv. For a remote session `_write_ssh_wrapper()`
writes a mode-0700 temp script (`/tmp/claude-ssh-XXXX.sh`) and passes it as `cli_path`; the local
subprocess cwd is set to `$HOME` (it must exist locally — the real cwd is set remotely). Generated
shape:

```sh
#!/bin/sh
_q=''
for _a in "$@"; do
  _q="${_q} '$(printf '%s' "$_a" | sed "s/'/'\\''/g")'"
done
exec ssh -T -o BatchMode=yes … rodrigo@192.168.0.28 "cd '/home/rodrigo/assistant' && PATH=/home/rodrigo/.local/bin:$PATH CLAUDE_CONFIG_DIR='/home/rodrigo/assistant/.claude_config' CLAUDE_CODE_OAUTH_TOKEN='…' exec '/home/rodrigo/.local/bin/claude'${_q}"
```

`CLAUDE_CODE_OAUTH_TOKEN` is forwarded when set locally, so remote sessions authenticate with the
long-lived token instead of the remote's refreshable credentials file
([deployment.md](deployment.md#claude-code-authentication-on-the-jetson)). The caller removes the
script when the session ends (`cleanup_ssh_wrapper_script`).

### Qwen and Gemini: direct argv

These managers build their own argv, so there is no `"$@"` to forward: `build_remote_argv()` returns
`ssh … "<rendered remote command> '<arg1>' '<arg2>' …"` with every argument single-quoted, passed
straight to `asyncio.create_subprocess_exec`. Qwen forwards no env — the remote has its own
`context/.env`, and forwarding would expose local keys in the remote `ps`.

## The SSH quoting bug and the fix

SSH does **not** pass trailing arguments as separate tokens: it space-joins everything after the host
into **one string** for the remote shell. The naive

```sh
ssh host bash -c 'cd /proj && exec /claude "$@"' _ --output-format stream-json
```

arrives as `bash -c cd /proj && exec /claude "$@" _ --output-format stream-json` and is re-parsed:
the `-c` body swallows `_` and the flags, `"$@"` is empty, and the `cd` effect is lost. Sessions
still started — but with cwd `/home/rodrigo`, i.e. the wrong project key, the wrong JSONL bucket,
wrong memory paths and no project skills. The fix, now in `write_ssh_wrapper_script` and
`build_remote_argv`: quote each argument **locally** and send the whole remote command (cd, env,
exec, args) as **one** argument.

The same root cause bites hand-written remote commands: `ssh host "cd ~/assistant && git …"` built
through several quoting layers can drop the `cd`. For manual commands use a quoted heredoc with an
absolute path ([deployment.md](deployment.md#rules-and-why)).

## How cwd and project keys work

Claude derives its project key (JSONL location, auto-memory path, skill discovery) from its real OS
cwd — the `cwd` in the `init` system event — not from `$PWD`. The SDK sets `cwd` for the local `ssh`
process only; the remote `cd` plus `exec` is the **only** thing that sets claude's cwd. Setting
`PWD` cannot override it.

On both machines `.claude_config/projects/-home-rodrigo-assistant` → `../../context` and
`.claude_config/skills` → `../context/skills`, so a remote session writes its JSONL straight into the
remote's `context/`, which context-sync mirrors back within seconds; the session then shows in the
history list on both machines and can be resumed from either.

## What is shared and what is not

- Shared through `context/`: skills, scripts, agents, memory, wrapper-session JSONL, `.env`.
- Not shared: each machine's `.claude_config/` credentials and `.claude.json` (MCP servers configured
  there apply to sessions *run* on that machine), `assistant_config.json`, local files outside
  `context/` (a remote session sees the remote's disk, e.g. the laptop's `projects/`).
- Sessions started with a bare `claude` (terminal, VS Code) don't use `CLAUDE_CONFIG_DIR` and stay in
  `~/.claude/projects/…` on that machine only.
- Trust: a backend that can SSH into a machine has that machine's user account.

## Stopping a remote turn

Signalling the local `ssh` client does not stop the remote command: without a tty, sshd sends no
SIGHUP when the client goes away, so an interrupted Qwen/Gemini turn kept running on the remote (and
so did a shell command it had started — the Node CLIs spawn those detached). The remote command
therefore starts with `echo __ARCHIE_REMOTE_PID__=$$` before `exec`-ing the CLI (`RemoteCommand(...,
announce_pid=True)`; `exec` keeps the PID). The session reads that line from stdout, and
`interrupt()` / `_kill_proc()` run `kill_remote_tree()` over the same ControlMaster connection: it
snapshots the remote process tree, SIGINTs the CLI, then TERM/KILLs what is left. Claude does not
need this: its SDK interrupt travels over the stream-json stdin.

## The nvm exit-127 trap

A remote session dying with **exit 127** has two causes with identical exit codes — tell them apart
by stderr:

1. **CLI not found** (`qwen: command not found`). Ubuntu's stock `~/.bashrc` returns early for
   non-interactive shells (`case $- in *i*) ;; *) return;;`) before the nvm block, so `which` finds
   nothing for an nvm-installed CLI. Fixed by the nvm glob in `default_cli_search_paths()` — a glob,
   not a symlink to one node version (that silently breaks on the next `nvm install`) — and by
   `ls -t` in the fallback: plain `ls` sorts lexically, so `head -1` picked the **oldest** node
   (v16 before v22).
2. **node not found** (`/usr/bin/env: 'node': No such file or directory`). The CLIs are Node scripts
   with `#!/usr/bin/env node`; `node` lives in the same nvm bin dir, still not on `PATH`. Fixed by
   `RemoteCommand.render_shell()` prepending the CLI's directory to `PATH`.

The resolved path is **cached in-process**: after fixing anything on the remote, restart the backend
(`sudo systemctl restart agentic-backend.service` on the Jetson) or you keep testing the stale value.

## Debugging

- **Check the `init` event** (first JSON line of a session): `cwd` must be `/home/rodrigo/assistant`
  (not `/home/rodrigo`), `memory_paths.auto` must contain `-home-rodrigo-assistant`, and
  `slash_commands` should list the project skills.
- **Find the live wrapper:** `ls -lt /tmp/claude-ssh-*.sh | head -3` on the machine running the
  backend. Run it by hand:
  `/tmp/claude-ssh-<id>.sh --output-format stream-json --verbose --print hi 2>/dev/null | head -1`.
- **Test in the real direction.** Probing laptop → laptop fails with `Permission denied (publickey)`
  (no self-key) — a phantom auth bug. Test Jetson → laptop.
- `CLAUDE_CONFIG_DIR` only takes effect when claude fully initializes; `claude --version` ignores it.
  Test with a real `--print` run.
- Backend logs show CLI stderr as `claude CLI stderr [<local_id>]: …`
  (`journalctl -u agentic-backend.service` on the Jetson).
- `RemoteHostUnreachableError` at start = the ICMP probe failed (target asleep or offline).

## History

- 2026-04-17: feature introduced (Jetson → laptop); the space-joining quoting bug found and fixed.
- 2026-04-20/21: after the Jetson crash — cached path probe, per-host create lock, bounded resume
  retry, ICMP pre-probe (PR #38: `a122447`, `e660045`, `b837b73`); `manager/auth.py` honors
  `CLAUDE_CONFIG_DIR`.
- Later: SSH code extracted into `backend/manager/_ssh.py`, shared by Claude, Qwen and Gemini.
- 2026-08-24: `CLAUDE_CODE_OAUTH_TOKEN` forwarded to remote sessions.
- 2026-08-28: nvm exit-127 two-layer fix (glob, `ls -t`, PATH prepend).
