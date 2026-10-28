"""Imported ChatGPT conversations: harness detection, indexing, and read-only resume exclusion.

Covers the three integration points of ``shared/scripts/chatgpt_import.py`` output:
  * ``_detect_harness`` recognises the ``chatgpt_meta`` header (and still recognises the others).
  * ``extract_session`` / ``session_sources`` make the imported JSONL searchable.
  * ``_kind_matches`` / ``is_read_only_session`` keep them out of every resume path.
"""

import json

import pytest

from utils import history_index as hx


# ── fixtures ──────────────────────────────────────────────────────────────────


def _chatgpt_lines(cid: str = "11111111-2222-3333-4444-555555555555") -> list[dict]:
    """A minimal import: header + a text turn, a voice turn, and a reasoning turn."""
    return [
        {
            "type": "chatgpt_meta",
            "harness": "chatgpt",
            "schema_version": 1,
            "read_only": True,
            "session_id": cid,
            "conversation_id": cid,
            "title": "Imported conversation",
            "create_time": 1700000000.0,
            "timestamp": "2023-11-14T22:13:20+00:00",
            "ui": {"label": "ChatGPT", "icon": "chatgpt-mark", "resumable": False},
            "counts": {"turns": 3},
        },
        {
            "type": "user",
            "create_time": 1700000001.0,
            "timestamp": "2023-11-14T22:13:21+00:00",
            "node_id": "n1",
            "parent_id": None,
            "message": {"role": "user", "content": [{"type": "text", "text": "How do gradients flow?"}]},
        },
        {
            "type": "assistant",
            "create_time": 1700000002.0,
            "timestamp": "2023-11-14T22:13:22+00:00",
            "node_id": "n2",
            "parent_id": "n1",
            "voice": True,
            "message": {"role": "assistant", "content": [{"type": "text", "text": "Backwards through the chain rule."}]},
            "audio": {"available": False, "direction": "out", "format": "wav",
                      "file_id": "file_dead", "reason": "not_in_export"},
        },
        {
            "type": "assistant",
            "create_time": 1700000003.0,
            "timestamp": "2023-11-14T22:13:23+00:00",
            "node_id": "n3",
            "parent_id": "n2",
            "reasoning": True,
            "reasoning_mode": "thoughts",
            "content_type": "thoughts",
            "message": {"role": "assistant", "content": [{"type": "text", "text": "Weighing two framings here."}]},
        },
    ]


@pytest.fixture
def imported(tmp_path):
    """A context dir with one imported ChatGPT conversation. -> (context_dir, session_id)."""
    cid = "11111111-2222-3333-4444-555555555555"
    d = tmp_path / "chatgpt-history" / "jsonl"
    d.mkdir(parents=True)
    with (d / f"{cid}.jsonl").open("w", encoding="utf-8") as fh:
        for line in _chatgpt_lines(cid):
            fh.write(json.dumps(line) + "\n")
    return tmp_path, cid


# ── 1. detection ──────────────────────────────────────────────────────────────


class TestDetectHarness:
    def test_chatgpt_meta_header(self):
        assert hx._detect_harness({"type": "chatgpt_meta", "harness": "chatgpt"}) == "chatgpt"

    def test_harness_field_alone(self):
        assert hx._detect_harness({"harness": "chatgpt"}) == "chatgpt"

    @pytest.mark.parametrize(
        "first,expected",
        [
            ({"type": "orchestrator_meta"}, "orchestrator"),
            ({"orchestrator": True}, "orchestrator"),
            ({"projectHash": "abc", "kind": "chat"}, "gemini"),
            ({"message": {"parts": []}}, "qwen"),
            ({"type": "user", "message": {"content": "hi"}}, "claude"),
        ],
    )
    def test_other_harnesses_unchanged(self, first, expected):
        assert hx._detect_harness(first) == expected

    def test_chatgpt_turn_line_alone_is_not_misdetected_as_chatgpt(self):
        """A turn line is Claude-shaped on purpose; only the header marks the harness."""
        turn = _chatgpt_lines()[1]
        assert hx._detect_harness(turn) == "claude"


# ── 2. indexing ───────────────────────────────────────────────────────────────


