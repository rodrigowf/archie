"""Retrieval eval: run every question's utterance through HistorySearcher, score the rank of the
correct session (or its copies), and break the numbers down by type, language and kind.

The utterance is used verbatim as the query — the worst case of an agent that just forwards
what the user said. Agent-written queries are measured by the agent eval (agent.py).

Metrics: hit@1/@3/@5, MRR@10 over positives; for those found, the share labelled "strong";
for negatives, the share whose top result is "strong" (a false alarm) and the share with no
result at all.

Usage:
  context/scripts/run.sh shared/scripts/history_eval/retrieval.py --split dev --name baseline
  ... --compare baseline       print the delta against a saved run
  ... --db index/other.sqlite3 evaluate an alternative index file
"""
from __future__ import annotations

import argparse
import collections
import json
import time
from pathlib import Path

from datetime import date

from common import RUNS_DIR, accepted, load_questions, load_sessions
from utils import history_index as hi
from utils.timewords import find_time_expression

_MODELS: dict = {}


def encoder(name: str):
    """Query encoder for an index built with model `name` (prefix included)."""
    if name not in _MODELS:
        from sentence_transformers import SentenceTransformer
        m = SentenceTransformer(name)
        if m.max_seq_length < 256:
            m.max_seq_length = 256
        prefix = hi.query_prefix(name)
        _MODELS[name] = lambda text: m.encode([prefix + text])[0].tolist()
    return _MODELS[name]


TODAY = date(2026, 10, 6)  # the questions' time references are relative to this day


def evaluate(questions, searcher, copies, *, time_mode="none", model_name=hi.MODEL_NAME) -> list[dict]:
    encode = encoder(model_name)
    rows = []
    for q in questions:
        query = q["utterance"]
        kwargs = {}
        if time_mode != "none":
            win, cleaned = find_time_expression(query, TODAY)
            if win:
                query = cleaned or query
                kwargs = {"window": win.iso(), "window_mode": time_mode}
        t0 = time.perf_counter()
        vec = encode(query)
        res = searcher.search(query, vec, max_sessions=10, **kwargs)
        ms = (time.perf_counter() - t0) * 1000
        ids = [s["session_id"] for s in res["sessions"]]
        rel = [s.get("relevance") for s in res["sessions"]]
        ok = accepted(q, copies)
        rank = next((i + 1 for i, sid in enumerate(ids) if sid in ok), None)
        # A collapsed copy listed under another session also counts.
        if rank is None:
            for i, s in enumerate(res["sessions"]):
                if ok & set(s.get("copies", [])):
                    rank = i + 1
                    break
        rows.append({
            "id": q["id"], "type": q["type"], "lang": q["lang"], "kind": q.get("kind"),
            "rank": rank, "found_relevance": rel[rank - 1] if rank else None,
            "top": ids[:5], "top_relevance": rel[0] if rel else None, "n_results": len(ids), "ms": round(ms, 1),
        })
    return rows


def evaluate_service(questions, copies, *, rerank_on: bool) -> list[dict]:
    """Through the production SearchService, with the request the search_history tool builds
    from the user's words (time phrase → preferred window, kind/window as preferences)."""
    from utils import search_service

    service = search_service.SearchService(search_service.sentence_transformer_loader())
    rows = []
    for q in questions:
        query, window = q["utterance"], None
        w, cleaned = find_time_expression(query, TODAY)
        if w and len(cleaned.split()) >= 2:
            query, window = cleaned, list(w.iso())
        t0 = time.perf_counter()
        res = service.history_search({
            "query": query, "max_sessions": 10, "window": window, "window_mode": "prefer",
            "kind_mode": "prefer", "rerank": rerank_on, "request_text": q["utterance"],
        })
        ms = (time.perf_counter() - t0) * 1000
        ids = [x["session_id"] for x in res["sessions"]]
        rel = [x.get("relevance") for x in res["sessions"]]
        ok = accepted(q, copies)
        rank = next((i + 1 for i, x in enumerate(res["sessions"]) if x["session_id"] in ok or ok & set(x.get("copies", []))), None)
        rows.append({
            "id": q["id"], "type": q["type"], "lang": q["lang"], "kind": q.get("kind"),
            "rank": rank, "found_relevance": rel[rank - 1] if rank else None,
            "top": ids[:5], "top_relevance": rel[0] if rel else None, "n_results": len(ids), "ms": round(ms, 1),
        })
    return rows


