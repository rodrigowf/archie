"""Memory index: hybrid (keyword + semantic) search over the memory wiki (context/memory/**/*.md).

Same storage design as history_index.py — one SQLite file (``index/memory.sqlite3``) with an
FTS5 keyword index and float32 embeddings searched in numpy — replacing the chroma ``memory``
collection (10-line chunks, semantic only, never pruned, corrupted by WAL replays).

Memory files are structured, so chunks follow it:
- the YAML frontmatter is parsed, not chunked: title (``name``), ``description``, ``category``,
  ``tags``, ``modified``, and ``source`` — the conversation(s) the note was written from;
- the body is split by markdown headings; each section into ~110-word windows that keep their
  line range, so a hit points at ``file:lines`` for ``read_file``;
- every chunk is indexed with a context line ``<title> › <section path>`` in front (the file's
  description goes with the first window), so a fragment like "yes, use the drain" still says
  which note and section it belongs to.

``source`` links are what let a conversation search say "already saved to memory in X" and a
memory hit say "written from conversation Y".
"""
from __future__ import annotations

import hashlib
import json
import re
import sqlite3
import time
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Callable, Iterable, Sequence

from utils import history_index as hi
from utils.paths import get_index_dir, get_memory_dir

INDEX_VERSION = 1
NAVIGATION_FILES = {"MEMORY.md", "INDEX.md", "ARCHIVE.md"}  # lists of links: findable, ranked lower
NAVIGATION_DEMOTION = 0.5
FILE_W_NEXT = 0.0   # file score = best section + W_NEXT·(next two) + W_TAIL·(next seven);
                    # best-section-only won on the dev eval (hit@1 .54→.62), as for history
FILE_W_TAIL = 0.0

_UUID_RE = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", re.IGNORECASE)
_HEADING_RE = re.compile(r"^(#{1,6})\s+(.*\S)\s*$")
_FRONTMATTER_RE = re.compile(r"^---\n(.*?)\n---\n", re.DOTALL)

Encoder = Callable[[list[str]], list[list[float]]]


def get_memory_db_path() -> Path:
    return get_index_dir() / "memory.sqlite3"


# ── parsing ───────────────────────────────────────────────────────────────────


def parse_frontmatter(text: str) -> tuple[dict, int]:
    """(fields, number of lines the frontmatter block occupies). Simple YAML subset: scalars,
    inline lists `[a, b]`, and block lists of `- item`."""
    m = _FRONTMATTER_RE.match(text)
    if not m:
        return {}, 0
    out: dict = {}
    key = None
    for raw in m.group(1).splitlines():
        line = raw.rstrip()
        if not line.strip():
            continue
        if line.lstrip().startswith("- ") and key:
            out.setdefault(key, [])
            if isinstance(out[key], list):
                out[key].append(line.lstrip()[2:].strip().strip('"\''))
            continue
        if ":" in line and not line.startswith(" "):
            k, _, v = line.partition(":")
            key, v = k.strip(), v.strip()
            if not v:
                out[key] = []
            elif v.startswith("[") and v.endswith("]"):
                out[key] = [x.strip().strip('"\'') for x in v[1:-1].split(",") if x.strip()]
            else:
                out[key] = v.strip('"\'')
    return out, m.group(0).count("\n")


@dataclass
class MemoryChunk:
    ord: int
    start_line: int  # 1-based, inclusive
    end_line: int
    heading: str
    text: str
    context: str

    @property
    def body(self) -> str:
        return f"{self.context}\n{self.text}"

    @property
    def text_hash(self) -> str:
        return hashlib.sha1(self.body.encode("utf-8")).hexdigest()


@dataclass
class MemoryDoc:
    rel: str
    path: Path
    title: str
    description: str | None
    category: str | None
    tags: list[str]
    modified: str | None
    sources: list[str]
    chunks: list[MemoryChunk] = field(default_factory=list)


