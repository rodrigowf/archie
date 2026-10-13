"""Steering (spec 12 I-16): a message sent while an agent turn runs goes
into that turn instead of waiting behind it.

The Claude CLI (2.1.292, probes 2026-10-13) takes a user message written to
its stdin mid-turn and hands it to the model at the turn's next request
(``status: requesting``), recording it as a ``queued_command`` attachment.
When the turn ends before another request, the CLI answers it in a turn of
its own right after (``init`` … ``ResultMessage``, origin unset).  After an
interrupt the CLI still starts that turn — Stop must stop it too.
"""

from __future__ import annotations

import asyncio
import json
from collections import deque
from unittest.mock import AsyncMock, MagicMock, patch

import orjson
import pytest
from claude_agent_sdk import AssistantMessage, ResultMessage, SystemMessage, TextBlock
from starlette.websockets import WebSocketState

from api.pool import SessionPool
from manager.claude.adapter import ClaudeAdapter
from manager.claude.session import SessionManager
from manager.types import TextComplete, TurnComplete
from tests.test_session import _make_mock_client

pytestmark = pytest.mark.asyncio


def _init():
    return SystemMessage(subtype="init", data={"session_id": "sdk-1"})


def _requesting():
    return SystemMessage(subtype="status", data={"status": "requesting"})


def _text(t):
    return AssistantMessage(content=[TextBlock(text=t)], model="m")


def _result(text="done", total=0.0):
    # total_cost_usd is the CLI's running total; TurnComplete.cost the delta.
    return ResultMessage(
        subtype="success", duration_ms=1, duration_api_ms=1, is_error=False, num_turns=1,
        session_id="sdk-1", total_cost_usd=total, usage={"input_tokens": 1, "output_tokens": 1},
        result=text,
    )


async def _process(sm, *msgs):
    out = []
    for m in msgs:
        async for ev in sm._process_message(m):
            out.append(ev)
    return out


def _turn_open(sm):
    """A send() of ours is running and the CLI started on it."""
    sm._event_inbox = asyncio.Queue()
    sm._client = MagicMock()
    sm._client.query = AsyncMock()
    sm._client.interrupt = AsyncMock()


# ---------------------------------------------------------------------------
# Session manager bookkeeping
# ---------------------------------------------------------------------------

class TestSteerBookkeeping:
    async def test_steer_refused_without_a_turn_of_ours(self):
        sm = SessionManager()
        assert await sm.steer("hi") is False          # not connected
        sm._client = MagicMock()
        sm._client.query = AsyncMock()
        assert await sm.steer("hi") is False          # no send() running
        sm._client.query.assert_not_awaited()

    async def test_steer_absorbed_by_the_next_request_ends_normally(self):
        sm = SessionManager()
        _turn_open(sm)
        await _process(sm, _init(), _requesting())
        assert await sm.steer("also do X") is True
        sm._client.query.assert_awaited_once_with("also do X")
        await _process(sm, _requesting())               # the CLI hands it over
        (tc,) = [e for e in await _process(sm, _text("ok"), _result()) if isinstance(e, TurnComplete)]
        assert tc.prompted and not tc.continues
        assert sm._steers == []

    async def test_turn_ending_first_continues_and_carries_cost(self):
        sm = SessionManager()
        _turn_open(sm)
        await _process(sm, _init(), _requesting())
        assert await sm.steer("also do X")
        (first,) = [e for e in await _process(sm, _result(total=0.02)) if isinstance(e, TurnComplete)]
        assert first.continues and not first.prompted
        # The CLI's own turn for the message follows at once.
        evs = await _process(sm, _init(), _requesting(), _text("did X"), _result(total=0.05))
        (last,) = [e for e in evs if isinstance(e, TurnComplete)]
        assert last.prompted and not last.continues
        assert last.cost == pytest.approx(0.05) and last.num_turns == 2
        assert sm._steers == []

    async def test_steer_waits_for_the_cli_to_start_the_turn(self):
        sm = SessionManager()
        _turn_open(sm)                                   # prompt written, no init yet
        task = asyncio.create_task(sm.steer("early"))
        await asyncio.sleep(0.02)
        sm._client.query.assert_not_awaited()
        await _process(sm, _init())
        assert await task is True
        assert sm._steers == [sm._turn_gen]

    async def test_stop_drops_the_turn_the_cli_starts_for_steers(self):
        sm = SessionManager()
        _turn_open(sm)
        await _process(sm, _init(), _requesting())
        assert await sm.steer("also do X")
        await sm.interrupt()
        (tc,) = [e for e in await _process(sm, _result()) if isinstance(e, TurnComplete)]
        assert tc.prompted and not tc.continues          # Stop ends the user's turn
        sm._inbox_closed = True                          # send() got its TurnComplete
        # The CLI starts the steer's turn anyway: interrupted at once, unseen.
        assert await _process(sm, _init(), _requesting(), _text("did X"), _result()) == []
        await asyncio.sleep(0)
        assert sm._client.interrupt.await_count == 2
        assert not sm._swallow_turn and sm._steers == []


