"""Tests for manager/qwen/run_settings.py — the per-run system settings file."""

from __future__ import annotations

import json
import os
import stat

import pytest

from manager.qwen import run_settings as rs


def _user_settings() -> dict:
    return {
        "env": {"DASHSCOPE_API_KEY": "sk-secret-env"},
        "security": {"auth": {"selectedType": "openai", "apiKey": "sk-secret-auth"}},
        "model": {"name": "qwen3.6-plus"},
        "modelProviders": {
            "openai": [
                {
                    "id": "qwen3.6-plus",
                    "name": "[MS] qwen3.6-plus",
                    "baseUrl": "https://dashscope-intl.aliyuncs.com/compatible-mode/v1",
                    "envKey": "DASHSCOPE_API_KEY",
                    "apiKey": "sk-inline-secret",
                    "generationConfig": {
                        "extra_body": {"enable_thinking": True},
                        "contextWindowSize": 1000000,
                        "customHeaders": {"Authorization": "Bearer sk-hdr", "X-Trace": "1"},
                        "samplingParams": {"max_tokens": 4096},
                    },
                },
                {
                    "id": "deepseek-v4-flash",
                    "name": "[MS] deepseek-v4-flash",
                    "baseUrl": "https://dashscope-intl.aliyuncs.com/compatible-mode/v1",
                    "envKey": "DASHSCOPE_API_KEY",
                    "generationConfig": {"contextWindowSize": 1000000},
                },
                {
                    "id": "qwen3.8-max",
                    "name": "q38",
                    "baseUrl": "https://dashscope-intl.aliyuncs.com/compatible-mode/v1",
                    "envKey": "DASHSCOPE_API_KEY",
                },
            ],
            "ollama": [{"id": "llama3", "baseUrl": "http://localhost:11434/v1"}],
        },
    }


def _entry(settings: dict, model_id: str) -> dict:
    for entries in settings["modelProviders"].values():
        for e in entries:
            if e["id"] == model_id:
                return e
    raise AssertionError(f"{model_id} not in modelProviders")


# ── fixed keys ─────────────────────────────────────────────────────────


def test_fixed_keys_always_present():
    for model, opts in [(None, None), ("qwen3.6-plus", {"thinking": False}), ("x", {})]:
        s = rs.build_run_settings(model, opts, _user_settings(), env={})
        assert s["memory"] == {
            "enableManagedAutoMemory": False,
            "enableManagedAutoDream": False,
            "enableAutoSkill": False,
        }
        assert s["general"]["outputLanguage"] == "English"
        assert s["general"]["enableAutoUpdate"] is False
        assert s["agents"]["crossSessionMessaging"] is False
        assert s["$version"] == 4


def test_no_model_no_options_emits_only_fixed_keys():
    s = rs.build_run_settings(None, None, _user_settings(), env={})
    assert s == rs.fixed_settings()
    assert "modelProviders" not in s


def test_known_model_without_options_leaves_providers_alone():
    s = rs.build_run_settings("deepseek-v4-flash", {}, _user_settings(), env={})
    assert "modelProviders" not in s


def test_remote_mode_only_fixed_keys():
    s = rs.build_run_settings(
        "qwen3.6-plus", {"thinking": False}, _user_settings(), include_providers=False,
    )
    assert s == rs.fixed_settings()


# ── secrets ────────────────────────────────────────────────────────────


def test_no_secrets_copied():
    s = rs.build_run_settings("qwen3.6-plus", {"thinking": False}, _user_settings(), env={})
    dumped = json.dumps(s)
    for secret in ("sk-secret-env", "sk-secret-auth", "sk-inline-secret", "sk-hdr"):
        assert secret not in dumped
    assert "env" not in s and "security" not in s
    e = _entry(s, "qwen3.6-plus")
    assert "apiKey" not in e
    # envKey is an env var *name*, it must survive; harmless headers too.
    assert e["envKey"] == "DASHSCOPE_API_KEY"
    assert e["generationConfig"]["customHeaders"] == {"X-Trace": "1"}
    # max_tokens is not a credential.
    assert e["generationConfig"]["samplingParams"]["max_tokens"] == 4096


def test_every_provider_entry_kept():
    s = rs.build_run_settings("qwen3.6-plus", {"thinking": False}, _user_settings(), env={})
    ids = [e["id"] for entries in s["modelProviders"].values() for e in entries]
    assert ids == ["qwen3.6-plus", "deepseek-v4-flash", "qwen3.8-max", "llama3"]


def test_user_settings_not_mutated():
    user = _user_settings()
    before = json.dumps(user, sort_keys=True)
    rs.build_run_settings("qwen3.6-plus", {"thinking": False, "temperature": 0.2}, user, env={})
    assert json.dumps(user, sort_keys=True) == before


# ── model patching ─────────────────────────────────────────────────────


def test_thinking_off_patches_selected_entry_only():
    s = rs.build_run_settings("qwen3.6-plus", {"thinking": False}, _user_settings(), env={})
    assert _entry(s, "qwen3.6-plus")["generationConfig"]["extra_body"] == {"enable_thinking": False}
    assert "extra_body" not in _entry(s, "deepseek-v4-flash")["generationConfig"]


def test_default_model_is_patched_when_no_model_chosen():
    s = rs.build_run_settings(None, {"thinking": False}, _user_settings(), env={})
    assert _entry(s, "qwen3.6-plus")["generationConfig"]["extra_body"]["enable_thinking"] is False


