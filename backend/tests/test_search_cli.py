"""Tests for shared/scripts/search.py — argument → request mapping and output."""
from __future__ import annotations

import json
import sys

import search


def run(monkeypatch, capsys, argv, reply):
    sent = {}

    def fake_call(request):
        sent.update(request)
        return reply

    monkeypatch.setattr(search, "_call", fake_call)
    monkeypatch.setattr(sys, "argv", ["search.py", *argv])
    search.main()
    return sent, capsys.readouterr().out


def test_history_request_and_output(monkeypatch, capsys):
    reply = {"sessions": [{"session_id": "abc", "title": "Lamps", "started_at": "2026-07-20T10:00", "kind": "orchestrator",
                           "relevance": "strong", "summary": "Tuya lamps", "hits": [{"turn": 3, "role": "user", "match": "keyword",
                                                                                   "date": "2026-07-20T10:05", "text": "the QR fails"}],
                           "saved_in_memory": [{"file": "context/memory/lamps.md"}]}],
             "memory": [{"file": "context/memory/lamps.md", "title": "Lamps"}]}
    sent, out = run(monkeypatch, capsys, ["lamp", "QR", "--collection", "history", "--when", "2026-07",
                                          "--also", "lâmpada QR", "--kind", "orchestrator", "--rerank"], reply)
    assert sent["command"] == "history_search" and sent["query"] == "lamp QR"
    assert sent["window"] == ["2026-06-27", "2026-08-05"] and sent["queries"] == ["lâmpada QR"]
    assert sent["kind"] == "orchestrator" and sent["kind_mode"] == "prefer" and sent["rerank"] is True
    assert "=== Lamps [abc]" in out and "the QR fails" in out and "saved in memory: context/memory/lamps.md" in out


def test_memory_request_and_json(monkeypatch, capsys):
    reply = {"files": [{"file": "context/memory/a.md", "title": "A", "relevance": "strong", "sections": []}]}
    sent, out = run(monkeypatch, capsys, ["wake", "word", "--folder", "assistant", "--json"], reply)
    assert sent == {"command": "memory_search", "query": "wake word", "max_files": 5, "folder": "assistant", "queries": []}
    assert json.loads(out)["files"][0]["file"] == "context/memory/a.md"
