"""Paths and dataset loading shared by the eval scripts."""
from __future__ import annotations

import json
import sys
from pathlib import Path

PROJECT_DIR = Path(__file__).resolve().parents[3]  # shared/scripts/history_eval → repo root
sys.path.insert(0, str(PROJECT_DIR / "backend"))

from utils.paths import get_context_dir  # noqa: E402

EVAL_DIR = get_context_dir() / "evals" / "history_search"
SESSIONS_FILE = EVAL_DIR / "sessions.json"
QUESTIONS_FILE = EVAL_DIR / "questions.jsonl"
RUNS_DIR = EVAL_DIR / "runs"


def load_sessions() -> dict:
    return json.loads(SESSIONS_FILE.read_text(encoding="utf-8"))


def load_questions(split: str | None = None) -> list[dict]:
    out = []
    for line in QUESTIONS_FILE.read_text(encoding="utf-8").splitlines():
        if line.strip():
            q = json.loads(line)
            if split is None or q["split"] == split:
                out.append(q)
    return out


def accepted(q: dict, copies: dict[str, list[str]]) -> set[str]:
    """Session ids that count as a correct answer: the gold one and its copies."""
    gold = q.get("session_id")
    if not gold:
        return set()
    return {gold, *copies.get(gold, [])}