class TestExtractSession:
    def test_turns_roles_and_text(self, imported):
        ctx, cid = imported
        doc = hx.extract_session(ctx / "chatgpt-history" / "jsonl" / f"{cid}.jsonl")
        assert doc.harness == "chatgpt"
        assert doc.session_id == cid
        assert [t.role for t in doc.turns] == ["user", "assistant"]
        # Consecutive assistant turns (voice + reasoning) merge, as for every other harness.
        assert "chain rule" in doc.turns[1].text
        assert "two framings" in doc.turns[1].text

    def test_header_line_is_not_a_turn(self, imported):
        ctx, cid = imported
        doc = hx.extract_session(ctx / "chatgpt-history" / "jsonl" / f"{cid}.jsonl")
        assert "Imported conversation" not in " ".join(t.text for t in doc.turns)

    def test_voice_transcription_is_kept(self, imported):
        ctx, cid = imported
        doc = hx.extract_session(ctx / "chatgpt-history" / "jsonl" / f"{cid}.jsonl")
        assert "chain rule" in " ".join(t.text for t in doc.turns)

    def test_dates_survive(self, imported):
        """The ISO mirror must reach the index: a float-only timestamp is dropped silently."""
        ctx, cid = imported
        doc = hx.extract_session(ctx / "chatgpt-history" / "jsonl" / f"{cid}.jsonl")
        assert doc.started_at == "2023-11-14T22:13:21+00:00"
        # The two assistant lines merge into one turn, which keeps the first line's timestamp
        # (`add()` behaviour, shared by every harness) — so the last turn's ts is 22:13:22.
        assert doc.ended_at == "2023-11-14T22:13:22+00:00"

    def test_float_only_timestamp_would_be_dropped(self, tmp_path):
        """Guards the dual-field decision rather than the code: documents why ISO is written."""
        p = tmp_path / "x.jsonl"
        lines = _chatgpt_lines()
        for line in lines[1:]:
            line.pop("timestamp")
        with p.open("w", encoding="utf-8") as fh:
            for line in lines:
                fh.write(json.dumps(line) + "\n")
        doc = hx.extract_session(p)
        assert doc.turns and doc.started_at is None


class TestTitle:
    def test_header_title_is_captured(self, imported):
        """Imported conversations keep the title the user saw in ChatGPT (they are not in
        `.titles.json`, and the LLM auto-title would otherwise replace it)."""
        ctx, cid = imported
        doc = hx.extract_session(ctx / "chatgpt-history" / "jsonl" / f"{cid}.jsonl")
        assert doc.title == "Imported conversation"

    def test_file_title_beats_the_llm_auto_title(self, imported):
        """A title the file carries wins over a summary title, like a `.titles.json` rename."""
        ctx, cid = imported
        conn = hx.connect(ctx / "h.sqlite3")
        path = ctx / "chatgpt-history" / "jsonl" / f"{cid}.jsonl"
        hx.index_all(conn, [path], lambda texts: [[0.0] * 8 for _ in texts],
                     summaries={cid: {"title": "LLM invented this", "summary": "s"}})
        row = conn.execute("SELECT auto_title, harness FROM sessions WHERE id=?", (cid,)).fetchone()
        assert row[0] == "Imported conversation"
        assert row[1] == "chatgpt"

    def test_other_harnesses_have_no_file_title(self, tmp_path):
        p = tmp_path / "c.jsonl"
        p.write_text(json.dumps({"type": "user", "message": {"content": "hi"}}) + "\n", encoding="utf-8")
        assert hx.extract_session(p).title is None


class TestFindSessionFile:
    def test_resolves_an_imported_conversation(self, imported):
        ctx, cid = imported
        found = hx.find_session_file(cid, context_dir=ctx)
        assert found is not None and found.name == f"{cid}.jsonl"

    def test_unknown_id_is_none(self, imported):
        ctx, _ = imported
        assert hx.find_session_file("nope", context_dir=ctx) is None

    def test_read_and_grep_report_the_title_and_kind(self, imported):
        from utils import history_nav as nav

        ctx, cid = imported
        r = nav.read_conversation(cid, context_dir=ctx)
        assert r["kind"] == "chatgpt"
        assert r["title"] == "Imported conversation"
        assert r["total_turns"] == 2
        g = nav.grep_conversation(cid, "gradients", context_dir=ctx)
        assert g["title"] == "Imported conversation"
        assert g["total_matches"] == 1


class TestSessionSources:
    def test_includes_imported_dir(self, imported):
        ctx, cid = imported
        chats = ctx / "chats"
        chats.mkdir()
        found = hx.session_sources(context_dir=ctx, chats_dir=chats)
        assert any(p.name == f"{cid}.jsonl" for p in found)

    def test_absent_dir_is_fine(self, tmp_path):
        chats = tmp_path / "chats"
        chats.mkdir()
        assert hx.session_sources(context_dir=tmp_path, chats_dir=chats) == []

    def test_session_id_is_the_conversation_id(self, imported):
        ctx, cid = imported
        p = ctx / "chatgpt-history" / "jsonl" / f"{cid}.jsonl"
        assert hx.session_id_for(p) == cid


# ── 3. read-only exclusion ────────────────────────────────────────────────────


