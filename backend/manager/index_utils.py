"""Utilities for keeping the search indexes in step with session operations.

Conversation history lives in ``index/history.sqlite3`` (see utils/history_index.py), a plain
SQLite file in WAL mode, so removing a deleted session's chunks is a direct transaction: no
chroma client, no subprocess, no warm-server round trip.
"""

from __future__ import annotations

import logging
import sqlite3

from utils import history_index

logger = logging.getLogger(__name__)


def remove_session_from_index(
    session_id: str,
    collection_name: str = "history",
    timeout: float = 30.0,
) -> bool:
    """Remove all chunks for a session from the history index.

    Returns True when the session is gone from the index (including when it was never
    indexed), False on a database error. Even on False nothing is left stale for long: the
    next HistoryIndexer run drops index entries whose JSONL no longer exists.
    """
    if collection_name != "history":
        logger.warning("remove_session_from_index: unsupported collection %r", collection_name)
        return False
    db_path = history_index.get_history_db_path()
    if not db_path.exists():
        return True
    try:
        conn = history_index.connect(db_path)
        try:
            conn.execute(f"PRAGMA busy_timeout={int(timeout * 1000)}")
            removed = history_index.delete_session(conn, session_id)
        finally:
            conn.close()
    except sqlite3.Error as e:
        logger.warning("Index cleanup for session %s failed: %s", session_id, e)
        return False
    if removed:
        logger.info("Index cleanup for session %s: removed %d chunks", session_id, removed)
    return True
