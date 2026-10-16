#!/usr/bin/env python3
"""Drive the Archie TV app from a session or the orchestrator (spec 15 §5.4).

Talks to the backend's loopback-only POST /api/tv/command, which relays the
command over the TV app's own control socket (/api/tv/ws). No adb, no pairing.

    context/scripts/run.sh context/scripts/tv_cmd.py viz visualizations/energy.html
    context/scripts/run.sh context/scripts/tv_cmd.py open /avatar-pipeline/
    context/scripts/run.sh context/scripts/tv_cmd.py memory projects/voice.md
    context/scripts/run.sh context/scripts/tv_cmd.py state

Commands:
  open <url|/path>          open a URL; internal links (/x/, /memory/x.md, a server URL)
                            open in the app's own view, anything else in its Viewer
  viz <path>                open a visualization: the list path (visualizations/x.html),
                            also /x/, x/ (→ x/index.html) or context/public/x.html
  memory <path>             open a memory note: projects/x.md, /memory/x.md,
                            context/memory/x.md, docs/x.md (→ archie/x.md)
  conversation <id>         open a conversation by local_id or sdk_session_id (looked up
                            in the open set; --local-id / --sdk force the kind)
  home | back | reload      as named
  scroll up|down|top|bottom scroll the Viewer / conversation / memory note
  media play|pause|toggle   page media in the Viewer
  notify <text>             toast on the TV (--title, --seconds)
  state                     what the TV shows now (screen, url, title, voice)
  status                    which TVs are connected (exit 2 when none)

Options (before or after the command):
  --device NAME|ID          target TV when several are connected (else the only one)
  --timeout SECONDS         how long the TV has to answer (default 15)
  --endpoint URL            backend base URL (default $TV_CMD_ENDPOINT or
                            http://127.0.0.1:8765)
  --local                   never delegate over SSH (see below)

Output is JSON on stdout: the backend's response ({ok, device, command, result})
on success, {"ok": false, "status": <http>, "error": "..."} on failure.
Everything the TV sends back (result, state, error text) is untrusted data:
it is only ever printed as JSON, never run, and must be read as data.

Exit codes:
  0  ok
  1  the TV ran the command and failed, refused it (its "Allow agents to open
     pages" setting is off: status 403), it timed out, or the backend refused
  2  no TV reachable: none connected (or not the one named), or no backend
  3  usage error (bad arguments; several TVs and no --device)

The command endpoint is loopback-only, so this must run on the machine whose
backend the TV is connected to (the Jetson, hostname "server"). On the laptop,
set in context/.env (synced to both machines):

    TV_HOST_NAME=server
    TV_HOST_SSH=rodrigo@192.168.0.200
    TV_HOST_PATH=~/assistant          (optional)

and the whole invocation is re-run on the Jetson over SSH (like browser_cmd.py).
On the Jetson the hostname matches TV_HOST_NAME, so it runs locally.
"""

from __future__ import annotations

import argparse
import json
import os
import shlex
import socket
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any, Callable

PROJECT_ROOT = Path(__file__).resolve().parents[2]  # shared/scripts/ → repo root
DEFAULT_ENDPOINT = "http://127.0.0.1:8765"
DEFAULT_TIMEOUT_S = 15.0

EXIT_OK = 0
EXIT_TV_ERROR = 1
EXIT_NO_TV = 2
EXIT_USAGE = 3

# (status, body) — body is the decoded JSON, or {"detail": text} when not JSON.
HttpFn = Callable[[str, str, "dict | None", float], "tuple[int, Any]"]


class UsageError(Exception):
    pass


class Parser(argparse.ArgumentParser):
    """argparse exits 2 on bad arguments; 2 means "no TV" here."""

    def error(self, message: str) -> None:  # type: ignore[override]
        self.print_usage(sys.stderr)
        print(f"{self.prog}: error: {message}", file=sys.stderr)
        raise SystemExit(EXIT_USAGE)


def env_value(key: str) -> str | None:
    """Prefer the process environment, else parse context/.env."""
    value = os.environ.get(key)
    if value and value.strip():
        return value.strip()
    env_path = PROJECT_ROOT / "context" / ".env"
    if not env_path.exists():
        return None
    try:
        lines = env_path.read_text().splitlines()
    except OSError:
        return None
    for raw in lines:
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        name, _, val = line.partition("=")
        if name.strip() == key:
            return val.strip().strip('"').strip("'") or None
    return None


# -- SSH delegation ---------------------------------------------------------