def parse_memory_file(path: Path, root: Path) -> MemoryDoc:
    text = path.read_text(encoding="utf-8", errors="replace")
    fm, fm_lines = parse_frontmatter(text)
    lines = text.splitlines()
    body_lines = lines[fm_lines:]
    h1 = next((m.group(2) for ln in body_lines if (m := _HEADING_RE.match(ln)) and len(m.group(1)) == 1), None)
    name = fm.get("name") if isinstance(fm.get("name"), str) else None
    # Prefer the document's H1; `name` is often a slug (television_integration_project).
    title = h1 or (name.replace("_", " ").replace("-", " ") if name else None) or path.stem.replace("_", " ")
    sources_raw = fm.get("source") or fm.get("sources") or ""
    sources_text = " ".join(sources_raw) if isinstance(sources_raw, list) else str(sources_raw)
    tags = fm.get("tags") if isinstance(fm.get("tags"), list) else ([fm["tags"]] if isinstance(fm.get("tags"), str) else [])
    doc = MemoryDoc(
        rel=str(path.relative_to(root)),
        path=path,
        title=title,
        description=fm.get("description") if isinstance(fm.get("description"), str) else None,
        category=fm.get("category") if isinstance(fm.get("category"), str) else None,
        tags=[t for t in tags if t],
        modified=fm.get("modified") if isinstance(fm.get("modified"), str) else None,
        sources=sorted({u.lower() for u in _UUID_RE.findall(sources_text)}),
    )

    # Sections: (heading path, [(line_no, text)]) — line numbers are 1-based in the whole file.
    sections: list[tuple[str, list[tuple[int, str]]]] = []
    path_stack: list[tuple[int, str]] = []
    current: list[tuple[int, str]] = []
    heading = ""
    for i, ln in enumerate(body_lines, start=fm_lines + 1):
        m = _HEADING_RE.match(ln)
        if m:
            if current:
                sections.append((heading, current))
            level = len(m.group(1))
            path_stack = [(lv, h) for lv, h in path_stack if lv < level] + [(level, m.group(2))]
            heading = " › ".join(h for _, h in path_stack if h != title)
            current = [(i, ln)]
            continue
        current.append((i, ln))
    if current:
        sections.append((heading, current))

    ordinal = 0
    for heading, sec in sections:
        words: list[tuple[int, str]] = [(ln_no, w) for ln_no, ln in sec for w in ln.split()]
        if len(words) < hi.MIN_TURN_WORDS:
            continue
        step = hi.CHUNK_WORDS - hi.CHUNK_OVERLAP
        i = 0
        while i < len(words):
            window = words[i : i + hi.CHUNK_WORDS]
            start, end = window[0][0], window[-1][0]
            win_text = "\n".join(ln for ln_no, ln in sec if start <= ln_no <= end).strip()
            context = f"{title} › {heading}" if heading else title
            if ordinal == 0 and doc.description:
                context = f"{context} — {doc.description}"
            doc.chunks.append(MemoryChunk(ordinal, start, end, heading, win_text, context))
            ordinal += 1
            if i + hi.CHUNK_WORDS >= len(words):
                break
            i += step
    return doc


# ── store ─────────────────────────────────────────────────────────────────────

_SCHEMA = """
CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS files (
    path TEXT PRIMARY KEY,
    title TEXT NOT NULL,
    description TEXT,
    category TEXT,
    tags TEXT,
    modified TEXT,
    sources TEXT,
    source_size INTEGER NOT NULL,
    source_mtime_ns INTEGER NOT NULL,
    version INTEGER NOT NULL,
    n_chunks INTEGER NOT NULL,
    navigation INTEGER NOT NULL DEFAULT 0,
    indexed_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS chunks (
    id INTEGER PRIMARY KEY,
    path TEXT NOT NULL,
    ord INTEGER NOT NULL,
    start_line INTEGER NOT NULL,
    end_line INTEGER NOT NULL,
    heading TEXT,
    ts TEXT,
    body TEXT NOT NULL,
    text_hash TEXT NOT NULL,
    embedding BLOB NOT NULL
);
CREATE INDEX IF NOT EXISTS chunks_path ON chunks(path);
CREATE VIRTUAL TABLE IF NOT EXISTS chunks_fts USING fts5(
    body, content='chunks', content_rowid='id',
    tokenize='porter unicode61 remove_diacritics 2'
);
CREATE TRIGGER IF NOT EXISTS chunks_ai AFTER INSERT ON chunks BEGIN
    INSERT INTO chunks_fts(rowid, body) VALUES (new.id, new.body);
END;
CREATE TRIGGER IF NOT EXISTS chunks_ad AFTER DELETE ON chunks BEGIN
    INSERT INTO chunks_fts(chunks_fts, rowid, body) VALUES ('delete', old.id, old.body);
END;
"""


