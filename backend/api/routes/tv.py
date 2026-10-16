"""TV control channel: WebSocket hub for the Archie TV app (spec 15 §5).

The Archie TV app (``apps/android/app-tv``, ``com.assistant.archie.tv``)
connects here whenever it is connected to the server and waits for commands;
agents and the orchestrator drive it through ``POST /api/tv/command`` (via
``shared/scripts/tv_cmd.py``) and the visualizations "Show on TV" endpoint.
This is the transport layer only — what a command does on screen lives in the
app's ``CommandHandler``.

Wire protocol, JSON text frames both directions (modelled on
``api/routes/browser.py``)::

    tv      → {"type": "hello", "device_id": "<uuid>", "device_name": "Living room TV",
               "client": "archie-tv", "version": "1.0.0",
               "capabilities": ["open_url", ...], "token": "..."?}
    backend → {"type": "ready"}  |  {"type": "error", "error": "<reason>"} then close 4401
    backend → {"id": "ab12...", "type": "command", "command": "open_visualization", "params": {...}}
    tv      → {"id": "ab12...", "type": "result", "ok": true, "result": {...}}
    tv      → {"id": "ab12...", "type": "result", "ok": false, "error": "..."}
    tv      → {"type": "state", "screen": "viewer", "url": "...", "title": "...", ...}
    tv     ↔  {"type": "ping"} / {"type": "pong"}

Unlike the browser hub this is **multi-device**: one connection per
``device_id``. A device reconnecting with the same id replaces its old socket
(close 4409); each connection owns its pending commands, so one TV dropping
fails only its own in-flight commands.

**Auth is optional.** A TV only *receives* URLs to show, so the risk of an
open socket is an impostor seeing them; same-``device_id`` replacement caps
it. When ``TV_CONTROL_TOKEN`` is configured (env, then ``context/.env``) the
hello must carry it. The command endpoint is loopback-only either way.
"""

from __future__ import annotations

import asyncio
import logging
import os
import secrets
import time
import uuid
from dataclasses import dataclass, field
from typing import Any

import orjson
from fastapi import APIRouter, HTTPException, Request, WebSocket, WebSocketDisconnect

from api.net import is_proxied_from_lan
from utils.paths import PROJECT_ROOT

logger = logging.getLogger(__name__)
router = APIRouter(tags=["tv"])


TOKEN_ENV_VAR = "TV_CONTROL_TOKEN"
HELLO_TIMEOUT_S = 10.0
# The TV may have to bring its activity to the front and start loading a page
# before it answers; 15 s covers a cold start on the AFTKM.
DEFAULT_COMMAND_TIMEOUT_S = 15.0
MAX_COMMAND_TIMEOUT_S = 120.0
_MAX_ID_LEN = 128
_MAX_NAME_LEN = 120

# Application close codes (4000-4999 is the range reserved for app use).
WS_CLOSE_UNAUTHORIZED = 4401
WS_CLOSE_REPLACED = 4409


class TvNotConnected(RuntimeError):
    """No TV (or not the requested one) is attached."""


class TvAmbiguous(RuntimeError):
    """Several TVs are attached and the caller did not say which."""

    def __init__(self, names: list[str]) -> None:
        self.names = names
        super().__init__(
            "several TVs are connected; pass device= one of: "
            + ", ".join(repr(n) for n in names)
        )


class TvCommandError(RuntimeError):
    """The TV ran the command and reported a failure."""


class TvCommandTimeout(RuntimeError):
    """The TV never answered within the timeout."""


def _resolve_token() -> str | None:
    """Return the shared token, preferring the process environment.

    Same resolution as ``api.routes.browser._resolve_token``: ``run.sh``
    normally exports ``context/.env``; otherwise the file is parsed directly.
    None (or a blank value) means "no token required".
    """
    token = os.environ.get(TOKEN_ENV_VAR)
    if token and token.strip():
        return token.strip()

    env_path = PROJECT_ROOT / "context" / ".env"
    if not env_path.exists():
        return None
    try:
        for raw in env_path.read_text().splitlines():
            line = raw.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            name, _, value = line.partition("=")
            if name.strip() == TOKEN_ENV_VAR:
                # Strip quotes a shell `source` would have removed.
                resolved = value.strip().strip('"').strip("'")
                return resolved or None
    except OSError:
        logger.exception("Failed to read context/.env for %s", TOKEN_ENV_VAR)
    return None


