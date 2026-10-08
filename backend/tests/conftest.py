import sys
from pathlib import Path

# Make shared/scripts/ importable (search.py, index_client.py, … live here).
SCRIPTS_DIR = Path(__file__).resolve().parents[2] / "shared" / "scripts"
sys.path.insert(0, str(SCRIPTS_DIR))

import pytest

# ``context/scripts/run.sh`` exports the real provider credentials from
# context/.env.  Harness catalog loaders (and a few other code paths) call
# live APIs when a key is present, so a test that forgot to mock them would
# quietly spend quota or depend on the network.  Tests that need a key set
# their own with monkeypatch.setenv (which runs after this fixture).
_LIVE_CREDENTIALS = (
    "DASHSCOPE_API_KEY",
    "GEMINI_API_KEY",
    "GOOGLE_API_KEY",
    "OPENAI_API_KEY",
    "ANTHROPIC_API_KEY",
    "CLAUDE_CODE_OAUTH_TOKEN",
)


@pytest.fixture(autouse=True)
def _no_live_credentials(monkeypatch):
    for key in _LIVE_CREDENTIALS:
        monkeypatch.delenv(key, raising=False)
    from manager.harness_catalog import clear_catalog_cache
    clear_catalog_cache()
    yield
    clear_catalog_cache()
