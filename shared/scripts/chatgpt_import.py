#!/usr/bin/env python3
"""Convert a ChatGPT data export into one JSONL per conversation, plus assets and indices.

Reads an untouched ChatGPT export (``conversations-NNN.json`` shards + ``file*.dat`` blobs) and
writes, under ``context/chatgpt-history/``:

    jsonl/<conversation_id>.jsonl     one header line + one line per turn of the canonical thread
    attachments/<conversation_id>/    the conversation's images and voice audio, real extensions
    index.csv                         one row per conversation
    attachments.csv                   one row per asset pointer (``present=false`` when expired)
    MANIFEST.json                     provenance: shard sha256s, export timestamp, counts

Design notes
------------
* **Canonical thread.** A ChatGPT conversation is a tree. This export carries only ``parent``
  pointers (no ``children`` arrays), so the children map is inverted here. The thread written out
  is ``current_node`` walked upward to its root and reversed; abandoned edit/regeneration branches
  are discarded (counted in ``counts.branches_discarded`` / ``counts.nodes_off_spine``). Assets
  that existed only on those branches are still copied into ``attachments/`` as *loose* files
  referenced by no turn (``on_spine=false`` in attachments.csv), so no blob is left behind.
* **Timestamps.** ``create_time`` is the canonical float epoch. ``timestamp`` is the derived
  ISO-8601 UTC mirror, because the history indexer only accepts string timestamps
  (``backend/utils/history_index.py`` ``Turn.ts``).
* **Voice.** The transcription *is* the content and is always written as text. Most audio blobs
  expire server-side and are absent from the export; those turns get ``audio.available: false``
  and an ``attachments`` entry with ``present: false`` rather than a path that does not resolve.
* **Read-only.** These are imported artifacts. The header marks ``harness: "chatgpt"`` and
  ``read_only: true``; nothing here is resumable through Archie.
* **Idempotent.** ``jsonl/`` and ``attachments/`` are derived: they are cleared and rebuilt on
  every run, deterministically (conversations sorted by create_time, then id).

Usage
-----
    context/scripts/run.sh shared/scripts/chatgpt_import.py [--root DIR] [--export DIR]
                                                            [--dry-run] [--hardlink]
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import os
import shutil
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterator

SCHEMA_VERSION = 1
HARNESS = "chatgpt"

# Conversation-level content types we carry through, and how each is labelled.
REASONING_MODES = {"thoughts": "thoughts", "reasoning_recap": "recap"}


# ── helpers ───────────────────────────────────────────────────────────────────


def iso(epoch: float | None) -> str | None:
    """Float epoch -> ISO-8601 UTC. The indexer reads dates by slicing this string."""
    if epoch is None:
        return None
    try:
        return datetime.fromtimestamp(float(epoch), timezone.utc).isoformat()
    except (OverflowError, OSError, ValueError):
        return None


def sha256_of(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for block in iter(lambda: fh.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


_MAGIC: tuple[tuple[bytes, str], ...] = (
    (b"\x89PNG\r\n\x1a\n", "png"),
    (b"\xff\xd8\xff", "jpg"),
    (b"GIF8", "gif"),
    (b"%PDF", "pdf"),
)


def sniff_ext(path: Path, fallback_name: str | None = None) -> str:
    """Real extension from magic bytes. The ``.dat`` suffix in the export is meaningless."""
    try:
        with path.open("rb") as fh:
            head = fh.read(16)
    except OSError:
        head = b""
    for magic, ext in _MAGIC:
        if head.startswith(magic):
            return ext
    if head[:4] == b"RIFF" and len(head) >= 12:
        if head[8:12] == b"WAVE":
            return "wav"
        if head[8:12] == b"WEBP":
            return "webp"
    if head[4:8] == b"ftyp":
        return "mp4"
    if fallback_name and "." in fallback_name:
        ext = fallback_name.rsplit(".", 1)[-1].lower()
        if ext.isalnum() and 1 <= len(ext) <= 5:
            return ext
    return "bin"


def file_id_of(pointer: str | None) -> str | None:
    """``sediment://file_abc`` / ``file-service://file-abc`` -> ``file_abc`` / ``file-abc``."""
    if not isinstance(pointer, str) or not pointer:
        return None
    return pointer.rsplit("://", 1)[-1] or None


