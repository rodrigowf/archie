"""AskUserQuestion actually asks the user (spec 12 QU-1..QU-6).

The bundled CLI has no UI channel of its own for this tool: it collects the
answers *through the permission callback* and reads them back out of the tool
input it is handed — the schema calls ``answers`` "User answers collected by
the permission component".  A host that merely allows the call hands the tool
an input with no answers, and the CLI resolves it itself in ~70 ms with "The
user did not answer the questions."  That is what we did until 2026-11-05: 54
such calls across 14 sessions, the agent never waiting and the user never
asked (one of them was a deploy question in the session that found the bug).

These tests cover the gate itself; the CLI side of the contract (which shapes
it accepts, and that ``response`` overrides ``answers``) was verified against
the real binary and is pinned in ``test_cli_answer_contract``.
"""

import asyncio
from typing import Any

import pytest

from manager.base_session import QuestionAnswer
from manager.claude.session import ClaudeSessionManager
from manager.config import ManagerConfig
from manager.types import QuestionRequest, QuestionResolved


class _Ctx:
    """Stand-in for the SDK's ToolPermissionContext."""

    def __init__(self, tool_use_id: str | None = "toolu_1"):
        self.tool_use_id = tool_use_id
        self.suggestions: list = []
        self.signal = None


def _manager() -> ClaudeSessionManager:
    sm = ClaudeSessionManager(config=ManagerConfig())
    sm._event_inbox = asyncio.Queue()  # normally installed by send()
    return sm


def _questions(multi: bool = False) -> list[dict[str, Any]]:
    return [{
        "question": "Which database?",
        "header": "Storage",
        "multiSelect": multi,
        "options": [{"label": "Postgres", "description": "…"}, {"label": "SQLite"}],
    }]


async def _ask(sm: ClaudeSessionManager, questions=None, ctx=None):
    """Start the gated call and wait until it is registered as pending."""
    task = asyncio.create_task(
        sm._answer_user_question({"questions": questions or _questions()}, ctx or _Ctx()),
    )
    for _ in range(50):
        await asyncio.sleep(0)
        if sm.pending_question_ids():
            return task, sm.pending_question_ids()[0]
    raise AssertionError("the question was never registered as pending")


@pytest.mark.asyncio
async def test_the_call_blocks_until_answered_and_carries_the_answer_back():
    sm = _manager()
    task, rid = await _ask(sm)

    # Blocked: this is the whole point — the agent waits for a person.
    assert not task.done()
    request = _next_event(sm)
    assert isinstance(request, QuestionRequest)
    assert request.request_id == rid
    assert request.tool_use_id == "toolu_1"
    assert request.questions[0]["options"][0]["label"] == "Postgres"

    assert sm.resolve_question(rid, answers={"Which database?": "SQLite"}) is True
    result = await asyncio.wait_for(task, timeout=2)

    assert result.updated_input["answers"] == {"Which database?": "SQLite"}
    # The questions must survive: the CLI looks the answers up by question text.
    assert result.updated_input["questions"] == _questions()
    assert "response" not in result.updated_input
    resolved = _next_event(sm)
    assert isinstance(resolved, QuestionResolved)
    assert resolved.answers == {"Which database?": "SQLite"}
    assert resolved.responder == "user"
    assert sm.pending_question_ids() == []


@pytest.mark.asyncio
async def test_typed_prose_answers_as_response():
    """QU-4: the user types instead of picking. The CLI renders this as
    "The user responded: …"."""
    sm = _manager()
    task, rid = await _ask(sm)

    assert sm.resolve_question(rid, response="neither — use the file store") is True
    result = await asyncio.wait_for(task, timeout=2)

    assert result.updated_input["response"] == "neither — use the file store"
    assert result.updated_input["answers"] == {}


@pytest.mark.asyncio
async def test_a_typed_note_never_silently_eats_the_selections():
    """The CLI ignores `answers` entirely when `response` is non-empty, so a
    user who both picks an option and types a note would lose the pick. The
    note is demoted to an annotation, which the CLI renders next to it."""
    sm = _manager()
    task, rid = await _ask(sm)

    sm.resolve_question(
        rid,
        answers={"Which database?": "Postgres"},
        response="but check the disk first",
    )
    result = await asyncio.wait_for(task, timeout=2)

    assert result.updated_input["answers"] == {"Which database?": "Postgres"}
    assert "response" not in result.updated_input
    assert result.updated_input["annotations"] == {
        "Which database?": {"notes": "but check the disk first"},
    }


@pytest.mark.asyncio
async def test_multi_select_answers_pass_through_as_a_list():
    sm = _manager()
    task, rid = await _ask(sm, questions=_questions(multi=True))

    sm.resolve_question(rid, answers={"Which database?": ["Postgres", "SQLite"]})
    result = await asyncio.wait_for(task, timeout=2)

    assert result.updated_input["answers"] == {"Which database?": ["Postgres", "SQLite"]}


