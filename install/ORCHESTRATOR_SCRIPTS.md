# Orchestrator Scripts

The **allowlist** of scripts the orchestrator can run with the `run_script` tool. Only the scripts
listed here can run. The orchestrator keeps this file up to date with `write_file`. Claude sessions
add an entry when they create a reusable script that runs in a single call.

**Entry format:** a `### name` heading, one or two sentences on what the script does, then a fenced
block. In the block, the first line is `path:` followed by the script's repo-relative path (for
example `context/scripts/my_script.py`); the allowlist is read from these lines. Then an `args:`
line and an `example:` line, for example
`run_script(script="context/scripts/my_script.py", args=["status"])`.

---

_No scripts yet._