def pointer_scheme(pointer: str | None) -> str:
    if isinstance(pointer, str) and "://" in pointer:
        return pointer.split("://", 1)[0]
    return ""


# ── export discovery ──────────────────────────────────────────────────────────


def find_export(root: Path, explicit: Path | None) -> Path:
    """The export directory: one holding ``conversations-000.json``.

    Looked for under ``<root>/raw/`` first (the staged home), then directly under ``<root>``.
    """
    if explicit:
        explicit = Path(explicit)
        if not (explicit / "conversations-000.json").is_file():
            raise SystemExit(f"not a ChatGPT export (no conversations-000.json): {explicit}")
        return explicit
    for base in (root / "raw", root):
        if not base.is_dir():
            continue
        for cand in sorted(base.iterdir()):
            if cand.is_dir() and (cand / "conversations-000.json").is_file():
                return cand
    raise SystemExit(f"no ChatGPT export found under {root}/raw/ or {root}/")


def load_shards(export: Path) -> tuple[list[dict], list[Path]]:
    shards = sorted(export.glob("conversations-*.json"))
    if not shards:
        raise SystemExit(f"no conversations-*.json shards in {export}")
    convs: list[dict] = []
    for shard in shards:
        with shard.open(encoding="utf-8") as fh:
            data = json.load(fh)
        if not isinstance(data, list):
            raise SystemExit(f"{shard.name}: expected a list of conversations")
        for conv in data:
            conv["_shard"] = shard.name
        convs.extend(data)
    return convs, shards


# ── tree ──────────────────────────────────────────────────────────────────────


def invert_children(mapping: dict[str, dict]) -> dict[str, list[str]]:
    """Children lists, which this export omits. Keys are mapping keys, kept in file order."""
    children: dict[str, list[str]] = {}
    for key, node in mapping.items():
        parent = node.get("parent")
        if parent is not None:
            children.setdefault(parent, []).append(key)
    return children


def spine_keys(conv: dict) -> list[str]:
    """The canonical thread: ``current_node`` upward to its root, reversed.

    Guards for real anomalies in this export: a node id may be the empty string (so test
    ``is None``, never truthiness), one conversation has two roots, and a malformed cycle must
    not hang the walk.
    """
    mapping: dict[str, dict] = conv.get("mapping") or {}
    cur = conv.get("current_node")
    if cur is None or cur not in mapping:
        # Fall back to the deepest leaf so a broken current_node still yields a thread.
        cur = next(iter(mapping), None)
        if cur is None:
            return []
    chain: list[str] = []
    seen: set[str] = set()
    guard = len(mapping) + 1
    while cur is not None and guard > 0:
        guard -= 1
        node = mapping.get(cur)
        if node is None or cur in seen:
            break
        seen.add(cur)
        chain.append(cur)
        cur = node.get("parent")
    chain.reverse()
    return chain


# ── message conversion ────────────────────────────────────────────────────────


class AssetRef:
    """One asset pointer found on the canonical thread."""

    __slots__ = ("file_id", "kind", "scheme", "size_bytes", "width", "height", "duration_s",
                 "direction", "original_name", "present", "ext", "rel_path", "node_id", "on_spine")

    def __init__(self, **kw: Any) -> None:
        for slot in self.__slots__:
            setattr(self, slot, kw.get(slot))
        if self.on_spine is None:
            self.on_spine = True


def _audio_ref(ptr_obj: dict, direction: str, node_id: str) -> AssetRef:
    meta = ptr_obj.get("metadata") or {}
    start, end = meta.get("start"), meta.get("end")
    duration = None
    if isinstance(start, (int, float)) and isinstance(end, (int, float)) and end >= start:
        duration = round(float(end) - float(start), 3)
    return AssetRef(
        file_id=file_id_of(ptr_obj.get("asset_pointer")),
        kind="audio",
        scheme=pointer_scheme(ptr_obj.get("asset_pointer")),
        size_bytes=ptr_obj.get("size_bytes"),
        duration_s=duration,
        direction=direction,
        node_id=node_id,
    )


