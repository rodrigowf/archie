"""Tests for manager/codex/adapter.py, items.py and catalog.py.

The rollout fixture is a trimmed real CLI 0.161 rollout (two app-server
sessions on one thread: text, ``ls``, a file write, a resume, an
interrupted turn).  Older generations are covered with small synthetic
files in the CLI's documented shapes.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from manager.codex import catalog as cat
from manager.codex import home, items
from manager.codex.adapter import (
    CodexAdapter,
    _codex_discover_sessions,
    _codex_jsonl_candidates,
    session_id_from_path,
)
from manager.harness_catalog import validate_options
from manager.protocol import get_registry as provider_registry
from manager.registry import ensure_all_registered, get_registry

FIXTURE = (
    Path(__file__).parent / "fixtures" / "codex"
    / "rollout-2026-10-07T20-54-43-01a118ca-7254-75a1-8888-c5a12eae0a9c.jsonl"
)
TID = "01a118ca-7254-75a1-8888-c5a12eae0a9c"
adapter = CodexAdapter()


def _blocks(msg):
    content = msg["message"]["content"]
    return content if isinstance(content, list) else [{"type": "text", "text": content}]


# ---------------------------------------------------------------------------
# Adapter — current ("items") rollouts
# ---------------------------------------------------------------------------


def test_detect_provider_only_claims_rollouts(tmp_path):
    assert adapter.detect_provider(FIXTURE)
    other = tmp_path / "x.jsonl"
    other.write_text(json.dumps({"type": "user", "message": {"role": "user", "content": "hi"}}) + "\n")
    assert not adapter.detect_provider(other)
    gem = tmp_path / "g.jsonl"
    gem.write_text(json.dumps({"sessionId": "s", "projectHash": "p", "kind": "main", "startTime": "t"}) + "\n")
    assert not adapter.detect_provider(gem)
    # And no other harness claims a Codex rollout.
    ensure_all_registered()
    for name, other_adapter in provider_registry().all().items():
        if name != "codex":
            assert not other_adapter.detect_provider(FIXTURE), name


def test_read_messages_items_generation():
    msgs = adapter.read_messages(FIXTURE)
    users = [m["message"]["content"] for m in msgs if m["type"] == "user" and isinstance(m["message"]["content"], str)]
    assert users[0] == "Reply with exactly: hello from codex"
    assert not any("environment_context" in u for u in users)
    assert len(users) == 6
    tool_uses = [b for m in msgs for b in _blocks(m) if b["type"] == "tool_use"]
    assert [t["name"] for t in tool_uses] == ["Bash", "Bash", "Write"]
    assert tool_uses[0]["input"] == {"command": "ls"}
    assert tool_uses[2]["input"] == {"file_path": "/work/note.txt", "content": "hi\n"}
    results = {b["tool_use_id"]: b for m in msgs for b in _blocks(m) if b["type"] == "tool_result"}
    assert set(results) == {t["id"] for t in tool_uses}
    assert "note.txt" in results[tool_uses[2]["id"]]["content"]
    texts = [b["text"] for m in msgs if m["type"] == "assistant" for b in _blocks(m) if b["type"] == "text"]
    assert texts[0] == "hello from codex" and texts[-1] == "still alive"
    assert all("_last" not in m for m in msgs)


def test_parse_session_info():
    info = adapter.parse_session_info(FIXTURE, TID, titles={})
    assert info.title == "Reply with exactly: hello from codex"
    assert info.started_at.isoformat().startswith("2026-10-07T23:54:43")
    assert info.last_activity > info.started_at
    assert info.message_count == 14
    titled = adapter.parse_session_info(FIXTURE, TID, titles={TID: "Named"})
    assert titled.title == "Named"


def test_visible_line_indices_match_rest_messages():
    objs = [json.loads(line) if line.strip() else None for line in FIXTURE.read_text().splitlines()]
    idx = adapter.visible_line_indices(objs)
    assert len(idx) == 14 == adapter.parse_session_info(FIXTURE, TID).message_count
    assert idx == sorted(idx)


def test_session_id_from_path():
    assert session_id_from_path(FIXTURE) == TID
    assert session_id_from_path(Path("rollout-x.jsonl")) is None


# ---------------------------------------------------------------------------
# Older generations
# ---------------------------------------------------------------------------


def _write(path: Path, lines: list[dict]) -> Path:
    path.write_text("".join(json.dumps(x) + "\n" for x in lines))
    return path


def test_events_generation(tmp_path):
    ts = "2025-12-05T06:34:42.833Z"
    p = _write(tmp_path / f"rollout-2025-12-05T03-34-42-{TID}.jsonl", [
        {"timestamp": ts, "type": "session_meta", "payload": {"id": TID, "timestamp": ts, "originator": "codex_exec"}},
        {"timestamp": ts, "type": "response_item", "payload": {"type": "message", "role": "user", "content": [
            {"type": "input_text", "text": "<environment_context>\n</environment_context>"}]}},
        {"timestamp": ts, "type": "response_item", "payload": {"type": "message", "role": "user", "content": [
            {"type": "input_text", "text": "show the README"}]}},
        {"timestamp": ts, "type": "event_msg", "payload": {"type": "user_message", "message": "show the README"}},
        {"timestamp": ts, "type": "event_msg", "payload": {"type": "agent_reasoning", "text": "**Reading**"}},
        {"timestamp": ts, "type": "response_item", "payload": {"type": "function_call", "name": "shell_command",
            "arguments": json.dumps({"command": "cat README.md"}), "call_id": "call_1"}},
        {"timestamp": ts, "type": "response_item", "payload": {"type": "function_call_output", "call_id": "call_1",
            "output": "Exit code: 0\nOutput:\n# Title"}},
        {"timestamp": ts, "type": "response_item", "payload": {"type": "custom_tool_call", "name": "apply_patch",
            "input": "*** Begin Patch\n*** End Patch", "call_id": "call_2"}},
        {"timestamp": ts, "type": "response_item", "payload": {"type": "custom_tool_call_output", "call_id": "call_2",
            "output": json.dumps({"output": "Success.", "metadata": {"exit_code": 0}})}},
        {"timestamp": ts, "type": "event_msg", "payload": {"type": "agent_message", "message": "Here it is."}},
    ])
    msgs = adapter.read_messages(p)
    assert msgs[0]["message"]["content"] == "show the README"
    assistant = _blocks(msgs[1])
    assert [b["type"] for b in assistant] == ["thinking", "tool_use"]
    assert assistant[1]["name"] == "Bash" and assistant[1]["input"] == {"command": "cat README.md"}
    flat = [b for m in msgs for b in _blocks(m)]
    patch = next(b for b in flat if b.get("name") == "apply_patch")
    assert patch["input"]["patch"].startswith("*** Begin Patch")
    out = next(b for b in flat if b["type"] == "tool_result" and b["tool_use_id"] == "call_2")
    assert out["content"] == "Success." and out["is_error"] is False
    assert _blocks(msgs[-1])[-1] == {"type": "text", "text": "Here it is."}
    assert adapter.parse_session_info(p, TID).title == "show the README"


def test_responses_generation(tmp_path):
    p = _write(tmp_path / f"rollout-2025-09-07T16-46-12-{TID}.jsonl", [
        {"id": TID, "timestamp": "2025-09-07T19:46:12.837Z", "instructions": None},
        {"record_type": "state"},
        {"type": "message", "role": "user", "content": [{"type": "input_text", "text": "<environment_context>x"}]},
        {"type": "message", "role": "user", "content": [{"type": "input_text", "text": "Hi there"}]},
        {"type": "reasoning", "summary": [{"type": "summary_text", "text": "**Plan**"}]},
        {"type": "function_call", "name": "shell", "arguments": json.dumps({"command": ["bash", "-lc", "ls -la"]}), "call_id": "c1"},
        {"type": "function_call_output", "call_id": "c1", "output": json.dumps({"output": "total 0", "metadata": {"exit_code": 1}})},
        {"type": "message", "role": "assistant", "content": [{"type": "output_text", "text": "All set."}]},
    ])
    assert adapter.detect_provider(p)
    msgs = adapter.read_messages(p)
    assert msgs[0]["message"]["content"] == "Hi there"
    use = next(b for b in _blocks(msgs[1]) if b["type"] == "tool_use")
    assert use["input"] == {"command": "ls -la"}
    res = _blocks(msgs[2])[0]
    assert res["content"] == "total 0" and res["is_error"] is True
    info = adapter.parse_session_info(p, TID)
    assert info.title == "Hi there" and info.message_count == 3


# ---------------------------------------------------------------------------
# items.py
# ---------------------------------------------------------------------------


def test_display_command_strips_shell_wrapper():
    assert items.display_command("/bin/bash -lc 'ls -la'") == "ls -la"
    assert items.display_command(["/bin/bash", "-lc", "echo 'a b'"]) == "echo 'a b'"
    assert items.display_command("git status") == "git status"


def test_tool_mapping_live_and_rollout_spellings():
    live = {"type": "commandExecution", "id": "e1", "command": "/bin/bash -lc 'false'",
            "status": "failed", "aggregatedOutput": "", "exitCode": 1}
    assert items.tool_call(live) == ("Bash", {"command": "false"})
    assert items.tool_result(live) == ("Exit code 1", True)
    rollout = {"type": "CommandExecution", "id": "e1", "command": ["/bin/bash", "-lc", "ls"],
               "status": "completed", "aggregated_output": "a\n", "exit_code": 0}
    assert items.tool_call(rollout) == ("Bash", {"command": "ls"})
    assert items.tool_result(rollout) == ("a\n", False)
    mcp = {"type": "mcpToolCall", "id": "m", "server": "github", "tool": "search", "arguments": {"q": "x"},
           "status": "completed", "result": {"content": [{"type": "text", "text": "found"}]}}
    assert items.tool_call(mcp) == ("mcp__github__search", {"q": "x"})
    assert items.tool_result(mcp) == ("found", False)
    mcp_err = dict(mcp, status="failed", result=None, error={"message": "boom"})
    assert items.tool_result(mcp_err) == ("boom", True)
    web = {"type": "webSearch", "id": "w", "query": "", "action": {"type": "search", "query": "codex docs"}}
    assert items.tool_call(web) == ("WebSearch", {"query": "codex docs"})
    multi = {"type": "fileChange", "id": "f", "status": "completed", "changes": [
        {"path": "a", "kind": {"type": "add"}, "diff": "x"}, {"path": "b", "kind": {"type": "delete"}, "diff": ""}]}
    name, inp = items.tool_call(multi)
    assert name == "apply_patch" and [c["kind"] for c in inp["changes"]] == ["add", "delete"]
    assert items.tool_call({"type": "agentMessage", "id": "x"}) is None


def test_diff_sides():
    old, new = items.diff_sides("--- a/x\n+++ b/x\n@@ -1,3 +1,3 @@\n keep\n-old\n+new\n@@ -9 +9 @@\n-z\n+y\n")
    assert old == "keep\nold\n…\nz"
    assert new == "keep\nnew\n…\ny"


# ---------------------------------------------------------------------------
# Storage: resolver + discoverer
# ---------------------------------------------------------------------------


def _rollout(root: Path, tid: str, originator: str) -> Path:
    d = root / "2026" / "10" / "07"
    d.mkdir(parents=True, exist_ok=True)
    p = d / f"rollout-2026-10-07T20-00-00-{tid}.jsonl"
    p.write_text(json.dumps({"timestamp": "2026-10-07T23:00:00Z", "type": "session_meta",
                             "payload": {"id": tid, "timestamp": "2026-10-07T23:00:00Z", "originator": originator}}) + "\n")
    return p


def test_discoverer_and_resolver(tmp_path, monkeypatch):
    shared = tmp_path / "shared"
    dedicated = tmp_path / "dedicated"
    project = tmp_path / "project"
    monkeypatch.setenv("CODEX_HOME", str(shared))
    monkeypatch.delenv("ARCHIE_CODEX_HOME", raising=False)
    monkeypatch.setattr(home, "ARCHIE_HOME_DEFAULT", str(dedicated))
    t_ctx = "01a118ca-0000-7000-8000-00000000000a"
    t_archie = "01a118ca-0000-7000-8000-00000000000b"
    t_vscode = "01a118ca-0000-7000-8000-00000000000c"
    _rollout(project / "context" / "codex" / "sessions", t_ctx, "codex_vscode")  # synced dir: all listed
    _rollout(shared / "sessions", t_archie, "archie")
    _rollout(shared / "sessions", t_vscode, "codex_vscode")  # shared home: Archie's only
    # Another project dir only sees its own context/codex/sessions.
    assert set(dict(_codex_discover_sessions(str(project)))) == {t_ctx}
    monkeypatch.setattr(home, "PROJECT_ROOT", project)
    found = dict(_codex_discover_sessions(str(project)))
    assert set(found) == {t_ctx, t_archie}
    monkeypatch.setattr("manager.codex.adapter._project_dir", lambda: str(project))
    assert [p.name for p in _codex_jsonl_candidates(t_vscode)] == [f"rollout-2026-10-07T20-00-00-{t_vscode}.jsonl"]
    assert _codex_jsonl_candidates("01a118ca-0000-7000-8000-0000000000ff") == []
    # Shared fallback until the dedicated home has a login.
    assert home.codex_home() == shared and home.is_shared_fallback()
    dedicated.mkdir()
    (dedicated / "auth.json").write_text("{}")
    assert home.codex_home() == dedicated and not home.is_shared_fallback()
    # A thread whose rollout lives in the shared home is resumed there
    # (when that home can still log in).
    assert home.home_for_thread(t_archie) == dedicated
    (shared / "auth.json").write_text("{}")
    assert home.home_for_thread(t_archie) == shared
    monkeypatch.setenv("ARCHIE_CODEX_HOME", str(tmp_path / "override"))
    assert home.codex_home() == tmp_path / "override"


def test_codex_env_strips_api_keys(monkeypatch, tmp_path):
    monkeypatch.setenv("OPENAI_API_KEY", "sk-x")
    monkeypatch.setenv("CLAUDECODE", "1")
    monkeypatch.setenv("CODEX_API_KEY", "keep")
    env = home.codex_env(tmp_path)
    assert "OPENAI_API_KEY" not in env and "CLAUDECODE" not in env
    assert env["CODEX_API_KEY"] == "keep" and env["CODEX_HOME"] == str(tmp_path)


# ---------------------------------------------------------------------------
# Spec + catalog
# ---------------------------------------------------------------------------


def test_harness_spec():
    ensure_all_registered()
    spec = get_registry().require("codex")
    assert spec.label == "Codex"
    assert spec.comm_prefix == "codex" and spec.ssh_control_path_prefix == "codex"
    assert spec.cli_binary == "codex" and spec.npm_package == "@openai/codex"
    assert spec.catalog_loader is not None and spec.session_discoverer is not None
    assert spec.session_class_loader().__name__ == "CodexSessionManager"


MODEL_LIST = [
    {"id": "gpt-6-luna", "model": "gpt-6-luna", "displayName": "GPT-6-Luna", "description": "Fast", "hidden": False,
     "supportedReasoningEfforts": [{"reasoningEffort": e} for e in ("low", "medium", "high", "xhigh", "max")],
     "defaultReasoningEffort": "medium", "inputModalities": ["text", "image"], "isDefault": True},
    {"id": "gpt-5.6-terra", "model": "gpt-5.6-terra", "displayName": "GPT-5.6-Terra", "hidden": False,
     "supportedReasoningEfforts": [{"reasoningEffort": e} for e in ("low", "medium", "ultra")],
     "defaultReasoningEffort": "medium", "inputModalities": ["text"], "isDefault": False},
    {"id": "gpt-5.5", "model": "gpt-5.5", "displayName": "GPT-5.5", "hidden": True,
     "supportedReasoningEfforts": [{"reasoningEffort": "low"}], "isDefault": False},
]


def test_models_from_model_list():
    models, default = cat.models_from_model_list(MODEL_LIST, context_windows={"gpt-6-luna": 272000})
    assert default == "gpt-6-luna"
    assert [m.id for m in models] == ["gpt-6-luna", "gpt-5.6-terra"]  # hidden dropped
    luna = models[0]
    assert luna.efforts == ("low", "medium", "high", "xhigh", "max") and luna.default_effort == "medium"
    assert luna.context_window == 272000 and luna.supports_vision is True and luna.source == "cli"
    assert models[1].supports_vision is False


def test_models_from_cache():
    data = {"models": [
        {"slug": "gpt-6-luna", "display_name": "GPT-6-Luna", "visibility": "list", "context_window": 272000,
         "default_reasoning_level": "medium", "supported_reasoning_levels": [{"effort": "low"}, {"effort": "max"}]},
        {"slug": "gpt-5.5", "visibility": "hide"},
    ]}
    models = cat.models_from_cache(data)
    assert [m.id for m in models] == ["gpt-6-luna"]
    assert models[0].efforts == ("low", "max") and models[0].source == "settings"


def _isolate_home(tmp_path, monkeypatch):
    monkeypatch.setenv("ARCHIE_CODEX_HOME", str(tmp_path))
    monkeypatch.setenv("CODEX_HOME", str(tmp_path / "shared"))
    monkeypatch.delenv("CODEX_API_KEY", raising=False)


def test_catalog_live(tmp_path, monkeypatch):
    _isolate_home(tmp_path, monkeypatch)
    (tmp_path / "auth.json").write_text("{}")
    monkeypatch.setattr(cat, "fetch_model_list", lambda home_dir, timeout=5.0: MODEL_LIST)
    catalog = cat.load_codex_catalog()
    assert catalog.provider == "codex" and catalog.default_model == "gpt-6-luna"
    assert catalog.warnings == ()
    keys = [o.key for o in catalog.options]
    assert keys == ["effort", "reasoning_summary", "verbosity", "web_search"]
    effort = catalog.option("effort")
    assert [c.value for c in effort.choices] == ["low", "medium", "high", "xhigh", "max", "ultra"]
    assert catalog.option("reasoning_summary").default == "concise"
    assert validate_options(catalog, {"effort": "ultra", "web_search": "live", "verbosity": None}) == {
        "effort": "ultra", "web_search": "live", "verbosity": None}
    with pytest.raises(ValueError):
        validate_options(catalog, {"web_search": "bogus"})
    json.dumps(catalog.to_dict())


def test_catalog_falls_back_to_cache_then_builtin(tmp_path, monkeypatch):
    _isolate_home(tmp_path, monkeypatch)

    def boom(home_dir, timeout=5.0):
        raise RuntimeError("codex not runnable")

    monkeypatch.setattr(cat, "fetch_model_list", boom)
    (tmp_path / "models_cache.json").write_text(json.dumps({"models": [
        {"slug": "gpt-x", "display_name": "GPT-X", "visibility": "list",
         "supported_reasoning_levels": [{"effort": "low"}]}]}))
    catalog = cat.load_codex_catalog()
    assert [m.id for m in catalog.models] == ["gpt-x"]
    assert any("not logged in" in w for w in catalog.warnings)
    assert any("Live model list unavailable" in w for w in catalog.warnings)
    (tmp_path / "models_cache.json").unlink()
    catalog = cat.load_codex_catalog()
    assert catalog.default_model == "gpt-6-luna" and len(catalog.models) == 3
    assert any("built-in" in w for w in catalog.warnings)


def test_catalog_warns_on_shared_fallback(tmp_path, monkeypatch):
    monkeypatch.delenv("ARCHIE_CODEX_HOME", raising=False)
    monkeypatch.setattr(home, "ARCHIE_HOME_DEFAULT", str(tmp_path / "dedicated"))
    monkeypatch.setenv("CODEX_HOME", str(tmp_path / "shared"))
    monkeypatch.setattr(cat, "fetch_model_list", lambda home_dir, timeout=5.0: MODEL_LIST)
    catalog = cat.load_codex_catalog()
    assert any(home.LOGIN_HINT in w and "shared" in w for w in catalog.warnings)


def test_fetch_model_list_against_fake_server(tmp_path, monkeypatch):
    import sys

    fake = Path(__file__).parent / "fixtures" / "codex" / "fake_app_server.py"
    script = tmp_path / "codex"
    script.write_text(f'#!/bin/sh\nexec "{sys.executable}" "{fake}" "$@"\n')
    script.chmod(0o755)
    monkeypatch.setenv("CODEX_CLI_PATH", str(script))
    rows = cat.fetch_model_list(tmp_path)
    assert [r["id"] for r in rows] == ["gpt-6-luna", "gpt-5.6-terra"]
    monkeypatch.setenv("CODEX_CLI_PATH", str(tmp_path / "missing"))
    with pytest.raises(RuntimeError):
        cat.fetch_model_list(tmp_path)
