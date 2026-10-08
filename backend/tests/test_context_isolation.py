"""Guards for the conftest rule that tests never write into the real context/."""

from __future__ import annotations

from unittest.mock import MagicMock

from orchestrator.config import OrchestratorConfig
from orchestrator.session import OrchestratorSession
from utils.paths import get_context_dir


def test_orchestrator_jsonl_goes_to_the_sandbox(_real_context_is_off_limits):
    """Regression: a test that let the WS route build a real orchestrator wrote
    ``context/orch-1.jsonl`` into the private context repo."""
    session = OrchestratorSession(
        config=OrchestratorConfig(), context={"pool": MagicMock(), "store": MagicMock()},
        local_id="orch-isolation-guard",
    )
    path = session._get_jsonl_path()
    assert path.parent == _real_context_is_off_limits
    assert not path.is_relative_to(get_context_dir())


def test_leak_filter_ignores_live_session_names(context_leak_filter):
    _is_test_leak = context_leak_filter

    assert _is_test_leak("orch-1.jsonl")
    assert _is_test_leak("trash/indexed-session.20261008T033224.jsonl")
    assert _is_test_leak("trash/qwen-sess-1.20261008T033224.jsonl")
    assert not _is_test_leak("01a118ca-7254-75a1-8888-c5a12eae0a9c.config.json")
    assert not _is_test_leak("chats/session-2026-10-08T03-03-2682432d.jsonl")
    assert not _is_test_leak("trash/0c1d2e3f-0000-4000-8000-000000000000.20261008T000000.jsonl")
    assert not _is_test_leak(".titles.json.tmp")