def convert_message(msg: dict, node_id: str) -> dict | None:
    """One ChatGPT message -> one JSONL turn dict (without timestamps), or None to skip.

    Returns a dict with ``blocks`` (text blocks), ``assets`` (list[AssetRef]), and flags.
    """
    role = (msg.get("author") or {}).get("role")
    if role not in ("user", "assistant"):
        return None
    content = msg.get("content") or {}
    ctype = content.get("content_type")

    blocks: list[dict] = []
    assets: list[AssetRef] = []
    voice = False
    directions: set[str] = set()

    def add_text(text: Any) -> None:
        if isinstance(text, str) and text.strip():
            blocks.append({"type": "text", "text": text})

    if ctype in ("text", "multimodal_text"):
        for part in content.get("parts") or []:
            if isinstance(part, str):
                add_text(part)
                continue
            if not isinstance(part, dict):
                continue
            pct = part.get("content_type")
            if pct == "audio_transcription":
                # The spoken content. Always kept as text, whether or not audio survived.
                add_text(part.get("text"))
                voice = True
                if isinstance(part.get("direction"), str):
                    directions.add(part["direction"])
            elif pct == "audio_asset_pointer":
                voice = True
                d = part.get("tool_audio_direction") or ("in" if role == "user" else "out")
                assets.append(_audio_ref(part, d, node_id))
            elif pct == "real_time_user_audio_video_asset_pointer":
                voice = True
                inner = part.get("audio_asset_pointer") or {}
                if inner:
                    assets.append(_audio_ref(inner, "in", node_id))
            elif pct == "image_asset_pointer":
                assets.append(AssetRef(
                    file_id=file_id_of(part.get("asset_pointer")),
                    kind="image",
                    scheme=pointer_scheme(part.get("asset_pointer")),
                    size_bytes=part.get("size_bytes"),
                    width=part.get("width"),
                    height=part.get("height"),
                    node_id=node_id,
                ))

    elif ctype == "thoughts":
        # Reasoning-mode content: keep it, labelled, never silently dropped.
        for thought in content.get("thoughts") or []:
            if not isinstance(thought, dict):
                continue
            summary, body = thought.get("summary"), thought.get("content")
            if isinstance(summary, str) and summary.strip() and isinstance(body, str) and body.strip():
                add_text(f"{summary}\n{body}")
            else:
                add_text(body if isinstance(body, str) else summary)

    elif ctype == "reasoning_recap":
        add_text(content.get("content"))

    elif ctype == "user_editable_context":
        parts = [content.get("user_instructions"), content.get("user_profile")]
        for p in parts:
            add_text(p)

    else:  # unknown future content type: keep any string parts rather than lose the turn
        for part in content.get("parts") or []:
            if isinstance(part, str):
                add_text(part)

    if not blocks and not assets:
        return None

    out: dict[str, Any] = {"role": role, "blocks": blocks, "assets": assets}
    if ctype in REASONING_MODES:
        out["reasoning"] = True
        out["reasoning_mode"] = REASONING_MODES[ctype]
    if voice:
        out["voice"] = True
        out["directions"] = directions
    if ctype not in ("text", "multimodal_text"):
        out["content_type"] = ctype
    return out


# ── conversion of one conversation ────────────────────────────────────────────


def off_spine_assets(conv: dict, spine: set[str]) -> list[AssetRef]:
    """Assets referenced only by abandoned edit/regeneration branches.

    The canonical thread does not include them, so no turn ever references them — but the blobs
    exist and belong to this conversation, so they are copied in as *loose* attachments
    (``on_spine=false`` in attachments.csv) rather than left behind in ``raw/``.
    """
    refs: list[AssetRef] = []
    for key, node in (conv.get("mapping") or {}).items():
        if key in spine:
            continue
        msg = node.get("message")
        if not isinstance(msg, dict):
            continue
        converted = convert_message(msg, key)
        if converted:
            refs.extend(converted["assets"])
    return refs


