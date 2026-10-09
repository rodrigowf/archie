"""Browser-origin guard for the credential routes (`/api/accounts`, `/api/env`, `/api/auth/*` writes).

The API is open to the LAN with `CORS allow_origins=["*"]` and has no login, so any web page open in
a browser on the LAN could otherwise call these routes: a POST with a simple content type needs no
preflight, so `POST /api/env/X/reveal` would hand a secret to an arbitrary site. The guard refuses
a request when:

* `Sec-Fetch-Site: cross-site` (modern browsers send it on every request);
* an `Origin` header is present and is not this server (same host name as the request's `Host`;
  nginx passes `Host $host`, without the port), the web dev servers (ports 5450 / 5451 / 8799 on a
  trusted host — they proxy `/api` here), or listed in `ARCHIE_TRUSTED_ORIGINS` (comma-separated);
* the `Host` is not one a DNS-rebinding page could not fake: IP literals, single-label names,
  `localhost`, `*.local`, `*.lan`, `*.home`, `*.internal`, `*.localhost`, `*.ts.net`, this
  machine's host names, or `ARCHIE_TRUSTED_HOSTS` (comma-separated).

Requests without `Origin` (the Android app, curl, scripts) pass the origin check.
"""

from __future__ import annotations

import ipaddress
import os
import socket
from functools import lru_cache
from urllib.parse import urlsplit

from fastapi import HTTPException, Request

DEV_PORTS = frozenset({5450, 5451, 8799})
_PRIVATE_SUFFIXES = (".local", ".lan", ".home", ".internal", ".localhost", ".ts.net")


@lru_cache(maxsize=1)
def _machine_names() -> frozenset[str]:
    names = {socket.gethostname().lower()}
    try:
        names.add(socket.getfqdn().lower())
    except OSError:
        pass
    return frozenset(n for n in names if n)


def _extra(var: str) -> set[str]:
    return {x.strip().lower().rstrip("/") for x in os.environ.get(var, "").split(",") if x.strip()}


def _hostname(host_header: str) -> str:
    """`Host` value without the port (handles `[v6]:port`)."""
    h = host_header.strip().lower()
    if h.startswith("["):
        return h[1:h.find("]")] if "]" in h else h
    if h.count(":") == 1:
        return h.split(":", 1)[0]
    return h


def host_trusted(hostname: str) -> bool:
    h = hostname.strip().lower().rstrip(".")
    if not h:
        return False
    try:
        ipaddress.ip_address(h)
        return True
    except ValueError:
        pass
    if "." not in h or h == "localhost" or h.endswith(_PRIVATE_SUFFIXES):
        return True
    return h in _machine_names() or h in _extra("ARCHIE_TRUSTED_HOSTS")


def origin_allowed(origin: str, host_header: str) -> bool:
    origin = origin.strip().lower().rstrip("/")
    if origin in _extra("ARCHIE_TRUSTED_ORIGINS"):
        return True
    parts = urlsplit(origin)
    if parts.scheme not in ("http", "https") or not parts.hostname:
        return False  # "null" (sandboxed frames, file://) and anything odd
    if parts.hostname == _hostname(host_header):
        return True
    try:
        port = parts.port
    except ValueError:
        return False
    return port in DEV_PORTS and host_trusted(parts.hostname)


def require_trusted_origin(request: Request) -> None:
    """FastAPI dependency: 403 for cross-site browser requests and untrusted hosts."""
    if request.headers.get("sec-fetch-site", "").lower() == "cross-site":
        raise HTTPException(status_code=403, detail="Cross-site requests are not allowed on this route.")
    host = request.headers.get("host", "")
    if not host_trusted(_hostname(host)):
        raise HTTPException(status_code=403, detail=f"Unexpected Host {host!r}; add it to ARCHIE_TRUSTED_HOSTS if it is yours.")
    origin = request.headers.get("origin")
    if origin is not None and not origin_allowed(origin, host):
        raise HTTPException(status_code=403, detail=f"Requests from {origin} are not allowed on this route; add it to ARCHIE_TRUSTED_ORIGINS if it is yours.")
