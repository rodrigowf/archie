## 1. Transport, hosting and auth

### 1.1 Process / network topology

| Layer | Production (Jetson, 192.168.0.200) | Dev (laptop) |
|---|---|---|
| App server | `uvicorn api.app:create_app --factory --host 127.0.0.1 --port 8765` (systemd unit `agentic-backend.service`, not in repo) | `context/scripts/run.sh -m uvicorn api.app:create_app --factory --host 0.0.0.0 --port 8765` (CLAUDE.md) |
| TLS / public port | **nginx** (custom config `~/nginx-server.conf` on the Jetson, not in repo) terminates TLS on **:443** with a self-signed cert and also serves plain HTTP on **:80**; both `proxy_pass http://127.0.0.1:8765` | Vite dev server on `:5432` (`frontend/vite.config.ts:15-18`), HTTPS only if `context/certs/{key,cert}.pem` exist (`frontend/vite.config.ts:6-7,19-21`); proxies `/api` (incl. WS, `ws: true`) to `http://localhost:8765` (`frontend/vite.config.ts:22-27`) |
| WebSocket upgrade | nginx sets `Upgrade`/`Connection` via a `map`, `proxy_http_version 1.1`, `proxy_buffering off`, `proxy_read/send/connect_timeout 86400` | Vite proxy |