class TestSendFollowsTheSteerTurn:
    async def test_one_send_spans_the_cli_turn_for_the_steer(self):
        steered = asyncio.Event()
        calls = {"n": 0}

        async def response():
            calls["n"] += 1
            if calls["n"] == 1:                          # our prompt
                yield _init()
                yield _requesting()
                await steered.wait()
                yield _text("first answer")
                yield _result()
                yield _init()                            # the CLI's turn for the steer
                yield _requesting()
                yield _text("second answer")
                yield _result()
            # the steer itself: its messages come from the stream above
            return

        client = _make_mock_client([], response)
        with patch("manager.claude.session.ClaudeSDKClient", return_value=client):
            sm = SessionManager()
            await sm.start()
            events: list = []

            async def consume():
                async for e in sm.send("go"):
                    events.append(e)
            consumer = asyncio.create_task(consume())
            for _ in range(100):
                if sm._cli_turn_active:
                    break
                await asyncio.sleep(0.01)
            assert await sm.steer("and then this") is True
            steered.set()
            await asyncio.wait_for(consumer, 5)
            texts = [e.text for e in events if isinstance(e, TextComplete)]
            assert texts == ["first answer", "second answer"]
            ends = [e for e in events if isinstance(e, TurnComplete)]
            assert [e.continues for e in ends] == [True, False]
            assert ends[-1].prompted
            await sm.stop()


# ---------------------------------------------------------------------------
# Pool
# ---------------------------------------------------------------------------

def _capture_ws(store: list):
    ws = MagicMock()
    ws.client_state = WebSocketState.CONNECTED

    async def _send(data):
        store.append(orjson.loads(data))
    ws.send_bytes = _send
    return ws


def _stub_sm(steers: bool):
    sm = MagicMock()
    sm.local_id = "s1"
    sm.sdk_session_id = "sdk-1"
    sm.provider_name = "claude"
    sm.stream_id = None
    sm.pending_permission_ids = MagicMock(return_value=[])
    sm._receive_loop_done = asyncio.Event()
    sm.supports_steering = steers
    sm.steer = AsyncMock(return_value=steers)
    return sm


def _install(pool, sm):
    pool._sessions[sm.local_id] = sm
    pool._subscribers[sm.local_id] = set()
    pool._locks[sm.local_id] = asyncio.Lock()
    pool._pending_prompts[sm.local_id] = deque()
    pool._pending_locks[sm.local_id] = asyncio.Lock()


def _blocking_send(release: asyncio.Event, started: asyncio.Event, sent: list):
    async def _send(text):
        sent.append(text)
        started.set()
        await release.wait()
        yield TextComplete(text=f"out-{text}")
        yield TurnComplete(cost=0.0, num_turns=1, session_id="sdk-1")
    return _send


