"""``voice_status: summarizing`` goes to the device starting voice, never to everyone (2026-10-12).

A passive device attaching to another device's call reaches ``_attach_voice_payload``
without an initiator socket; the status used to be broadcast then, and every other
device's face sat on "Preparing" — nothing ends that phase on a device that didn't
start the call (the A300M after a POCO voice call).
"""

from __future__ import annotations

from pathlib import Path
from unittest.mock import AsyncMock, MagicMock, patch

import orjson
import pytest

from api.routes.orchestrator import _attach_voice_payload


def _session(tmp_path: Path, pool: MagicMock) -> MagicMock:
    jsonl = tmp_path / "conv.jsonl"
    jsonl.write_text("{}\n")
    s = MagicMock()
    s._jsonl_path = jsonl
    s._context = {"pool": pool}
    s._voice_relay = None
    s._voice_provider = None
    s.needs_voice_relay = False
    s.get_session_update = AsyncMock(return_value={})
    return s


@pytest.mark.asyncio
async def test_a_passive_attach_never_broadcasts_summarizing(tmp_path: Path) -> None:
    pool = MagicMock(broadcast_orchestrator=AsyncMock())
    with patch("api.routes.orchestrator.summary_cache.is_fresh", return_value=False):
        await _attach_voice_payload({}, _session(tmp_path, pool), initiator=False, initiator_ws=None)
    pool.broadcast_orchestrator.assert_not_called()


@pytest.mark.asyncio
async def test_the_initiator_hears_it(tmp_path: Path) -> None:
    pool = MagicMock(broadcast_orchestrator=AsyncMock())
    ws = MagicMock()
    with patch("api.routes.orchestrator.summary_cache.is_fresh", return_value=False), \
            patch("api.routes.orchestrator._safe_send_bytes", new=AsyncMock()) as send:
        await _attach_voice_payload({}, _session(tmp_path, pool), initiator=True, initiator_ws=ws)
    frames = [orjson.loads(c.args[1]) for c in send.call_args_list if c.args[0] is ws]
    assert {"type": "voice_status", "status": "summarizing"} in [f.get("event") for f in frames]
    pool.broadcast_orchestrator.assert_not_called()
