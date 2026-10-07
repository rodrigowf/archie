"""Tests for utils/history_nav.py — listing, grepping and reading past conversations."""
from __future__ import annotations

import json
from pathlib import Path

import pytest

from utils import history_index as hi
from utils import history_nav as nav


def _write(path: Path, lines: list[dict]) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("".join(json.dumps(l) + "\n" for l in lines), encoding="utf-8")
    return path


def _msg(role, text, ts, **extra):
    return {"type": role, "message": {"role": role, "content": text}, "timestamp": ts, **extra}


@pytest.fixture
def ctx(tmp_path: Path):
    d = tmp_path / "context"
    (d / "chats").mkdir(parents=True)
    _write(d / "orch1.jsonl", [
        {"type": "orchestrator_meta", "orchestrator": True},
        _msg("user", "Vamos ver o JACK e o OBS hoje", "2026-10-05T10:00:00Z"),
        _msg("assistant", "Ok, o OBS não conecta no JACK por causa do snap.", "2026-10-05T10:01:00Z"),
    ])
    _write(d / "claude1.jsonl", [
        _msg("user", "Refactor the tarot canvas grid", "2026-09-01T10:00:00Z", cwd="/home/rodrigo/tarot"),
        _msg("assistant", "x " * 5000 + "END", "2026-09-01T10:05:00Z"),
        _msg("user", "thanks, now the colors", "2026-09-01T10:10:00Z"),
    ])
    (d / ".titles.json").write_text(json.dumps({"claude1": "Tarot canvas"}))
    conn = hi.connect(tmp_path / "h.sqlite3")
    for p in hi.session_sources(d, d / "chats"):
        hi.index_session(conn, p, lambda texts: [[1.0] + [0.0] * 383 for _ in texts])
    yield d, conn
    conn.close()


class TestList:
    def test_newest_first_with_metadata(self, ctx):
        d, conn = ctx
        r = nav.list_conversations(conn, context_dir=d)
        assert [c["session_id"] for c in r["conversations"]] == ["orch1", "claude1"]
        orch, claude = r["conversations"]
        assert orch["kind"] == "orchestrator" and orch["title"] is None and orch["first_message"].startswith("Vamos ver")
        assert claude["title"] == "Tarot canvas" and claude["cwd"] == "/home/rodrigo/tarot"
        assert "resume_conversation" in claude["open"]["how"] and "switch_conversation" in orch["open"]["how"]

    def test_filters_and_paging(self, ctx):
        d, conn = ctx
        ids = lambda **kw: [c["session_id"] for c in nav.list_conversations(conn, context_dir=d, **kw)["conversations"]]
        assert ids(after="2026-10-01", before="2026-10-07") == ["orch1"]
        assert ids(kind="agent") == ["claude1"]
        assert ids(kind="orchestrator") == ["orch1"]
        assert ids(text="tarot") == ["claude1"]
        assert ids(text="jack obs") == ["orch1"]
        assert ids(exclude=("orch1",)) == ["claude1"]
        page = nav.list_conversations(conn, limit=1, context_dir=d)
        assert page["total"] == 2 and page["next_offset"] == 1
        assert ids(limit=1, offset=1) == ["claude1"]


class TestGrep:
    def test_accent_and_case_insensitive(self, ctx):
        d, _ = ctx
        r = nav.grep_conversation("orch1", "nao conecta", context_dir=d)
        assert r["total_matches"] == 1 and r["hits"][0]["turn"] == 1
        assert "não conecta" in r["hits"][0]["snippet"]

    def test_regex_and_errors(self, ctx):
        d, _ = ctx
        assert nav.grep_conversation("orch1", r"jack|obs", regex=True, context_dir=d)["total_matches"] == 4
        assert "error" in nav.grep_conversation("orch1", "(", regex=True, context_dir=d)
        assert "error" in nav.grep_conversation("nope", "x", context_dir=d)


