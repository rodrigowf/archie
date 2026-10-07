"""The search requests behind the orchestrator's history and memory tools, in one place.

Used by the warm search-server (shared/scripts/search-server.py), the one-shot fallback
(shared/scripts/search.py) and the evaluation harness, so all three answer a request exactly
the same way. Each index records the embedding model it was built with (meta.model); queries
are encoded with that model (and its query prefix). ``load_model`` supplies models; the server
passes its already-loaded one so nothing is loaded twice.

Requests (JSON-like dicts):
  history_search  {query, queries[], max_sessions, exclude_sessions[], after, before, session_id,
                   kind, kind_mode, window[after, before], window_mode, include_memory,
                   rerank, request_text}
  memory_search   {query, queries[], max_files, folder}
"""
from __future__ import annotations

import threading
from typing import Callable

from utils import history_index, memory_index, rerank

MEMORY_IN_HISTORY = 3  # strong memory matches attached to a history search
MIN_SEQ_TOKENS = 256


class SearchService:
    def __init__(self, load_model: Callable[[str], object]):
        self._load_model = load_model
        self._encoders: dict[str, Callable[[str], list[float]]] = {}
        self._history: history_index.HistorySearcher | None = None
        self._memory: memory_index.MemorySearcher | None = None
        self._lock = threading.Lock()

    def encoder(self, model_name: str) -> Callable[[str], list[float]]:
        if model_name not in self._encoders:
            model = self._load_model(model_name)
            prefix = history_index.query_prefix(model_name)
            self._encoders[model_name] = lambda text, m=model: m.encode([prefix + text])[0].tolist()
        return self._encoders[model_name]

    def _encoder_for(self, conn) -> Callable[[str], list[float]]:
        return self.encoder(history_index.index_model(conn))

    # ── history ──────────────────────────────────────────────────────────────

    def history_search(self, request: dict) -> dict:
        query = (request.get("query") or "").strip()
        if not query:
            return {"sessions": [], "error": "Missing 'query' field"}
        with self._lock:
            if self._history is None:
                self._history = history_index.HistorySearcher()
            try:
                conn = self._history._conn()
                try:
                    encode = self._encoder_for(conn)
                finally:
                    conn.close()
                window = request.get("window")
                max_sessions = int(request.get("max_sessions") or 5)
                rerank_on = bool(request.get("rerank"))
                result = self._history.search(
                    query,
                    encode(query),
                    extra_queries=[(q, encode(q)) for q in (request.get("queries") or []) if q and q.strip()],
                    max_sessions=max(max_sessions, rerank.RERANK_K) if rerank_on else max_sessions,
                    hits_per_session=int(request.get("hits_per_session") or 3),
                    exclude_sessions=request.get("exclude_sessions") or (),
                    session_id=request.get("session_id"),
                    after=request.get("after"),
                    before=request.get("before"),
                    kind=request.get("kind"),
                    kind_mode=request.get("kind_mode") or "filter",
                    window=tuple(window) if window else None,
                    window_mode=request.get("window_mode") or "filter",
                )
            except FileNotFoundError as e:
                return {"sessions": [], "error": str(e)}
            if rerank_on and result["sessions"]:
                request_text = " / ".join(x for x in [request.get("request_text") or query, *(request.get("queries") or [])] if x)
                reordered = rerank.rerank_sessions(request_text, result["sessions"][: rerank.RERANK_K])
                if reordered is not None:
                    result["sessions"] = reordered
                    result["reranked"] = True
            result["sessions"] = result["sessions"][:max_sessions]
            link_memory(result)
            if request.get("include_memory"):
                mem = self._memory_search_unlocked({
                    "query": query, "queries": request.get("queries"), "max_files": MEMORY_IN_HISTORY,
                })
                result["memory"] = [f for f in mem.get("files", []) if f.get("relevance") == "strong"]
        result["error"] = None
        return result

    # ── memory ───────────────────────────────────────────────────────────────

    def memory_search(self, request: dict) -> dict:
        if not (request.get("query") or "").strip():
            return {"files": [], "error": "Missing 'query' field"}
        with self._lock:
            result = self._memory_search_unlocked(request)
        result.setdefault("error", None)
        return result

    def _memory_search_unlocked(self, request: dict) -> dict:
        query = (request.get("query") or "").strip()
        if self._memory is None:
            self._memory = memory_index.MemorySearcher()
        try:
            conn = self._memory.conn()
            try:
                encode = self._encoder_for(conn)
            finally:
                conn.close()
            return self._memory.search(
                query,
                encode(query),
                extra_queries=[(q, encode(q)) for q in (request.get("queries") or []) if q and q.strip()],
                max_files=int(request.get("max_files") or 5),
                folder=request.get("folder"),
                session_titles=history_index._load_titles(),
            )
        except FileNotFoundError as e:
            return {"files": [], "error": str(e)}


def link_memory(result: dict) -> None:
    """Mark conversations already saved to memory (a memory file's frontmatter `source`)."""
    db = memory_index.get_memory_db_path()
    ids = [s["session_id"] for s in result.get("sessions", [])]
    if not ids or not db.exists():
        return
    conn = memory_index.connect(db, readonly=True)
    try:
        links = memory_index.files_for_sessions(conn, ids)
    finally:
        conn.close()
    for s in result["sessions"]:
        files = links.get(s["session_id"].lower())
        if files:
            s["saved_in_memory"] = [{"file": f"context/memory/{f['file']}", "title": f["title"]} for f in files]


def sentence_transformer_loader(preloaded: dict[str, object] | None = None) -> Callable[[str], object]:
    """Model loader for SearchService: reuse preloaded models, load others on demand."""
    cache = dict(preloaded or {})

    def load(name: str):
        if name not in cache:
            from sentence_transformers import SentenceTransformer
            model = SentenceTransformer(name)
            # Chunks are ~110 words (~150–200 tokens); some models default to 128 and would
            # silently drop the end of every chunk.
            if model.max_seq_length < MIN_SEQ_TOKENS:
                model.max_seq_length = MIN_SEQ_TOKENS
            cache[name] = model
        return cache[name]

    return load