@pytest.mark.asyncio
async def test_an_unoffered_label_is_accepted():
    """"Other" free text is attributed to its own question by sending it as the
    answer; the CLI passes it through with a "read carefully" framing."""
    sm = _manager()
    task, rid = await _ask(sm)

    sm.resolve_question(rid, answers={"Which database?": "DuckDB"})
    result = await asyncio.wait_for(task, timeout=2)

    assert result.updated_input["answers"] == {"Which database?": "DuckDB"}


@pytest.mark.asyncio
async def test_unanswered_at_stream_end_lets_the_tool_report_it():
    """The turn ends with nobody answering: allow the call unchanged so the CLI
    emits its own "did not answer" result. The agent carries on; it does not hang."""
    sm = _manager()
    task, _rid = await _ask(sm)

    sm._drain_pending_questions()
    result = await asyncio.wait_for(task, timeout=2)

    assert result.updated_input is None
    assert sm.pending_questions() == []
    resolved = [e for e in _drain_queue(sm) if isinstance(e, QuestionResolved)]
    assert resolved and resolved[0].responder == "system"
    assert resolved[0].message == "stream ended"


@pytest.mark.asyncio
async def test_no_active_stream_does_not_block_the_turn():
    """Nobody is listening (an out-of-band call): answering is impossible, so
    the call must not hang waiting for an answer that cannot come."""
    sm = ClaudeSessionManager(config=ManagerConfig())
    result = await asyncio.wait_for(
        sm._answer_user_question({"questions": _questions()}, _Ctx()), timeout=2,
    )
    assert result.updated_input is None


@pytest.mark.asyncio
async def test_malformed_input_is_left_to_the_cli():
    sm = _manager()
    result = await asyncio.wait_for(
        sm._answer_user_question({"questions": []}, _Ctx()), timeout=2,
    )
    assert result.updated_input is None
    assert sm.pending_questions() == []


@pytest.mark.asyncio
async def test_first_answer_wins():
    sm = _manager()
    task, rid = await _ask(sm)

    assert sm.resolve_question(rid, answers={"Which database?": "SQLite"}) is True
    assert sm.resolve_question(rid, answers={"Which database?": "Postgres"}) is False
    result = await asyncio.wait_for(task, timeout=2)
    assert result.updated_input["answers"] == {"Which database?": "SQLite"}


@pytest.mark.asyncio
async def test_pending_snapshot_is_what_a_late_client_seeds_from():
    """QU-6, the PM-6 lesson applied up front: `question_request` is live-only,
    so a view that attaches mid-question can only learn about it from here."""
    sm = _manager()
    task, rid = await _ask(sm)

    assert sm.pending_questions() == [{
        "request_id": rid,
        "questions": _questions(),
        "tool_use_id": "toolu_1",
    }]

    sm.resolve_question(rid, response="go")
    await asyncio.wait_for(task, timeout=2)
    assert sm.pending_questions() == []


@pytest.mark.asyncio
@pytest.mark.parametrize("answers,response", [
    ({"Which database?": "SQLite"}, "a note"),
    ({"Which database?": ["Postgres"]}, "   "),
    ({}, "just prose"),
    ({"Which database?": "SQLite"}, None),
])
async def test_answers_and_response_are_never_sent_together(answers, response):
    """Pins the CLI contract measured against the real binary on 2026-11-05
    (CLI 2.1.292): a non-empty ``response`` makes it ignore ``answers``
    entirely — 'answers + response' resolved to "The user responded: …" with
    the selection dropped. Whatever a client sends, the payload we hand the
    tool must therefore carry at most one of the two.
    """
    sm = _manager()
    task, rid = await _ask(sm)

    sm.resolve_question(rid, answers=answers, response=response)
    result = await asyncio.wait_for(task, timeout=2)

    patch = result.updated_input
    assert not (patch.get("answers") and patch.get("response")), patch
    # Nothing is lost: a note that cannot ride along as `response` becomes one.
    if answers and (response or "").strip():
        assert patch["annotations"]["Which database?"]["notes"] == response.strip()


def test_question_answer_knows_when_it_is_empty():
    assert QuestionAnswer(answers={"q": "a"}).answered()
    assert QuestionAnswer(response="hi").answered()
    assert QuestionAnswer(annotations={"q": {"notes": "n"}}).answered()
    assert not QuestionAnswer().answered()
    assert not QuestionAnswer(response="   ").answered()


