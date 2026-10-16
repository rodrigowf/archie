"""Tests for api/routes/sessions.py — REST session endpoints."""

from datetime import datetime, timezone
from unittest.mock import AsyncMock, MagicMock

import pytest
from httpx import ASGITransport, AsyncClient

from api.app import create_app
from api.deps import get_pool, get_store
from manager.types import MessagePreview, SessionDetail, SessionInfo


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------

_NOW = datetime(2026, 2, 5, 12, 0, 0, tzinfo=timezone.utc)


def _sample_sessions():
    return [
        SessionInfo(
            session_id="s1",
            started_at=_NOW,
            last_activity=_NOW,
            title="First session",
            message_count=5,
        ),
        SessionInfo(
            session_id="s2",
            started_at=_NOW,
            last_activity=_NOW,
            title="Second session",
            message_count=3,
        ),
    ]


def _sample_detail():
    return SessionDetail(
        session_id="s1",
        started_at=_NOW,
        last_activity=_NOW,
        title="First session",
        message_count=5,
        messages=[
            MessagePreview(role="user", text="Hello", timestamp=_NOW),
            MessagePreview(role="assistant", text="Hi there", timestamp=_NOW),
        ],
    )


def _make_app(sessions=None, detail=None, delete_ok=True):
    app = create_app()

    mock_store = MagicMock()
    mock_store.list_sessions.return_value = sessions or []
    mock_store.get_session.return_value = detail
    mock_store.get_preview.return_value = detail.messages[:5] if detail else []
    mock_store.delete_session.return_value = delete_ok

    # The list_sessions route also reads from the pool to surface live
    # sessions (with no JSONL yet).  AsyncClient bypasses the FastAPI
    # lifespan that would normally populate ``app.state.pool``, so we
    # inject a stub directly via the dependency-override hook.
    mock_pool = MagicMock()
    mock_pool.list_sessions.return_value = []
    mock_pool.has_orchestrator.return_value = False

    app.dependency_overrides[get_store] = lambda: mock_store
    app.dependency_overrides[get_pool] = lambda: mock_pool
    return app


@pytest.fixture
async def client():
    app = _make_app(
        sessions=_sample_sessions(),
        detail=_sample_detail(),
    )
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as ac:
        yield ac


@pytest.fixture
async def client_empty():
    app = _make_app(sessions=[], detail=None, delete_ok=False)
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as ac:
        yield ac


# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------

class TestListSessions:
    async def test_returns_sessions(self, client):
        resp = await client.get("/api/sessions")
        assert resp.status_code == 200
        data = resp.json()
        assert len(data) == 2
        assert data[0]["session_id"] == "s1"
        assert data[0]["title"] == "First session"
        assert data[0]["message_count"] == 5

    async def test_empty_list(self, client_empty):
        resp = await client_empty.get("/api/sessions")
        assert resp.status_code == 200
        assert resp.json() == []


class TestGetSession:
    async def test_found(self, client):
        resp = await client.get("/api/sessions/s1")
        assert resp.status_code == 200
        data = resp.json()
        assert data["session_id"] == "s1"
        assert len(data["messages"]) == 2
        assert data["messages"][0]["role"] == "user"

    async def test_not_found(self, client_empty):
        resp = await client_empty.get("/api/sessions/nope")
        assert resp.status_code == 404


class TestGetPreview:
    async def test_returns_previews(self, client):
        resp = await client.get("/api/sessions/s1/preview")
        assert resp.status_code == 200
        data = resp.json()
        assert len(data) == 2

    async def test_not_found(self, client_empty):
        resp = await client_empty.get("/api/sessions/nope/preview")
        assert resp.status_code == 404


class TestDeleteSession:
    async def test_delete_success(self, client):
        resp = await client.delete("/api/sessions/s1")
        assert resp.status_code == 204

    async def test_delete_not_found(self, client_empty):
        resp = await client_empty.delete("/api/sessions/nope")
        assert resp.status_code == 404