class TestReadOnly:
    def test_chatgpt_is_read_only(self):
        assert "chatgpt" in hx.READ_ONLY_HARNESSES

    def test_agent_kind_excludes_imported(self):
        assert hx._kind_matches("agent", "chatgpt") is False

    @pytest.mark.parametrize("harness", ["claude", "qwen", "gemini", "codex"])
    def test_agent_kind_still_matches_real_harnesses(self, harness):
        assert hx._kind_matches("agent", harness) is True

    def test_agent_kind_still_excludes_orchestrator(self):
        assert hx._kind_matches("agent", "orchestrator") is False

    def test_explicit_chatgpt_kind_matches(self):
        assert hx._kind_matches("chatgpt", "chatgpt") is True

    def test_is_read_only_session(self, imported):
        ctx, cid = imported
        assert hx.is_read_only_session(cid, context_dir=ctx) is True

    def test_unknown_session_is_not_read_only(self, imported):
        ctx, _ = imported
        assert hx.is_read_only_session("not-a-session", context_dir=ctx) is False

    @pytest.mark.parametrize("sid", ["", "   ", "../escape", "a/b", "a\\b"])
    def test_rejects_empty_and_traversal(self, sid, imported):
        ctx, _ = imported
        assert hx.is_read_only_session(sid, context_dir=ctx) is False

    def test_search_kind_enum_accepts_chatgpt(self):
        from orchestrator.tools.search import _KINDS

        assert "chatgpt" in _KINDS


class TestAdapter:
    """The ProviderAdapter that lets SessionStore list and read imported conversations."""

    def test_detected_by_header_not_by_turn_shape(self, imported):
        from manager.chatgpt.adapter import ChatGptAdapter

        ctx, cid = imported
        p = ctx / "chatgpt-history" / "jsonl" / f"{cid}.jsonl"
        assert ChatGptAdapter().detect_provider(p) is True

    def test_claude_adapter_rejects_the_header(self, imported):
        """Turn lines are Claude-shaped on purpose, so Claude must reject the header explicitly —
        otherwise detection would depend on adapter registration order."""
        from manager.claude.adapter import ClaudeAdapter

        ctx, cid = imported
        p = ctx / "chatgpt-history" / "jsonl" / f"{cid}.jsonl"
        assert ClaudeAdapter().detect_provider(p) is False

    def test_registry_detection_picks_chatgpt(self, imported):
        from manager.protocol import detect_provider, ensure_all_registered

        ensure_all_registered()
        ctx, cid = imported
        p = ctx / "chatgpt-history" / "jsonl" / f"{cid}.jsonl"
        adapter = detect_provider(p)
        assert adapter is not None and adapter.provider_name == "chatgpt"

    def test_claude_file_still_detected_as_claude(self, tmp_path):
        from manager.protocol import detect_provider, ensure_all_registered

        ensure_all_registered()
        p = tmp_path / "c.jsonl"
        p.write_text(json.dumps({"type": "user", "message": {"content": "hi"}}) + "\n", encoding="utf-8")
        adapter = detect_provider(p)
        assert adapter is not None and adapter.provider_name == "claude"

    def test_read_messages_skips_the_header_and_keeps_voice_text(self, imported):
        from manager.chatgpt.adapter import ChatGptAdapter

        ctx, cid = imported
        msgs = ChatGptAdapter().read_messages(ctx / "chatgpt-history" / "jsonl" / f"{cid}.jsonl")
        assert [m["type"] for m in msgs] == ["user", "assistant", "assistant"]
        assert "chain rule" in msgs[1]["message"]["content"][0]["text"]

    def test_parse_session_info_is_read_only_and_titled(self, imported):
        from manager.chatgpt.adapter import ChatGptAdapter

        ctx, cid = imported
        info = ChatGptAdapter().parse_session_info(
            ctx / "chatgpt-history" / "jsonl" / f"{cid}.jsonl", cid
        )
        assert info is not None
        assert info.provider == "chatgpt"
        assert info.read_only is True
        assert info.title == "Imported conversation"
        assert info.message_count == 3
        assert info.is_orchestrator is False

    def test_a_titles_json_rename_wins(self, imported):
        from manager.chatgpt.adapter import ChatGptAdapter

        ctx, cid = imported
        info = ChatGptAdapter().parse_session_info(
            ctx / "chatgpt-history" / "jsonl" / f"{cid}.jsonl", cid, titles={cid: "My rename"}
        )
        assert info is not None and info.title == "My rename"

    def test_not_an_import_returns_none(self, tmp_path):
        from manager.chatgpt.adapter import ChatGptAdapter

        p = tmp_path / "c.jsonl"
        p.write_text(json.dumps({"type": "user", "message": {"content": "hi"}}) + "\n", encoding="utf-8")
        assert ChatGptAdapter().parse_session_info(p, "c") is None


