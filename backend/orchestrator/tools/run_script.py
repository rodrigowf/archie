"""run_script tool — run a curated, allowlisted script locally via run.sh.

The allowlist lives in `context/memory/ORCHESTRATOR_SCRIPTS.md` (next to the
orchestrator memory file). It is plain markdown that the orchestrator curates
itself with read_file/write_file, and that Claude sessions update when they
create new reusable scripts. Each allowed script is declared by a `path:` line
inside a fenced block; `utils/script_runner.py` parses those lines (it also
runs the scripts for the scripts API that visualizations call).

Scripts run through `context/scripts/run.sh` (the project venv) with no
isolation. This tool is for SINGLE self-contained script calls only — anything
that is an actual task, is open-ended, or needs more than one script call
chained together must be delegated to a Claude session instead.
"""

from __future__ import annotations

import json
import logging
from typing import Any

from orchestrator.tools import registry
from utils import script_runner

logger = logging.getLogger(__name__)

# Max seconds a script may run before it is killed.
_TIMEOUT_SECONDS = 300
# Cap captured output so a chatty script can't blow up the prompt.
_MAX_OUTPUT_CHARS = 20_000


@registry.register(
    name="run_script",
    description=(
        "Run ONE allowlisted script locally and capture its output. Only scripts "
        "listed in context/memory/ORCHESTRATOR_SCRIPTS.md (injected into your "
        "prompt) can be run — pass `script` exactly as its `path:` line reads. "
        "This is for a single self-contained script call (e.g. toggle a lamp, "
        "generate one image). If the request is an actual task, is open-ended, "
        "needs judgment, or needs more than one script call chained together, do "
        "NOT use this — delegate to a Claude session via open_agent_session + "
        "send_to_agent_session. Returns exit_code, stdout, and stderr."
    ),
    input_schema={
        "type": "object",
        "properties": {
            "script": {
                "type": "string",
                "description": (
                    "Path of the script to run, exactly as written in its "
                    "allowlist `path:` line (e.g. 'context/scripts/tuya_lamps.py')."
                ),
            },
            "args": {
                "type": "array",
                "items": {"type": "string"},
                "description": (
                    "Command-line arguments passed to the script, in order. "
                    "Each element is one argument (e.g. ['off', 'all'])."
                ),
            },
        },
        "required": ["script"],
    },
)
async def run_script(
    context: dict[str, Any],
    script: str,
    args: list[str] | None = None,
) -> str:
    args = args or []

    # The orchestrator runs only its own list (ORCHESTRATOR_SCRIPTS.md, in its prompt); the scripts
    # API that visualizations call also accepts context/scripts/allowed_scripts.json.
    allowed = script_runner.load_orchestrator_paths()
    normalized = script_runner.normalize(script)

    if normalized not in allowed:
        return json.dumps({
            "error": (
                f"Script '{script}' is not in the allowlist. Only scripts listed "
                "in ORCHESTRATOR_SCRIPTS.md can be run. Add an entry there with "
                "write_file if this script should be runnable, or delegate the "
                "task to a Claude session."
            ),
            "allowed": sorted(allowed),
        })

    target = (script_runner.paths.PROJECT_ROOT / normalized).resolve()
    if not target.is_file():
        return json.dumps({
            "error": (
                f"Script is allowlisted but the file does not exist on disk: "
                f"{normalized}. The allowlist may be stale — fix it with write_file."
            ),
        })

    # Coerce all args to strings — the model occasionally emits numbers.
    str_args = [str(a) for a in args]
    logger.info("run_script: %s %s", normalized, str_args)

    try:
        result = await script_runner.run_command(
            script_runner.command_for(target, str_args), _TIMEOUT_SECONDS,
        )
    except script_runner.ScriptError as e:
        return json.dumps({"error": e.message, "script": normalized})
    except Exception as e:
        return json.dumps({"error": f"Failed to launch script: {e}", "script": normalized})

    if result["timed_out"]:
        return json.dumps({
            "error": f"Script timed out after {_TIMEOUT_SECONDS}s",
            "script": normalized,
        })

    return json.dumps({
        "script": normalized,
        "args": str_args,
        "exit_code": result["exit_code"],
        "stdout": script_runner.clip(result["stdout"].strip(), _MAX_OUTPUT_CHARS),
        "stderr": script_runner.clip(result["stderr"].strip(), _MAX_OUTPUT_CHARS),
    })
