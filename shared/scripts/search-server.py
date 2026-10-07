#!/usr/bin/env python3
"""
Persistent search server: keeps the embedding model warm for the history and memory indexes.

Loading PyTorch + sentence-transformers + a model takes ~100 s on the Jetson, so this process
loads once and answers many requests. The indexes themselves are plain SQLite files
(index/history.sqlite3, index/memory.sqlite3 — see backend/utils/{history,memory}_index.py);
this server only encodes text and runs searches over them through utils/search_service.py,
the same code the one-shot fallback (search.py) and the eval harness use.

Two transports, same JSON-line protocol:

1. stdin/stdout — when spawned by the orchestrator (backend/orchestrator/tools/search.py).
2. Unix domain socket at <index>/.search-server.sock — for other processes on the machine
   (the indexers in index-memory.py borrow the warm model through it; search.py too).

Requests
--------
  {"command": "ping"}                                   -> {"status": "ready"}
  {"command": "encode", "text": "...", "model": "..."}   -> {"embedding": [float], "error": null}
  {"command": "encode_many", "texts": [...], "model": "..."} -> {"embeddings": [[float]], "error": null}
  {"command": "history_search", "query": "...", ...}     -> {"sessions": [...], "memory": [...], "error": null}
  {"command": "memory_search", "query": "...", ...}      -> {"files": [...], "error": null}
  {"command": "shutdown"}                               -> process exits cleanly

`model` defaults to the model the history index was built with. A flock on
<index>/.search-server.lock keeps a second server from starting.
"""
import errno
import fcntl
import json
import os
import socket
import sys
import threading
import traceback
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
PROJECT_DIR = SCRIPT_DIR.parent.parent  # shared/scripts/ → repo root
sys.path.insert(0, str(PROJECT_DIR / "backend"))  # backend packages (utils, …)

from utils.paths import get_index_dir  # noqa: E402

INDEX_DIR = get_index_dir()
LOCKFILE = INDEX_DIR / ".search-server.lock"
SOCKET_PATH = INDEX_DIR / ".search-server.sock"

# sentence-transformers batches its forward pass in groups of this many texts; between batches
# the GIL is released, so a long encode_many doesn't starve concurrent searches.
ENCODE_BATCH = 32


def stdio_send(data: dict) -> None:
    sys.stdout.write(json.dumps(data) + "\n")
    sys.stdout.flush()


def acquire_lock(lockfile: Path):
    lockfile.parent.mkdir(parents=True, exist_ok=True)
    fd = os.open(str(lockfile), os.O_RDWR | os.O_CREAT, 0o644)
    try:
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except OSError as e:
        if e.errno in (errno.EWOULDBLOCK, errno.EAGAIN):
            print(f"[search-server] lockfile {lockfile} held by another process; refusing to start",
                  file=sys.stderr, flush=True)
            sys.exit(2)
        raise
    os.write(fd, f"{os.getpid()}\n".encode())
    return fd


def default_model() -> str:
    """The model the history index was built with (what searches will need first)."""
    from utils import history_index

    db = history_index.get_history_db_path()
    if db.exists():
        conn = history_index.connect(db, readonly=True)
        try:
            return history_index.index_model(conn)
        finally:
            conn.close()
    return history_index.MODEL_NAME