def connect(db_path: Path | None = None, *, readonly: bool = False, model: str | None = None) -> sqlite3.Connection:
    db_path = Path(db_path or get_memory_db_path())
    if readonly:
        conn = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True, timeout=30)
    else:
        db_path.parent.mkdir(parents=True, exist_ok=True)
        conn = sqlite3.connect(str(db_path), timeout=30)
        conn.execute("PRAGMA journal_mode=WAL")
        conn.execute("PRAGMA synchronous=NORMAL")
        conn.executescript(_SCHEMA)
        conn.execute(
            "INSERT OR IGNORE INTO meta(key, value) VALUES ('model', ?), ('generation', '0')",
            (model or hi.MODEL_NAME,),
        )
        conn.commit()
    conn.execute("PRAGMA busy_timeout=30000")
    return conn


def index_file(conn: sqlite3.Connection, path: Path, root: Path, encode: Encoder) -> int:
    """(Re)index one memory file atomically, reusing embeddings of unchanged chunks."""
    st = path.stat()
    doc = parse_memory_file(path, root)
    model = hi.index_model(conn)
    prefix = hi.passage_prefix(model)
    existing = dict(conn.execute("SELECT text_hash, embedding FROM chunks WHERE path=?", (doc.rel,)))
    todo: dict[str, str] = {}
    for c in doc.chunks:
        if c.text_hash not in existing:
            todo.setdefault(c.text_hash, c.body)
    hashes = list(todo)
    for i in range(0, len(hashes), hi.ENCODE_BATCH):
        batch = hashes[i : i + hi.ENCODE_BATCH]
        for h, v in zip(batch, encode([prefix + todo[h] for h in batch])):
            existing[h] = hi._to_blob(v)
    ts = doc.modified or datetime.fromtimestamp(st.st_mtime, tz=timezone.utc).date().isoformat()
    with conn:
        conn.execute("DELETE FROM chunks WHERE path=?", (doc.rel,))
        conn.executemany(
            "INSERT INTO chunks(path, ord, start_line, end_line, heading, ts, body, text_hash, embedding) "
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            [(doc.rel, c.ord, c.start_line, c.end_line, c.heading, ts, c.body, c.text_hash, existing[c.text_hash])
             for c in doc.chunks],
        )
        conn.execute(
            "INSERT OR REPLACE INTO files(path, title, description, category, tags, modified, sources, "
            "source_size, source_mtime_ns, version, n_chunks, navigation, indexed_at) "
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            (doc.rel, doc.title, doc.description, doc.category, json.dumps(doc.tags), doc.modified,
             json.dumps(doc.sources), st.st_size, st.st_mtime_ns, INDEX_VERSION, len(doc.chunks),
             int(path.name in NAVIGATION_FILES or path.name.startswith("ORCHESTRATOR_MEMORY")),
             datetime.now(timezone.utc).isoformat()),
        )
        hi._bump_generation(conn)
    return len(hashes)


def memory_files(root: Path | None = None) -> list[Path]:
    root = Path(root or get_memory_dir())
    return sorted(p for p in root.rglob("*.md") if ".git" not in p.parts)


def index_all(
    conn: sqlite3.Connection,
    encode: Encoder,
    *,
    root: Path | None = None,
    log: Callable[[str], None] = print,
) -> hi.IndexStats:
    """Bring the index in line with the memory folder; prunes files that were deleted or moved."""
    root = Path(root or get_memory_dir())
    stats = hi.IndexStats()
    known = {p: (sz, mt, v) for p, sz, mt, v in conn.execute("SELECT path, source_size, source_mtime_ns, version FROM files")}
    seen: set[str] = set()
    for path in memory_files(root):
        rel = str(path.relative_to(root))
        seen.add(rel)
        try:
            st = path.stat()
        except OSError:
            continue
        if known.get(rel) == (st.st_size, st.st_mtime_ns, INDEX_VERSION):
            stats.unchanged += 1
            continue
        try:
            stats.embedded_chunks += index_file(conn, path, root, encode)
            stats.indexed += 1
        except Exception as e:  # noqa: BLE001 — one bad file never blocks the rest
            stats.failed.append((rel, f"{type(e).__name__}: {e}"))
            log(f"  FAILED {rel}: {type(e).__name__}: {e}")
    for rel in set(known) - seen:
        with conn:
            conn.execute("DELETE FROM chunks WHERE path=?", (rel,))
            conn.execute("DELETE FROM files WHERE path=?", (rel,))
            hi._bump_generation(conn)
        stats.removed += 1
    return stats