def _unwrap(event):
    """The Claude manager stamps injected events with a seq and appends them to
    the replay ring (so a reconnecting subscriber gets the question replayed);
    the payload rides inside a SequencedEvent."""
    return getattr(event, "event", event)


def _next_event(sm):
    return _unwrap(sm._event_inbox.get_nowait())


def _drain_queue(sm) -> list:
    out = []
    while True:
        try:
            out.append(_unwrap(sm._event_inbox.get_nowait()))
        except asyncio.QueueEmpty:
            return out


# ---------------------------------------------------------------------------
# The wire: WS frame, typed text, REST twin, orchestrator (QU-3..QU-6)
# ---------------------------------------------------------------------------

class _Sm:
    """A session blocked on one question."""

    sdk_session_id = "sdk-1"
    supports_steering = True

    def __init__(self):
        self.answered: list[dict] = []

    def pending_question_ids(self):
        return ["q1"]

    def pending_questions(self):
        return [{"request_id": "q1", "questions": _questions(), "tool_use_id": "toolu_1"}]

    def pending_permission_ids(self):
        return []

    def pending_permissions(self):
        return []

    def resolve_question(self, request_id, *, answers=None, response=None,
                         annotations=None, responder="user"):
        self.answered.append({
            "request_id": request_id, "answers": answers,
            "response": response, "responder": responder,
        })
        return True


@pytest.mark.asyncio
async def test_pool_routes_an_answer_to_the_session():
    from api.pool import SessionPool

    pool = SessionPool()
    sm = _Sm()
    pool._sessions["s-a"] = sm  # type: ignore[assignment]

    assert await pool.resolve_session_question(
        "s-a", "q1", answers={"Which database?": "SQLite"},
    ) is True
    assert sm.answered[0]["answers"] == {"Which database?": "SQLite"}
    assert await pool.resolve_session_question("nope", "q1", response="x") is False


@pytest.mark.asyncio
async def test_typed_text_answers_the_question_instead_of_being_sent():
    """QU-4. The harness is blocked inside the tool call and hands the text to
    the model as the tool's result, so the message must not also be steered —
    that would deliver it twice."""
    from api.routes.chat import _answer_pending_questions

    class _Pool:
        def __init__(self):
            self.calls: list[tuple] = []

        async def resolve_session_question(self, sid, rid, **kw):
            self.calls.append((sid, rid, kw))
            return True

    pool, sm = _Pool(), _Sm()
    assert await _answer_pending_questions(pool, sm, "s-a", "use sqlite") is True
    assert pool.calls == [("s-a", "q1", {"response": "use sqlite", "responder": "user"})]

    class _Quiet(_Sm):
        def pending_question_ids(self):
            return []

    assert await _answer_pending_questions(_Pool(), _Quiet(), "s-a", "hello") is False


@pytest.mark.asyncio
async def test_pool_lists_agent_questions_for_the_orchestrator():
    from api.pool import SessionPool
    from api.routes.orchestrator import _attach_agent_permissions

    pool = SessionPool()
    pool._sessions["s-a"] = _Sm()  # type: ignore[assignment]

    payload = _attach_agent_permissions({"type": "session_started"}, pool)
    assert payload["agent_questions"] == [{
        "session_id": "s-a",
        "request_id": "q1",
        "questions": _questions(),
        "tool_use_id": "toolu_1",
    }]
    assert payload["agent_permissions"] == []


@pytest.mark.asyncio
async def test_orchestrator_tool_answers_a_question():
    from orchestrator.tools.agent_sessions import respond_to_agent_question

    class _Pool:
        def __init__(self, won=True):
            self.won, self.calls = won, []

        async def resolve_session_question(self, sid, rid, **kw):
            self.calls.append((sid, rid, kw))
            return self.won

    pool = _Pool()
    out = await respond_to_agent_question(
        {"pool": pool}, session_id="s-a", request_id="q1",
        answers={"Which database?": "SQLite"},
    )
    assert '"result": "ok"' in out
    assert pool.calls[0][2]["responder"] == "orchestrator"

    lost = await respond_to_agent_question(
        {"pool": _Pool(won=False)}, session_id="s-a", request_id="q1", response="x",
    )
    assert '"no_op"' in lost

    empty = await respond_to_agent_question({"pool": _Pool()}, session_id="s", request_id="q")
    assert "error" in empty


