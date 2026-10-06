"""Tests for the prefixed SPA routes (/compat/, /legacy/, /legacy_compat/) and the
retired /next* preview redirects — cutover, spec 13 §1.7 (was BX-1)."""

from pathlib import Path

import pytest
from starlette.testclient import TestClient

import api.app as app_module

PREFIXES = ("compat", "legacy", "legacy_compat")


def _make_build(root: Path, marker: str) -> None:
    (root / "assets").mkdir(parents=True)
    (root / "index.html").write_text(f"<html>{marker}</html>")
    (root / "assets" / "app-abc123.js").write_text(f"console.log('{marker}')")
    (root / "icon.svg").write_text("<svg/>")


@pytest.fixture
def spa_client(tmp_path, monkeypatch):
    dirs = []
    for prefix in PREFIXES:
        root = tmp_path / "builds" / prefix
        _make_build(root, f"build-{prefix}")
        dirs.append((prefix, root))
    (tmp_path / "secret.txt").write_text("top secret")
    monkeypatch.setattr(app_module, "_spa_dirs", lambda: dirs)
    # No lifespan: static routes need none.
    return TestClient(app_module.create_app())


@pytest.mark.parametrize("prefix", PREFIXES)
def test_index_served_with_no_cache(spa_client, prefix):
    for path in (f"/{prefix}", f"/{prefix}/"):
        resp = spa_client.get(path, follow_redirects=False)
        assert resp.status_code == 200
        assert f"build-{prefix}" in resp.text
        assert "no-cache" in resp.headers["cache-control"]


@pytest.mark.parametrize("prefix", PREFIXES)
def test_assets_and_files_served(spa_client, prefix):
    resp = spa_client.get(f"/{prefix}/assets/app-abc123.js")
    assert resp.status_code == 200
    assert f"build-{prefix}" in resp.text
    resp = spa_client.get(f"/{prefix}/icon.svg")
    assert resp.status_code == 200
    assert resp.text == "<svg/>"


@pytest.mark.parametrize("prefix", PREFIXES)
def test_spa_fallback(spa_client, prefix):
    resp = spa_client.get(f"/{prefix}/some/client/route")
    assert resp.status_code == 200
    assert f"build-{prefix}" in resp.text
    assert "no-cache" in resp.headers["cache-control"]


@pytest.mark.parametrize("prefix", PREFIXES)
def test_traversal_rejected(spa_client, prefix):
    resp = spa_client.get(f"/{prefix}/..%2F..%2F..%2Fsecret.txt")
    assert resp.status_code == 404
    assert "top secret" not in resp.text


def test_legacy_does_not_capture_legacy_compat(spa_client):
    resp = spa_client.get("/legacy_compat/")
    assert "build-legacy_compat" in resp.text
    resp = spa_client.get("/legacy_compat/assets/app-abc123.js")
    assert "build-legacy_compat" in resp.text


@pytest.mark.parametrize("path,target", [
    ("/next", "/"), ("/next/", "/"), ("/next/index.html", "/"),
    ("/next-compat", "/compat/"), ("/next-compat/", "/compat/"), ("/next-compat/a/b", "/compat/"),
])
def test_retired_preview_paths_redirect(spa_client, path, target):
    resp = spa_client.get(path, follow_redirects=False)
    assert resp.status_code == 307
    assert resp.headers["location"] == target


def test_retired_redirect_ignores_query(spa_client):
    resp = spa_client.get("/next/?target=https://example.com", follow_redirects=False)
    assert resp.headers["location"] == "/"


def test_existing_routes_not_shadowed(spa_client):
    """Prefixed routes come after every /api route and only claim their own prefix."""
    paths = [getattr(r, "path", "") for r in spa_client.app.routes]
    spa_idx = [i for i, p in enumerate(paths) if p.startswith(tuple(f"/{p}" for p in PREFIXES + ("next",)))]
    api_idx = [i for i, p in enumerate(paths) if p.startswith("/api/")]
    assert spa_idx and api_idx
    assert min(spa_idx) > max(api_idx)
    # A root path that merely starts with a prefix is not captured.
    resp = spa_client.get("/legacyish-file.html")
    assert "build-legacy" not in resp.text
    resp = spa_client.get("/nextish-file.html", follow_redirects=False)
    assert resp.status_code != 307


def test_routes_absent_when_build_missing(tmp_path, monkeypatch):
    monkeypatch.setattr(
        app_module, "_spa_dirs",
        lambda: [(p, tmp_path / f"nope-{p}") for p in PREFIXES],
    )
    app = app_module.create_app()
    paths = {getattr(r, "path", None) for r in app.routes}
    assert not any(p and p.startswith(("/compat", "/legacy")) for p in paths)