def convert_conversation(
    conv: dict,
    export: Path,
    dat_index: dict[str, Path],
    asset_names: dict[str, str],
    out_root: Path,
    *,
    dry_run: bool,
    hardlink: bool,
) -> tuple[list[dict], dict, list[AssetRef]]:
    """-> (jsonl lines, index row, asset refs). Copies this conversation's assets."""
    cid = conv.get("id") or conv.get("conversation_id") or ""
    mapping: dict[str, dict] = conv.get("mapping") or {}
    children = invert_children(mapping)
    spine = spine_keys(conv)
    spine_set = set(spine)

    branch_points = sum(1 for kids in children.values() if len(kids) > 1)
    roots = [k for k, n in mapping.items() if n.get("parent") is None]

    turns: list[dict] = []
    assets: list[AssetRef] = []
    for key in spine:
        node = mapping.get(key) or {}
        msg = node.get("message")
        if not isinstance(msg, dict):
            continue  # the message-less tree root
        conv_msg = convert_message(msg, key)
        if conv_msg is None:
            continue
        ct = msg.get("create_time")
        line: dict[str, Any] = {
            "type": conv_msg["role"],
            "create_time": ct,
            "timestamp": iso(ct),
            "node_id": key,
            "parent_id": node.get("parent"),
        }
        md = msg.get("metadata") or {}
        if isinstance(md.get("model_slug"), str):
            line["model"] = md["model_slug"]
        if conv_msg.get("reasoning"):
            line["reasoning"] = True
            line["reasoning_mode"] = conv_msg["reasoning_mode"]
        if conv_msg.get("content_type"):
            line["content_type"] = conv_msg["content_type"]
        if conv_msg.get("voice"):
            line["voice"] = True
            if md.get("bidi_voice_mode_message") is not None:
                line["bidi_voice_mode"] = bool(md["bidi_voice_mode_message"])
        line["message"] = {"role": conv_msg["role"], "content": conv_msg["blocks"]}
        turns.append(line)
        assets.extend(conv_msg["assets"])

    # Assets on abandoned branches: copied in as loose attachments, referenced by no turn.
    # Only those whose blob survived in the export — dead off-spine pointers carry no information.
    loose = [r for r in off_spine_assets(conv, spine_set) if (r.file_id or "") in dat_index]
    for ref in loose:
        ref.on_spine = False
    assets.extend(loose)

    # Resolve + copy assets, then attach them to their turns.
    conv_dir = out_root / "attachments" / cid
    copied = 0
    for ref in assets:
        src = dat_index.get(ref.file_id or "")
        ref.original_name = asset_names.get(f"{ref.file_id}.dat")
        if src is None:
            ref.present = False
            continue
        ref.present = True
        ref.ext = sniff_ext(src, ref.original_name)
        name = f"{ref.file_id}.{ref.ext}"
        ref.rel_path = f"attachments/{cid}/{name}"
        if not dry_run:
            conv_dir.mkdir(parents=True, exist_ok=True)
            dst = conv_dir / name
            if hardlink:
                try:
                    os.link(src, dst)
                except OSError:
                    shutil.copy2(src, dst)
            else:
                shutil.copy2(src, dst)
        copied += 1

    by_node: dict[str, list[AssetRef]] = {}
    for ref in assets:
        if ref.on_spine:  # loose assets are referenced by no turn, by design
            by_node.setdefault(ref.node_id or "", []).append(ref)
    for line in turns:
        refs = by_node.get(line["node_id"]) or []
        if not refs:
            continue
        entries = []
        for r in refs:
            e: dict[str, Any] = {"kind": r.kind, "file_id": r.file_id, "present": bool(r.present)}
            if r.rel_path:
                e["path"] = r.rel_path
            if r.original_name:
                e["original_name"] = r.original_name
            for k in ("size_bytes", "width", "height", "duration_s"):
                if getattr(r, k) is not None:
                    e[k] = getattr(r, k)
            if not r.present:
                e["reason"] = "not_in_export"
            entries.append(e)
        line["attachments"] = entries
        audio = [r for r in refs if r.kind == "audio"]
        if audio and line.get("voice"):
            a = audio[0]
            line["audio"] = {
                "available": bool(a.present),
                "direction": a.direction,
                "format": a.ext or "wav",
                "file_id": a.file_id,
                **({"path": a.rel_path} if a.rel_path else {}),
                **({"duration_s": a.duration_s} if a.duration_s is not None else {}),
                **({"size_bytes": a.size_bytes} if a.size_bytes is not None else {}),
                **({} if a.present else {"reason": "not_in_export"}),
            }

    create_time = conv.get("create_time")
    update_time = conv.get("update_time")
    ts = [t["create_time"] for t in turns if isinstance(t.get("create_time"), (int, float))]
    models = sorted({t["model"] for t in turns if t.get("model")})
    voice_turns = sum(1 for t in turns if t.get("voice"))
    reasoning_turns = sum(1 for t in turns if t.get("reasoning"))
    spine_assets = [r for r in assets if r.on_spine]
    loose_assets = [r for r in assets if not r.on_spine]
    images = sum(1 for r in spine_assets if r.kind == "image")
    audio_refs = [r for r in spine_assets if r.kind == "audio"]
    words = sum(len(b["text"].split())
                for t in turns for b in t["message"]["content"] if b.get("text"))

    header = {
        "type": "chatgpt_meta",
        "harness": HARNESS,
        "schema_version": SCHEMA_VERSION,
        "read_only": True,
        "session_id": cid,
        "conversation_id": cid,
        "title": conv.get("title") or "",
        "create_time": create_time,
        "update_time": update_time,
        "timestamp": iso(create_time),
        "started_at": iso(min(ts)) if ts else iso(create_time),
        "ended_at": iso(max(ts)) if ts else iso(update_time),
        "default_model_slug": conv.get("default_model_slug"),
        "models": models,
        "voice": conv.get("voice"),
        "is_archived": bool(conv.get("is_archived")),
        "is_starred": bool(conv.get("is_starred")),
        "memory_scope": conv.get("memory_scope"),
        "source": {
            "export": export.name,
            "shard": conv.get("_shard"),
            "origin": "chatgpt_export",
        },
        "ui": {"label": "ChatGPT", "icon": "chatgpt-mark", "resumable": False},
        "counts": {
            "turns": len(turns),
            "nodes": len(mapping),
            "nodes_off_spine": len(mapping) - len(spine_set),
            "branches_discarded": branch_points,
            "roots": len(roots),
            "voice_turns": voice_turns,
            "reasoning_turns": reasoning_turns,
            "images": images,
            "audio_refs": len(audio_refs),
            "audio_files_present": sum(1 for r in audio_refs if r.present),
            # Blobs from abandoned branches, present in attachments/ but referenced by no turn.
            "loose_attachments": len(loose_assets),
            "words": words,
        },
    }

    row = {
        "conversation_id": cid,
        "title": conv.get("title") or "",
        "create_time": "" if create_time is None else f"{float(create_time):.6f}",
        "update_time": "" if update_time is None else f"{float(update_time):.6f}",
        "started_iso": (header["started_at"] or "")[:10],
        "ended_iso": (header["ended_at"] or "")[:10],
        "turns": len(turns),
        "user_turns": sum(1 for t in turns if t["type"] == "user"),
        "assistant_turns": sum(1 for t in turns if t["type"] == "assistant"),
        "words": words,
        "models": ";".join(models),
        "voice": conv.get("voice") or "",
        "voice_turns": voice_turns,
        "reasoning_turns": reasoning_turns,
        "audio_refs": len(audio_refs),
        "audio_present": sum(1 for r in audio_refs if r.present),
        "images": images,
        "loose_attachments": len(loose_assets),
        "is_archived": str(bool(conv.get("is_archived"))).lower(),
        "nodes": len(mapping),
        "nodes_off_spine": len(mapping) - len(spine_set),
        "shard": conv.get("_shard") or "",
        "jsonl_path": f"jsonl/{cid}.jsonl",
    }

    return [header, *turns], row, assets


