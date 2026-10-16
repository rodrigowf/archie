"""Network helpers shared by routes that must tell local callers from LAN ones.

The loopback-only command channels (``POST /api/browser/command``,
``POST /api/tv/command``) use :func:`is_proxied_from_lan` to refuse anything
that reached the backend through the reverse proxy.
"""

from __future__ import annotations

from fastapi import Request

_LOOPBACK_VALUES = ("127.0.0.1", "::1", "localhost")


def is_proxied_from_lan(request: Request) -> str | None:
    """Return the offending client address if the request came from the LAN.

    The Jetson's nginx (``~/nginx-server.conf``) proxies 443 → 127.0.0.1:8765
    and sets ``X-Real-IP`` to the true client address. A request made directly
    to the loopback port carries no such header. So the header's presence — and
    its value — reliably distinguishes "someone on the LAN" from "a process on
    this machine", which app-level ``request.client.host`` cannot do behind a
    proxy (everything looks like 127.0.0.1 there).

    Returns None when the caller is local.
    """
    forwarded = (
        request.headers.get("x-real-ip")
        or (request.headers.get("x-forwarded-for") or "").split(",")[0].strip()
    )
    if not forwarded:
        return None
    if forwarded in _LOOPBACK_VALUES:
        return None
    return forwarded