class TestDuplicateSession:
    async def test_duplicate_success(self, client):
        app = client._transport.app
        app.dependency_overrides[get_store]().duplicate_session.return_value = "new-id"
        resp = await client.post("/api/sessions/s1/duplicate")
        assert resp.status_code == 201
        assert resp.json() == {"session_id": "new-id"}

    async def test_duplicate_missing(self, client):
        app = client._transport.app
        app.dependency_overrides[get_store]().duplicate_session.return_value = None
        resp = await client.post("/api/sessions/nope/duplicate")
        assert resp.status_code == 404

    async def test_duplicate_allowed_for_live_session(self, client):
        """Duplicate is safe while source is open — it's a file read; the new
        file is independent."""
        app = client._transport.app
        app.dependency_overrides[get_store]().duplicate_session.return_value = "new-id"
        app.dependency_overrides[get_pool]().list_sessions.return_value = [
            {"session_id": "local-1", "sdk_session_id": "s1"}
        ]
        resp = await client.post("/api/sessions/s1/duplicate")
        assert resp.status_code == 201


class TestTruncateSession:
    async def test_truncate_success(self, client):
        app = client._transport.app
        app.dependency_overrides[get_store]().truncate_session.return_value = True
        resp = await client.post(
            "/api/sessions/s1/truncate", json={"drop_last_n": 1}
        )
        assert resp.status_code == 200

    async def test_truncate_missing_field(self, client):
        resp = await client.post("/api/sessions/s1/truncate", json={})
        assert resp.status_code == 400

    async def test_truncate_negative_value(self, client):
        resp = await client.post(
            "/api/sessions/s1/truncate", json={"drop_last_n": -1}
        )
        assert resp.status_code == 400

    async def test_truncate_not_found(self, client):
        app = client._transport.app
        app.dependency_overrides[get_store]().truncate_session.return_value = False
        resp = await client.post(
            "/api/sessions/nope/truncate", json={"drop_last_n": 1}
        )
        assert resp.status_code == 404


class TestForkSession:
    async def test_fork_success(self, client):
        app = client._transport.app
        app.dependency_overrides[get_store]().fork_session.return_value = "forked-id"
        resp = await client.post(
            "/api/sessions/s1/fork", json={"drop_last_n": 2}
        )
        assert resp.status_code == 201
        assert resp.json() == {"session_id": "forked-id"}

    async def test_fork_bad_value(self, client):
        resp = await client.post(
            "/api/sessions/s1/fork", json={"drop_last_n": "bad"}
        )
        assert resp.status_code == 400

    async def test_fork_allowed_for_live_session(self, client):
        """Fork only mutates the copy, so the source can stay open."""
        app = client._transport.app
        app.dependency_overrides[get_store]().fork_session.return_value = "forked-id"
        app.dependency_overrides[get_pool]().list_sessions.return_value = [
            {"session_id": "local-1", "sdk_session_id": "s1"}
        ]
        resp = await client.post(
            "/api/sessions/s1/fork", json={"drop_last_n": 1}
        )
        assert resp.status_code == 201


class TestResolvePermission:
    """POST /api/sessions/{local_id}/permission — REST twin of the chat WS
    ``permission_response`` frame (agent approvals from anywhere)."""

    @staticmethod
    def _pool(client):
        pool = client._transport.app.dependency_overrides[get_pool]()
        pool.has.return_value = True
        pool.resolve_session_permission = AsyncMock(return_value=True)
        return pool

    async def test_allow_resolves(self, client):
        pool = self._pool(client)
        resp = await client.post(
            "/api/sessions/local-1/permission",
            json={"request_id": "r1", "decision": "allow"},
        )
        assert resp.status_code == 200
        assert resp.json() == {"ok": True}
        pool.resolve_session_permission.assert_awaited_once_with(
            "local-1", "r1", "allow", message=None, responder="user",
        )

    async def test_deny_with_message(self, client):
        pool = self._pool(client)
        resp = await client.post(
            "/api/sessions/local-1/permission",
            json={"request_id": "r1", "decision": "deny", "message": "not now"},
        )
        assert resp.status_code == 200
        pool.resolve_session_permission.assert_awaited_once_with(
            "local-1", "r1", "deny", message="not now", responder="user",
        )

    async def test_unknown_session_404(self, client):
        pool = self._pool(client)
        pool.has.return_value = False
        resp = await client.post(
            "/api/sessions/nope/permission",
            json={"request_id": "r1", "decision": "allow"},
        )
        assert resp.status_code == 404
        pool.resolve_session_permission.assert_not_awaited()

    async def test_already_answered_409(self, client):
        pool = self._pool(client)
        pool.resolve_session_permission.return_value = False
        resp = await client.post(
            "/api/sessions/local-1/permission",
            json={"request_id": "gone", "decision": "deny"},
        )
        assert resp.status_code == 409

    @pytest.mark.parametrize("body", [
        {"decision": "allow"},
        {"request_id": "", "decision": "allow"},
        {"request_id": "r1"},
        {"request_id": "r1", "decision": "maybe"},
        {"request_id": "r1", "decision": "deny", "message": 3},
        ["r1", "allow"],
    ])
    async def test_invalid_body_400(self, client, body):
        pool = self._pool(client)
        resp = await client.post("/api/sessions/local-1/permission", json=body)
        assert resp.status_code == 400
        pool.resolve_session_permission.assert_not_awaited()

    async def test_malformed_json_400(self, client):
        self._pool(client)
        resp = await client.post(
            "/api/sessions/local-1/permission",
            content=b"{not json",
            headers={"content-type": "application/json"},
        )
        assert resp.status_code == 400


