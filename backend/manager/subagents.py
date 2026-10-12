"""Subagent bookkeeping shared by every harness (spec 12 §4.8, SA-*).

A subagent is a nested agent started by a tool call (Claude Code / Model
Studio ``Agent`` alias ``Task``, Qwen ``agent``, Gemini ``invoke_agent``,
Codex ``spawnAgent``).  It is keyed by the **tool_use_id of the spawning
call**.

Two producers feed the same :data:`SubagentSummary` shape:

* a live :class:`SubagentRegistry` inside a session manager, which turns the
  harness's native lifecycle signals into :class:`~manager.types.SubagentStarted`
  / ``SubagentProgress`` / ``SubagentFinished`` events (exactly one
  ``started`` and one ``finished`` per subagent);
* the provider adapter's disk reader (``ProviderAdapter.list_subagents``) for
  history.

:func:`merge_summaries` combines them (live wins) for ``session_started`` and
``GET /api/sessions/{sdk_id}/subagents``.  A harness plugs in by calling the
registry from its event translation and implementing the two adapter hooks;
nothing here knows any CLI.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any

from .types import SubagentFinished, SubagentProgress, SubagentStarted

#: Tool names that spawn a subagent (spec 12 SA-2 normalisation + Codex).
SPAWN_TOOL_NAMES = frozenset({"Agent", "Task", "agent", "task", "invoke_agent", "spawnAgent"})

#: Native terminal statuses → wire status.
_STATUS_MAP = {
    "completed": "done",
    "done": "done",
    "success": "done",
    "failed": "failed",
    "error": "failed",
    "stopped": "stopped",
    "killed": "stopped",
    "cancelled": "stopped",
    "interrupted": "stopped",
}

#: Wire fields of a SubagentSummary, in order.
SUMMARY_FIELDS = (
    "tool_use_id", "parent_tool_use_id", "agent_type", "description", "background",
    "status", "activity", "usage", "summary", "started_at", "ended_at",
)

SubagentSummary = dict[str, Any]


def is_spawn_tool(name: str | None) -> bool:
    return bool(name) and name in SPAWN_TOOL_NAMES


def wire_status(raw: str | None) -> str | None:
    """Map a native terminal status to ``done`` | ``failed`` | ``stopped`` (None = not terminal)."""
    if not raw:
        return None
    return _STATUS_MAP.get(str(raw).lower())


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def usage_dict(tokens: Any = None, tool_uses: Any = None, duration_ms: Any = None) -> dict[str, Any]:
    def num(v: Any) -> int | None:
        if isinstance(v, bool):
            return None
        if isinstance(v, (int, float)):
            return int(v)
        if isinstance(v, str) and v.strip().isdigit():
            return int(v.strip())
        return None
    return {"tokens": num(tokens), "tool_uses": num(tool_uses), "duration_ms": num(duration_ms)}


def spawn_fields(tool_input: dict[str, Any] | None) -> tuple[str, str, bool]:
    """``(agent_type, description, background)`` from a spawning call's input."""
    inp = tool_input if isinstance(tool_input, dict) else {}
    agent_type = inp.get("subagent_type") or inp.get("agent_name") or ""
    description = inp.get("description") or inp.get("objective") or ""
    background = bool(inp.get("run_in_background"))
    return str(agent_type), str(description), background


@dataclass(slots=True)
class SubagentRecord:
    tool_use_id: str
    parent_tool_use_id: str | None = None
    agent_type: str = ""
    description: str = ""
    background: bool = False
    status: str = "running"
    activity: str | None = None
    usage: dict[str, Any] | None = None
    summary: str | None = None
    started_at: str | None = None
    ended_at: str | None = None
    #: Harness-side id (Claude: the task / agent id). Not on the wire.
    harness_id: str | None = None
    started_sent: bool = False
    finished_sent: bool = False
    extra: dict[str, Any] = field(default_factory=dict)

    def to_summary(self) -> SubagentSummary:
        return {
            "tool_use_id": self.tool_use_id,
            "parent_tool_use_id": self.parent_tool_use_id,
            "agent_type": self.agent_type,
            "description": self.description,
            "background": self.background,
            "status": self.status,
            "activity": self.activity,
            "usage": dict(self.usage) if self.usage else None,
            "summary": self.summary,
            "started_at": self.started_at,
            "ended_at": self.ended_at,
        }


