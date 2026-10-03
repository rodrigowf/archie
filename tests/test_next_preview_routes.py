"""Tests for the frontend-next preview routes (/next/, /next-compat/) — BX-1."""

from pathlib import Path

import pytest
from starlette.testclient import TestClient

import api.app as app_module


def _make_build(root: Path, marker: str) -> None:
    (root / "assets").mkdir(parents=True)
    (root / "index.html").write_text(f"<html>{marker}</html>")
    (root / "assets" / "app-abc123.js").write_text(f"console.log('{marker}')")
    (root / "icon.svg").write_text("<svg/>")


@pytest.fixture
def preview_client(tmp_path, monkeypatch):
    main = tmp_path / "dist-preview" / "main"
    compat = tmp_path / "dist-preview" / "compat"
    _make_build(main, "next-main")
    _make_build(compat, "next-compat")
    (tmp_path / "secret.txt").write_text("top secret")
    monkeypatch.setattr(
        app_module, "_next_preview_dirs",
        lambda: [("next", main), ("next-compat", compat)],
    )
    # No lifespan: static routes need none.
    return TestClient(app_module.create_app())


@pytest.mark.parametrize("prefix,marker", [
    ("next", "next-main"), ("next-compat", "next-compat"),
])
def test_index_served_with_no_cache(preview_client, prefix, marker):
    for path in (f"/{prefix}", f"/{prefix}/"):
        resp = preview_client.get(path, follow_redirects=False)
        assert resp.status_code == 200
        assert marker in resp.text
        assert "no-cache" in resp.headers["cache-control"]


@pytest.mark.parametrize("prefix,marker", [
    ("next", "next-main"), ("next-compat", "next-compat"),
])
def test_assets_and_files_served(preview_client, prefix, marker):
    resp = preview_client.get(f"/{prefix}/assets/app-abc123.js")
    assert resp.status_code == 200
    assert marker in resp.text
    resp = preview_client.get(f"/{prefix}/icon.svg")
    assert resp.status_code == 200
    assert resp.text == "<svg/>"


def test_spa_fallback(preview_client):
    resp = preview_client.get("/next/some/client/route")
    assert resp.status_code == 200
    assert "next-main" in resp.text
    assert "no-cache" in resp.headers["cache-control"]
    resp = preview_client.get("/next-compat/whatever")
    assert "next-compat" in resp.text


def test_traversal_rejected(preview_client):
    resp = preview_client.get("/next/..%2F..%2Fsecret.txt")
    assert resp.status_code == 404
    assert "top secret" not in resp.text
    resp = preview_client.get("/next-compat/..%2F..%2Fsecret.txt")
    assert resp.status_code == 404


def test_existing_routes_not_shadowed(preview_client):
    """Preview routes come after every /api route and only claim /next*."""
    paths = [getattr(r, "path", "") for r in preview_client.app.routes]
    next_idx = [i for i, p in enumerate(paths) if p.startswith("/next")]
    api_idx = [i for i, p in enumerate(paths) if p.startswith("/api/")]
    assert next_idx and api_idx
    assert min(next_idx) > max(api_idx)
    assert all(
        p in ("/next", "/next/", "/next-compat", "/next-compat/")
        or p.startswith(("/next/", "/next-compat/"))
        for p in (paths[i] for i in next_idx)
    )
    # A root path that merely starts with "next" is not captured.
    resp = preview_client.get("/nextish-file.html")
    assert "next-main" not in resp.text


def test_routes_absent_when_build_missing(tmp_path, monkeypatch):
    monkeypatch.setattr(
        app_module, "_next_preview_dirs",
        lambda: [("next", tmp_path / "nope"), ("next-compat", tmp_path / "nope2")],
    )
    app = app_module.create_app()
    paths = {getattr(r, "path", None) for r in app.routes}
    assert not any(p and p.startswith("/next") for p in paths)