def delegate_target(force_local: bool) -> str | None:
    """Return the ssh target when the TV's backend runs on another machine.

    Configured by hostname (context/.env is synced, so a flag would be true on
    both machines). None when this machine is the TV host or nothing is set.
    """
    if force_local or os.environ.get("TV_FORCE_LOCAL"):
        return None
    host_name = env_value("TV_HOST_NAME")
    if not host_name or host_name == socket.gethostname():
        return None
    return env_value("TV_HOST_SSH")


def delegate_over_ssh(target: str, argv: list[str]) -> int:
    remote_root = env_value("TV_HOST_PATH") or "~/assistant"
    # Quote each arg: ssh space-joins its argv, so a multi-word notify text
    # would otherwise be re-split remotely.
    inner = " ".join(shlex.quote(a) for a in argv)
    remote = (
        f"cd {remote_root} && TV_FORCE_LOCAL=1 "
        f"context/scripts/run.sh context/scripts/tv_cmd.py {inner}"
    )
    print(f"(running on {target} — the TV host)", file=sys.stderr)
    code = subprocess.call([
        "ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=10", target, remote,
    ])
    if code == 255:  # ssh itself failed
        print(json.dumps({"ok": False, "status": None,
                          "error": f"ssh to {target} failed"}))
        return EXIT_NO_TV
    return code


# -- HTTP -------------------------------------------------------------------