class IndexServer:
    """Dispatches requests; methods return the reply dict (never write to stdio/socket)."""

    def __init__(self):
        from utils import search_service

        self.load_model = search_service.sentence_transformer_loader()
        self.service = search_service.SearchService(self.load_model)
        self.default_model = default_model()
        self.load_model(self.default_model)  # warm before announcing readiness

    def handle(self, request: dict) -> dict:
        try:
            cmd = request.get("command")
            if cmd == "ping":
                return {"status": "ready"}
            if cmd == "shutdown":
                return {"shutting_down": True}
            if cmd == "encode":
                model = self.load_model(request.get("model") or self.default_model)
                return {"embedding": model.encode([request["text"]], batch_size=ENCODE_BATCH)[0].tolist(), "error": None}
            if cmd == "encode_many":
                model = self.load_model(request.get("model") or self.default_model)
                texts = request.get("texts") or []
                return {"embeddings": [v.tolist() for v in model.encode(texts, batch_size=ENCODE_BATCH)] if texts else [],
                        "error": None}
            if cmd == "history_search":
                return self.service.history_search(request)
            if cmd == "memory_search":
                return self.service.memory_search(request)
            return {"error": f"Unknown command: {cmd}"}
        except Exception as e:
            print(f"[search-server FAILED] req={str(request)[:300]}\n{traceback.format_exc()}", file=sys.stderr, flush=True)
            return {"error": f"{type(e).__name__}: {e}"}


def run_stdio_loop(server: IndexServer, shutdown_flag: threading.Event) -> None:
    for line in sys.stdin:
        if shutdown_flag.is_set():
            return
        line = line.strip()
        if not line:
            continue
        try:
            request = json.loads(line)
        except json.JSONDecodeError:
            stdio_send({"error": f"Invalid JSON: {line[:200]}"})
            continue
        stdio_send(server.handle(request))
        if request.get("command") == "shutdown":
            shutdown_flag.set()
            return


def _create_listening_socket(sock_path: Path) -> socket.socket:
    sock_path.parent.mkdir(parents=True, exist_ok=True)
    if sock_path.exists():
        sock_path.unlink()
    sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    sock.bind(str(sock_path))
    os.chmod(str(sock_path), 0o600)
    sock.listen(8)
    sock.settimeout(0.5)
    return sock


def run_socket_loop(server: IndexServer, sock: socket.socket, sock_path: Path, shutdown_flag: threading.Event) -> None:
    def serve_conn(conn: socket.socket) -> None:
        # Buffered file I/O: readline() accumulates large requests correctly (unbuffered mode
        # lost data past the ~200 KB socket buffer). Flush after each write.
        try:
            f = conn.makefile("rwb")
            while not shutdown_flag.is_set():
                line = f.readline()
                if not line:
                    return
                try:
                    request = json.loads(line.decode().strip())
                except json.JSONDecodeError:
                    reply = {"error": "Invalid JSON"}
                    request = {}
                else:
                    reply = server.handle(request)
                try:
                    f.write(json.dumps(reply).encode() + b"\n")
                    f.flush()
                except BrokenPipeError:
                    return
                if request.get("command") == "shutdown":
                    shutdown_flag.set()
                    return
        finally:
            try:
                conn.close()
            except Exception:
                pass

    try:
        while not shutdown_flag.is_set():
            try:
                conn, _ = sock.accept()
            except socket.timeout:
                continue
            threading.Thread(target=serve_conn, args=(conn,), daemon=True).start()
    finally:
        sock.close()
        try:
            sock_path.unlink()
        except FileNotFoundError:
            pass


def main() -> None:
    INDEX_DIR.mkdir(parents=True, exist_ok=True)
    _ = acquire_lock(LOCKFILE)  # kept open for the process lifetime
    use_socket = "--socket" in sys.argv

    server = IndexServer()
    shutdown_flag = threading.Event()
    sock = _create_listening_socket(SOCKET_PATH) if use_socket else None  # bind before announcing

    ready: dict = {"status": "ready", "model": server.default_model}
    if sock is not None:
        ready["socket"] = str(SOCKET_PATH)
    stdio_send(ready)
    print(f"[search-server] ready, model={server.default_model}", file=sys.stderr, flush=True)

    if sock is not None:
        threading.Thread(target=run_socket_loop, args=(server, sock, SOCKET_PATH, shutdown_flag), daemon=True).start()
    run_stdio_loop(server, shutdown_flag)
    shutdown_flag.set()


if __name__ == "__main__":
    main()
