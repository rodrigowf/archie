"""Tests for manager/gemini/workspace_settings.py — Archie's keys in the
Gemini CLI workspace settings (``<repo>/.gemini/settings.json``)."""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from manager.gemini import workspace_settings as ws
from utils.paths import PROJECT_ROOT

SEED = PROJECT_ROOT / "install" / "cli-runtime" / "gemini" / "settings.json"


def test_seed_file_matches_module_definition() -> None:
    assert SEED.read_text() == ws.render_seed()


def test_seed_uses_the_keys_the_cli_reads() -> None:
    seed = json.loads(SEED.read_text())
    # The CLI reads context.fileFiltering; a top-level fileFiltering is ignored.
    assert "fileFiltering" not in seed
    assert seed["context"]["fileFiltering"]["respectGitIgnore"] is False
    # Retention would delete old sessions in context/chats/ on every start.
    assert seed["general"]["sessionRetention"]["enabled"] is False
    assert seed["security"]["auth"]["selectedType"] == "${ARCHIE_GEMINI_AUTH_TYPE:-gemini-api-key}"
    overrides = seed["modelConfigs"]["customOverrides"]
    assert overrides[0]["match"] == {"model": "chat-base-3"}
    # Every gate defaults to a model name that never exists.
    assert {o["match"]["model"] for o in overrides[1:]} == {
        "${ARCHIE_GEMINI_LEVEL_MODEL:-archie-off}",
        "${ARCHIE_GEMINI_BUDGET_MODEL:-archie-off}",
    }


def test_merge_migrates_legacy_top_level_file_filtering() -> None:
    old = {"fileFiltering": {"respectGitIgnore": False, "respectGeminiIgnore": False}}
    out = ws.merge_archie_settings(old)
    assert "fileFiltering" not in out
    assert out["context"]["fileFiltering"] == {"respectGitIgnore": False, "respectGeminiIgnore": False}
    assert old == {"fileFiltering": {"respectGitIgnore": False, "respectGeminiIgnore": False}}


def test_merge_keeps_user_keys_and_overrides_and_is_idempotent() -> None:
    mine = {"match": {"model": "gemini-2.5-pro"}, "modelConfig": {"generateContentConfig": {"temperature": 0.2}}}
    stale_archie = {"match": {"model": "${ARCHIE_GEMINI_BUDGET_0:-archie-off}"}, "modelConfig": {}}
    existing = {
        "ui": {"theme": "Dracula"},
        "general": {"vimMode": True, "sessionRetention": {"enabled": True, "maxAge": "7d"}},
        "context": {"fileFiltering": {"respectGitIgnore": True}},
        "modelConfigs": {"customOverrides": [mine, stale_archie]},
    }
    out = ws.merge_archie_settings(existing)
    assert out["ui"] == {"theme": "Dracula"}
    assert out["general"]["vimMode"] is True
    assert out["general"]["sessionRetention"]["enabled"] is False
    assert out["context"]["fileFiltering"]["respectGitIgnore"] is False
    assert out["modelConfigs"]["customOverrides"] == [mine, *ws.ARCHIE_OVERRIDES]
    assert ws.merge_archie_settings(out) == out


def test_ensure_creates_updates_and_reports(tmp_path: Path) -> None:
    assert ws.ensure_workspace_settings(tmp_path) is True
    path = tmp_path / ".gemini" / "settings.json"
    assert json.loads(path.read_text()) == ws.ARCHIE_SETTINGS
    assert path.stat().st_mode & 0o777 == 0o644
    assert ws.ensure_workspace_settings(tmp_path) is False
    path.write_text(json.dumps({"fileFiltering": {"respectGitIgnore": False}}))
    path.chmod(0o664)
    assert ws.ensure_workspace_settings(tmp_path) is True
    assert path.stat().st_mode & 0o777 == 0o664
    assert json.loads(path.read_text())["general"]["sessionRetention"]["enabled"] is False


@pytest.mark.parametrize("content", ["{ // comment\n}", "[1, 2]", "not json"])
def test_ensure_never_overwrites_what_it_cannot_parse(tmp_path: Path, content: str) -> None:
    path = tmp_path / ".gemini" / "settings.json"
    path.parent.mkdir()
    path.write_text(content)
    with pytest.raises(ws.WorkspaceSettingsError):
        ws.ensure_workspace_settings(tmp_path)
    assert path.read_text() == content


def test_script_entry_point(tmp_path: Path, capsys: pytest.CaptureFixture[str]) -> None:
    assert ws._main([str(tmp_path)]) == 0
    assert "updated" in capsys.readouterr().out
    assert ws._main([]) == 2
