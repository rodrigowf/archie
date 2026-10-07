"""Tests for utils/memory_index.py — the SQLite memory index."""
from __future__ import annotations

import hashlib
import re
from pathlib import Path

import numpy as np
import pytest

from utils import history_index as hi
from utils import memory_index as mi


def _vec(text: str) -> list[float]:
    v = np.zeros(hi.EMBED_DIM, dtype=np.float32)
    for w in re.findall(r"\w+", text.lower()):
        v[int(hashlib.md5(w.encode()).hexdigest(), 16) % hi.EMBED_DIM] += 1.0
    return v.tolist()


def encode(texts):
    return [_vec(t) for t in texts]


NOTE = """---
name: lamps_project
description: Tuya lamps and scenes
category: home
tags: [tuya, lamps]
source: session 0a1b2c3d-1111-2222-3333-444455556666 ("Lamps!", 2026-07-20)
modified: 2026-07-21
---

# Lamps Project

Intro line about the living room lamps and why we moved off Google Home.

## Scenes

The dusk scene turns the hallway lamp warm at sunset.
It runs from the Tuya cloud, not the Jetson.

## Pairing

Pairing needs the Western America data center; Eastern failed the QR.
"""


@pytest.fixture
def root(tmp_path: Path) -> Path:
    r = tmp_path / "memory"
    (r / "projects").mkdir(parents=True)
    (r / "projects" / "lamps.md").write_text(NOTE)
    (r / "projects" / "INDEX.md").write_text("# Projects\n\n- lamps.md — Tuya lamps and scenes\n")
    return r


@pytest.fixture
def conn(tmp_path: Path):
    c = mi.connect(tmp_path / "memory.sqlite3")
    yield c
    c.close()


class TestParse:
    def test_frontmatter_title_sources_and_sections(self, root):
        doc = mi.parse_memory_file(root / "projects" / "lamps.md", root)
        assert doc.title == "Lamps Project"
        assert doc.sources == ["0a1b2c3d-1111-2222-3333-444455556666"]
        assert doc.tags == ["tuya", "lamps"] and doc.category == "home"
        headings = [c.heading for c in doc.chunks]
        assert headings == ["", "Scenes", "Pairing"]
        scenes = doc.chunks[1]
        lines = (root / "projects" / "lamps.md").read_text().splitlines()
        assert lines[scenes.start_line - 1] == "## Scenes"
        assert "hallway lamp" in "\n".join(lines[scenes.start_line - 1 : scenes.end_line])
        assert scenes.context == "Lamps Project › Scenes"
        assert doc.chunks[0].context.endswith("— Tuya lamps and scenes")

    def test_frontmatter_block_lists(self):
        fm, n = mi.parse_frontmatter("---\nreferences:\n  - a.md\n  - b.md\ntitle: x\n---\nbody\n")
        assert fm == {"references": ["a.md", "b.md"], "title": "x"} and n == 6


class TestIndex:
    def test_incremental_and_prune(self, root, conn):
        st = mi.index_all(conn, encode, root=root, log=lambda m: None)
        assert st.indexed == 2 and st.embedded_chunks > 0
        assert mi.index_all(conn, encode, root=root, log=lambda m: None).unchanged == 2
        (root / "projects" / "INDEX.md").unlink()
        st = mi.index_all(conn, encode, root=root, log=lambda m: None)
        assert st.removed == 1
        assert [r[0] for r in conn.execute("SELECT path FROM files")] == ["projects/lamps.md"]

    def test_search_returns_sections_and_sources(self, root, conn, tmp_path):
        mi.index_all(conn, encode, root=root, log=lambda m: None)
        s = mi.MemorySearcher(tmp_path / "memory.sqlite3", root)
        q = "which data center did the lamp pairing need"
        r = s.search(q, _vec(q), session_titles={"0a1b2c3d-1111-2222-3333-444455556666": "Lamps!"})
        top = r["files"][0]
        assert top["file"] == "context/memory/projects/lamps.md"
        assert top["sections"][0]["heading"] == "Pairing"
        assert top["from_conversations"] == [{"session_id": "0a1b2c3d-1111-2222-3333-444455556666", "title": "Lamps!"}]

    def test_navigation_files_rank_below_notes(self, root, conn, tmp_path):
        mi.index_all(conn, encode, root=root, log=lambda m: None)
        s = mi.MemorySearcher(tmp_path / "memory.sqlite3", root)
        r = s.search("tuya lamps scenes", _vec("tuya lamps scenes"))
        assert [f["file"] for f in r["files"]][0] == "context/memory/projects/lamps.md"