nginx facts (read from the Jetson's `~/nginx-server.conf`): `listen 443 ssl; server_name 192.168.0.200 server.local; ssl_protocols TLSv1.2 TLSv1.3;` and an identical `listen 80` block. `X-Real-IP` is forwarded. **No `client_max_body_size` is set**, so nginx's default of **1 MiB** applies to every request body (see §8, G-1).

- **Base URL convention.** Clients address the backend by origin only (`https://<host>` or `http://<host>`); every API path starts with `/api/…`. The web client derives the WS origin from `location` (`frontend/src/api/websocket.ts:14-15`: `wss:` when the page is `https:`). The Android client stores a configurable base URL and strips/rewrites `ws(s)://` ↔ `http(s)://` and trailing `/api/orchestrator[/chat]` / `/api/sessions/chat` segments (`android/app/src/main/java/com/assistant/peripheral/network/ApiClient.kt:41-52`).
- **HTTPS.** Self-signed certificate. Browsers need a one-time trust exception; Android must use a trust-all / pinned trust manager. Microphone (`getUserMedia`) and WebRTC require a secure context in browsers, so the web app must be loaded over `https://` (or `localhost`).
- **Frame encoding (all WebSockets).** **Every server→client frame is a *binary* frame** containing UTF-8 JSON (`ws.send_bytes(orjson.dumps(...))` — `api/pool.py:1362`, `api/pool.py:564`, `api/routes/chat.py:141` etc., `api/routes/orchestrator.py:98`). **LIVE**-verified: frames arrive as `bytes`. Client→server frames **must be text** frames: both handlers call `ws.receive_text()` (`api/routes/chat.py:137`, `api/routes/orchestrator.py:132`); a binary client frame raises inside the handler and the socket is dropped without a close frame (**LIVE**: `ConnectionClosedError no close frame`). ⚠ GOTCHA G-2.
- **Heartbeats.** The server sends no application-level pings. On the orchestrator WS, client frames `{"type":"ping"}` / `{"type":"pong"}` are accepted and silently ignored (`api/routes/orchestrator.py:147-148`). On the chat WS, `ping` is **not** recognised and returns `error: unknown_type` (`api/routes/chat.py:254-258`). Rely on WebSocket-protocol pings (OkHttp `pingInterval`, browser built-in) for keep-alive. nginx allows 24 h idle.

### 1.2 CORS

`CORSMiddleware(allow_origins=["*"], allow_methods=["*"], allow_headers=["*"])` (`api/app.py:154-159`). No credentials mode is configured (`allow_credentials` defaults to False), which is fine because **the API uses no cookies**.

### 1.3 Authentication — there is none for clients

- **There is no user/client authentication on any REST or WebSocket endpoint** except the browser-extension channel (§3.13). No tokens, cookies or headers are required. The trust model is "anyone on the LAN / tailnet". `GET /api/config/openai-key` even hands out the raw OpenAI key (`api/routes/config.py:304-322`).
- `/api/auth/*` (`api/routes/auth.py`) is about whether **the backend's bundled Claude Code CLI** has valid Anthropic OAuth credentials — not about the client. The web `AuthGate` (`frontend/src/components/AuthGate.tsx:15-19`) calls `GET /api/auth/status` on mount and, if `authenticated` is false, shows a screen to trigger `POST /api/auth/login` (non-headless only) or paste a `.credentials.json` into `POST /api/auth/credentials`. Endpoint details in §3.1.
- Headless detection: `HEADLESS=1|true|yes` env, or no `DISPLAY` on non-Windows (`api/app.py:46-49`). The Jetson is headless (**LIVE**: `{"authenticated":true,"auth_url":null,"headless":true}`).

### 1.4 Static file serving (non-`/api` paths)

Registration order matters — earlier routes win (`api/app.py:161-302`). All `/api/*` routers are registered first (`api/app.py:161-174`).

| URL | Serves | Code | Notes |
|---|---|---|---|
| `/compat`, `/compat/` | `frontend-compat/dist/index.html` with `Cache-Control: no-cache, no-store, must-revalidate` | `api/app.py:183-190` | Only if `frontend-compat/dist` exists at startup |
| `/compat/assets/*` | `frontend-compat/dist/assets` (StaticFiles) | `api/app.py:185` | Hashed, cacheable |
| `/compat/{path}` | file under `frontend-compat/dist` if it exists, else compat `index.html` (no-cache) | `api/app.py:192-197` | SPA fallback |
| `/projects/{path}` | file under repo `projects/` (traversal-guarded) else 404 | `api/app.py:212-225` | Only if `projects/` exists at startup |
| `/uploads/{path}` | file under `context/uploads/` (traversal-guarded, resolved per request) else 404 | `api/app.py:231-242` | Target of `POST /api/uploads` URLs |
| `/memory`, `/memory/` | `context/memory/MEMORY.md` (`Content-Type: text/markdown; charset=utf-8`, **LIVE**) | `api/app.py:251-257` | Only if `context/memory` exists at startup |
| `/memory/{path}` | raw file under `context/memory/` (traversal-guarded) else 404; **no directory listings** | `api/app.py:259-271` | Has `etag` + `last-modified` (Starlette `FileResponse`, **LIVE**) |
| `/assets/*` | `frontend/dist/assets` | `api/app.py:276` | Only if `frontend/dist` exists |
| `/` | `frontend/dist/index.html` (no-cache) | `api/app.py:279-281` | |
| `/{path}` (catch-all) | 1) file under `context/public/{path}` → 2) file under `frontend/dist/{path}` → 3) `frontend/dist/index.html` (no-cache) | `api/app.py:283-302` | **Visualizations are served by this catch-all.** |

- ⚠ GOTCHA: unknown paths return **200 + `index.html`**, never 404 (SPA fallback, `api/app.py:301-302`). A client probing whether a visualization URL exists must check `Content-Type`/body, not status.
- ⚠ GOTCHA: `context/public/` (visualizations, photo-server, downloads) is only served **inside the `if frontend_dist.exists()` block** (`api/app.py:275-302`). A backend without a built `frontend/dist` serves no visualizations at all.
- ⚠ GOTCHA: none of these routes answer `HEAD` (FastAPI `@app.get` only) → `HEAD` returns `405` JSON. Use `GET` (or `GET` with `Range`) for existence checks.
- **PWA files** (`/manifest.json`, `/sw.js`, `/icon-*.png`, `/icon.svg`, `/pcm-capture-worklet.js`) are **owned by the web frontend build** (`frontend/public/` → `frontend/dist/`), served through the catch-all. The backend has no manifest/service-worker logic of its own. Current `sw.js` only does network-first for navigations with an offline fallback to cached `/index.html` (`frontend/public/sw.js:24-32`).

---