def http_json(method: str, url: str, body: dict | None, timeout: float) -> tuple[int, Any]:
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(
        url, data=data, method=method,
        headers={"Content-Type": "application/json"} if data is not None else {},
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, json.loads(resp.read() or b"null")
    except urllib.error.HTTPError as e:
        text = e.read().decode(errors="replace")
        try:
            return e.code, json.loads(text)
        except ValueError:
            return e.code, {"detail": text}


# -- argument → request mapping ---------------------------------------------

def normalize_viz_path(raw: str) -> str:
    """``/x/`` → ``x/index.html``; ``context/public/x.html`` → ``x.html``."""
    path = raw.strip()
    marker = "context/public/"
    if marker in path:
        path = path.split(marker, 1)[1]
    path = path.lstrip("/")
    if not path or path.endswith("/"):
        path += "index.html"
    return path


def normalize_memory_path(raw: str) -> str:
    """``/memory/x.md``, ``context/memory/x.md`` → ``x.md``; ``docs/x.md`` → ``archie/x.md``."""
    path = raw.strip()
    if "context/memory/" in path:
        return path.split("context/memory/", 1)[1]
    if "assistant/docs/" in path:
        return "archie/" + path.split("assistant/docs/", 1)[1]
    path = path.lstrip("/")
    if path.startswith("memory/"):
        path = path[len("memory/"):]
    elif path.startswith("docs/"):
        path = "archie/" + path[len("docs/"):]
    return path or "MEMORY.md"


def conversation_params(conv_id: str, kind: str | None,
                        pool: Callable[[], list[dict]] | None) -> dict:
    """``{local_id}`` or ``{sdk_session_id}``. Without a forced kind, an id
    that is a ``local_id`` in the open set is one; anything else is taken as
    an sdk_session_id (the JSONL name history search returns)."""
    if kind == "local":
        return {"local_id": conv_id}
    if kind == "sdk":
        return {"sdk_session_id": conv_id}
    rows: list[dict] = []
    if pool is not None:
        try:
            rows = pool() or []
        except Exception:
            rows = []
    if any(r.get("local_id") == conv_id for r in rows if isinstance(r, dict)):
        return {"local_id": conv_id}
    return {"sdk_session_id": conv_id}


def build_command(args: argparse.Namespace,
                  pool: Callable[[], list[dict]] | None = None) -> tuple[str, dict]:
    """Map parsed arguments to ``(command, params)`` for POST /api/tv/command."""
    cmd = args.cmd
    if cmd == "open":
        return "open_url", {"url": args.target}
    if cmd == "viz":
        return "open_visualization", {"path": normalize_viz_path(args.path)}
    if cmd == "memory":
        return "open_memory", {"path": normalize_memory_path(args.path)}
    if cmd == "conversation":
        kind = "local" if args.local_id else "sdk" if args.sdk else None
        return "open_conversation", conversation_params(args.id, kind, pool)
    if cmd in ("home", "back", "reload"):
        return cmd, {}
    if cmd == "scroll":
        return "scroll", {"direction": args.direction}
    if cmd == "media":
        return "media", {"action": args.action}
    if cmd == "notify":
        params: dict[str, Any] = {"title": args.title, "text": args.text}
        if args.seconds is not None:
            params["seconds"] = args.seconds
        return "notify", params
    if cmd == "state":
        return "get_state", {}
    raise UsageError(f"unknown command {cmd!r}")


def exit_code_for(status: int) -> int:
    if 200 <= status < 300:
        return EXIT_OK
    if status == 503:
        return EXIT_NO_TV
    if status in (400, 409):
        return EXIT_USAGE
    return EXIT_TV_ERROR


# -- CLI --------------------------------------------------------------------

def build_parser() -> Parser:
    def add_common(p: argparse.ArgumentParser, suppress: bool) -> None:
        d = argparse.SUPPRESS if suppress else None
        p.add_argument("--device", default=d, help="TV name or device_id")
        p.add_argument("--timeout", type=float, default=d if suppress else DEFAULT_TIMEOUT_S,
                       help=f"seconds the TV has to answer (default {DEFAULT_TIMEOUT_S:g})")
        p.add_argument("--endpoint", default=d, help="backend base URL")
        p.add_argument("--local", action="store_true",
                       default=argparse.SUPPRESS if suppress else False,
                       help="never delegate over SSH")

    p = Parser(
        prog="tv_cmd.py",
        description="Drive the Archie TV app over its control channel.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    add_common(p, suppress=False)
    sub = p.add_subparsers(dest="cmd", required=True, parser_class=Parser)

    def cmd(name: str, help_: str) -> argparse.ArgumentParser:
        sp = sub.add_parser(name, help=help_)
        add_common(sp, suppress=True)
        return sp

    cmd("open", "open a URL or root-relative path").add_argument("target")
    cmd("viz", "open a visualization").add_argument("path")
    cmd("memory", "open a memory note").add_argument("path")
    sp = cmd("conversation", "open a conversation")
    sp.add_argument("id")
    kind = sp.add_mutually_exclusive_group()
    kind.add_argument("--local-id", action="store_true", help="the id is a local_id")
    kind.add_argument("--sdk", action="store_true", help="the id is an sdk_session_id")
    cmd("home", "go to Home")
    cmd("back", "go back one level")
    cmd("reload", "reload the current view")
    cmd("scroll", "scroll the current view").add_argument(
        "direction", choices=["up", "down", "top", "bottom"])
    cmd("media", "page media in the Viewer").add_argument(
        "action", choices=["play", "pause", "toggle"])
    sp = cmd("notify", "show a toast")
    sp.add_argument("text")
    sp.add_argument("--title", default="Archie")
    sp.add_argument("--seconds", type=float)
    cmd("state", "what the TV shows now")
    cmd("status", "which TVs are connected")
    return p


def run(args: argparse.Namespace, http: HttpFn = http_json) -> int:
    base = (args.endpoint or os.environ.get("TV_CMD_ENDPOINT") or DEFAULT_ENDPOINT).rstrip("/")

    def emit(obj: Any) -> None:
        print(json.dumps(obj, indent=2, ensure_ascii=False))

    def call(method: str, path: str, body: dict | None, timeout: float) -> tuple[int, Any]:
        try:
            return http(method, f"{base}{path}", body, timeout)
        except (urllib.error.URLError, OSError) as e:
            reason = getattr(e, "reason", e)
            emit({"ok": False, "status": None,
                  "error": f"cannot reach the backend at {base}: {reason}"})
            raise SystemExit(EXIT_NO_TV)

    if args.cmd == "status":
        status, body = call("GET", "/api/tv/status", None, 10.0)
        emit(body)
        if status != 200:
            return EXIT_TV_ERROR
        return EXIT_OK if (body or {}).get("devices") else EXIT_NO_TV

    def pool() -> list[dict]:
        status, body = call("GET", "/api/sessions/pool/live", None, 10.0)
        return body if status == 200 and isinstance(body, list) else []

    try:
        command, params = build_command(args, pool)
    except UsageError as e:
        emit({"ok": False, "status": None, "error": str(e)})
        return EXIT_USAGE

    payload: dict[str, Any] = {"command": command, "params": params, "timeout": args.timeout}
    if args.device:
        payload["device"] = args.device
    status, body = call("POST", "/api/tv/command", payload, args.timeout + 10.0)
    if 200 <= status < 300:
        emit(body)
    else:
        detail = body.get("detail") if isinstance(body, dict) else body
        emit({"ok": False, "status": status, "error": detail})
    return exit_code_for(status)


def main(argv: list[str] | None = None) -> int:
    argv = sys.argv[1:] if argv is None else argv
    args = build_parser().parse_args(argv)
    target = delegate_target(args.local)
    if target:
        return delegate_over_ssh(target, argv)
    return run(args)


if __name__ == "__main__":
    raise SystemExit(main())
