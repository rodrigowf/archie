import sys
from pathlib import Path

# Make shared/scripts/ importable (search.py, index_client.py, … live here).
SCRIPTS_DIR = Path(__file__).resolve().parents[2] / "shared" / "scripts"
sys.path.insert(0, str(SCRIPTS_DIR))