@dataclass(eq=False)
class TvConnection:
    """One attached TV. Identity (``is``) matters: a replaced connection's
    receive loop must not tear down the one that replaced it."""

    ws: Any
    device_id: str
    device_name: str
    client: str = "unknown"
    version: str | None = None
    capabilities: list[str] = field(default_factory=list)
    connected_at: float = field(default_factory=time.time)
    # Last unsolicited ``state`` frame (or ``get_state`` result), minus "type".
    state: dict[str, Any] | None = None
    pending: dict[str, asyncio.Future[Any]] = field(default_factory=dict)

    def describe(self) -> dict[str, Any]:
        return {
            "device_id": self.device_id,
            "device_name": self.device_name,
            "client": self.client,
            "version": self.version,
            "capabilities": list(self.capabilities),
            "connected_at": self.connected_at,
            "state": dict(self.state) if self.state is not None else None,
        }

    def fail_pending(self, exc: Exception) -> None:
        """Resolve every in-flight command with ``exc`` (no caller waits out
        its full timeout for a socket that is already gone)."""
        pending, self.pending = self.pending, {}
        for fut in pending.values():
            if not fut.done():
                fut.set_exception(exc)


class TvHub:
    """Tracks attached TVs and correlates commands to results."""

    def __init__(self) -> None:
        self._devices: dict[str, TvConnection] = {}

    # -- state ----------------------------------------------------------

    @property
    def connected(self) -> bool:
        return bool(self._devices)

    def devices(self) -> list[TvConnection]:
        return sorted(self._devices.values(), key=lambda c: c.connected_at)

    def status(self) -> dict[str, Any]:
        return {"devices": [c.describe() for c in self.devices()]}

    def resolve(self, device: str | None = None) -> TvConnection:
        """Pick the target TV: by id, else by name (case-insensitive); omitted
        means the only connected TV."""
        conns = self.devices()
        if not conns:
            raise TvNotConnected("no Archie TV app is connected")
        if device:
            if device in self._devices:
                return self._devices[device]
            wanted = device.strip().casefold()
            named = [c for c in conns if c.device_name.casefold() == wanted]
            if len(named) == 1:
                return named[0]
            if len(named) > 1:
                raise TvAmbiguous([f"{c.device_name} ({c.device_id})" for c in named])
            raise TvNotConnected(
                f"no TV {device!r} is connected; connected: "
                + ", ".join(repr(c.device_name) for c in conns)
            )
        if len(conns) > 1:
            raise TvAmbiguous([c.device_name for c in conns])
        return conns[0]

    # -- lifecycle ------------------------------------------------------

    async def attach(self, conn: TvConnection) -> None:
        """Register a connection, displacing a previous one of the same device."""
        old = self._devices.get(conn.device_id)
        self._devices[conn.device_id] = conn
        if old is not None and old is not conn:
            old.fail_pending(TvNotConnected("TV reconnected"))
            try:
                await old.ws.close(code=WS_CLOSE_REPLACED)
            except Exception:
                pass  # Already gone; nothing to salvage.
        logger.info("TV attached: %s (%s) %s %s", conn.device_name, conn.device_id,
                    conn.client, conn.version)

    async def detach(self, conn: TvConnection) -> None:
        """Deregister ``conn`` if it is still the device's active connection."""
        if self._devices.get(conn.device_id) is not conn:
            # Already displaced by a newer connection; its pending commands
            # were failed at replacement time.
            conn.fail_pending(TvNotConnected("TV reconnected"))
            return
        del self._devices[conn.device_id]
        conn.fail_pending(TvNotConnected("TV disconnected"))
        logger.info("TV detached: %s (%s)", conn.device_name, conn.device_id)

    # -- command dispatch -----------------------------------------------

    async def send_command(
        self,
        command: str,
        params: dict[str, Any] | None = None,
        device: str | None = None,
        timeout: float = DEFAULT_COMMAND_TIMEOUT_S,
    ) -> tuple[TvConnection, Any]:
        """Send a command to one TV and await its matching ``result`` frame.

        Returns ``(connection, result)`` so callers can say which TV answered.
        """
        conn = self.resolve(device)

        request_id = uuid.uuid4().hex[:12]
        fut: asyncio.Future[Any] = asyncio.get_running_loop().create_future()
        conn.pending[request_id] = fut

        try:
            await conn.ws.send_text(orjson.dumps({
                "id": request_id,
                "type": "command",
                "command": command,
                "params": params or {},
            }).decode())
        except Exception as exc:
            conn.pending.pop(request_id, None)
            raise TvNotConnected(f"failed to send command: {exc}") from exc

        try:
            result = await asyncio.wait_for(fut, timeout)
        except asyncio.TimeoutError:
            raise TvCommandTimeout(
                f"command {command!r} to {conn.device_name!r} timed out after {timeout}s"
            ) from None
        finally:
            conn.pending.pop(request_id, None)

        if command == "get_state" and isinstance(result, dict):
            conn.state = {k: v for k, v in result.items() if k != "type"}
        return conn, result

    @staticmethod
    def handle_result(conn: TvConnection, msg: dict[str, Any]) -> None:
        """Resolve the connection's pending future for an inbound ``result``."""
        request_id = msg.get("id")
        if not isinstance(request_id, str):
            return
        fut = conn.pending.get(request_id)
        if fut is None or fut.done():
            # Late arrival after a timeout, a duplicate, or an id this
            # connection never issued. Nothing to do.
            return
        if msg.get("ok"):
            fut.set_result(msg.get("result"))
        else:
            fut.set_exception(TvCommandError(str(msg.get("error") or "unknown error")))

    @staticmethod
    def handle_state(conn: TvConnection, msg: dict[str, Any]) -> None:
        """Cache an unsolicited ``state`` frame for ``GET /api/tv/status``."""
        conn.state = {k: v for k, v in msg.items() if k != "type"}


