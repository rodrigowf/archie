"""Tests for shared/scripts/index_client.py — socket client and Encoder."""
from __future__ import annotations

import json
import socket
import threading

import numpy as np

import index_client


def _serve_once(path, reply):
    srv = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    srv.bind(str(path))
    srv.listen(1)

    def run():
        conn, _ = srv.accept()
        f = conn.makefile("rwb")
        req = json.loads(f.readline())
        f.write(json.dumps(reply(req)).encode() + b"\n")
        f.flush()
        conn.close()
        srv.close()

    threading.Thread(target=run, daemon=True).start()


def test_encoder_uses_the_server_with_its_model(tmp_path, monkeypatch):
    sock = tmp_path / "s.sock"
    monkeypatch.setattr(index_client, "SOCKET_PATH", sock)
    seen = {}

    def reply(req):
        seen.update(req)
        return {"embeddings": [[1.0]] * len(req["texts"]), "error": None}

    _serve_once(sock, reply)
    with index_client.Encoder("multi-model") as enc:
        assert enc.mode == "socket"
        assert enc.encode_many(["a", "b"]) == [[1.0], [1.0]]
    assert seen["command"] == "encode_many" and seen["model"] == "multi-model"


def test_encoder_falls_back_to_a_local_model(tmp_path, monkeypatch):
    monkeypatch.setattr(index_client, "SOCKET_PATH", tmp_path / "missing.sock")

    class Fake:
        def encode(self, texts, batch_size=32):
            return np.ones((len(texts), 2))

    import utils.search_service as ss
    monkeypatch.setattr(ss, "sentence_transformer_loader", lambda: (lambda name: Fake()))
    enc = index_client.Encoder("m")
    assert enc.mode == "local" and enc.encode_many(["x"]) == [[1.0, 1.0]]
    assert index_client.Encoder("m", use_server=False).mode == "local"


def test_try_connect_without_server(tmp_path, monkeypatch):
    monkeypatch.setattr(index_client, "SOCKET_PATH", tmp_path / "missing.sock")
    assert index_client.try_connect(retries=1) is None
