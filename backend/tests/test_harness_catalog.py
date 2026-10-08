"""Tests for ``manager/harness_catalog.py`` and the harness-options plumbing.

Covers the schema (validation, merge), the per-provider catalog cache, the
``/api/config/harnesses`` + ``/api/config/harness/{p}/catalog`` routes, the
global ``harness_options`` PUT, the per-session PUT validation, and the
resolution into ``ManagerConfig.harness_options``.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from manager import harness_catalog as hc
from manager.harness_catalog import (
    Choice,
    HarnessCatalog,
    HarnessModel,
    HarnessOption,
    effort_option,
    merge_options,
    validate_options,
)
from manager.registry import HarnessSpec, ensure_all_registered, get_registry


def _catalog() -> HarnessCatalog:
    return HarnessCatalog(
        provider="fakeh",
        models=(
            HarnessModel(id="m-big", label="Big", efforts=("low", "high"), supports_thinking=True),
            HarnessModel(id="m-small", label="Small", efforts=()),
        ),
        options=(
            effort_option(("low", "medium", "high"), default="medium"),
            HarnessOption(key="thinking", label="Thinking", kind="toggle", default=True),
            HarnessOption(key="budget", label="Budget", kind="number", min=0, max=1000),
            HarnessOption(key="mode", label="Mode", choices=(Choice("a", "A"), Choice("b", "B")), models=("m-big",)),
        ),
        default_model="m-big",
    )


@pytest.fixture
def fake_harness(monkeypatch: pytest.MonkeyPatch):
    """Register a throwaway harness with a counting catalog loader."""
    ensure_all_registered()
    calls = {"n": 0}

    def loader() -> HarnessCatalog:
        calls["n"] += 1
        return _catalog()

    spec = HarnessSpec(
        name="fakeh",
        label="Fake",
        description="test harness",
        session_class_loader=lambda: None,  # type: ignore[arg-type,return-value]
        adapter_loader=lambda: None,  # type: ignore[arg-type,return-value]
        comm_prefix="fakeh",
        kill_helper_loader=lambda: (lambda pid: False),
        ssh_control_path_prefix="fakeh",
        jsonl_path_resolver=lambda sid: [],
        catalog_loader=loader,
    )
    reg = get_registry()
    monkeypatch.setitem(reg._specs, "fakeh", spec)
    hc.clear_catalog_cache()
    yield calls
    hc.clear_catalog_cache()


# ── schema ────────────────────────────────────────────────────────────


def test_to_dict_shape() -> None:
    d = _catalog().to_dict()
    assert d["provider"] == "fakeh"
    assert d["default_model"] == "m-big"
    assert d["models"][0] == {
        "id": "m-big", "label": "Big", "source": "builtin",
        "supports_thinking": True, "efforts": ["low", "high"],
    }
    assert d["models"][1]["efforts"] == []
    opts = {o["key"]: o for o in d["options"]}
    assert opts["effort"]["choices"][2] == {"value": "high", "label": "High"}
    assert opts["effort"]["default"] == "medium"
    assert opts["thinking"]["kind"] == "toggle"
    assert opts["budget"]["min"] == 0 and opts["budget"]["max"] == 1000
    assert opts["mode"]["models"] == ["m-big"]
    json.dumps(d)  # serializable


def test_validate_accepts_valid_values_and_none() -> None:
    c = _catalog()
    assert validate_options(c, {"effort": "high", "thinking": False, "budget": 10.0, "mode": None}) == {
        "effort": "high", "thinking": False, "budget": 10, "mode": None,
    }
    assert validate_options(c, None) == {}
    assert validate_options(c, {}) == {}


@pytest.mark.parametrize("opts", [
    {"nope": "x"},
    {"effort": "ultra"},
    {"effort": 3},
    {"thinking": "yes"},
    {"budget": "10"},
    {"budget": True},
    {"budget": 5000},
    {"budget": -1},
])
def test_validate_rejects(opts: dict) -> None:
    with pytest.raises(ValueError):
        validate_options(_catalog(), opts)


def test_validate_without_catalog_rejects_any_key() -> None:
    with pytest.raises(ValueError):
        validate_options(None, {"effort": "high"})


def test_merge_session_overrides_and_none_means_cli_default() -> None:
    assert merge_options({"effort": "low", "thinking": True}, {"effort": "high"}) == {"effort": "high", "thinking": True}
    # session None = "CLI default here", even though global has a value
    assert merge_options({"effort": "low"}, {"effort": None}) == {}
    assert merge_options(None, None) == {}
    assert merge_options({"effort": None}, None) == {}


# ── cache ─────────────────────────────────────────────────────────────


def test_get_catalog_caches_and_refreshes(fake_harness) -> None:
    assert hc.get_catalog("fakeh").provider == "fakeh"
    hc.get_catalog("fakeh")
    assert fake_harness["n"] == 1
    hc.get_catalog("fakeh", refresh=True)
    assert fake_harness["n"] == 2


def test_get_catalog_unknown_or_no_loader() -> None:
    assert hc.get_catalog("does-not-exist") is None


def test_get_catalog_survives_a_raising_loader(monkeypatch: pytest.MonkeyPatch, fake_harness) -> None:
    from dataclasses import replace

    reg = get_registry()

    def boom() -> HarnessCatalog:
        raise RuntimeError("upstream down")

    monkeypatch.setitem(reg._specs, "fakeh", replace(reg._specs["fakeh"], catalog_loader=boom))
    c = hc.get_catalog("fakeh", refresh=True)
    assert c is not None and c.models == () and "upstream down" in c.warnings[0]


# ── routes ────────────────────────────────────────────────────────────


@pytest.fixture
def client(tmp_path: Path, monkeypatch: pytest.MonkeyPatch, fake_harness) -> TestClient:
    from dataclasses import replace

    reg = get_registry()
    for name, spec in list(reg._specs.items()):  # real loaders may hit the network
        if name != "fakeh":
            monkeypatch.setitem(reg._specs, name, replace(spec, catalog_loader=None))
    monkeypatch.setenv("QWEN_HOME", str(tmp_path / "qwen"))
    import utils.paths
    monkeypatch.setattr(utils.paths, "PROJECT_ROOT", tmp_path)
    import api.routes.config as cfg_module
    monkeypatch.setattr(cfg_module, "PROJECT_ROOT", tmp_path)
    import api.routes.session_config as sc
    monkeypatch.setattr(sc, "get_context_dir", lambda: tmp_path)
    from api.app import create_app
    return TestClient(create_app())


def test_harnesses_route_lists_every_registered_harness(client: TestClient) -> None:
    r = client.get("/api/config/harnesses")
    assert r.status_code == 200
    rows = {h["id"]: h for h in r.json()["harnesses"]}
    assert {"claude", "qwen", "gemini", "fakeh"} <= set(rows)
    assert rows["fakeh"]["label"] == "Fake"
    assert rows["fakeh"]["catalog"]["default_model"] == "m-big"


def test_catalog_route(client: TestClient) -> None:
    r = client.get("/api/config/harness/fakeh/catalog")
    assert r.status_code == 200 and r.json()["provider"] == "fakeh"
    assert client.get("/api/config/harness/nope/catalog").status_code == 404


def test_global_harness_options_put_merges_and_validates(client: TestClient) -> None:
    cfg = client.get("/api/config").json()
    assert cfg["harness_options"].get("fakeh", {}) == {} or "fakeh" not in cfg["harness_options"]

    r = client.put("/api/config", json={"harness_options": {"fakeh": {"effort": "high", "thinking": False}}})
    assert r.status_code == 200, r.text
    assert r.json()["harness_options"]["fakeh"] == {"effort": "high", "thinking": False}

    # per-key merge; None drops the key
    r = client.put("/api/config", json={"harness_options": {"fakeh": {"effort": None, "budget": 50}}})
    assert r.json()["harness_options"]["fakeh"] == {"thinking": False, "budget": 50}

    assert client.put("/api/config", json={"harness_options": {"fakeh": {"effort": "ultra"}}}).status_code == 400
    assert client.put("/api/config", json={"harness_options": {"fakeh": {"zzz": 1}}}).status_code == 400
    assert client.put("/api/config", json={"harness_options": {"nope": {}}}).status_code == 400


def test_session_harness_options_put_validates_against_session_provider(client: TestClient) -> None:
    r = client.put("/api/sessions/sid-1/config", json={"provider": "fakeh", "harness_options": {"effort": "low"}})
    assert r.status_code == 200, r.text
    assert r.json()["harness_options"] == {"effort": "low"}
    # provider now pinned → later PUTs validate against it
    assert client.put("/api/sessions/sid-1/config", json={"harness_options": {"effort": "ultra"}}).status_code == 400
    # null = inherit everything
    r = client.put("/api/sessions/sid-1/config", json={"harness_options": None})
    assert r.status_code == 200 and r.json()["harness_options"] is None


# ── resolution ────────────────────────────────────────────────────────


def test_session_factory_overlays_session_options(monkeypatch: pytest.MonkeyPatch, tmp_path: Path, fake_harness) -> None:
    import api.routes.config as cfg_module
    import api.routes.session_config as sc
    from api import session_factory

    monkeypatch.setattr(sc, "get_context_dir", lambda: tmp_path)
    assistant = cfg_module._default_config()
    assistant["provider"] = "fakeh"
    assistant["harness_options"] = {"fakeh": {"effort": "low", "thinking": True}, "claude": {"effort": "max"}}
    monkeypatch.setattr(cfg_module, "_load_config", lambda: assistant)
    (tmp_path / "sid-2.config.json").write_text(json.dumps({
        "provider": "fakeh", "harness_options": {"effort": "high", "thinking": None},
    }))

    config, _mcp, info = session_factory.build_session_config(resume_sdk_id="sid-2")
    assert config.provider == "fakeh"
    assert config.harness_options == {"effort": "high"}
    assert info["harness_options"] == {"effort": "high"}

    # fresh session: global map only, other providers' maps don't leak in
    config, _mcp, _info = session_factory.build_session_config()
    assert config.harness_options == {"effort": "low", "thinking": True}


def test_manager_json_model_does_not_leak_into_other_harnesses(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    """``.manager.json``'s Claude model must not reach Codex/Qwen/Gemini."""
    import api.routes.config as cfg_module
    import api.routes.session_config as sc
    from api import session_factory
    from manager.config import ManagerConfig

    monkeypatch.setattr(sc, "get_context_dir", lambda: tmp_path)
    monkeypatch.setattr(ManagerConfig, "load", classmethod(lambda cls, path=None: cls(model="claude-opus-5[1m]")))
    assistant = cfg_module._default_config()
    monkeypatch.setattr(cfg_module, "_load_config", lambda: assistant)

    assistant["provider"] = "qwen"
    config, _m, _i = session_factory.build_session_config()
    assert config.provider == "qwen" and config.model is None

    assistant["harness_model"] = {"qwen": "qwen3.6-plus"}
    config, _m, _i = session_factory.build_session_config()
    assert config.model == "qwen3.6-plus"

    assistant["provider"] = "claude"
    config, _m, _i = session_factory.build_session_config()
    assert config.model == "claude-opus-5[1m]"