def test_read_agent_session_names_pending_questions():
    """The orchestrator cannot answer what it cannot name: the request_id and
    the options have to reach the model through its own read tool."""
    import json as _json
    from datetime import datetime, timezone
    from types import SimpleNamespace

    from orchestrator.tools.agent_sessions import read_agent_session

    preview = SimpleNamespace(
        role="assistant", text="thinking",
        timestamp=datetime(2026, 11, 5, tzinfo=timezone.utc),
    )
    ctx = {
        "pool": SimpleNamespace(get=lambda _s: _Sm(), restored_sdk_id=lambda _s: None),
        "store": SimpleNamespace(get_preview=lambda _s, max_messages=None: [preview]),
        "session": SimpleNamespace(is_voice=False),
    }
    body = _json.loads(asyncio.get_event_loop().run_until_complete(
        read_agent_session(ctx, session_id="s-a"),
    ) if False else asyncio.run(read_agent_session(ctx, session_id="s-a")))

    pending = body["live"]["pending_questions"]
    assert pending[0]["request_id"] == "q1"
    assert pending[0]["questions"][0]["options"] == ["Postgres", "SQLite"]
    assert pending[0]["questions"][0]["question"] == "Which database?"


def test_question_frames_serialize_for_the_wire():
    from api.serializers import serialize_event

    req = serialize_event(QuestionRequest(
        request_id="q1", questions=_questions(), tool_use_id="toolu_1",
    ))
    assert req == {
        "type": "question_request", "request_id": "q1",
        "questions": _questions(), "tool_use_id": "toolu_1",
    }
    res = serialize_event(QuestionResolved(
        request_id="q1", responder="user",
        answers={"Which database?": "SQLite"}, response=None,
    ))
    assert res["type"] == "question_resolved"
    assert res["answers"] == {"Which database?": "SQLite"}
    assert res["responder"] == "user"


@pytest.mark.asyncio
async def test_a_question_keeps_a_delegated_turn_alive():
    """The runner reaps a delegated turn after 30 min without events. A turn
    waiting on a person is not idle — a background agent that asks something
    must not be killed while the answer is still on its way (the same rule a
    pending permission already had)."""
    import time
    from unittest.mock import MagicMock

    from orchestrator.runner import BackgroundAgentRunner, _TurnRecord, _TurnTimedOut

    runner = BackgroundAgentRunner(MagicMock(), MagicMock(), MagicMock())
    record = _TurnRecord(
        turn_id="t1", session_id="s-a", session_title=None, origin_tool_use_id=None,
        started_at=time.monotonic(), started_at_wall=time.time(),
        last_progress_at=time.monotonic() - 10.0,  # already past the timeout
    )

    async def _never():
        await asyncio.sleep(30)

    # Waiting on a question: not idle, so the watchdog leaves it running.
    record.pending_question_ids = ("q1",)
    with pytest.raises(asyncio.TimeoutError):
        await asyncio.wait_for(runner._run_watched(record, _never(), idle_timeout=0.2), timeout=0.6)

    # Answered: the clock starts counting again and the turn is reaped.
    record.pending_question_ids = ()
    record.last_progress_at = time.monotonic() - 10.0
    with pytest.raises(_TurnTimedOut):
        await asyncio.wait_for(runner._run_watched(record, _never(), idle_timeout=0.2), timeout=5)


@pytest.mark.asyncio
async def test_rest_twin_answers_or_explains_why_it_cannot():
    """A device with no socket open on that agent (an approvals list, a
    notification action) answers over REST instead — spec 12 QU-3."""
    from fastapi import HTTPException

    from api.routes.sessions import resolve_question as rest_resolve

    class _Req:
        def __init__(self, body): self._body = body
        async def json(self): return self._body

    class _Pool:
        def __init__(self, live=True, won=True):
            self._live, self._won, self.calls = live, won, []

        def has(self, _sid): return self._live

        async def resolve_session_question(self, sid, rid, **kw):
            self.calls.append((sid, rid, kw))
            return self._won

    pool = _Pool()
    assert await rest_resolve("s-a", _Req({
        "request_id": "q1", "answers": {"Which database?": "SQLite"},
    }), pool) == {"ok": True}
    assert pool.calls[0][2]["answers"] == {"Which database?": "SQLite"}

    for body, code in (
        ({"answers": {"q": "a"}}, 400),           # no request_id
        ({"request_id": "q1"}, 400),              # neither answers nor response
        ({"request_id": "q1", "response": "  "}, 400),
        ({"request_id": "q1", "answers": "nope"}, 400),
    ):
        with pytest.raises(HTTPException) as e:
            await rest_resolve("s-a", _Req(body), _Pool())
        assert e.value.status_code == code

    with pytest.raises(HTTPException) as e:
        await rest_resolve("gone", _Req({"request_id": "q1", "response": "x"}), _Pool(live=False))
    assert e.value.status_code == 404

    # Someone answered first: 409, and the UI simply closes its picker on the
    # question_resolved broadcast that is already on its way.
    with pytest.raises(HTTPException) as e:
        await rest_resolve("s-a", _Req({"request_id": "q1", "response": "x"}), _Pool(won=False))
    assert e.value.status_code == 409
