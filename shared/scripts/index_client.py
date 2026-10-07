"""Client for the warm search server (shared/scripts/search-server.py).

The server listens on `<index>/.search-server.sock` when started with --socket (the backend
does). Other processes use it to borrow the already-loaded embedding model instead of loading
their own (~100 s and several hundred MB on the Jetson):

    with Encoder("paraphrase-multilingual-MiniLM-L12-v2") as enc:
        vectors = enc.encode_many(["some text", ...])

When no server is reachable, Encoder loads the model in-process.

Used by:
  - shared/scripts/index-memory.py (embeds history and memory chunks)
  - shared/scripts/search.py (searches through the server when it runs)
"""
from __future__ import annotations

import errno
import fcntl
import json
import os
import socket
import sys
import time
from pathlib import Path
from typing import Optional

SCRIPT_DIR = Path(__file__).resolve().parent
PROJECT_DIR = SCRIPT_DIR.parent.parent  # shared/scripts/ → repo root
sys.path.insert(0, str(PROJECT_DIR / "backend"))  # backend packages (utils, …)

from utils.paths import get_index_dir  # noqa: E402

INDEX_DIR = get_index_dir()
SOCKET_PATH = INDEX_DIR / ".search-server.sock"
LOCKFILE = INDEX_DIR / ".search-server.lock"


def warm_server_running() -> bool:
    """True if a search server holds the lockfile."""
    if not LOCKFILE.exists():
        return False
    fd = os.open(str(LOCKFILE), os.O_RDONLY)
    try:
        fcntl.flock(fd, fcntl.LOCK_SH | fcntl.LOCK_NB)
    except OSError as e:
        return e.errno in (errno.EWOULDBLOCK, errno.EAGAIN)
    else:
        fcntl.flock(fd, fcntl.LOCK_UN)
        return False
    finally:
        os.close(fd)


class SocketClient:
    """Line-delimited JSON over the Unix domain socket. Single-threaded."""

    def __init__(self, sock_path: Path = SOCKET_PATH, timeout: float = 60.0):
        self.sock_path = sock_path
        self.timeout = timeout
        self._sock: Optional[socket.socket] = None
        self._file = None

    def __enter__(self):
        self.connect()
        return self

    def __exit__(self, *exc):
        self.close()

    def connect(self) -> None:
        s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        s.settimeout(self.timeout)
        s.connect(str(self.sock_path))
        self._sock = s
        # Buffered I/O; flush() after each write (unbuffered mode lost data on large writes).
        self._file = s.makefile("rwb")

    def close(self) -> None:
        try:
            if self._file:
                self._file.close()
        finally:
            self._file = None
            if self._sock:
                try:
                    self._sock.close()
                finally:
                    self._sock = None

    def call(self, request: dict, *, request_timeout: Optional[float] = None) -> dict:
        if self._file is None:
            raise RuntimeError("not connected")
        if request_timeout is not None and self._sock is not None:
            self._sock.settimeout(request_timeout)
        self._file.write(json.dumps(request).encode() + b"\n")
        self._file.flush()
        line = self._file.readline()
        if not line:
            raise ConnectionError("warm server closed the connection")
        return json.loads(line.decode())


def try_connect(timeout: float = 5.0, retries: int = 3) -> Optional[SocketClient]:
    """Connect to the warm server, retrying briefly (right after a restart the socket may not
    accept yet). None when there is no server."""
    for attempt in range(retries):
        if not SOCKET_PATH.exists():
            return None
        c = SocketClient(SOCKET_PATH, timeout=timeout)
        try:
            c.connect()
            return c
        except (FileNotFoundError, ConnectionRefusedError, socket.timeout, OSError):
            if attempt + 1 < retries:
                time.sleep(1.0)
    return None


class Encoder:
    """Embeds texts with a given model: through the warm server when reachable, else in-process."""

    def __init__(self, model: str, use_server: bool = True):
        self.model = model
        self._client: Optional[SocketClient] = try_connect() if use_server else None
        self._local = None

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        if self._client:
            self._client.close()

    @property
    def mode(self) -> str:
        return "socket" if self._client else "local"

    def encode_many(self, texts: list[str]) -> list[list[float]]:
        if not texts:
            return []
        if self._client:
            r = self._client.call({"command": "encode_many", "texts": texts, "model": self.model}, request_timeout=300.0)
            if r.get("error"):
                raise RuntimeError(f"encode_many failed: {r['error']}")
            return r["embeddings"]
        if self._local is None:
            from utils.search_service import sentence_transformer_loader
            self._local = sentence_transformer_loader()(self.model)
        return [v.tolist() for v in self._local.encode(texts, batch_size=32)]
