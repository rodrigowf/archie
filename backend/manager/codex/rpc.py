"""Minimal JSON-RPC 2.0 client for ``codex app-server`` over stdio.

The app-server speaks newline-delimited JSON-RPC on stdin/stdout (the
server omits the ``"jsonrpc"`` field; we send it anyway).  Three kinds of
inbound message:

* **responses** ``{"id", "result"|"error"}`` — matched to the pending
  request future by id;
* **notifications** ``{"method", "params"}`` — streaming events
  (``item/agentMessage/delta``, ``turn/completed``, …), handed to
  ``on_notification``;
* **server requests** ``{"id", "method", "params"}`` — approvals and
  similar, handed to ``on_request``, whose return value (or raised
  :class:`CodexRpcError`) is sent back as the response.

One :class:`CodexRpc` owns one process's stdio.  :meth:`run` is the read
loop; it returns when stdout reaches EOF (the process exited) and fails
every pending request with :class:`CodexConnectionClosed`.
"""

from __future__ import annotations

import asyncio
import itertools
import json
import logging
from collections.abc import Awaitable, Callable
from typing import Any

logger = logging.getLogger(__name__)

NotificationHandler = Callable[[str, dict[str, Any]], None]
RequestHandler = Callable[[str, dict[str, Any]], Awaitable[Any]]


class CodexRpcError(Exception):
    """A JSON-RPC error response (or one we send back to a server request)."""

    def __init__(self, message: str, code: int = -32000, data: Any = None) -> None:
        super().__init__(message)
        self.code = code
        self.data = data


class CodexConnectionClosed(ConnectionError):
    """The app-server's stdout closed while a request was pending."""


class CodexRpc:
    """JSON-RPC peer over an asyncio reader/writer pair."""

    def __init__(
        self,
        reader: asyncio.StreamReader,
        writer: asyncio.StreamWriter,
        *,
        on_notification: NotificationHandler | None = None,
        on_request: RequestHandler | None = None,
        name: str = "codex",
    ) -> None:
        self._reader = reader
        self._writer = writer
        self._on_notification = on_notification
        self._on_request = on_request
        self._name = name
        self._ids = itertools.count(1)
        self._pending: dict[int, asyncio.Future[Any]] = {}
        self._closed = False
        self._write_lock = asyncio.Lock()
        self._request_tasks: set[asyncio.Task[None]] = set()

    @property
    def closed(self) -> bool:
        return self._closed

    # -- outbound -----------------------------------------------------------

    async def _write(self, obj: dict[str, Any]) -> None:
        if self._closed:
            raise CodexConnectionClosed(f"{self._name}: connection closed")
        line = json.dumps(obj, ensure_ascii=False) + "\n"
        async with self._write_lock:
            try:
                self._writer.write(line.encode("utf-8"))
                await self._writer.drain()
            except (BrokenPipeError, ConnectionResetError) as e:
                raise CodexConnectionClosed(f"{self._name}: {e}") from e

    async def request(
        self, method: str, params: dict[str, Any] | None = None, *, timeout: float | None = 30.0,
    ) -> Any:
        """Send a request and await its result (raises :class:`CodexRpcError`)."""
        req_id = next(self._ids)
        fut: asyncio.Future[Any] = asyncio.get_running_loop().create_future()
        self._pending[req_id] = fut
        msg: dict[str, Any] = {"jsonrpc": "2.0", "id": req_id, "method": method}
        if params is not None:
            msg["params"] = params
        try:
            await self._write(msg)
            if timeout is None:
                return await fut
            return await asyncio.wait_for(fut, timeout=timeout)
        finally:
            self._pending.pop(req_id, None)

    async def notify(self, method: str, params: dict[str, Any] | None = None) -> None:
        msg: dict[str, Any] = {"jsonrpc": "2.0", "method": method}
        if params is not None:
            msg["params"] = params
        await self._write(msg)

    # -- inbound ------------------------------------------------------------

    async def run(self) -> None:
        """Read loop.  Returns at EOF; never raises for a malformed line."""
        try:
            while True:
                line = await self._reader.readline()
                if not line:
                    return
                text = line.decode("utf-8", errors="replace").strip()
                if not text:
                    continue
                try:
                    msg = json.loads(text)
                except json.JSONDecodeError:
                    logger.warning("%s: non-JSON stdout line: %r", self._name, text[:200])
                    continue
                if not isinstance(msg, dict):
                    continue
                self._dispatch(msg)
        finally:
            self._closed = True
            for fut in self._pending.values():
                if not fut.done():
                    fut.set_exception(CodexConnectionClosed(f"{self._name}: app-server exited"))
            self._pending.clear()
            for task in list(self._request_tasks):
                task.cancel()

    def _dispatch(self, msg: dict[str, Any]) -> None:
        method = msg.get("method")
        msg_id = msg.get("id")
        if method is None:
            # Response to one of our requests.
            if not isinstance(msg_id, int):
                return
            fut = self._pending.get(msg_id)
            if fut is None or fut.done():
                return
            if "error" in msg and msg["error"] is not None:
                err = msg["error"] if isinstance(msg["error"], dict) else {"message": str(msg["error"])}
                fut.set_exception(CodexRpcError(
                    str(err.get("message") or "error"), int(err.get("code") or -32000), err.get("data"),
                ))
            else:
                fut.set_result(msg.get("result"))
            return
        params = msg.get("params") if isinstance(msg.get("params"), dict) else {}
        if msg_id is None:
            if self._on_notification is not None:
                try:
                    self._on_notification(str(method), params)
                except Exception:
                    logger.exception("%s: notification handler failed for %s", self._name, method)
            return
        # Server → client request: answer from a task so a slow handler
        # never blocks the read loop.
        task = asyncio.create_task(self._answer(msg_id, str(method), params))
        self._request_tasks.add(task)
        task.add_done_callback(self._request_tasks.discard)

    async def _answer(self, msg_id: Any, method: str, params: dict[str, Any]) -> None:
        try:
            if self._on_request is None:
                raise CodexRpcError(f"unsupported request {method}", -32601)
            result = await self._on_request(method, params)
            reply: dict[str, Any] = {"jsonrpc": "2.0", "id": msg_id, "result": result}
        except CodexRpcError as e:
            reply = {"jsonrpc": "2.0", "id": msg_id, "error": {"code": e.code, "message": str(e)}}
        except asyncio.CancelledError:
            raise
        except Exception as e:  # noqa: BLE001 — never leave the server waiting
            logger.exception("%s: request handler failed for %s", self._name, method)
            reply = {"jsonrpc": "2.0", "id": msg_id, "error": {"code": -32603, "message": str(e)}}
        try:
            await self._write(reply)
        except CodexConnectionClosed:
            pass
