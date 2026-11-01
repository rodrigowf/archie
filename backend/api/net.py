"""Network helpers shared by routes that must tell local callers from LAN ones.

The loopback-only command channels (``POST /api/browser/command``,
``POST /api/tv/command``) use :func:`is_proxied_from_lan` to refuse anything
that did not come from a process on this machine; ``GET /api/tv/status`` uses
it to decide how much to show, and ``POST /api/transcribe`` uses
:func:`is_from_home_network` to keep its OpenAI calls to the home network.

Note on ``request.client``: uvicorn's default ``--proxy-headers`` (trusting
``FORWARDED_ALLOW_IPS``, 127.0.0.1 by default) replaces the peer with the
``X-Forwarded-For`` client when the TCP peer is the local nginx. So behind
nginx the "peer" may already be a LAN address taken from a header; the checks
below therefore also look at ``X-Real-IP``, which nginx always overwrites.
"""

from __future__ import annotations

import ipaddress

from fastapi import Request

# Headers a reverse proxy adds. nginx on the Jetson (``~/nginx-server.conf``)
# sets ``X-Real-IP $remote_addr`` on both the 443 and the 80 server block.
_PROXY_HEADERS = ("x-real-ip", "x-forwarded-for", "forwarded")

# Networks a home client can come from: private LAN ranges and Tailscale's
# CGNAT range. Loopback is accepted separately.
_HOME_NETWORKS = tuple(ipaddress.ip_network(n) for n in (
    "192.168.0.0/16", "10.0.0.0/8", "172.16.0.0/12", "100.64.0.0/10",
))


def _ip(value: str | None) -> ipaddress.IPv4Address | ipaddress.IPv6Address | None:
    if not value:
        return None
    try:
        ip = ipaddress.ip_address(value.strip())
    except ValueError:
        return None
    if isinstance(ip, ipaddress.IPv6Address) and ip.ipv4_mapped is not None:
        return ip.ipv4_mapped  # "::ffff:127.0.0.1" is 127.0.0.1
    return ip


def is_loopback(value: str | None) -> bool:
    """True for a loopback address (127.0.0.0/8, ::1, v4-mapped) or "localhost"."""
    if value is not None and value.strip() == "localhost":
        return True
    ip = _ip(value)
    return ip is not None and ip.is_loopback


def _peer(request: Request) -> str | None:
    return request.client.host if request.client else None


def real_client_address(request: Request) -> str | None:
    """The address of whoever made the request.

    Behind nginx the TCP peer is always 127.0.0.1 and the true client is in
    ``X-Real-IP`` (set by nginx, overwriting anything the client sent). That
    header is trusted only when the peer is loopback, i.e. the request came
    through the local proxy; from any other peer it is a client-chosen string
    and the peer address wins. ``X-Forwarded-For`` is never trusted: its first
    hop is whatever the client wrote.
    """
    peer = _peer(request)
    if is_loopback(peer):
        real = (request.headers.get("x-real-ip") or "").strip()
        if real:
            return real
    return peer


def is_proxied_from_lan(request: Request) -> str | None:
    """Return the offending client address unless the caller is on this machine.

    A request counts as local only when the TCP peer is loopback **and**
    either no proxy header is present (a process on this machine talking to
    the port directly) or ``X-Real-IP`` is loopback (nginx relaying a local
    caller). Everything else is the LAN: a direct LAN client of a backend
    listening on 0.0.0.0 (the laptop's dev backend), a proxied LAN client,
    and a proxied request that carries ``X-Forwarded-For`` without
    ``X-Real-IP`` (nginx always sets the latter; the former's first hop is
    spoofable, so it never makes a request local).

    Returns None when the caller is local.
    """
    peer = _peer(request)
    if not is_loopback(peer):
        return peer or "unknown"
    real = (request.headers.get("x-real-ip") or "").strip()
    if real:
        return None if is_loopback(real) else real
    if any(request.headers.get(h) for h in _PROXY_HEADERS):
        forwarded = (request.headers.get("x-forwarded-for") or "").split(",")[0].strip()
        return forwarded or "unknown (proxied)"
    return None


def is_home_network(value: str | None) -> bool:
    """True for loopback and the private LAN / Tailscale ranges."""
    if is_loopback(value):
        return True
    ip = _ip(value)
    return ip is not None and any(ip in net for net in _HOME_NETWORKS)


def is_from_home_network(request: Request) -> bool:
    """True when the caller is on this machine, the LAN or Tailscale.

    Both the peer and, when present, ``X-Real-IP`` must be home addresses:
    behind nginx ``X-Real-IP`` is the true client (and the peer may have been
    rewritten from a client-written ``X-Forwarded-For``); without nginx a
    client-written ``X-Real-IP`` can only make the answer stricter.
    """
    if not is_home_network(_peer(request)):
        return False
    real = (request.headers.get("x-real-ip") or "").strip()
    return not real or is_home_network(real)
