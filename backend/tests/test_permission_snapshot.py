"""Pending-permission snapshots (spec 12 PM-6).

``permission_request`` is a live-only frame: it is never written to the session's
JSONL, and the pool replays it only to a subscriber that still holds a checkpoint
on the same stream.  A view that attaches *after* the request was emitted — another
device, a reopened conversation, a replay that overflowed — therefore had no way to
learn about it: no approval card appeared and the agent waited on the gate forever
(2026-11-05, an ``ExitPlanMode`` that sat unanswered for ~20 minutes).

The session now remembers each unanswered request, ``session_started`` advertises
them, and the clients seed the card from that snapshot.
"""

import asyncio
from collections.abc import AsyncIterator

import pytest

from manager.base_session import BaseSessionManager
from manager.config import ManagerConfig
from manager.types import Event, PermissionRequest, PermissionResolved


class _Session(BaseSessionManager):
    """Minimal concrete manager: only the permission plumbing is exercised."""

    @property
    def provider_name(self) -> str:
        return "test"

    async def _run_lifecycle(self) -> None:  # pragma: no cover - never started
        raise NotImplementedError

    async def send(self, prompt: str) -> AsyncIterator[Event]:  # pragma: no cover
        raise NotImplementedError
        yield  # type: ignore[unreachable]

    async def interrupt(self) -> None:  # pragma: no cover
        pass


def _session() -> _Session:
    sm = _Session(ManagerConfig())
    # send() normally installs the inbox; the permission path refuses to emit
    # (and auto-allows) without one, so give it a stand-in.
    sm._event_inbox = asyncio.Queue()
    return sm


@pytest.mark.asyncio
async def test_pending_permission_is_advertised_until_answered():
    sm = _session()
    gate = asyncio.create_task(
        sm._emit_permission_request("ExitPlanMode", {"plan": "1. Do it"}),
    )
    await asyncio.sleep(0)  # let the emit register the future

    pending = sm.pending_permissions()
    assert len(pending) == 1
    row = pending[0]
    assert row["tool_name"] == "ExitPlanMode"
    assert row["tool_input"] == {"plan": "1. Do it"}
    assert sm.pending_permission_ids() == [row["request_id"]]
    # The snapshot is the request frame minus the envelope, so a client can
    # build exactly the card it missed.
    emitted = sm._event_inbox.get_nowait()
    assert isinstance(emitted, PermissionRequest)
    assert emitted.request_id == row["request_id"]
    assert emitted.tool_name == row["tool_name"]
    assert emitted.tool_input == row["tool_input"]

    assert sm.resolve_permission(row["request_id"], "allow") is True
    assert await gate == ("allow", None)
    assert sm.pending_permissions() == []
    assert isinstance(sm._event_inbox.get_nowait(), PermissionResolved)


@pytest.mark.asyncio
async def test_tool_input_snapshot_is_a_copy():
    """A later mutation of the caller's dict must not change what we advertise."""
    sm = _session()
    tool_input = {"plan": "original"}
    gate = asyncio.create_task(sm._emit_permission_request("ExitPlanMode", tool_input))
    await asyncio.sleep(0)

    tool_input["plan"] = "mutated"
    assert sm.pending_permissions()[0]["tool_input"] == {"plan": "original"}

    sm.resolve_permission(sm.pending_permission_ids()[0], "deny")
    await gate


@pytest.mark.asyncio
async def test_drain_clears_the_snapshot():
    """End of stream: the backend auto-denies, so nothing stays advertised
    (the clients expire their card the same way, PM-4)."""
    sm = _session()
    gate = asyncio.create_task(sm._emit_permission_request("ExitPlanMode", {}))
    await asyncio.sleep(0)
    assert sm.pending_permissions() != []

    sm._drain_pending_permissions()
    assert sm.pending_permissions() == []
    assert sm.pending_permission_ids() == []
    assert await gate == ("deny", "stream ended")


@pytest.mark.asyncio
async def test_pool_aggregates_pending_permissions_for_the_orchestrator():
    """The orchestrator's "Agent approvals" list is otherwise fed only by live
    ``nested_session_event`` frames, so its view needs the same snapshot."""
    from api.pool import SessionPool

    pool = SessionPool()
    a, b = _session(), _session()
    pool._sessions["s-a"] = a
    pool._sessions["s-b"] = b
    gate = asyncio.create_task(a._emit_permission_request("Bash", {"command": "ls"}))
    await asyncio.sleep(0)

    rows = pool.pending_agent_permissions()
    assert len(rows) == 1
    assert rows[0]["session_id"] == "s-a"
    assert rows[0]["tool_name"] == "Bash"
    assert rows[0]["request_id"] in a.pending_permission_ids()

    a.resolve_permission(rows[0]["request_id"], "deny")
    await gate
    assert pool.pending_agent_permissions() == []


@pytest.mark.asyncio
async def test_pool_tolerates_a_manager_without_the_method():
    """Never break a `session_started` over a manager that predates PM-6."""
    from api.pool import SessionPool

    class _Legacy:
        pass

    pool = SessionPool()
    pool._sessions["old"] = _Legacy()  # type: ignore[assignment]
    assert pool.pending_agent_permissions() == []


@pytest.mark.asyncio
async def test_orchestrator_session_started_carries_agent_permissions():
    """Every orchestrator `session_started` advertises them, so the "Agent
    approvals" list survives a reconnect, a voice rebuild and a second device."""
    from api.pool import SessionPool
    from api.routes.orchestrator import _attach_agent_permissions

    pool = SessionPool()
    sm = _session()
    pool._sessions["s-a"] = sm
    gate = asyncio.create_task(sm._emit_permission_request("ExitPlanMode", {"plan": "1."}))
    await asyncio.sleep(0)

    payload = _attach_agent_permissions({"type": "session_started"}, pool)
    assert payload["agent_permissions"] == [{
        "session_id": "s-a",
        "request_id": sm.pending_permission_ids()[0],
        "tool_name": "ExitPlanMode",
        "tool_input": {"plan": "1."},
    }]

    sm.resolve_permission(sm.pending_permission_ids()[0], "allow")
    await gate
    assert _attach_agent_permissions({}, pool)["agent_permissions"] == []


def test_orchestrator_payload_tolerates_a_pool_without_the_method():
    from api.routes.orchestrator import _attach_agent_permissions

    assert _attach_agent_permissions({}, object())["agent_permissions"] == []  # type: ignore[arg-type]