def get_hub(request: Request) -> TvHub:
    return request.app.state.tv_hub


@router.get("/api/tv/status")
async def tv_status(request: Request) -> dict[str, Any]:
    """Connected TVs with their last reported state, so callers can tell
    'no TV' apart from 'command failed'."""
    status = get_hub(request).status()
    status["token_configured"] = _resolve_token() is not None
    return status


@router.post("/api/tv/command")
async def tv_command(request: Request) -> dict[str, Any]:
    """Run one command on a TV and return its result.

    Body: ``{command, params?, device?, timeout?}``; ``device`` is a
    ``device_id`` or a ``device_name``. Loopback only (a request through the
    reverse proxy from the LAN is refused), like ``POST /api/browser/command``.
    """
    offender = is_proxied_from_lan(request)
    if offender:
        logger.warning("Refusing TV command from LAN address %s", offender)
        raise HTTPException(
            status_code=403,
            detail=(
                "TV commands are loopback-only; this request arrived from "
                f"{offender} via the reverse proxy. Run tv_cmd.py on the server."
            ),
        )

    try:
        body = await request.json()
    except Exception:
        raise HTTPException(status_code=400, detail="body must be JSON") from None
    if not isinstance(body, dict):
        raise HTTPException(status_code=400, detail="body must be a JSON object")

    command = body.get("command")
    if not command or not isinstance(command, str):
        raise HTTPException(status_code=400, detail="'command' is required")

    params = body.get("params") or {}
    if not isinstance(params, dict):
        raise HTTPException(status_code=400, detail="'params' must be an object")

    device = body.get("device")
    if device is not None and not isinstance(device, str):
        raise HTTPException(status_code=400, detail="'device' must be a string")

    try:
        timeout = float(body.get("timeout") or DEFAULT_COMMAND_TIMEOUT_S)
    except (TypeError, ValueError):
        raise HTTPException(status_code=400, detail="'timeout' must be a number") from None
    timeout = min(max(timeout, 0.1), MAX_COMMAND_TIMEOUT_S)

    hub = get_hub(request)
    try:
        conn, result = await hub.send_command(command, params, device=device, timeout=timeout)
    except TvNotConnected as e:
        # 503, not 500: the backend is fine, the TV just isn't attached.
        raise HTTPException(status_code=503, detail=f"TV not connected: {e}") from e
    except TvAmbiguous as e:
        raise HTTPException(status_code=409, detail=str(e)) from e
    except TvCommandTimeout as e:
        raise HTTPException(status_code=504, detail=str(e)) from e
    except TvCommandError as e:
        # The command ran and failed (unknown command, bad path) — a client
        # error, distinct from the transport being broken.
        raise HTTPException(status_code=422, detail=str(e)) from e

    return {
        "ok": True,
        "device": {"device_id": conn.device_id, "device_name": conn.device_name},
        "command": command,
        "result": result,
    }