class TestPoolSteer:
    async def test_mid_turn_send_is_steered_and_echoed_to_everyone(self):
        pool = SessionPool()
        sm = _stub_sm(steers=True)
        release, started, sent = asyncio.Event(), asyncio.Event(), []
        sm.send = _blocking_send(release, started, sent)
        _install(pool, sm)
        frames: list = []
        sender = _capture_ws(frames)
        pool._subscribers["s1"].add(sender)

        await pool.start_turn("s1", "first")
        await started.wait()
        assert await pool.send_or_queue("s1", "change course", source_ws=sender) == "steered"
        sm.steer.assert_awaited_once_with("change course")
        assert {"type": "user_message", "text": "change course", "steered": True} in frames
        assert not pool._pending_prompts["s1"]
        release.set()
        await asyncio.sleep(0.05)
        assert sent == ["first"]                          # no second turn

    async def test_the_orchestrator_runners_send_counts_as_a_running_turn(self):
        pool = SessionPool()
        sm = _stub_sm(steers=True)
        _install(pool, sm)
        async with pool._locks["s1"]:                     # pool.send() in flight
            assert pool.turn_in_flight("s1")
            assert await pool.steer("s1", "hey") is True
        assert not pool.turn_in_flight("s1")
        assert await pool.steer("s1", "late") is False

    async def test_harness_without_steering_still_queues(self):
        pool = SessionPool()
        sm = _stub_sm(steers=False)
        release, started, sent = asyncio.Event(), asyncio.Event(), []
        sm.send = _blocking_send(release, started, sent)
        _install(pool, sm)

        await pool.start_turn("s1", "first")
        await started.wait()
        assert await pool.send_or_queue("s1", "second") == "queued"
        release.set()
        await asyncio.sleep(0.1)
        assert sent == ["first", "second"]

    async def test_continuing_turn_complete_is_not_broadcast(self):
        pool = SessionPool()
        sm = _stub_sm(steers=True)

        async def _send(text):
            yield TextComplete(text="first")
            yield TurnComplete(origin="steer-continues")
            yield TextComplete(text="second")
            yield TurnComplete(cost=0.0, num_turns=2, session_id="sdk-1")
        sm.send = _send
        _install(pool, sm)
        frames: list = []
        pool._subscribers["s1"].add(_capture_ws(frames))
        finished = []
        pool.announce_turn_finished = lambda sid, **kw: finished.append(kw)

        async for _ in pool.send("s1", "go"):
            pass
        assert [f["type"] for f in frames].count("turn_complete") == 1
        assert len(finished) == 1 and finished[0]["text"] == "second"


# ---------------------------------------------------------------------------
# History: a message handed to the running turn reads back as a user message
# ---------------------------------------------------------------------------

def test_history_reads_a_steered_message_as_a_user_message(tmp_path):
    lines = [
        {"type": "user", "uuid": "u1", "message": {"role": "user", "content": "go"}, "timestamp": "2026-10-13T10:00:00Z"},
        {"type": "assistant", "uuid": "a1", "message": {"role": "assistant", "content": [{"type": "text", "text": "working"}]}},
        {"type": "queue-operation", "operation": "enqueue", "content": "also X"},
        {"type": "attachment", "uuid": "q1", "timestamp": "2026-10-13T10:00:05Z",
         "attachment": {"type": "queued_command", "prompt": "also X", "commandMode": "prompt"}},
        {"type": "attachment", "uuid": "q2",
         "attachment": {"type": "queued_command", "prompt": "<task-notification>…", "commandMode": "task-notification"}},
        {"type": "assistant", "uuid": "a2", "message": {"role": "assistant", "content": [{"type": "text", "text": "did X"}]}},
    ]
    path = tmp_path / "s.jsonl"
    path.write_text("\n".join(json.dumps(line) for line in lines) + "\n")
    adapter = ClaudeAdapter()
    msgs = adapter.read_messages(path)
    assert [(m["type"], m.get("steered", False)) for m in msgs] == [
        ("user", False), ("assistant", False), ("user", True), ("assistant", False),
    ]
    assert msgs[2]["message"]["content"] == "also X"
    objs = [json.loads(line) for line in path.read_text().splitlines()]
    assert adapter.visible_line_indices(objs) == [0, 1, 3, 5]