def test_budget_and_temperature():
    s = rs.build_run_settings(
        "qwen3.6-plus",
        {"thinking": True, "thinking_budget": 2048, "temperature": 0.3},
        _user_settings(),
        env={},
    )
    gen = _entry(s, "qwen3.6-plus")["generationConfig"]
    assert gen["extra_body"] == {"enable_thinking": True, "thinking_budget": 2048}
    assert gen["samplingParams"] == {"max_tokens": 4096, "temperature": 0.3}
    assert gen["contextWindowSize"] == 1000000


def test_budget_dropped_when_thinking_off():
    patch = rs.generation_patch("qwen3.6-plus", {"thinking": False, "thinking_budget": 500})
    assert patch == {"extra_body": {"enable_thinking": False}}


def test_budget_ignored_for_deepseek():
    assert rs.generation_patch("deepseek-v4-flash", {"thinking_budget": 500}) == {}


def test_thinking_ignored_for_thinking_only_model():
    assert rs.generation_patch("qwen3.8-max", {"thinking": False}) == {}
    assert rs.generation_patch("glm-5.3", {"thinking": False}) == {}


def test_effort_only_for_qwen38():
    assert rs.generation_patch("qwen3.8-max", {"effort": "low"}) == {
        "extra_body": {"reasoning_effort": "low"},
    }
    assert rs.generation_patch("qwen3.6-plus", {"effort": "low"}) == {}
    # A level the model does not take is dropped, not forwarded.
    assert rs.generation_patch("qwen3.8-max", {"effort": "max"}) == {}


def test_unknown_options_ignored():
    assert rs.generation_patch("qwen3.6-plus", {"bogus": 1, "thinking": None}) == {}


def test_effort_lands_on_existing_entry():
    s = rs.build_run_settings("qwen3.8-max", {"effort": "medium"}, _user_settings(), env={})
    assert _entry(s, "qwen3.8-max")["generationConfig"] == {
        "extra_body": {"reasoning_effort": "medium"},
    }


# ── live-only model ────────────────────────────────────────────────────


def test_live_only_model_gets_synthetic_dashscope_entry():
    s = rs.build_run_settings("qwen3.7-plus", {}, _user_settings(), env={})
    e = _entry(s, "qwen3.7-plus")
    assert e["baseUrl"] == "https://dashscope-intl.aliyuncs.com/compatible-mode/v1"
    assert e["envKey"] == "DASHSCOPE_API_KEY"
    assert e["generationConfig"]["contextWindowSize"] == 1_000_000
    assert e in s["modelProviders"]["openai"]


def test_live_only_model_with_options():
    s = rs.build_run_settings("qwen3.7-plus", {"thinking": False}, _user_settings(), env={})
    assert _entry(s, "qwen3.7-plus")["generationConfig"]["extra_body"] == {"enable_thinking": False}


def test_live_only_reuses_user_envkey_name():
    user = _user_settings()
    for e in user["modelProviders"]["openai"]:
        e["envKey"] = "MY_DS_KEY"
    s = rs.build_run_settings("qwen3.7-plus", {}, user, env={})
    assert _entry(s, "qwen3.7-plus")["envKey"] == "MY_DS_KEY"


def test_unknown_model_without_dashscope_setup_passes_through():
    """OAuth / non-DashScope installs: no synthetic entry (the CLI keeps
    handling --model on its own, as before)."""
    user = {"model": {"name": "coder-model"}, "security": {"auth": {"selectedType": "qwen-oauth"}}}
    s = rs.build_run_settings("coder-model", {"thinking": False}, user, env={})
    assert "modelProviders" not in s


def test_unknown_model_with_dashscope_key_env_synthesizes():
    s = rs.build_run_settings("qwen3.7-plus", {}, {}, env={"DASHSCOPE_API_KEY": "sk-env-value"})
    e = _entry(s, "qwen3.7-plus")
    assert e["baseUrl"].startswith("https://dashscope-intl")
    assert "sk-env-value" not in json.dumps(s)  # only the var name travels


# ── files ──────────────────────────────────────────────────────────────


def test_write_and_remove_file(tmp_path, monkeypatch):
    monkeypatch.setenv("XDG_RUNTIME_DIR", str(tmp_path))
    path = rs.write_run_settings(rs.fixed_settings(), name="local/../id")
    assert path.parent == tmp_path / "archie-qwen"
    assert stat.S_IMODE(path.stat().st_mode) == 0o600
    assert stat.S_IMODE(path.parent.stat().st_mode) == 0o700
    assert json.loads(path.read_text()) == rs.fixed_settings()
    assert "/" not in path.name.replace(".json", "")
    rs.remove_run_settings(path)
    assert not path.exists()
    rs.remove_run_settings(path)  # idempotent
    rs.remove_run_settings(None)


def test_runtime_dir_falls_back_to_tmp(tmp_path, monkeypatch):
    monkeypatch.delenv("XDG_RUNTIME_DIR", raising=False)
    monkeypatch.setattr(rs.tempfile, "gettempdir", lambda: str(tmp_path))
    d = rs.runtime_dir()
    assert d == tmp_path / f"archie-qwen-{os.getuid()}"


def test_runtime_dir_never_under_context(monkeypatch):
    from utils.paths import PROJECT_ROOT

    d = rs.runtime_dir()
    assert not str(d.resolve()).startswith(str((PROJECT_ROOT / "context").resolve()))


@pytest.mark.parametrize("key", ["apiKey", "api_key", "Authorization", "accessToken", "token", "client_secret", "password"])
def test_secret_key_names_stripped(key):
    assert rs._strip_secrets({key: "v", "keep": 1}) == {"keep": 1}


@pytest.mark.parametrize("key", ["envKey", "max_tokens", "maxTokens", "contextWindowSize", "baseUrl"])
def test_non_secret_key_names_kept(key):
    assert rs._strip_secrets({key: "v"}) == {key: "v"}