class TestRead:
    def test_window_and_long_turn_cut(self, ctx):
        d, _ = ctx
        r = nav.read_conversation("claude1", turn=0, before=0, after=2, context_dir=d)
        assert r["range"] == [0, 2] and "truncated" in r["turns"][1]
        assert len(r["turns"][1]["text"]) == nav.MAX_TURN_CHARS

    def test_single_turn_is_paged_in_full(self, ctx):
        d, _ = ctx
        first = nav.read_conversation("claude1", start=1, end=1, context_dir=d)["turns"][0]
        assert first["chars"] == 10003 and first["next_char_offset"] == nav.MAX_PAGE_CHARS
        rest = nav.read_conversation("claude1", start=1, end=1, char_offset=first["next_char_offset"], context_dir=d)["turns"][0]
        assert rest["text"].endswith("END") and "next_char_offset" not in rest

    def test_range_is_capped_and_hints(self, ctx):
        d, _ = ctx
        r = nav.read_conversation("orch1", start=0, end=0, context_dir=d)
        assert r["later"] == "turns 1–1 not shown" and "switch_conversation" in r["open"]["how"]
        assert "error" in nav.read_conversation("nope", context_dir=d)


class TestMemoryLinks:
    def test_saved_in_memory_comes_from_frontmatter_source(self, tmp_path):
        from unittest.mock import patch

        from utils import memory_index as mi
        from utils import search_service

        root = tmp_path / "memory"
        root.mkdir()
        (root / "lamps.md").write_text(
            "---\nname: lamps\ndescription: Tuya lamps\nsource: session abcdef12-1111-2222-3333-444455556666 (\"Lamps\")\n---\n"
            "# Lamps\n\nScenes run at dusk and the hub is in the hallway.\n"
        )
        db = tmp_path / "memory.sqlite3"
        conn = mi.connect(db)
        mi.index_all(conn, lambda t: [[1.0] + [0.0] * 383 for _ in t], root=root, log=lambda m: None)
        assert mi.files_for_sessions(conn, ["ABCDEF12-1111-2222-3333-444455556666"]) == {
            "abcdef12-1111-2222-3333-444455556666": [{"file": "lamps.md", "title": "Lamps"}]
        }
        conn.close()
        result = {"sessions": [{"session_id": "abcdef12-1111-2222-3333-444455556666"}, {"session_id": "other"}]}
        with patch.object(mi, "get_memory_db_path", return_value=db):
            search_service.link_memory(result)
        assert result["sessions"][0]["saved_in_memory"] == [{"file": "context/memory/lamps.md", "title": "Lamps"}]
        assert "saved_in_memory" not in result["sessions"][1]


class TestRerankDegradation:
    def test_quota_error_pauses_rerank(self, monkeypatch):
        from utils import rerank

        class QuotaError(Exception):
            status_code = 429

        class FakeClient:
            class chat:
                class completions:
                    calls = 0

                    @classmethod
                    def create(cls, **kw):
                        cls.calls += 1
                        raise QuotaError("You have no credits remaining")

        monkeypatch.setenv("OPENAI_API_KEY", "x")
        monkeypatch.setattr(rerank, "_client", FakeClient)
        monkeypatch.setattr(rerank, "_paused_until", 0.0)
        sessions = [{"session_id": "a" * 36, "relevance": "strong", "hits": []}]
        assert rerank.rerank_sessions("q", sessions) is None
        assert rerank.rerank_sessions("q", sessions) is None  # paused: no second call
        assert FakeClient.chat.completions.calls == 1
        assert sessions[0]["relevance"] == "strong"  # untouched: caller keeps its own labels

    def test_rerank_reorders_and_relabels(self, monkeypatch):
        from types import SimpleNamespace

        from utils import rerank

        class FakeClient:
            class chat:
                class completions:
                    @staticmethod
                    def create(**kw):
                        msg = SimpleNamespace(content='{"matches": ["bbbbbbbb"]}')
                        return SimpleNamespace(choices=[SimpleNamespace(message=msg)])

        monkeypatch.setenv("OPENAI_API_KEY", "x")
        monkeypatch.setattr(rerank, "_client", FakeClient)
        monkeypatch.setattr(rerank, "_paused_until", 0.0)
        a = {"session_id": "a" * 36, "relevance": "strong", "hits": []}
        b = {"session_id": "b" * 36, "relevance": "weak", "hits": []}
        out = rerank.rerank_sessions("q", [a, b])
        assert [s["session_id"][0] for s in out] == ["b", "a"]
        assert [s["relevance"] for s in out] == ["strong", "weak"]