class TestStoreListing:
    def test_imported_conversations_are_listed_read_only(self, imported):
        from manager.store import SessionStore

        ctx, cid = imported
        store = SessionStore(ctx.parent)
        store._sessions_dir = ctx
        store._chats_dir = ctx / "chats"
        store._imported_dir = ctx / "chatgpt-history" / "jsonl"
        rows = {s.session_id: s for s in store.list_sessions()}
        assert cid in rows
        assert rows[cid].provider == "chatgpt"
        assert rows[cid].read_only is True
        assert rows[cid].title == "Imported conversation"

    def test_locate_jsonl_finds_an_import(self, imported):
        from manager.store import SessionStore

        ctx, cid = imported
        store = SessionStore(ctx.parent)
        store._sessions_dir = ctx
        store._chats_dir = ctx / "chats"
        store._imported_dir = ctx / "chatgpt-history" / "jsonl"
        found = store._locate_jsonl(cid)
        assert found is not None and found.name == f"{cid}.jsonl"

    def test_a_normal_session_is_not_read_only(self, imported):
        from manager.store import SessionStore

        ctx, _ = imported
        (ctx / "aaaaaaaa-0000-0000-0000-000000000000.jsonl").write_text(
            json.dumps({"type": "user", "timestamp": "2026-01-01T00:00:00+00:00",
                        "message": {"content": "hello"}}) + "\n",
            encoding="utf-8",
        )
        store = SessionStore(ctx.parent)
        store._sessions_dir = ctx
        store._chats_dir = ctx / "chats"
        store._imported_dir = ctx / "chatgpt-history" / "jsonl"
        rows = {s.session_id: s for s in store.list_sessions()}
        row = rows["aaaaaaaa-0000-0000-0000-000000000000"]
        assert row.provider == "claude" and row.read_only is False


class TestStartRefusesAnImport:
    """The chat WebSocket must never spawn a live session for an imported conversation: the
    read-only invariant has to hold for every client, not just the web app's open path."""

    @staticmethod
    def _client(pool):
        from unittest.mock import MagicMock
        from starlette.testclient import TestClient

        from api.app import create_app

        app = create_app()
        app.state.pool = pool
        app.state.store = MagicMock()
        return TestClient(app)

    def test_start_on_an_imported_id_is_refused(self, imported, monkeypatch):
        import orjson
        from unittest.mock import AsyncMock

        from api.pool import SessionPool

        ctx, cid = imported
        monkeypatch.setattr(hx, "get_context_dir", lambda: ctx)
        pool = SessionPool()
        pool.create = AsyncMock()  # must not be called
        with self._client(pool).websocket_connect("/api/sessions/chat") as ws:
            ws.send_text(orjson.dumps({"type": "start", "resume_sdk_id": cid}).decode())
            frame = orjson.loads(ws.receive_bytes())
        assert frame["error"] == "read_only_session"
        pool.create.assert_not_called()

    def test_start_on_a_normal_id_is_not_refused(self, imported, monkeypatch):
        """The guard must be specific: an ordinary resume still reaches the pool."""
        import orjson
        from unittest.mock import AsyncMock, MagicMock, patch

        from api.pool import SessionPool

        ctx, _ = imported
        monkeypatch.setattr(hx, "get_context_dir", lambda: ctx)
        pool = SessionPool()
        pool.create = AsyncMock(return_value="local-1")
        with patch("api.session_factory.build_session_config", return_value=(MagicMock(), None, {})), \
                patch("api.routes.chat._send_session_started", new=AsyncMock()), \
                self._client(pool).websocket_connect("/api/sessions/chat") as ws:
            ws.send_text(orjson.dumps({"type": "start", "resume_sdk_id": "some-other-session"}).decode())
            frames = [orjson.loads(ws.receive_bytes())]
        assert all(f.get("error") != "read_only_session" for f in frames)
        pool.create.assert_called_once()


class TestResumeExclusion:
    @pytest.mark.asyncio
    async def test_resume_conversation_refuses_imported(self, imported, monkeypatch):
        ctx, cid = imported
        monkeypatch.setattr(hx, "get_context_dir", lambda: ctx)
        from orchestrator.tools import agent_sessions

        called = False

        async def _should_not_run(*a, **k):
            nonlocal called
            called = True
            return json.dumps({})

        monkeypatch.setattr(agent_sessions, "open_agent_session", _should_not_run)
        out = json.loads(await agent_sessions.resume_conversation({}, session_id=cid))
        assert "read-only" in out["error"]
        assert "read_conversation" in out["hint"]
        assert called is False

    @pytest.mark.asyncio
    async def test_resume_conversation_still_requires_a_session_id(self):
        from orchestrator.tools import agent_sessions

        out = json.loads(await agent_sessions.resume_conversation({}, session_id=""))
        assert "session_id is required" in out["error"]