class SubagentRegistry:
    """Live subagents of one session, in spawn order.

    The ``spawn`` / ``start`` / ``progress`` / ``finish`` methods return the
    wire event to emit (or ``None`` when there is nothing new), so a harness
    adapter can stay a thin translator: ``ev = reg.finish(...); if ev: yield ev``.
    """

    def __init__(self) -> None:
        self._records: dict[str, SubagentRecord] = {}
        self._by_harness_id: dict[str, str] = {}

    # -- lookup ------------------------------------------------------------

    def get(self, tool_use_id: str | None) -> SubagentRecord | None:
        return self._records.get(tool_use_id) if tool_use_id else None

    def __contains__(self, tool_use_id: object) -> bool:
        return isinstance(tool_use_id, str) and tool_use_id in self._records

    def by_harness_id(self, harness_id: str | None) -> SubagentRecord | None:
        tid = self._by_harness_id.get(harness_id) if harness_id else None
        return self._records.get(tid) if tid else None

    def link(self, tool_use_id: str, harness_id: str | None) -> None:
        rec = self._records.get(tool_use_id)
        if rec is not None and harness_id:
            rec.harness_id = harness_id
            self._by_harness_id[harness_id] = tool_use_id

    def summaries(self) -> list[SubagentSummary]:
        return [r.to_summary() for r in self._records.values()]

    def running(self) -> list[SubagentRecord]:
        return [r for r in self._records.values() if r.status == "running"]

    # -- transitions ---------------------------------------------------------

    def spawn(
        self,
        tool_use_id: str,
        *,
        parent_tool_use_id: str | None,
        agent_type: str,
        description: str,
        background: bool,
    ) -> SubagentRecord:
        """Record a spawning tool call (no event: the card itself is the signal)."""
        rec = self._records.get(tool_use_id)
        if rec is None:
            rec = SubagentRecord(tool_use_id=tool_use_id)
            self._records[tool_use_id] = rec
        rec.parent_tool_use_id = parent_tool_use_id
        rec.agent_type = agent_type or rec.agent_type
        rec.description = description or rec.description
        rec.background = rec.background or background
        return rec

    def start(
        self,
        tool_use_id: str,
        *,
        harness_id: str | None = None,
        agent_type: str | None = None,
        description: str | None = None,
        background: bool | None = None,
        parent_tool_use_id: str | None = None,
    ) -> SubagentStarted | None:
        rec = self._records.get(tool_use_id)
        if rec is None:
            rec = SubagentRecord(tool_use_id=tool_use_id, parent_tool_use_id=parent_tool_use_id)
            self._records[tool_use_id] = rec
        if agent_type and not rec.agent_type:
            rec.agent_type = agent_type
        if description and not rec.description:
            rec.description = description
        if background:
            rec.background = True
        self.link(tool_use_id, harness_id)
        if rec.started_sent:
            return None
        rec.started_sent = True
        rec.status = "running"
        rec.started_at = rec.started_at or now_iso()
        return SubagentStarted(
            tool_use_id=tool_use_id,
            parent_tool_use_id=rec.parent_tool_use_id,
            agent_type=rec.agent_type,
            description=rec.description,
            background=rec.background,
            started_at=rec.started_at,
        )

    def progress(
        self, tool_use_id: str, *, activity: str | None, usage: dict[str, Any] | None,
    ) -> SubagentProgress | None:
        rec = self._records.get(tool_use_id)
        if rec is None or rec.finished_sent:
            return None
        if activity:
            rec.activity = activity
        if usage:
            rec.usage = usage
        return SubagentProgress(
            tool_use_id=tool_use_id, activity=rec.activity or "", usage=dict(rec.usage or usage_dict()),
        )

    def note_activity(self, tool_use_id: str, activity: str | None) -> None:
        """Track the latest inner tool name (no event: clients derive it from the frame)."""
        rec = self._records.get(tool_use_id)
        if rec is not None and activity and not rec.finished_sent:
            rec.activity = activity

    def note_text(self, tool_use_id: str, text: str | None) -> None:
        """Remember the subagent's latest answer text: the summary of last
        resort when it finishes without one."""
        rec = self._records.get(tool_use_id)
        if rec is not None and text and text.strip():
            rec.extra["last_text"] = text

    def finish(
        self,
        tool_use_id: str,
        *,
        status: str,
        summary: str | None = None,
        usage: dict[str, Any] | None = None,
    ) -> SubagentFinished | None:
        rec = self._records.get(tool_use_id)
        if rec is None or rec.finished_sent:
            return None
        rec.finished_sent = True
        rec.status = status if status in ("done", "failed", "stopped") else "done"
        if summary:
            rec.summary = summary
        elif not rec.summary and rec.extra.get("last_text"):
            rec.summary = rec.extra["last_text"]
        if usage:
            rec.usage = usage
        rec.ended_at = now_iso()
        return self._finished_event(rec)

    def amend(
        self,
        tool_use_id: str,
        *,
        status: str,
        summary: str | None = None,
        usage: dict[str, Any] | None = None,
    ) -> SubagentFinished | None:
        """A finished subagent gets better data (its notification arrived
        after the finish was flushed): a second ``SubagentFinished`` carries
        it (spec 12 SA-3: a later subagent_finished always applies).  None
        when nothing changed."""
        rec = self._records.get(tool_use_id)
        if rec is None or not rec.finished_sent:
            return None
        changed = False
        if summary and summary != rec.summary:
            rec.summary, changed = summary, True
        if usage and usage != rec.usage:
            rec.usage, changed = usage, True
        if status in ("done", "failed", "stopped") and status != rec.status and rec.status != "stopped":
            rec.status, changed = status, True
        return self._finished_event(rec) if changed else None

    def _finished_event(self, rec: SubagentRecord) -> SubagentFinished:
        return SubagentFinished(
            tool_use_id=rec.tool_use_id,
            status=rec.status,
            summary=rec.summary or "",
            usage=dict(rec.usage or usage_dict()),
            ended_at=rec.ended_at or now_iso(),
        )

    def stop_all(self) -> list[SubagentFinished]:
        """Every running subagent ends as ``stopped`` (session teardown)."""
        out = []
        for rec in list(self._records.values()):
            ev = self.finish(rec.tool_use_id, status="stopped")
            if ev is not None:
                out.append(ev)
        return out


def merge_summaries(
    disk: list[SubagentSummary] | None,
    live: list[SubagentSummary] | None,
) -> list[SubagentSummary]:
    """Disk rows (history, in spawn order) then live-only rows; a live row's
    non-null fields win over the disk row for the same ``tool_use_id``."""
    out: dict[str, SubagentSummary] = {}
    for row in disk or []:
        tid = row.get("tool_use_id")
        if isinstance(tid, str) and tid:
            out[tid] = {k: row.get(k) for k in SUMMARY_FIELDS}
    for row in live or []:
        tid = row.get("tool_use_id")
        if not isinstance(tid, str) or not tid:
            continue
        base = out.get(tid) or {k: None for k in SUMMARY_FIELDS}
        for k in SUMMARY_FIELDS:
            v = row.get(k)
            if v is not None and v != "":
                base[k] = v
        out[tid] = base
    for row in out.values():
        row["status"] = row.get("status") or "running"
        row["agent_type"] = row.get("agent_type") or ""
        row["description"] = row.get("description") or ""
        row["background"] = bool(row.get("background"))
    return list(out.values())