def files_for_sessions(conn: sqlite3.Connection, session_ids: Iterable[str]) -> dict[str, list[dict]]:
    """session id → memory files whose frontmatter `source` names it."""
    wanted = {s.lower() for s in session_ids if s}
    out: dict[str, list[dict]] = {}
    if not wanted:
        return out
    for path, title, sources in conn.execute("SELECT path, title, sources FROM files WHERE sources != '[]'"):
        for sid in json.loads(sources or "[]"):
            if sid in wanted:
                out.setdefault(sid, []).append({"file": path, "title": title})
    return out


# ── search ───────────────────────────────────────────────────────────────────


class MemorySearcher:
    """Read side; reloads the embedding matrix when the indexer bumps `generation`."""

    def __init__(self, db_path: Path | None = None, root: Path | None = None):
        self._db_path = Path(db_path or get_memory_db_path())
        self._root = Path(root or get_memory_dir())
        self._generation = None
        self._matrix = None

    def conn(self) -> sqlite3.Connection:
        if not self._db_path.exists():
            raise FileNotFoundError(f"memory index not built yet: {self._db_path}")
        return connect(self._db_path, readonly=True)

    def _ensure_loaded(self, conn) -> None:
        import numpy as np

        gen = hi.get_generation(conn)
        if gen == self._generation and self._matrix is not None:
            return
        rows = conn.execute("SELECT id, path, ts, embedding FROM chunks ORDER BY id").fetchall()
        self._ids = np.array([r[0] for r in rows], dtype=np.int64)
        self._paths = np.array([r[1] for r in rows], dtype=object)
        self._ts = np.array([r[2] or "" for r in rows], dtype=object)
        self._matrix = (np.frombuffer(b"".join(r[3] for r in rows), dtype=np.float32).reshape(len(rows), -1)
                        if rows else np.zeros((0, hi.EMBED_DIM), dtype=np.float32))
        self._model = hi.index_model(conn)
        self._generation = gen

    def search(
        self,
        query: str,
        query_vec: Sequence[float] | None,
        *,
        extra_queries: Sequence[tuple[str, Sequence[float] | None]] = (),
        max_files: int = 5,
        sections_per_file: int = 2,
        folder: str | None = None,
        session_titles: dict[str, str] | None = None,
    ) -> dict:
        folder = (folder or "").strip("/")
        conn = self.conn()
        try:
            self._ensure_loaded(conn)

            def allowed(path: str, ts: str) -> bool:
                return not folder or path.startswith(folder + "/")

            scores: dict[int, float] = {}
            vec_sim: dict[int, float] = {}
            share: dict[int, float] = {}
            kw: set[int] = set()
            single = False
            for q, v in [(query, query_vec), *extra_queries]:
                if not (q or "").strip():
                    continue
                r = hi.hybrid_rank(conn, q, v, allowed, ids=self._ids, groups=self._paths, ts=self._ts,
                                   matrix=self._matrix, group_col="path")
                for cid, sc in r["scores"].items():
                    scores[cid] = scores.get(cid, 0.0) + sc
                for cid, x in r["vec_sim"].items():
                    vec_sim[cid] = max(x, vec_sim.get(cid, 0.0))
                for cid, x in r["share"].items():
                    share[cid] = max(x, share.get(cid, 0.0))
                kw |= r["kw"]
                single = single or r["n_terms"] == 1
            if not scores:
                return {"files": [], "total_files": 0}
            top = sorted(scores, key=scores.get, reverse=True)[: hi.CANDIDATES]
            info = {r[0]: r[1:] for r in conn.execute(
                f"SELECT id, path, start_line, end_line, heading, body FROM chunks WHERE id IN ({','.join('?' * len(top))})", top)}
            by_file: dict[str, list[int]] = {}
            for cid in top:
                if cid in info:
                    by_file.setdefault(info[cid][0], []).append(cid)
            conn.row_factory = sqlite3.Row
            meta = {r["path"]: dict(r) for r in conn.execute(
                f"SELECT * FROM files WHERE path IN ({','.join('?' * len(by_file))})", list(by_file))}
            conn.row_factory = None
        finally:
            conn.close()

        def file_score(path: str, cids: list[int]) -> float:
            best = [scores[c] for c in cids]
            s = best[0] + FILE_W_NEXT * sum(best[1:3]) + FILE_W_TAIL * sum(best[3:10])
            return s * (NAVIGATION_DEMOTION if meta.get(path, {}).get("navigation") else 1.0)

        def strong(cid: int) -> bool:
            sim, sh = vec_sim.get(cid, 0.0), (share.get(cid, 0.0) if cid in kw else 0.0)
            if single and sh:
                return True
            return hi.is_strong(sim, sh, self._model)

        ranked = sorted(by_file.items(), key=lambda kv: file_score(*kv), reverse=True)
        out = []
        for path, cids in ranked[:max_files]:
            m = meta.get(path, {})
            sections = []
            for cid in cids[:sections_per_file]:
                _, a, b, heading, body = info[cid]
                text = body.split("\n", 1)[1] if "\n" in body else body
                sections.append({
                    "lines": f"{a}-{b}",
                    "heading": heading or None,
                    "text": text if len(text) <= hi.SNIPPET_CHARS else text[: hi.SNIPPET_CHARS] + " …",
                })
            sources = json.loads(m.get("sources") or "[]")
            entry = {
                "file": f"context/memory/{path}",
                "title": m.get("title"),
                "description": m.get("description"),
                "category": m.get("category"),
                "modified": m.get("modified"),
                "relevance": "strong" if any(strong(c) for c in cids[:5]) else "weak",
                "sections": sections,
            }
            if sources:
                entry["from_conversations"] = [
                    {"session_id": s, "title": (session_titles or {}).get(s)} for s in sources
                ]
            out.append(entry)
        return {"files": out, "total_files": len(ranked)}