# ---------------------------------------------------------------------------
# POST /api/sessions/inject — restored sessions and reopen-on-send
# ---------------------------------------------------------------------------

def _inject_app(*, live=None, restored=None, info=None):
    """``live`` / ``restored``: sdk id → local id of a spawned / restored-not-spawned session."""
    live = dict(live or {})
    restored = dict(restored or {})
    app = create_app()
    store = MagicMock()
    store.get_session_info.return_value = info
    pool = MagicMock()
    pool.find_open_by_sdk_id.side_effect = lambda sdk: live.get(sdk) or restored.get(sdk)
    pool.has.side_effect = lambda lid: lid in live.values()

    async def ensure_live(lid):
        if lid in restored.values():
            live.update({k: v for k, v in restored.items() if v == lid})
            return True
        return lid in live.values()

    pool.ensure_live = AsyncMock(side_effect=ensure_live)
    pool.create = AsyncMock(return_value="new-local")
    pool.send_or_queue = AsyncMock(return_value="started")
    app.dependency_overrides[get_store] = lambda: store
    app.dependency_overrides[get_pool] = lambda: pool
    return app, pool


async def _post(app, body):
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as ac:
        return await ac.post("/api/sessions/inject", json=body)


class TestInject:
    async def test_live_session(self):
        app, pool = _inject_app(live={"sdk1": "l1"})
        r = await _post(app, {"sdk_session_id": "sdk1", "text": "hi"})
        assert r.status_code == 200 and r.json()["local_id"] == "l1" and r.json()["reopened"] is False
        pool.send_or_queue.assert_awaited_once_with("l1", "hi")

    async def test_restored_session_is_spawned(self):
        app, pool = _inject_app(restored={"sdk1": "l1"})
        r = await _post(app, {"sdk_session_id": "sdk1", "text": "hi"})
        assert r.status_code == 200
        pool.ensure_live.assert_awaited_once_with("l1")
        pool.create.assert_not_called()

    async def test_closed_session_without_open_is_404(self):
        app, pool = _inject_app()
        r = await _post(app, {"sdk_session_id": "sdk1", "text": "hi"})
        assert r.status_code == 404 and "open: true" in r.json()["detail"]
        pool.create.assert_not_called()

    async def test_closed_session_reopens_with_open(self, monkeypatch):
        import api.session_factory

        monkeypatch.setattr(api.session_factory, "build_session_config", lambda **kw: ("cfg", {"m": {}}, {}))
        app, pool = _inject_app(info=SessionInfo(session_id="sdk1", started_at=_NOW, last_activity=_NOW, title="t", message_count=1))
        pool.has.side_effect = lambda lid: lid == "new-local"
        r = await _post(app, {"sdk_session_id": "sdk1", "text": "hi", "open": True})
        assert r.status_code == 200 and r.json() == {"ok": True, "local_id": "new-local", "delivery": "started", "reopened": True}
        pool.create.assert_awaited_once_with("cfg", resume_sdk_id="sdk1", mcp_servers={"m": {}})

    async def test_reopen_unknown_session_is_404(self):
        app, pool = _inject_app(info=None)
        r = await _post(app, {"sdk_session_id": "sdk1", "text": "hi", "open": True})
        assert r.status_code == 404
        pool.create.assert_not_called()

    async def test_reopen_refuses_archie_conversations(self):
        info = SessionInfo(session_id="sdk1", started_at=_NOW, last_activity=_NOW, title="t", message_count=1, is_orchestrator=True)
        app, pool = _inject_app(info=info)
        r = await _post(app, {"sdk_session_id": "sdk1", "text": "hi", "open": True})
        assert r.status_code == 400
        pool.create.assert_not_called()