def summarize(rows) -> dict:
    def block(rs):
        pos = [r for r in rs if r["type"] != "negative"]
        neg = [r for r in rs if r["type"] == "negative"]
        out = {"n": len(pos)}
        if pos:
            out.update(
                hit1=sum(1 for r in pos if r["rank"] == 1) / len(pos),
                hit3=sum(1 for r in pos if r["rank"] and r["rank"] <= 3) / len(pos),
                hit5=sum(1 for r in pos if r["rank"] and r["rank"] <= 5) / len(pos),
                mrr10=sum(1 / r["rank"] for r in pos if r["rank"]) / len(pos),
                strong_when_found=(lambda f: sum(1 for r in f if r["found_relevance"] == "strong") / len(f) if f else 0)(
                    [r for r in pos if r["rank"] and r["rank"] <= 5]),
            )
        if neg:
            out.update(neg_n=len(neg), neg_false_strong=sum(1 for r in neg if r["top_relevance"] == "strong") / len(neg),
                       neg_empty=sum(1 for r in neg if r["n_results"] == 0) / len(neg))
        return out

    s = {"all": block(rows)}
    for key in ("type", "lang", "kind"):
        for val in sorted({r[key] for r in rows if r[key]}):
            s[f"{key}={val}"] = block([r for r in rows if r[key] == val])
    lat = sorted(r["ms"] for r in rows)
    s["latency_ms_p50"] = lat[len(lat) // 2] if lat else None
    return s


def fmt(summary, base=None) -> str:
    lines = []
    for name, m in summary.items():
        if not isinstance(m, dict):
            lines.append(f"{name}: {m}")
            continue
        parts = []
        for k in ("hit1", "hit5", "mrr10", "strong_when_found", "neg_false_strong", "neg_empty"):
            if k in m:
                v = m[k]
                d = ""
                if base and isinstance(base.get(name), dict) and k in base[name]:
                    d = f" ({v - base[name][k]:+.2f})"
                parts.append(f"{k}={v:.2f}{d}")
        lines.append(f"{name:<22} n={m.get('n', 0):<3} " + "  ".join(parts))
    return "\n".join(lines)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--split", default="dev", choices=["dev", "test", "all"])
    ap.add_argument("--name", required=True)
    ap.add_argument("--db", default=None)
    ap.add_argument("--compare", default=None)
    ap.add_argument("--service", action="store_true", help="evaluate through the production SearchService")
    ap.add_argument("--rerank", action="store_true", help="with --service: LLM re-rank")
    ap.add_argument("--time", default="none", choices=["none", "filter", "boost", "prefer"],
                    help="detect a time phrase in the request and apply it as a filter or a boost")
    args = ap.parse_args()

    sessions = load_sessions()
    kind_of = {s["session_id"]: s["kind"] for s in sessions["sessions"]}
    qs = load_questions(None if args.split == "all" else args.split)
    for q in qs:
        q["kind"] = kind_of.get(q.get("session_id"))
    db = Path(args.db) if args.db else hi.get_history_db_path()
    searcher = hi.HistorySearcher(db)
    conn = hi.connect(db, readonly=True)
    model_name = hi.index_model(conn)
    conn.close()
    if args.service:
        hi.get_history_db_path = lambda: db
        if args.rerank:
            import agent  # noqa: F401  (loads OPENAI_API_KEY from context/.env)
            agent._load_env()
        rows = evaluate_service(qs, sessions["copies"], rerank_on=args.rerank)
    else:
        rows = evaluate(qs, searcher, sessions["copies"], time_mode=args.time, model_name=model_name)
    summary = summarize(rows)
    RUNS_DIR.mkdir(parents=True, exist_ok=True)
    out = RUNS_DIR / f"retrieval_{args.split}_{args.name}.json"
    out.write_text(json.dumps({"split": args.split, "name": args.name, "summary": summary, "rows": rows}, indent=1))
    base = None
    if args.compare:
        base = json.loads((RUNS_DIR / f"retrieval_{args.split}_{args.compare}.json").read_text())["summary"]
    print(fmt(summary, base))
    print(f"saved {out}")


if __name__ == "__main__":
    main()
