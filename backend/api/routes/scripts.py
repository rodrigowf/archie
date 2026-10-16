"""Scripts API: lets visualizations (and anything else on the LAN / tailnet) run allowlisted
project scripts directly, without a chat session.

* ``GET  /api/scripts`` → the runnable scripts: ``[{script, description, usage, timeout,
  max_runtime, source, exists}]``.
* ``POST /api/scripts/run {script, args?, input?, timeout?}`` → waits for the script:
  ``{script, args, exit_code, stdout, stderr, json, timed_out, duration_ms}``. A non-zero exit is
  still HTTP 200 — read ``exit_code``. ``json`` is stdout parsed when it is one JSON document.
* ``POST /api/scripts/run {…, background: true, max_runtime?}`` → ``{job_id, status: "running",
  …}`` at once; then ``GET /api/scripts/jobs/{job_id}`` (status + output so far, the newest
  200 000 characters per stream), ``DELETE /api/scripts/jobs/{job_id}`` (cancel),
  ``GET /api/scripts/jobs`` (recent jobs, no output).

Errors: 400 bad body, 403 not allowlisted, 404 missing file / unknown job, 429 too many runs.

Which scripts: ``context/scripts/allowed_scripts.json`` plus ``context/memory/ORCHESTRATOR_SCRIPTS.md``,
re-read on every call (``utils/script_runner.py``). Cross-site browser requests are refused by the
API-wide guard (``api/guard.py``) like every other ``/api`` write; reads are origin-checked here
too, since job output can carry anything a script prints.
"""

from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Request

from api.guard import require_trusted_origin
from utils import script_runner
from utils.script_runner import JobManager, ScriptError

router = APIRouter(prefix="/api/scripts", tags=["scripts"], dependencies=[Depends(require_trusted_origin)])


def get_jobs(request: Request) -> JobManager:
    jobs = getattr(request.app.state, "script_jobs", None)
    if jobs is None:
        jobs = request.app.state.script_jobs = JobManager()
    return jobs


def _fail(e: ScriptError) -> HTTPException:
    return HTTPException(e.status, detail=e.message)


@router.get("")
def list_scripts():
    return [s.as_dict() for s in script_runner.load_allowlist().values()]


@router.post("/run")
async def run_script(request: Request, jobs: JobManager = Depends(get_jobs)):
    try:
        body = await request.json()
    except ValueError:
        body = None
    if not isinstance(body, dict) or not isinstance(body.get("script"), str) or not body["script"].strip():
        raise HTTPException(400, detail="body must be a JSON object with a non-empty 'script'")
    try:
        if body.get("background"):
            job = await jobs.start(body["script"], body.get("args"), body.get("max_runtime"), body.get("input"))
            return job.as_dict()
        return await script_runner.run_allowed(body["script"], body.get("args"), body.get("timeout"), body.get("input"))
    except ScriptError as e:
        raise _fail(e)


@router.get("/jobs")
def list_jobs(jobs: JobManager = Depends(get_jobs)):
    return [j.as_dict(output=False) for j in jobs.list()]


@router.get("/jobs/{job_id}")
def get_job(job_id: str, jobs: JobManager = Depends(get_jobs)):
    job = jobs.get(job_id)
    if job is None:
        raise HTTPException(404, detail=f"No job {job_id!r} (finished jobs are kept for an hour).")
    return job.as_dict()


@router.delete("/jobs/{job_id}")
async def cancel_job(job_id: str, jobs: JobManager = Depends(get_jobs)):
    job = await jobs.cancel(job_id)
    if job is None:
        raise HTTPException(404, detail=f"No job {job_id!r}.")
    return job.as_dict()