def _short_str(value: Any, limit: int) -> str | None:
    if not isinstance(value, str):
        return None
    value = value.strip()
    return value[:limit] if value else None


@router.websocket("/api/tv/ws")
async def tv_ws(ws: WebSocket):
    await ws.accept()
    hub: TvHub = ws.app.state.tv_hub

    # --- handshake ---------------------------------------------------
    # A client that connects and says nothing would otherwise hold a socket
    # open indefinitely.
    try:
        raw = await asyncio.wait_for(ws.receive_text(), HELLO_TIMEOUT_S)
    except (asyncio.TimeoutError, WebSocketDisconnect):
        await _reject(ws, "hello_timeout")
        return

    try:
        hello = orjson.loads(raw)
    except (orjson.JSONDecodeError, ValueError):
        await _reject(ws, "invalid_json")
        return

    if not isinstance(hello, dict) or hello.get("type") != "hello":
        await _reject(ws, "expected_hello")
        return

    expected = _resolve_token()
    if expected:
        presented = hello.get("token")
        # compare_digest keeps the comparison time-independent of how many
        # leading characters happen to match.
        if not isinstance(presented, str) or not secrets.compare_digest(presented, expected):
            logger.warning("Rejecting TV %r: bad token", hello.get("device_name"))
            await _reject(ws, "unauthorized")
            return

    device_id = _short_str(hello.get("device_id"), _MAX_ID_LEN)
    if device_id is None:
        await _reject(ws, "missing_device_id")
        return
    caps = hello.get("capabilities")
    conn = TvConnection(
        ws=ws,
        device_id=device_id,
        device_name=_short_str(hello.get("device_name"), _MAX_NAME_LEN) or device_id,
        client=_short_str(hello.get("client"), _MAX_NAME_LEN) or "unknown",
        version=_short_str(hello.get("version"), _MAX_NAME_LEN),
        capabilities=[c for c in caps if isinstance(c, str)] if isinstance(caps, list) else [],
    )
    await hub.attach(conn)
    await ws.send_text(orjson.dumps({"type": "ready"}).decode())

    # --- receive loop --------------------------------------------------
    try:
        while True:
            raw = await ws.receive_text()
            try:
                msg = orjson.loads(raw)
            except (orjson.JSONDecodeError, ValueError):
                continue  # Ignore junk rather than dropping the connection.
            if not isinstance(msg, dict):
                continue

            msg_type = msg.get("type")
            if msg_type == "result":
                hub.handle_result(conn, msg)
            elif msg_type == "state":
                hub.handle_state(conn, msg)
            elif msg_type == "ping":
                await ws.send_text(orjson.dumps({"type": "pong"}).decode())
            # "pong" and anything else: no action.
    except WebSocketDisconnect:
        pass
    except Exception:
        logger.exception("TV receive loop failed")
    finally:
        await hub.detach(conn)


async def _reject(ws: WebSocket, reason: str) -> None:
    """Tell the client why, then close with 4401."""
    try:
        await ws.send_text(orjson.dumps({"type": "error", "error": reason}).decode())
    except Exception:
        pass
    try:
        await ws.close(code=WS_CLOSE_UNAUTHORIZED)
    except Exception:
        pass