# ── driver ────────────────────────────────────────────────────────────────────


INDEX_FIELDS = [
    "conversation_id", "title", "create_time", "update_time", "started_iso", "ended_iso",
    "turns", "user_turns", "assistant_turns", "words", "models", "voice", "voice_turns",
    "reasoning_turns", "audio_refs", "audio_present", "images", "loose_attachments",
    "is_archived", "nodes", "nodes_off_spine", "shard", "jsonl_path",
]
ATTACH_FIELDS = [
    "file_id", "conversation_id", "node_id", "kind", "present", "on_spine", "ext", "bytes",
    "duration_s", "width", "height", "direction", "original_name", "path", "pointer_scheme",
]


def reset_dir(path: Path, dry_run: bool) -> None:
    """Derived output: cleared and rebuilt each run so reruns are deterministic."""
    if dry_run:
        return
    if path.exists():
        shutil.rmtree(path)
    path.mkdir(parents=True, exist_ok=True)


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--root", type=Path, default=None,
                    help="output root (default: <repo>/context/chatgpt-history)")
    ap.add_argument("--export", type=Path, default=None,
                    help="the export dir (default: auto-discovered under <root>/raw/)")
    ap.add_argument("--dry-run", action="store_true", help="report only; write nothing")
    ap.add_argument("--hardlink", action="store_true",
                    help="hard-link assets instead of copying (same filesystem only)")
    args = ap.parse_args(argv)

    root = args.root
    if root is None:
        try:
            from utils.paths import get_context_dir

            root = Path(get_context_dir()) / "chatgpt-history"
        except Exception:  # noqa: BLE001 — usable outside the backend venv too
            root = Path(__file__).resolve().parents[2] / "context" / "chatgpt-history"
    root = Path(root)
    export = find_export(root, args.export)
    print(f"export : {export}")
    print(f"output : {root}")
    if args.dry_run:
        print("mode   : DRY RUN (no writes)")

    convs, shards = load_shards(export)
    print(f"shards : {len(shards)}  conversations: {len(convs)}")

    dat_index = {p.name[:-4]: p for p in export.glob("*.dat")}
    asset_names: dict[str, str] = {}
    names_path = export / "conversation_asset_file_names.json"
    if names_path.is_file():
        with names_path.open(encoding="utf-8") as fh:
            asset_names = json.load(fh)

    jsonl_dir, attach_dir = root / "jsonl", root / "attachments"
    reset_dir(jsonl_dir, args.dry_run)
    reset_dir(attach_dir, args.dry_run)

    # Deterministic order: create_time, then id.
    convs.sort(key=lambda c: (c.get("create_time") or 0.0, c.get("id") or ""))

    rows: list[dict] = []
    attach_rows: list[dict] = []
    totals = {"turns": 0, "voice_turns": 0, "reasoning_turns": 0, "images": 0,
              "audio_refs": 0, "audio_present": 0, "off_spine": 0, "branches": 0, "loose": 0}
    dup: set[str] = set()
    used_dats: set[str] = set()

    for conv in convs:
        cid = conv.get("id") or conv.get("conversation_id") or ""
        if not cid:
            print("  ! conversation with no id, skipped", file=sys.stderr)
            continue
        if cid in dup:
            print(f"  ! duplicate conversation id {cid}, skipped", file=sys.stderr)
            continue
        dup.add(cid)

        lines, row, assets = convert_conversation(
            conv, export, dat_index, asset_names, root,
            dry_run=args.dry_run, hardlink=args.hardlink,
        )
        if not args.dry_run:
            with (jsonl_dir / f"{cid}.jsonl").open("w", encoding="utf-8") as fh:
                for line in lines:
                    fh.write(json.dumps(line, ensure_ascii=False) + "\n")
        rows.append(row)
        for r in assets:
            if r.present and r.file_id:
                used_dats.add(r.file_id)
            attach_rows.append({
                "file_id": r.file_id or "",
                "conversation_id": cid,
                "node_id": r.node_id or "",
                "kind": r.kind or "",
                "present": str(bool(r.present)).lower(),
                "on_spine": str(bool(r.on_spine)).lower(),
                "ext": r.ext or "",
                "bytes": r.size_bytes if r.size_bytes is not None else "",
                "duration_s": r.duration_s if r.duration_s is not None else "",
                "width": r.width if r.width is not None else "",
                "height": r.height if r.height is not None else "",
                "direction": r.direction or "",
                "original_name": r.original_name or "",
                "path": r.rel_path or "",
                "pointer_scheme": r.scheme or "",
            })
        totals["turns"] += row["turns"]
        totals["voice_turns"] += row["voice_turns"]
        totals["reasoning_turns"] += row["reasoning_turns"]
        totals["images"] += row["images"]
        totals["loose"] += row["loose_attachments"]
        totals["audio_refs"] += row["audio_refs"]
        totals["audio_present"] += row["audio_present"]
        totals["off_spine"] += row["nodes_off_spine"]
        totals["branches"] += lines[0]["counts"]["branches_discarded"]

    rows.sort(key=lambda r: (r["create_time"], r["conversation_id"]))
    attach_rows.sort(key=lambda r: (r["conversation_id"], r["node_id"], r["file_id"]))

    if not args.dry_run:
        with (root / "index.csv").open("w", encoding="utf-8", newline="") as fh:
            w = csv.DictWriter(fh, fieldnames=INDEX_FIELDS)
            w.writeheader()
            w.writerows(rows)
        with (root / "attachments.csv").open("w", encoding="utf-8", newline="") as fh:
            w = csv.DictWriter(fh, fieldnames=ATTACH_FIELDS)
            w.writeheader()
            w.writerows(attach_rows)

    requested_at = None
    site_manifest = export / "sites" / "export_manifest.json"
    if site_manifest.is_file():
        try:
            with site_manifest.open(encoding="utf-8") as fh:
                requested_at = json.load(fh).get("requested_at")
        except (OSError, ValueError):
            pass

    orphans = sorted(set(dat_index) - used_dats)
    manifest = {
        "schema_version": SCHEMA_VERSION,
        "harness": HARNESS,
        "read_only": True,
        "generator": "shared/scripts/chatgpt_import.py",
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "source": {
            "export_dir": export.name,
            "requested_at": requested_at,
            "shards": [
                {"path": s.name, "size_bytes": s.stat().st_size, "sha256": sha256_of(s)}
                for s in shards
            ],
            "excluded_from_pipeline": {
                "user.json": "account identity (PII) — stays in raw/",
                "codex.json": "Codex IDE tasks, different schema — stays in raw/",
                "chat.html": "redundant render — stays in raw/, not a visualization path",
            },
        },
        "counts": {
            "conversations": len(rows),
            "turns": totals["turns"],
            "voice_turns": totals["voice_turns"],
            "reasoning_turns": totals["reasoning_turns"],
            "images": totals["images"],
            "audio_refs": totals["audio_refs"],
            "audio_files_present": totals["audio_present"],
            "audio_files_missing": totals["audio_refs"] - totals["audio_present"],
            "nodes_off_spine_discarded": totals["off_spine"],
            "branch_points_discarded": totals["branches"],
            "loose_attachments": totals["loose"],
            "dat_files_in_export": len(dat_index),
            "dat_files_not_copied": len(orphans),
        },
        "ui": {
            "label": "ChatGPT",
            "icon": "chatgpt-mark",
            "category": "imported",
            "resumable": False,
            "hint": "Imported from ChatGPT · read-only",
        },
        "layout": {
            "raw": "raw/ (original export, untouched)",
            "jsonl": "jsonl/<conversation_id>.jsonl",
            "attachments": "attachments/<conversation_id>/<file_id>.<ext>",
            "index": "index.csv",
            "attachments_index": "attachments.csv",
        },
    }
    if orphans:
        manifest["dat_files_not_copied"] = orphans
    if not args.dry_run:
        with (root / "MANIFEST.json").open("w", encoding="utf-8") as fh:
            json.dump(manifest, fh, ensure_ascii=False, indent=2)
            fh.write("\n")

    print(f"\nconversations : {len(rows)}")
    print(f"turns         : {totals['turns']}")
    print(f"voice turns   : {totals['voice_turns']}")
    print(f"reasoning     : {totals['reasoning_turns']}")
    print(f"images        : {totals['images']}")
    print(f"audio refs    : {totals['audio_refs']}  present: {totals['audio_present']}  "
          f"missing: {totals['audio_refs'] - totals['audio_present']}")
    print(f"loose attach. : {totals['loose']}  (from abandoned branches, referenced by no turn)")
    print(f"off-spine nodes discarded: {totals['off_spine']}  "
          f"(branch points: {totals['branches']})")
    print(f"export .dat files: {len(dat_index)}  not copied: {len(orphans)}")
    if orphans:
        for o in orphans[:10]:
            print(f"  not copied: {o}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