# ── navigation ───────────────────────────────────────────────────────────────


def browse(folder: str = "", *, root: Path | None = None, db_path: Path | None = None) -> dict:
    """One folder of the memory tree: its subfolders (with file counts) and its notes (title,
    one-line description, modified), plus the folder's INDEX.md if it has one."""
    root = Path(root or get_memory_dir())
    folder = folder.strip().strip("/").removeprefix("context/memory").strip("/")
    base = (root / folder).resolve()
    if not base.is_dir() or not base.is_relative_to(root.resolve()):
        return {"error": f"No memory folder {folder!r}."}
    meta: dict[str, dict] = {}
    db = Path(db_path or get_memory_db_path())
    if db.exists():
        conn = connect(db, readonly=True)
        try:
            conn.row_factory = sqlite3.Row
            meta = {r["path"]: dict(r) for r in conn.execute("SELECT path, title, description, modified FROM files")}
        finally:
            conn.close()
    folders, notes = [], []
    for p in sorted(base.iterdir()):
        if p.name.startswith(".") or p.name == "__pycache__":
            continue
        if p.is_dir():
            folders.append({"folder": str(p.relative_to(root)), "notes": sum(1 for _ in p.rglob("*.md"))})
        elif p.suffix == ".md":
            rel = str(p.relative_to(root))
            m = meta.get(rel, {})
            notes.append({"file": f"context/memory/{rel}", "title": m.get("title") or p.stem,
                          "description": m.get("description"), "modified": m.get("modified")})
    return {"folder": folder or ".", "folders": folders, "notes": notes,
            "index": f"context/memory/{folder + '/' if folder else ''}INDEX.md" if (base / "INDEX.md").exists() else None}


def grep(pattern: str, *, folder: str = "", regex: bool = False, max_hits: int = 20, root: Path | None = None) -> dict:
    """Exact words (accent/case-insensitive) or a regex across memory notes → file:line hits."""
    from utils.history_nav import fold

    root = Path(root or get_memory_dir())
    folder = folder.strip().strip("/").removeprefix("context/memory").strip("/")
    base = (root / folder) if folder else root
    if not pattern.strip():
        return {"error": "Empty pattern."}
    try:
        rx = re.compile(fold(pattern) if regex else re.escape(fold(pattern)))
    except re.error as e:
        return {"error": f"Invalid regex: {e}"}
    hits, total, files = [], 0, set()
    for path in memory_files(base) if base.is_dir() else []:
        for n, line in enumerate(path.read_text(encoding="utf-8", errors="replace").splitlines(), 1):
            if rx.search(fold(line)):
                total += 1
                files.add(path)
                if len(hits) < max(1, min(max_hits, 100)):
                    hits.append({"file": f"context/memory/{path.relative_to(root)}", "line": n,
                                 "text": line.strip()[:240]})
    return {"pattern": pattern, "total_matches": total, "files_matched": len(files), "hits": hits}
