"""Tests for manager/index_utils — removing a deleted session from the history index."""
from __future__ import annotations

import json
from pathlib import Path
from unittest.mock import patch

import pytest

from manager import index_utils
from utils import history_index as hi

pytestmark = pytest.mark.timeout(30)


@pytest.fixture
def db(tmp_path: Path):
    path = tmp_path / "history.sqlite3"
    session = tmp_path / "s1.jsonl"
    session.write_text(json.dumps(
        {"type": "user", "message": {"content": "hello from the session being deleted"}}
    ) + "\n")
    conn = hi.connect(path)
    hi.index_session(conn, session, lambda texts: [[1.0] + [0.0] * 383 for _ in texts])
    conn.close()
    with patch.object(hi, "get_history_db_path", return_value=path):
        yield path


def _count(path: Path, session_id: str) -> int:
    conn = hi.connect(path)
    try:
        return conn.execute("SELECT COUNT(*) FROM chunks WHERE session_id=?", (session_id,)).fetchone()[0]
    finally:
        conn.close()


def test_removes_session_chunks(db):
    assert _count(db, "s1") == 1
    assert index_utils.remove_session_from_index("s1") is True
    assert _count(db, "s1") == 0


def test_unknown_session_is_fine(db):
    assert index_utils.remove_session_from_index("nope") is True
    assert _count(db, "s1") == 1


def test_missing_index_is_not_created(tmp_path):
    path = tmp_path / "absent.sqlite3"
    with patch.object(hi, "get_history_db_path", return_value=path):
        assert index_utils.remove_session_from_index("s1") is True
    assert not path.exists()


def test_other_collections_are_refused(db):
    assert index_utils.remove_session_from_index("s1", collection_name="memory") is False
    assert _count(db, "s1") == 1
