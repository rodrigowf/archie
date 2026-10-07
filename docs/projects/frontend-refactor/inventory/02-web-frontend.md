# 02 — Web Frontend Inventory (current state, reference for the rebuild)

**Status:** Inventory of `frontend/` (React 19 + Vite, main web app) and `frontend-compat/` (React 18 legacy build served at `/compat/`) on branch `frontend-refactory`, read on 2026-10-03.
**Purpose:** This is the feature checklist and acceptance criteria for the from-scratch Material Design 3 rebuild. Every behaviour listed here must survive the rebuild unless it is listed as a bug in §6. Load-bearing workarounds are marked **[LOAD-BEARING]**.

## 0. Conventions

| Prefix | Means |
|---|---|
| `fe/…` | `frontend/src/…` |
| `fe-root/…` | `frontend/…` (index.html, vite.config.ts, public/) |
| `fc/…` | `frontend-compat/…` |
| no prefix (`api/…`, `manager/…`) | backend, relative to the repo root |

`file:line` citations refer to the code as of this branch. "WS" means WebSocket. "Chat WS" is `/api/sessions/chat`; "Orchestrator WS" is `/api/orchestrator/chat`.

**Size and shape of the current app:** about 11k lines of TS/TSX plus a single 3781-line `fe/App.css` and a 123-line `fe/index.css`. There is no router, no global store library, and no i18n. State lives in one reducer context (`TabsContext`), one reducer per chat tab (`useChatInstance`), and local component state.

---

## 1. Build, bootstrap and app shell

### 1.1 Tech stack and build

- Dependencies (`fe-root/package.json:12-22`): react/react-dom 19.2, react-markdown 10, remark-gfm 4, react-syntax-highlighter 16 (Prism + `oneDark`), diff 8, react-icons 5 (Material `Md*` icons), @tanstack/react-virtual 3.13 (**declared but never imported**, see §6.4).
- Build: `tsc -b && vite build` (`fe-root/package.json:7`). Output goes to `frontend/dist`, which the backend serves at `/`. The SPA catch-all also serves `context/public/*` (visualizations) before it falls back to `index.html` (`api/app.py:274-302`).
- Dev server (`fe-root/vite.config.ts:14-28`): port **5432** (strict), host `0.0.0.0`, HTTPS when `context/certs/key.pem` + `cert.pem` exist (lines 6-7, 18-20). HTTPS is required for mic/WebRTC on phones over the LAN. `/api` is proxied to `http://localhost:8765` with `ws: true`.
- The main build has **no** legacy plugin. The CLAUDE.md claim of "Legacy browser support with @vitejs/plugin-legacy" in the main frontend is stale: only `frontend-compat` uses it.

### 1.2 Bootstrap (`fe-root/index.html`, `fe/main.tsx`)

1. **Remote console logging** is an inline script that runs before the app (`fe-root/index.html:5-26`). See F-38.
2. Viewport is `width=device-width, initial-scale=1.0, viewport-fit=cover` (line 27). `viewport-fit=cover` is required for the `env(safe-area-inset-bottom)` paddings.
3. PWA metadata (lines 30-39), see §1.10.
4. Service worker registration happens after the app script (lines 44-48).
5. `fe/main.tsx:6-11`: **low-end device detection**. If `navigator.hardwareConcurrency <= 2` or `navigator.deviceMemory <= 1`, it adds class `low-end` to `<html>`, and `fe/index.css:110-115` then disables all animations and transitions. `prefers-reduced-motion: reduce` has the same effect (`fe/index.css:118-123`). **[LOAD-BEARING]** for the A300M / iPad mini 2 class of devices.
6. The app renders inside `<StrictMode>` (`fe/main.tsx:13-17`). Effects double-invoke in dev, and the code tolerates this.

### 1.3 Provider tree

`App` = `AuthGate` → `TabsProvider` → `AppContent` (`fe/App.tsx:224-232`). Nothing renders until auth passes (§1.11).

### 1.4 Layout

```
#root (flex row, 100% height, overflow hidden)       fe/index.css:93-95
├── Sidebar (aside, 280px; drawer on ≤768px)          fe/components/Sidebar.tsx
│   └── backdrop div when open (mobile)
└── main.main-content
    ├── .topbar-row
    │   ├── hamburger .sidebar-toggle-btn (mobile only) fe/App.tsx:181-185
    │   └── TabBar                                     fe/App.tsx:186
    ├── ChatPanelContainer (all tab panels)           fe/App.tsx:188
    └── ConfigPage overlay (lazy, Suspense)           fe/App.tsx:190-192
OrchestratorModal / ConfirmModal(delete) / BusyOverlay  fe/App.tsx:194-219
```

- `ConfigPage` is `React.lazy` (`fe/App.tsx:11`). `SessionConfigPage` is also lazy (`fe/components/ChatPanelContainer.tsx:19`).
- Config "floats over everything" so chat instances stay mounted (`fe/App.tsx:189`).
- While the mobile sidebar is open, `body.style.overflow = hidden` (`fe/App.tsx:153-157`).

### 1.5 Tab system (`fe/context/TabsContext.tsx`)

The tab is the primary navigation unit. **Tabs are not persisted.** A page reload restores only the sessions that are still live in the backend pool (F-23). History tabs, visualization tabs and memory tabs are lost.

**TabState** (`fe/types.ts:292-308`): `sessionId` (stable local id; this is the tab key), `resumeSdkId?`, `title`, `status: SessionStatus`, `connectionState`, `isOrchestrator?`, `vizPath?` + `vizUrl?` (visualization tab), `memoryPath?` (memory tab).

**Tab kinds:**

| Kind | Key (`sessionId`) | Backing | Created by |
|---|---|---|---|
| Chat | random UUID (`local_id`) | `useChatInstance` + Chat WS | New session, sidebar history click, pool sync, `agent_session_opened`, rewind/fork, termination recovery |
| Orchestrator | random UUID | `useChatInstance` on Orchestrator WS + `useVoiceOrchestrator` | New orchestrator button, sidebar click on an orchestrator session, pool sync |
| Visualization | `viz:<path>` (`TabsContext.tsx:33-35`) | iframe | Sidebar Visuals list |
| Memory | `memory:<path>` (`TabsContext.tsx:38-40`) | fetched markdown | Sidebar Memory tree |

`isDocTab(tab)` is true for viz and memory tabs (`TabsContext.tsx:47-49`). Doc tabs must be skipped wherever a chat instance or pool close would happen.

**Reducer actions** (`TabsContext.tsx:14-20`, `51-148`):
- `OPEN_TAB`: if the id is already open, switch to it. Otherwise append `{status:"connecting", connectionState:"disconnected"}` and **make it active** (lines 53-70). Every open steals focus, including background pool syncs (§6.2).
- `OPEN_VIZ_TAB` / `OPEN_MEMORY_TAB`: dedupe by namespaced id. These open as `status:"idle", connectionState:"connected"` so status-derived UI treats them as quiet (lines 72-113).
- `CLOSE_TAB`: if the closed tab was active, activate the right neighbour, else the left one, else none (lines 115-131).
- `SWITCH_TAB` (ignores unknown ids) and `UPDATE_TAB` (status / connectionState / title / resumeSdkId only).
- Helpers: `isTabOpen`, `hasActiveOrchestrator` (any orchestrator tab exists), `findTabByResumeId(sdkId)` (`TabsContext.tsx:203-216`).
- `getTabStatusIcon` (`TabsContext.tsx:249-263`) returns `null` for doc tabs and for tabs whose connection is not `connected`. It returns `"active"` for streaming/thinking/tool_use and `"idle"` otherwise. The comment says that non-connected tabs are "shown via tab opacity", but no such styling exists (§6.2).

### 1.6 Tab bar (`fe/components/TabBar.tsx`)

- Renders nothing when there are no tabs (line 93). The hamburger still shows on mobile.
- **Orchestrator tabs are always sorted first** (lines 96-100).
- **Tab title is derived, not stored** (lines 45-50): `sessions[]` is matched by `resumeSdkId`, then by `local_id`, and the tab's own placeholder title is the fallback. **[LOAD-BEARING]** (commit e3bfc89): keeps the tab and sidebar titles in sync and replaces the "Agent xxxxxxxx" placeholder after the first turn.
- Status dot: `.tab-status.active|idle` (line 120, CSS `fe/App.css:65-85`).
- **Rename by double-clicking the title** (lines 135-148). Not allowed for orchestrator or doc tabs. The tooltip "Double-click to rename" shows only on the active tab. Enter or blur commits, Escape cancels (lines 122-133). Commit goes through `useSessions.renameSession(resumeSdkId, title)`. It silently does nothing when the tab has no `resumeSdkId` yet, which is before the first turn (lines 57-70).
- **Close (×)**: for streaming/thinking/tool_use tabs it first shows `ConfirmCloseModal` ("Session is running … Closing it will interrupt the current response.", `fe/components/ConfirmCloseModal.tsx`). Close then calls `closeTab()` followed by `POST /api/sessions/{local_id}/close` (non-doc tabs only; errors ignored) (lines 74-91).
- CSS (`fe/App.css:19-130`): the bar is `--topbar-height` (52px; 48px at ≤640px) and scrolls horizontally with a 2px scrollbar. A tab is 7px/16px padded, min 88px and max 210px wide (56-140px on ≤768px). The close button is 18×18 and shows only on hover on desktop (`.tab:hover .tab-close`, line 130). On ≤768px it is 24×24 and always visible (`fe/App.css:2865-2866`). See §6.1: this bar is known to be too small.

### 1.7 Sidebar (`fe/components/Sidebar.tsx`)

- Header title follows the section: "Sessions" / "Memory" / "Visualizations" (lines 109-112).
- Header actions (lines 113-144):
  - Sessions section: **New orchestrator** (sun-like icon) and **New session** ("+").
  - Memory and Visuals sections: a **Refresh** button that refetches the tree or list. The backend has no watcher.
- A **"Configuration"** button opens the global ConfigPage and closes the mobile drawer (lines 147-153; `fe/App.tsx:175`).
- **Section switcher**: three `role="tab"` buttons placed directly above the list, each with a count badge (lines 156-184):
  - Sessions: count is `sessions.length`.
  - Memory: count is `countFiles(tree)` (lines 274-279).
  - Visuals: count is `visualizations.length`.
- The section is local component state. It is not persisted and resets to Sessions on reload.
- The list body depends on the section: sessions (F-19), memory tree (F-37) or visualizations (F-36). Each has an empty-state message (lines 209-211, 226-231, 251-257).
- **Delete overlay**: while a delete is in flight, the list dims, ignores pointer events, and shows a "Deleting…" spinner (lines 187, 261-266; CSS `fe/App.css:240-260`).
- Every item selection calls `onClose()`, which closes the mobile drawer.
- Width is 280px (`--sidebar-width`, 260px at ≤900px).

### 1.8 Status bar (`fe/components/StatusBar.tsx`)

The status bar sits above the input (commit adde1dd).

- Left side: a status dot and label from `formatStatus` (lines 35-46). The labels are "Connecting...", "Processing", "Streaming", "Thinking", "Using tool", "Interrupted", "Disconnected", and "Ready" as the **default for idle or any unknown value**.
- Centre: the raw `connectionState` string. This is hidden on ≤768px (`fe/App.css:2897`).
- Right side: "N turn(s) · $cost(4dp)", shown only when `turns > 0` (lines 23-29).
- Dot colours: `fe/App.css:2189-2201`. `streaming` and `thinking` pulse. `processing` and `connecting` have no style of their own.

### 1.9 Responsive behaviour

| Breakpoint | Effect | Source |
|---|---|---|
| `hover: none` | Message ⋮ menu always visible | `fe/App.css:773-775` |
| ≤900px | sidebar 260px; message, input and status padding 20px | `fe/App.css:3570-3591` |
| ≤768px | sidebar becomes an off-canvas drawer (`left:-280px` → `0`) with a 50% backdrop; hamburger shown; session rows replace hover icons with the ⋮ kebab; tab sizing changes; textarea font 16px (prevents iOS zoom); `env(safe-area-inset-bottom)` padding on input and voice bar; voice buttons 44-48px tall; tool `pre` max-height 150px; status centre hidden | `fe/App.css:2825-2905` |
| ≤640px | `--topbar-height:48px`; sidebar uses `transform` instead; messages 0.92rem; **textarea font 0.92rem, which overrides the 16px iOS-zoom fix** (§6.2); `.sidebar-overlay` rule that is dead | `fe/App.css:3593-3650` |

The layout is single-column at every width. There is no split view or tablet-specific layout. The sidebar is either pinned (>768px) or a drawer.

### 1.10 PWA

- Manifest (`fe-root/public/manifest.json`): id/start_url/scope `/`, name and short_name "Assistant", `display: standalone`, background and theme colour `#0d0d0f`, **orientation `portrait`**, icons 192 and 512 png plus a 512 maskable and an svg.
- `index.html` meta: `theme-color`, `mobile-web-app-capable`, `apple-mobile-web-app-status-bar-style: black-translucent`, `apple-mobile-web-app-title`, `apple-touch-icon` → `/icon-192.png` (`fe-root/index.html:30-39`).
- Service worker (`fe-root/public/sw.js`): precaches `/` and `/index.html` into `assistant-v1`, `skipWaiting` + `clients.claim`, and deletes old caches on activate. Navigation requests go **network-first with an `/index.html` fallback**. Everything else is network-only. The worker exists for installability. There is no real offline mode.
- `fe-root/public/pcm-capture-worklet.js` is the AudioWorklet for voice (F-26). It must be served at the root path `/pcm-capture-worklet.js`.

### 1.11 Auth gate (`fe/components/AuthGate.tsx`)

- On mount it calls `GET /api/auth/status` → `{authenticated, auth_url?, headless}`. On a fetch failure it treats the user as unauthenticated and not headless (lines 15-19).
- While the check is pending it shows "Checking authentication..." (lines 21-29).
- **Normal mode, unauthenticated:** a "Sign in with Claude" button calls `POST /api/auth/login`, plus an "Or paste credentials manually" link (lines 109-142).
- **Headless mode, or the manual view:** shows instructions to copy `~/.claude/.credentials.json`, an optional `auth_url` link ("Claude Console"), a 6-row JSON textarea, and **Set Credentials** (`POST /api/auth/credentials {credentials_json}`). Errors read "Invalid credentials…" or "Failed to set credentials…". A Back button appears only when not headless (lines 31-107).
- Once authenticated, the children render. There is no re-check later and no logout.

### 1.12 Theme

- **Dark only.** There is no light theme, no `prefers-color-scheme` handling and no theme switch. The rebuild should add M3 light/dark, but dark must remain the default to match `theme-color #0d0d0f`.
- Tokens live in `fe/index.css:3-89`:
  - Surfaces: `--bg #0d0d0f`, `--bg-surface`, `--bg-elevated`, `--bg-hover`, `--bg-input`.
  - Borders and text tones.
  - Accent `hsl(220 38% 66%)`.
  - Semantic `--error`, `--success`, `--status-pending`.
  - **Per-tool-category colours**: read, write, execute, script, navigate, capture, interact, todo, task, system, agent and search, each with an `-bg` tint. These drive the tool-block left accent (F-05).
  - Radii 8/14/20 px, sidebar width, topbar height, and transitions.
- Font: system stack at 15px, line-height 1.55. Scrollbars are 4px.
- Code blocks use Prism `oneDark`.

---

## 2. Features

Each feature lists its UI, client state, REST and WS traffic, and its edge cases and workarounds. Feature ids F-01…F-40 are referenced from §6.

### F-01 Chat tab lifecycle (`fe/hooks/useChatInstance.ts`, `fe/components/ChatPanelContainer.tsx`)

**Mounting model [LOAD-BEARING]:**
- For every non-doc tab, a headless `TabInstance` component holds one `useChatInstance` (`ChatPanelContainer.tsx:26-96`, `357-371`). It publishes the instance into a `Map` ref and forces a container re-render on every `messages` or `hasMoreMessages` change (lines 78-93).
- **Every tab's panel stays mounted**. Inactive panels are hidden with `display:none` (`ChatPanelContainer.tsx:373-379`) so that hooks, WebSockets, voice WebRTC, scroll position and the textarea draft survive tab switches. The rebuild must keep all open sessions live in the background, not unmount them.
- It syncs `status` and `connectionState` to the tab via `updateTab` (`ChatPanelContainer.tsx:51-56`; `useChatInstance.ts:886-893`). When the first `turn_complete` carries an SDK `session_id`, it stores `resumeSdkId` on the tab (`ChatPanelContainer.tsx:58-63`; `useChatInstance.ts:752-756`).

**Init sequence** (effect keyed on `localId` only; `useChatInstance.ts:898-921`):
1. Dispatch `RESET` and reset pagination.
2. `reloadHistoryFromRest()` calls `GET /api/sessions/{resumeSdkId}/messages?limit=50` → `LOAD_HISTORY`, then sets `hasMore` and `start_index` (lines 645-659). This is skipped when there is no `resumeSdkId` or when `skipHistory` is set (orchestrator tab without a resume id, `ChatPanelContainer.tsx:71`). Errors are swallowed.
3. Set `pendingStartRef = {resumeSdkId}`, then `setWsActive(true)`, which opens the WS.
4. **`resumeSdkId` is deliberately excluded from the deps** (lines 895-897, 920). It is set after the fact on the first turn, and re-running the effect would RESET the live messages. **[LOAD-BEARING]**

**`start` handshake, sent on every WS open** (`handleOpen`, lines 852-880). **[LOAD-BEARING]**: it is re-sent on every reconnect and every visibility resume because otherwise the socket is open but not subscribed (commit 5bde0d1).
```json
{"type":"start","local_id":"<tab id>","resume_sdk_id":"<sdk id, if known>",
 "mcp_servers":{...only on MCP restart},"resume_from":{"stream_id":"…","seq":N}}
```

**WS transport** (`fe/api/websocket.ts`, `fe/hooks/useWebSocket.ts`):
- The URL is built from `location` (`ws:`/`wss:`) (`websocket.ts:12-16`) with `binaryType = arraybuffer`. Binary frames are decoded as UTF-8 JSON (lines 21-37). Malformed frames are ignored.
- `onclose` synthesises `{type:"status",status:"disconnected"}`. `onerror` synthesises `{type:"error",error:"websocket_error"}` (lines 39-45). Both flow into the chat reducer (§6.2: the raw string "websocket_error" ends up in the error banner).
- `send()` silently drops the message unless the socket is `OPEN` (lines 48-52).
- **Reconnect**: every 2000ms, up to **10 attempts**, **never while `document.hidden`** (`useWebSocket.ts:6-8`, `60-78`).
- On `visibilitychange` to visible: if the socket is OPEN, it re-sends `start` without reconnecting. Otherwise it resets attempts and connects immediately (`useWebSocket.ts:112-125`). **[LOAD-BEARING]** for mobile and background tabs.
- `connectionState`: `connecting` → `connected` (on open; attempts reset) → `disconnected`/`error`.

**Server events handled by the chat tab** (`useChatInstance.ts:663-850`). These are covered in detail in §4.
- `session_started`: `session_id`, `context_window` or `model_info.context_window`, `resume_state{stream_id,next_seq}`, `replay_overflow`, `voice_session_update` and `voice_initiator`.
- Streaming and turn events: `text_delta`, `text_complete`, `thinking_delta`, `thinking_complete`, `tool_use{tool_use_id,tool_name,tool_input}`, `tool_result{tool_use_id,output,is_error}`, `turn_complete{cost,num_turns,session_id,input_tokens|usage.input_tokens}` and `compact_complete{summary}`.
- Status and lifecycle events: `session_stalled{elapsed_seconds,last_tool_name,last_tool_use_id}`, `permission_request{request_id,tool_name,tool_input}`, `permission_resolved{request_id,decision,responder,message}`, `status{status}`, `error{error,detail}`, `session_terminated{reason,detail,sdk_session_id}` and `session_stopped`.
- Pool events: `agent_session_opened{session_id,sdk_session_id}`, `agent_session_closed{session_id}` and `user_message{text,source?,queued?}`.
- Voice events: `voice_command{command}`, plus `voice_event`, `voice_ending`, `voice_ended`, `voice_stopped` and `voice_owner_active`, which are forwarded to the passive voice handler.
- **Ignored** (no case): `tool_executing`, `tool_progress`, `nested_session_event`, `voice_vad_state` (on the text WS), `voice_audio_out` (text WS), `ping` and `pong`. `tool_executing` and `tool_progress` are handled only by the dead `useChat.ts`.

**Client to server messages on the Chat WS** (backend `api/routes/chat.py:148-253`):

| Type | Payload | Sent by |
|---|---|---|
| `start` | see above | `handleOpen`, `restart()` (lines 955-962), `restartWithMcps` (dead) |
| `send` | `{text}` | `send()` (lines 923-929). **Backend: if a permission is pending, the text resolves it as `deny` with the text as the reason, then the prompt is queued if a turn is running (`send_or_queue`)** (`api/routes/chat.py:153-189`) |
| `interrupt` | — | Stop button, stall banner (lines 947-949). Backend replies `status: interrupted` |
| `compact` | — | Compact button (lines 1070-1073). Optimistic `status: streaming` |
| `permission_response` | `{request_id, decision:"allow"\|"deny", message?}` | PermissionBar (lines 1075-1091) |
| `stop` | — | Orchestrator voice `onBeforeStart` (F-26), `restartWithMcps` (dead). On the chat route it only unsubscribes this WS |
| `command` | `{text}` | **No UI calls it** (dead slash-command path, `useChatInstance.ts:940-945`) |

### F-02 Streaming message rendering (`fe/components/Message.tsx`, `MessageList.tsx`)

- A message is `{id, role, blocks[]}` (`fe/types.ts:250-270`). Block kinds are `text`, `thinking`, `compact` and `tool_use`, the last carrying a merged result. See §4 for how events become blocks.
- **User messages** render as a right-aligned bubble with `white-space: pre-wrap` and no markdown (`Message.tsx:17-34`; CSS `fe/App.css:958-980`). Max width is 72% (85-88% on mobile).
- **Assistant messages** are full-width prose. Every `text` block goes through `<Markdown>` (`Message.tsx:53-59`).
- Messages are centred in a 900px column with 32px side padding (20 at ≤900px, 12-14 on mobile) (`fe/App.css:945-956`). `content-visibility: auto; contain-intrinsic-size: auto 120px` is the perf substitute for virtualization.
- A message whose blocks include a `compact` block renders **only** a `CompactDivider` (`Message.tsx:40-43`).
- Streaming blocks have no cursor or caret indicator. Thinking shows "...", and tool blocks show a `MdMoreHoriz` icon until complete.

### F-03 Markdown, links and code highlighting (`fe/components/Markdown.tsx`)

- `react-markdown` + `remark-gfm` give tables, strikethrough, task lists and autolinks (line 14).
- **All links open in a new tab** with `target="_blank" rel="noopener noreferrer"` and class `md-link` (lines 16-28, commit e6f2f53). The compat build relies on `.md-link` CSS for iPad tap targets.
- Fenced code with a language becomes a `CodeBlock` (lines 50-80):
  - Header shows the language label and a **Copy** button (`navigator.clipboard.writeText`). The label reads "Copied" for 2 seconds.
  - Body is Prism `oneDark` at 0.85rem.
- Code without a language, including fenced code that has no language tag, renders as `<code class="inline-code">`. Fenced code without a language therefore gets no block styling (§6.2).
- No HTML passthrough, no math, no mermaid, and no images beyond the markdown default.

### F-04 Thinking blocks (`fe/components/ThinkingBlock.tsx`)

- A collapsible block. It **starts expanded if mounted while streaming** (`useState(streaming)`, line 9). When streaming ends it stays in whatever state it was in. Blocks loaded from history start collapsed. History never contains thinking blocks anyway (§4.3).
- The toggle shows "..." plus "Thinking" while streaming, and "Thought" afterwards, with a +/− indicator (lines 13-24). The content is plain text, not markdown (line 26).
- Styled soft grey (commit 28e030d; `fe/App.css:1098+`).

### F-05 Tool-use blocks (`fe/components/ToolUseBlock.tsx`)

**Name normalisation** (lines 58-93): Qwen snake_case names map to Claude names, for example `read_file`→`Read`, `run_shell_command`→`Bash`, `agent`→`Task`, `todo_write`→`TodoWrite` and `exit_plan_mode`→`ExitPlanMode`. `normalizeToolInput` is currently an identity function.

> Note: the orchestrator's own `read_file` / `write_file` tools collide with Qwen's `read_file` / `write_file` mapping. Both now render as `Read` / `Write`, so the orchestrator-specific `read_file` (`input.path`) case at lines 275-278 and 868-882 is **unreachable**. The `Read` renderer needs `file_path`, so orchestrator `read_file` falls back to generic JSON (§6.3).

**Category → accent colour** (`getToolCategory`, lines 101-150):

| Category | Tools |
|---|---|
| read | Read, Glob, Grep, WebFetch, WebSearch, chrome-devtools list/get console/network, list_pages, emulate |
| write | Write, Edit, NotebookEdit |
| todo | TodoWrite |
| task | Task |
| execute | Bash, Skill, EnterPlanMode, ExitPlanMode |
| interact | AskUserQuestion |
| agent | list/open/close/read/send_to/interrupt_agent_session, list_history |
| search | search_history, search_memory |
| navigate | chrome-devtools navigate/click/hover/drag/fill/fill_form/press_key/handle_dialog/new/close/select/resize_page/wait_for |
| capture | chrome-devtools take_screenshot/take_snapshot/performance_* |
| script | chrome-devtools evaluate_script |
| system | everything else |

**Collapsed summary text** (`formatToolSummary`, lines 209-350) is the one-line header:

| Tool | Summary |
|---|---|
| Read, Write, Edit | "<Verb> …/<last-2-segments>" (`formatFilePath` shortens paths deeper than 3 segments, lines 156-163) |
| Bash | `description`, else the first 60 chars of the command |
| Glob | "Glob <pattern>" |
| Grep | `Grep "<pattern>"` |
| WebFetch | "Fetch <url>" |
| WebSearch | `Search "<query>"` |
| Task | "Task: <description>" |
| TodoWrite | "Update todos" |
| AskUserQuestion | "Ask user" |
| Skill | "/<skill>" |
| EnterPlanMode, ExitPlanMode | "Enter plan mode", "Exit plan mode" |
| NotebookEdit | "Edit notebook <path>" |
| list_agent_sessions | "List active sessions" |
| open_agent_session | "Open agent session", or "Resume session" with `resume_sdk_id` |
| close, read and interrupt agent session tools | "<Verb> session <id8>" |
| send_to_agent_session | first 60 chars of the message |
| list_history | "List session history" |
| search_history, search_memory | `Search history/memory "<q>"` |
| chrome-devtools | about 25 specific phrasings ("Navigate to <url>", "Reload page", "Double click", "Press <key>", "Resize to W×H", "Select page #N", "Accept/Dismiss dialog", …), otherwise the action name with `_` replaced by spaces |
| Generic `mcp__server__tool` | the tool part with `_` replaced by spaces |
| Anything else | the raw name |

**Icons** (`getToolIcon`, lines 356-450):
- Not complete: `MdMoreHoriz` (the pending indicator).
- Error: `MdError`.
- Otherwise a per-tool Material icon: Read=Description, Write=Code, Edit=Edit, Bash=Terminal, Glob/Grep/WebSearch=Search, WebFetch=Language, Task=SmartToy, TodoWrite=Checklist, AskUserQuestion=HelpOutline, Skill=AutoAwesome, plan mode=EditNote, NotebookEdit=Book. Agent tools map to List / OpenInNew / Close / Visibility / Send / Stop / History. Chrome tools map to Navigation / TouchApp / Keyboard / CameraAlt / ContentCopy / Bolt / List / NetworkCheck / Speed. The default is `MdBuild`.

**Status pill**: when `complete`, shows "done" with `MdCheckCircle` or "error" with `MdError`. Nothing shows while running.

**Renderer per tool**, dispatched at lines 1146-1214:

| Tool | Block style | Content |
|---|---|---|
| `Task` | **Always expanded**, non-collapsible header (`TaskBlock`, 905-937) | Task description, Agent (`subagent_type`), Prompt (pre). Output in `<details open={len<2000}>` with "(N chars)" when over 500 |
| `TodoWrite` | **Always expanded** (`TodoWriteBlock`, 947-993) | Checklist. `pending` shows an empty indicator. `in_progress` shows `activeForm` (falling back to content) in bright bold. `completed` gets a check, 55% opacity and strikethrough. "No todos" when the list is empty. **The result is not shown** |
| `Bash` | Collapsible (`BashBlock`, 1000-1088) | Header: description line plus the command syntax-highlighted. Collapsed: single-line ellipsis, or up to **5 lines** with "... (N more lines)". Expanded: full wrapped command plus Output `<details open>`. **When expanded with no result yet, nothing shows below the header** |
| `send_to_agent_session` | Collapsible (`SendToAgentBlock`, 1091-1140) | Header "session <id8>" plus the message preview. Body: Session id, Message (pre), Output (`open` if under 500 chars) |
| All others | Collapsible generic (lines 1172-1213), **collapsed by default** | Input `<details open>` from `renderToolInput`, Output `<details open={len<500}>` |

**`renderToolInput`** (lines 756-899):
- **Read**: File plus a Range line ("lines a–b", "from line a", "first N lines").
- **Grep**: Pattern, Path, Glob, and Options (output_mode unless `files_with_matches`, "line numbers" for `-n`, "±N context" for `context` or `-C`).
- **Write** (and orchestrator `write_file`): File plus highlighted content. The language comes from the extension map (lines 165-198). Max height 400px.
- **Edit**: File, "Replace all occurrences" when `replace_all` is set, and a **unified diff** (F-06).
- **Bash**: Description plus the highlighted command.
- **Task**: see above.
- **send_to_agent_session**, **read_agent_session**: Session and "Max N messages".
- **search_history / search_memory**: Query and Max.
- **chrome evaluate_script**: highlighted JS (max 200px) and Args uids.
- **Default**: pretty-printed JSON in a `pre.generic-json`.

Output is always a plain `<pre>` with no highlighting, no ANSI handling and no image rendering. Error results use `.result-error`.

### F-06 Edit diffs

`EditInputView` (`ToolUseBlock.tsx:485-529`) uses `Diff.diffLines(old,new)` to produce add, remove and context lines, with markers `+ - ' '` and a trailing empty split line dropped. There are no line numbers and no hunk folding. Colours are at `fe/App.css:1367-1402`. This is the only diff UI. `Write` shows full content, not a diff.

### F-07 Long user message folding (`Message.tsx:15-34`)

When the content has **more than 25 newline-separated lines**, it renders collapsed to about 10 lines with a fade mask (`fe/App.css:982-991`) and a "Show all (N lines)" / "Show less" toggle. A single long line with no newlines never folds.

### F-08 Message actions menu (`fe/components/MessageActionsMenu.tsx`)

- A ⋮ button in the top-right of **every** non-compact message, user and assistant, in chat and orchestrator tabs (`Message.tsx:47-52`). It appears on hover (opacity 0→1) and is always visible on touch (`fe/App.css:700-775`).
- The dropdown is **portaled to `document.body` with `position:fixed`**. It is repositioned on scroll (capture) and resize so it never detaches from the button (lines 18-39). It closes on an outside mousedown or Escape (lines 41-58).
- Items: **"Rewind conversation to here"** and **"Fork conversation from here"**. Each passes `dropLastN = displayedMessages.length - 1 - index`, the number of messages below the clicked one (`MessageList.tsx:180-195`). The count is bottom-relative so it survives pagination. **[LOAD-BEARING]** contract with `api/routes/sessions.py:331-385`.

### F-09 Rewind and fork (`ChatPanelContainer.tsx:232-292`, `463-490`)

- Both require the tab's `resumeSdkId`. Without it the request is **silently ignored** (lines 242-256, §6.2).
- A `ConfirmModal` asks first:
  - **Rewind** (destructive): "All messages after the selected one will be removed… The current tab will be closed; reopen the conversation from the sidebar…". The copy is wrong, because the code reopens the tab automatically.
  - **Fork**: "A copy of this conversation will be created, truncated to the selected message. The original is unchanged."
- On confirm, `onMutationBusy("Rewinding…" / "Forking…")` shows the app-wide `BusyOverlay` (F-12).
- **Rewind** [LOAD-BEARING order]:
  1. `POST /api/sessions/{local_id}/close`, ignoring errors. The backend rejects truncation with 409 while the session is open.
  2. `closeTab`.
  3. `POST /api/sessions/{sdk}/truncate {drop_last_n}`.
  4. `openTab(newUUID, "Rewound conversation", isOrchestrator, sdk)`.
- **Fork**: `POST /api/sessions/{sdk}/fork {drop_last_n}` → `{session_id}`, then `openTab(newUUID, "Forked conversation", isOrchestrator, newSdk)`.
- The orchestrator flag is preserved on both paths (commit 458ffe6). Both call `onSessionChange()` to refresh the sidebar. Failure shows `alert("Rewind failed: …")`.

### F-10 Compaction and context usage

- **Compact button** in the input row (`ChatInput.tsx:95-125`):
  - Label: `NN%` of context used, or `?` when usage is 0 or unknown.
  - Colour classes: `--caution` from 50% and `--warning` from 80%.
  - Tooltip: "Compact conversation (N% context used)".
  - Disabled while recording or streaming.
  - Clicking sends `{type:"compact"}` and optimistically sets status to `streaming`.
- **Usage %** = `min(100, round(contextTokens / window * 100))` (`useChatInstance.ts:1093-1096`):
  - `contextTokens` is the latest `turn_complete.input_tokens`, or `usage.input_tokens`.
  - `window` is `session_started.context_window` (chat) or `model_info.context_window` (orchestrator), with a fallback of **200,000** (lines 24-28; commit 26dba48).
- `compact_complete{summary}` appends a standalone assistant message holding a single `compact` block (`useChatInstance.ts:349-361`). `contextTokens` is **not** reset; the next `turn_complete` corrects it.
- **CompactDivider** (`fe/components/CompactDivider.tsx`): a full-width line with a "⟳ Context compacted" button. When a summary exists, ▼/▲ expands it as plain text.

### F-11 Pagination and scroll management (`fe/components/MessageList.tsx`)

- **There is no virtualization.** Every loaded message is in the DOM. `content-visibility:auto` (`fe/App.css:946-947`) limits the paint cost.
- **Load more**:
  - Trigger: `scrollTop <= 80px` while `hasMore`, not already loading (`LOAD_MORE_THRESHOLD`, lines 17, 92-100).
  - The current count of `.message` DOM nodes is recorded as an anchor.
  - The next page comes from `GET /api/sessions/{sdk}/messages?limit=50&before={start_index}` and is prepended (`useChatInstance.ts:1055-1068`).
  - After render, the view scrolls to `firstOldMsg.offsetTop - 16` (lines 132-151). `overflow-anchor: none` on the list disables browser anchoring (`fe/App.css:891-901`). **[LOAD-BEARING]**
  - While more history exists, the list shows a "Scroll up for older messages" hint at the top.
- **Auto-scroll**: "near bottom" means within 150px (line 16). The list snaps to the bottom when the message count changes or the last message's block count changes, but only while near the bottom (lines 132-160).
  - Gap: auto-scroll is keyed on block **count**, so growth of the text inside the last block does not trigger it. The list keeps up during long streamed text only because the browser keeps the scroll pinned at the bottom (§6.2).
- **Freeze buffer** [LOAD-BEARING, commit 4624a5b]: when the user scrolls away from the bottom, `displayedMessages` stops updating. New messages are buffered, not rendered, so the viewport does not move while reading. Returning near the bottom, or pressing the button, flushes the buffer (lines 36-90). Prepends always sync immediately (lines 48-52).
- **Scroll-to-bottom button**: a round "↓" button, centred 16px above the input, shown whenever the user is not near the bottom. Clicking it flushes the buffer and snaps (lines 202-210; CSS `fe/App.css:863-889`).
- **Tab activation**: on hidden→visible, scroll to the bottom if the user was near it, otherwise re-run the scroll handler (lines 117-129). Panels are `display:none` while inactive.
- Empty state: "Start a conversation / Send a message to begin" (lines 167-171).

### F-12 Busy overlay (`fe/components/BusyOverlay.tsx`)

A whole-viewport dim with a spinner and label (`role=status`, `aria-busy`) that blocks input during duplicate ("Duplicating…"), rewind and fork (`fe/App.tsx:216-219`). It stacks above modals (`fe/App.css:2525-2540`).

### F-13 Stall banner (`ChatPanel.tsx:165-180`)

Shown when `stall` is set **and** status is busy (streaming, thinking, tool_use or processing). The text is "<tool> has been running for <t> with no response.", or "No response from Claude for <t>." when the tool is unknown. `t` is `Ns` under 90 seconds, otherwise `XmYs` (lines 9-14). An **Interrupt** button sends `interrupt`.

- Set by `session_stalled` (backend first fires at 120s of silence, then every 60s).
- Cleared by `tool_use`, `tool_result` and `turn_complete` (`useChatInstance.ts:312, 327, 342`).
- Advisory only. It does not abort anything.

### F-14 Permission bar (`fe/components/PermissionBar.tsx`)

This is an inline bar above the status bar, **not a modal** (commit 7bab248). It replaced the `PermissionModal` that CLAUDE.md still mentions.

- **ExitPlanMode**: label "Exit plan mode and start implementing?", hint "Approve to execute the plan above, reject or type a message to keep planning.". The plan text from `tool_input.plan` is appended to the conversation as an assistant text block when the request arrives (`useChatInstance.ts:382-397`).
- **Other gated tools**: "Allow <tool>?" with the hint "Approve, reject, or type a message to send feedback."
- Buttons **Reject** (`deny`) and **Approve** (`allow`) call `respondToPermission`. It clears the ref optimistically so the first click wins, and sends `permission_response` (`useChatInstance.ts:1075-1091`).
- **Typing a message** while a request is pending is the third path: the backend turns the text into a deny with that text as the reason (`api/routes/chat.py:158-172`).
- `permission_resolved` closes the bar **only if `request_id` matches**, so a stale resolve cannot close a newer request. It always appends a synthetic **user** message `[User|Orchestrator approved|rejected the request — <message>]` (`useChatInstance.ts:402-426`). The orchestrator can answer the same request and the first write wins.
- `role="region" aria-label="Permission request"`.

### F-15 Error banner and termination banner (`ChatPanel.tsx:145-164`)

- **Error banner**: plain red text showing `error.detail || error.error`. It persists until the next `session_started`, with **no dismiss** (`useChatInstance.ts:229, 428-429`).
- **Termination banner** (`role=alert`), driven by `session_terminated`:
  - Headline by reason (lines 21-34): subprocess_crashed → "This session crashed", subprocess_lost → "…ended unexpectedly", unreachable → "The host is unreachable", replaced → "…was replaced", closed_by_user → "…was closed". The detail is appended after a colon.
  - **"Continue in new tab"** shows when `sdk_session_id` is present and the tab is a **chat tab** (orchestrator tabs get no recovery handler). It opens `openTab(newUUID, title, false, sdk)` (`ChatPanelContainer.tsx:425-430`).
  - Status becomes `disconnected`. **Messages are left untouched**, so the optimistic prompt stays visible. The resume checkpoint is cleared (`useChatInstance.ts:431-442, 793-820`; commit 3a7fed8).
  - The `session_stopped` that follows also closes nothing for chat tabs (see below).
- **`session_stopped`** sets status to `disconnected` and **closes the chat tab** (`onSessionClosed` → `closeTab`) unless an MCP restart is in progress (`useChatInstance.ts:821-827`; `ChatPanelContainer.tsx:332-337, 369`). For terminated sessions, `session_stopped` follows `session_terminated`, so **the tab closes right after the banner appears**. This contradicts the "we DO NOT auto-close" comment at lines 797-804. See §6.3, and verify on a real crash.

### F-16 Chat input (`fe/components/ChatInput.tsx`)

- An **uncontrolled** `<textarea rows=1>` that auto-grows to 200px max (lines 70-74). The draft persists across tab switches because panels stay mounted, but it is lost on reload. Placeholder: "Send a message..." or "Waiting for response..." while streaming.
- **Keyboard**: Enter sends the trimmed text if non-empty, then clears and resets the height. Shift+Enter inserts a newline (lines 76-91). **Enter also sends while streaming**: the backend queues the prompt (`send_or_queue`) and other subscribers get `user_message{queued:true}`.
- **Buttons**, left to right (the rebuild may reorder them):
  - Compact (F-10). Present when `onCompact` is set, which is always.
  - **Interrupt** (red square), replacing **Send** (paper plane) while streaming.
  - VoiceRecordButton (F-17), only when `supportsAudio && onSendAudio`, which is orchestrator only.
  - VoiceButton (F-27), orchestrator only.
  - **Session config gear** (F-18), chat tabs only.
- All icon buttons are 48×48 (commit adde1dd).
- **Disabled states**:
  - The textarea is disabled when status is `disconnected` or `connecting`, and while recording.
  - The Send button is never disabled for connection reasons, only while recording.
- **The whole input bar is hidden while voice is active** (`ChatPanel.tsx:195-219`).
- The voice error text renders inline after the input when `voiceStatus==="error"` (lines 215-217).
- **There are no attachments or uploads, no slash-command palette and no autocomplete.** The backend has `POST /api/uploads` and an `inject_text` WS message (used by Android share and upload); the web has neither. The `command` WS message exists in the hook but is never used.

### F-17 Audio message recording (`fe/components/VoiceRecordButton.tsx`, `fe/hooks/useAudioRecorder.ts`)

- Shown only on **orchestrator** tabs, and only when `GET /api/orchestrator/models` returns at least one `audio_capable_models` entry. The check is whether **any** model is audio capable, not the selected one (`ChatPanelContainer.tsx:294-300, 346`).
- States:
  - **idle**: mic button; disabled when the input is disabled or a turn is streaming.
  - **recording**: stop square, `m:ss` timer and a cancel ×.
  - **processing**: spinner.
- Capture: `getUserMedia` with echoCancellation, noiseSuppression and sampleRate 16000. MediaRecorder MIME type is the first supported of webm;opus, webm, ogg;opus and mp4. Recording auto-stops at **60s**. On stop the blob is base64-encoded and the format is derived (webm, ogg, mp4 or wav) (`useAudioRecorder.ts:113-198, 71-111`).
- Cancel discards the recording. Errors are only logged: "Microphone access denied", "No microphone found" (`ChatInput.tsx:59-61`).
- Send: an optimistic user message `"[voice message]"` plus `{type:"send_audio", audio, format, text?}` on the Orchestrator WS (`useChatInstance.ts:931-938`; backend `api/routes/orchestrator.py:219-232`).

### F-18 Session config gear → SessionConfigPage

See F-32. Opened per tab from the gear (`ChatPanelContainer.tsx:349, 442, 492-512`). Not available on orchestrator tabs.

### F-19 Session list (sidebar)

Data: `GET /api/sessions` → `SessionInfo[]` with `session_id` (SDK id), `started_at`, `last_activity`, `title`, `message_count`, `is_orchestrator`, `provider`, and `local_id` when the session is live in the pool (`fe/types.ts:3-14`; `api/routes/sessions.py:37-115`).
- **When it refreshes**: on mount; on every `turn_complete` of any tab, together with the visualization list (`fe/App.tsx:39-42`); after mutations; and on pool pushes (F-23). Fetch errors set the list to `[]`.

**SessionItem** (`fe/components/SessionItem.tsx`):
- Title (or "Untitled") with a live-tab indicator dot when a tab is open: active or idle colour (lines 56-73).
- Meta row:
  - `orch` badge for orchestrator sessions.
  - Provider letter badge `C`/`Q`/`G` (else the first letter) with a tooltip naming the CLI. Hidden for orchestrator sessions (lines 74-93, 136-152).
  - Relative time: "just now", "Nm ago", "Nh ago" or "Nd ago" (lines 154-163).
  - "N msgs".
- Row states:
  - `active`: the active tab is this session, matched by `resumeSdkId` or by `local_id` (`Sidebar.tsx:192-194`).
  - `tab-open`: open in some tab but not active. The open set comes from tabs matched by `resumeSdkId` or by `local_id`→sdk via the session list (`Sidebar.tsx:81-103`).
- Hover-revealed icon buttons on desktop: **rename** (pencil), **duplicate**, **delete** (×). On ≤768px these are replaced by a ⋮ **SessionMenu** with "Edit title", "Duplicate" and a red "Delete" (`fe/components/SessionMenu.tsx`; CSS `fe/App.css:2844-2855`). The menu closes on an outside mousedown or Escape.
- **Inline rename**: the input is pre-filled and selected. Enter or blur commits when the value is non-empty and changed; Escape cancels (lines 22-46). It calls `PATCH /api/sessions/{sdk}/rename {title}` and optimistically updates the list. A 404 is tolerated (`fe/api/rest.ts:29-36`; `fe/hooks/useSessions.ts:51-56`).
- **Duplicate**: `POST /api/sessions/{sdk}/duplicate` → `{session_id}`, with the BusyOverlay "Duplicating…", then a list refresh. **The duplicate is not opened** (`useSessions.ts:58-68`).
- **Delete**: a ConfirmModal "Delete conversation? <title> will be moved to trash … recoverable manually from context/trash/" (destructive). Then `DELETE /api/sessions/{sdk}` (404 tolerated) with the list overlay, the item is removed, and `closeTab(sdk)` runs (`fe/App.tsx:73-89, 200-215`). See §6.3: the tab is keyed by `local_id`, so it usually stays open.

**Click / select** (`Sidebar.tsx:44-75`):
1. If a tab already maps to this session, switch to it. The lookup order is `local_id` → `findTabByResumeId(sdk)` → a tab whose id equals sdk.
2. Otherwise, for an **orchestrator** session: if **any** orchestrator tab is open, switch to it (even a different conversation). Otherwise call `onSelectOrchestrator` (F-25).
3. Otherwise open a new chat tab `openTab(newUUID, title, false, sdk)`, which loads history and resumes.
4. Close the mobile drawer.

Ordering comes from the backend. The client does not sort, filter or search, and does not group by date. **There is no search box** in the session list.

### F-20 New session

The "+" button calls `openTab(generateUUID(), "New session")` (`fe/App.tsx:67-70`). `generateUUID` falls back to `crypto.getRandomValues` and then `Math.random` on non-secure contexts (plain-HTTP LAN) (`fe/utils/uuid.ts`). **[LOAD-BEARING]**: `crypto.randomUUID` is unavailable over HTTP.

The backend creates the session on `start`, using the global provider, working directory and MCPs. History loading is skipped because there is no `resumeSdkId`. The SDK id arrives on the first `turn_complete`, after which the sidebar can match the tab and rename, rewind, fork and session config become possible.

### F-21 Close tab

See §1.6. Closing a chat tab also closes the pool session on the backend (`POST /api/sessions/{local_id}/close`). The Chat WS `stop` message only detaches; it does not close.

### F-22 Rename

Two entry points: tab double-click (§1.6) and the sidebar (F-19). Both go through `useSessions.renameSession` → `PATCH /api/sessions/{sdk}/rename`. Titles are stored server-side in `context/.titles.json`.

### F-23 Pool reconnect and live cross-client sync (`fe/hooks/useReconnectPoolSessions.ts`)

- `syncPoolSessions()` calls `GET /api/sessions/pool/live` → `[{local_id, sdk_session_id, status, cost, turns, title, is_orchestrator}]` (`fe/api/rest.ts:103-115`). For every `local_id` that is not already open, it calls `openTab(local_id, title || "Orchestrator"/"Session", is_orchestrator, sdk_session_id)` (lines 30-47). The backend then re-subscribes the new WS to the existing pool entry. A browser refresh therefore re-attaches to running sessions.
- Runs on mount and on every `visibilitychange` to visible (lines 49-64).
- **Pool pushes**: only an **orchestrator tab's** WS is registered as a pool watcher (`api/routes/orchestrator.py:127-128`).
  - `agent_session_opened{session_id, sdk_session_id}` → `openTab(session_id, knownTitle || "Agent <id8>", false, sdk)`, then `onPoolChanged()`, which refreshes sessions and visualizations and re-syncs the pool (`ChatPanelContainer.tsx:302-320`; `fe/App.tsx:30-34`).
  - `agent_session_closed{session_id}` → `closeTab(session_id)` plus `onPoolChanged()` (`ChatPanelContainer.tsx:322-330`).
  - Sessions opened by the orchestrator, or by another device, therefore pop up as tabs **and take focus**. Without an orchestrator tab open there are no live pushes, only visibility-triggered syncs.

### F-24 Reconnect replay protocol (`fe/utils/checkpoint.ts`)

- Every server event carrying both `seq` (number) and `stream_id` (non-empty string) updates a per-tab checkpoint in **sessionStorage**, key `ws-resume-checkpoint:<local_id>` (lines 30, 129-142).
- The checkpoint only moves forward within one `stream_id`. A different `stream_id` always overwrites (lines 77-99). All accessors are no-ops when storage is unavailable.
- `session_started.resume_state{stream_id,next_seq}` seeds the checkpoint at `next_seq-1` (`useChatInstance.ts:682-690`).
- `start` carries `resume_from` = the checkpoint (`useChatInstance.ts:874-877`). The backend replays newer events in order after `session_started`, or sets `replay_overflow:true`. On overflow the client clears the checkpoint and refetches history over REST (`useChatInstance.ts:679-681`; backend `api/routes/chat.py:379-421`).
- `session_terminated` clears the checkpoint.
- sessionStorage is chosen deliberately: it survives F5, is per browser tab and needs no cleanup. The rebuild must keep the per-tab scope.

### F-25 Orchestrator tab

- **Open new**: sidebar button → `handleNewOrchestrator`. If any orchestrator tab exists, show **OrchestratorModal** ("Orchestrator already active … Starting a new one will stop the current session." with **Stop & start new** / Cancel). Proceed closes all orchestrator tabs and opens `openTab(newUUID, "Orchestrator", true)` (`fe/App.tsx:91-142`; `fe/components/OrchestratorModal.tsx`).
- **Resume**: `openTab(newUUID, title, true, sdkId)`. The modal path for resume is effectively unreachable from the sidebar (F-19 step 2).
- **Only one orchestrator** at a time on the client. The tab is always sorted first.
- **Differences from a chat tab**:
  - WS endpoint `/api/orchestrator/chat` (`ChatPanelContainer.tsx:365`).
  - `skipHistory` when new.
  - The context window comes from `model_info`.
  - Pool watcher callbacks are active.
  - `onSessionClosed` is not wired, so `session_stopped` does not close the tab.
  - No session config gear.
  - No termination recovery button.
  - Audio record button.
  - Voice (F-26).
  - Rewind and fork keep the orchestrator flag.
  - Tab rename is disabled.
- Orchestrator WS message types used by the web: `start`, `send`, `send_audio`, `compact`, `interrupt`, `stop`, `voice_start`, `voice_stop`, `voice_event`, `voice_audio_in` and `voice_recording_chunk`/`_end` (backend `api/routes/orchestrator.py:150-360`). **Unused** by the web: `inject_text`, `set_model`, `get_model`, `get_models`, `voice_recording_end` semantics, and `permission_response` for nested sessions.
- **Not rendered**: `nested_session_event`, the mirror of agent-session events including their permission requests. The web orchestrator tab cannot answer an agent session's permission from the orchestrator view. Answering happens in the agent's own tab.

### F-26 Voice mode (`fe/hooks/useVoiceOrchestrator.ts`, `fe/voice/**`)

Voice runs on orchestrator tabs only. Each orchestrator panel mounts a `useVoiceOrchestrator` (`ChatPanelContainer.tsx:103-200`).

**`VoiceStatus`** (`fe/types.ts:238-246`): `off | connecting | active | speaking | thinking | tool_use | ending | error`.

**Start (`startVoice`, lines 689-825)**. Allowed only from `off` or `error`.
1. Set `isLocalVoice=true`, clear the remote flag, errors and VAD state, and set status `connecting`.
2. `onBeforeStart` → `instance.stop()` sends `{type:"stop"}` on the orchestrator **text** WS. On the orchestrator route this stops the whole orchestrator session (`api/routes/orchestrator.py:339-353`). This looks contrary to the "voice is a re-armable mode" design (commit 25a87a2). Verify before replicating (§6.3).
3. Open a **second, dedicated Orchestrator WS** (`new ChatSocket(...)`). This socket has no auto-reconnect. On open it sends `{type:"voice_start", local_id, resume_sdk_id?}`.
4. **Poll every 50ms, for up to 30s**, until `session_started.voice_connection_info` arrives. Timeout gives "Voice session did not start (no connection_info from server)". **[LOAD-BEARING]**: the Jetson summarizer can take 10-20s.
5. Branch on `connection_type`:
   - **`webrtc`** (OpenAI):
     - `POST /api/orchestrator/voice/session` → `{client_secret.value, connection_info.endpoint}` (`fe/api/voice.ts:33-40`).
     - `connectWebRTCVoiceSession` (`fe/voice/transports/webrtc.ts`):
       - Hidden `<audio autoplay>`.
       - `getUserMedia({echoCancellation, noiseSuppression, sampleRate: info.audio_in_format.sample_rate})`.
       - Data channel `"oai-events"`.
       - SDP offer `POST <callUrl>` with `Authorization: Bearer <ephemeral>` and `Content-Type: application/sdp`. The fallback URL is `https://api.openai.com/v1/realtime/calls?model=gpt-realtime` (`fe/api/voice.ts:46-65`).
     - `dc.onopen` sets status `active` and **flushes the queued provider commands** (lines 753-758).
     - **Every data-channel event is mirrored to the backend** as `{type:"voice_event", event}`, which is how JSONL persistence works for WebRTC (lines 451-454).
     - WebRTC audio-level analysis polls the mic and remote-stream RMS every 66ms (lines 466-506).
     - When `voice_recording_enabled`, a `VoiceRecorder` starts (F-30).
   - **`websocket`** (Qwen, Gemini Live):
     - `connectWebSocketVoiceSession` (`fe/voice/transports/websocket.ts`): `getUserMedia` → `AudioContext` → `audioWorklet.addModule("/pcm-capture-worklet.js")` → `AudioWorkletNode("pcm-capture", {targetSampleRate: in_rate, chunkMs:100})`.
     - The worklet linearly resamples to PCM16 LE and posts an ArrayBuffer every 100ms (`fe-root/public/pcm-capture-worklet.js`). The main thread base64-encodes it in 4096-byte steps and sends `{type:"voice_audio_in", audio}`.
     - The capture graph routes to a gain-0 sink so the worklet runs without feedback.
     - Playback: `voice_audio_out{audio}` → `PCMPlayer.push` decodes PCM16 to Float32 and schedules **gapless** playback on a running `nextStartTime` cursor (`fe/voice/audio/pcmPlayer.ts:60-87`).
     - `flush()` stops every scheduled source for barge-in.
     - Speaker level comes from the player's analyser at about 15fps. Mic level comes from a separate analyser.
     - Queued provider events are drained as `voice_event`, then status becomes `active`.

**Command routing.** `sendProviderEvent` (lines 206-222) sends to the data channel for WebRTC when it is open, and **queues** otherwise. For the WS transport it mirrors `voice_event` to the backend. Without any transport yet, it queues.

**[LOAD-BEARING]**: `session_started.voice_session_update`, which carries the system prompt, tools and transcription settings, arrives **before** the transport exists. Dropping it leaves OpenAI on defaults (lines 196-205). It is forwarded only when `voice_initiator !== false` and the connection type is webrtc (lines 550-571). `voice_command{command}` is forwarded only for webrtc (lines 573-579).

**Provider event normalisation (`handleProviderEvent`, lines 261-448)**:

*Status and errors:*

| Event | Effect |
|---|---|
| `voice_status{status:"preparing"\|"ready"}` (backend-synthesised) | `connecting` / `active` |
| `error{error:{code,message}}` | `session_expired` → "Voice session expired — please restart". Then cleanup → `error` → `onAfterStop` |

*Response lifecycle:*

| Event | Effect |
|---|---|
| `response.created` | `responseInFlight=true`, `speaking` |
| `response.done` | in-flight false, `active`, `onTurnComplete` (refresh list) |
| `response.output_item.added` with a function_call | `tool_use` |
| `response.function_call_arguments.done{call_id,name,arguments}` | `thinking` plus `onToolUse(callId,name,JSON.parse(args) or {})` → `dispatchToolUse` adds a tool block. **The result is never dispatched** (`dispatchToolResult` is unused), so voice tool blocks stay pending forever (§6.3) |

*Speech and transcripts:*

| Event | Effect |
|---|---|
| `input_audio_buffer.speech_started` | `active`. For the WS transport: `flushAudioOut()` (barge-in), and **send `response.cancel` only if a response is in flight**. **[LOAD-BEARING]**: DashScope 400s and closes the upstream on a stray cancel (lines 155-161, 389-410) |
| `input_audio_buffer.speech_stopped` | `thinking` |
| `conversation.item.input_audio_transcription.completed{transcript}` | user transcript → `addDisplayMessage("user", text)` |
| `conversation.item.created` with a user item containing `input_text` | user transcript |
| `response.output_audio_transcript.delta`, `response.audio_transcript.delta` or `response.text.delta` | assistant delta → `VOICE_ASSISTANT_DELTA` |
| `response.output_audio_transcript.done`, `response.audio_transcript.done` or `response.text.done` | `VOICE_ASSISTANT_COMPLETE` (GA and legacy names; commit 8eb6dd3) |

*Gemini Live (no top-level `type`)*, all under `serverContent`:

| Event | Effect |
|---|---|
| `inputTranscription.text` | **accumulated** in `pendingUserTranscriptRef` [LOAD-BEARING, commit ea96ce2: otherwise one bubble per word] |
| `outputTranscription.text` or `modelTurn.parts[].text` | flush the pending user transcript, then assistant delta |
| `interrupted` | `flushAudioOut`, `active` |
| `turnComplete` | flush user, `active`, `onAssistantComplete("")` (finalises without overwriting), `onTurnComplete` |
| `toolCall.functionCalls[]` | `tool_use` plus `onToolUse(id,name,args)` |

**Server events on the voice WS (`handleServerEvent`, lines 529-671)**:
- `ping` and `pong` are ignored (15s heartbeat).
- `session_started` (above). If it carries `voice_connection_error`, set `error`.
- `voice_event` (WS-transport provider events).
- `voice_audio_out`.
- `voice_ending`: status `ending` and a **5s safety timeout** that forces `off`.
- `voice_ended` and the legacy `voice_stopped`: cleanup, `off`, `onAfterStop` → `instance.restart()` re-sends `start` on the text WS.
- `error{error,detail}`: error and cleanup.
- `voice_vad_state{state,duration_ms}` (F-27).
- `voice_error{error: VoiceErrorEnvelope}`: shows `message`. When `recoverable:false`, cleanup and `error`. When recoverable, the transport stays up and waits for a new `session_started` (lines 641-660). The typed envelope (`category`, `recovery_hint`, `provider_doc_url`) is exposed as `voiceErrorDetails` but **no component renders it** (§6.4).
- Synthetic `status: disconnected` while a transport exists gives "Server connection lost" and `error`.

**Stop (`stopVoice`, lines 827-858)**: sends `{type:"voice_stop"}` on the voice WS, sets status `ending`, and starts a 5s timeout that forces `off`. With no socket it tears down locally. `voice_stop` ends only the voice connection; the orchestrator session survives (`api/routes/orchestrator.py:321-337`).

**Cleanup** (lines 235-258): stops the recorder and analysers, disconnects the transport, closes the voice WS, clears the queue and connection info, and resets mic and assistant mute and VAD. Unmount also cleans up.

**Transcript and tool rendering into the chat**:
- User transcripts become plain user messages.
- Assistant transcript deltas stream into the trailing assistant text block.
- Tool calls become tool blocks (§4.2 `DISPLAY_MESSAGE` / `VOICE_ASSISTANT_*`).
- `onTurnComplete` refreshes the session list.

**Debug**: `?debug=voice` or `?debug=all` enables timestamped `[voice t+Ns]` console logs (lines 44-63). Combined with remote console logging, these also reach the backend.

### F-27 VoiceButton (`fe/components/VoiceButton.tsx`)

This is the pill button in the input row. It is visible only while voice is **not** active, because the input bar is hidden during voice.

| State | Icon | Label | Action |
|---|---|---|---|
| off | mic | "Start Voice" | start |
| connecting | spinner | "Connecting…" | disabled |
| ending | spinner | "Ending…" | disabled |
| error | error icon | "Retry" | start |
| active / speaking / thinking / tool_use | — | "Voice Active" / "Speaking" / "Thinking" / "Working" | stop (in practice not reachable, because the bar is hidden) |
| **remoteActive** | phone-link icon | **"Active elsewhere"** | disabled, title "Voice active on another device" (lines 46-59) |

**VAD duration**: when `vadState==="listening"` and `vadDurationMs >= 3000`, an extra "Ns" counter appears (lines 72-104). Because the button is hidden while voice is active, **the VAD counter is effectively never visible** (§6.3).

### F-28 VoiceControls and the voice bar (`fe/components/VoiceControls.tsx`, `ChatPanel.tsx:220-252`)

While voice is active, a voice bar replaces the input. It contains:
- **Stop** (red, power icon) → `stopVoice`.
- **Mic mute** toggle with a 4-bar level meter. The meter reads 0 while muted. Muting disables the mic tracks.
- **Assistant mute** toggle with a level meter. For WebRTC it sets `audio.muted`; for WS it sets the PCMPlayer gain to 0.
- The mute buttons are disabled unless the status is active-ish (lines 34-74).
- The meter mapping is `level*3` clamped, with per-bar offsets (lines 79-95).
- Status label with a coloured dot: "Listening…" (or "Muted"), "Speaking…", "Thinking…", "Using tool…", "Connecting…" (`ChatPanel.tsx:236-249`).

### F-29 Multi-device voice ownership

- The owner is the device that called `startVoice` (`isLocalVoice`).
- Non-owner tabs register `handlePassiveVoiceEvent` on their **text** WS (`ChatPanelContainer.tsx:148-159`; `useChatInstance.ts:840-848`):
  - `voice_event` is passed to `handleProviderEvent` so transcripts mirror.
  - `voice_owner_active{active, owner_local_id}` sets `remoteVoiceActive`, which drives "Active elsewhere".
- **[LOAD-BEARING]**: `voice_ending`, `voice_ended` and `voice_stopped` are deliberately **ignored** by passive viewers, to avoid the "stuck in Ending…" wedge (commit b6d184f).
- `session_started.voice_session_update` is forwarded only by the initiator (`voice_initiator`, commit 71f19ce).
- **Bug**: the comment at lines 877-881 claims that `handleProviderEvent` guards status flips for passive viewers ("currentProvider==null"). **No such guard exists in the web code.** A passive viewer's `voiceStatus` therefore flips to `speaking`/`active`/`thinking` from mirrored events. That **hides its text input and shows a non-functional voice bar**, and it never returns to `off` because `voice_ended` is ignored. The rebuild must keep passive viewers' own status at `off` (§6.3).

### F-30 Voice recording (WebRTC only; `fe/voice/AudioRecorder.ts`)

- The intent: when `session_started.voice_recording_enabled` is true and the transport is WebRTC, capture mic and remote audio with the PCM worklet at the input rate, buffer base64 chunks, and every 5s send `{type:"voice_recording_chunk", session_id, channel:"user"|"assistant", audio}`. On stop it sends `{type:"voice_recording_end"}`. The backend writes `context/recordings/` (`api/routes/orchestrator.py:289-305`). For the WS transport the backend records directly.
- **Broken**: it instantiates `AudioWorkletNode(ctx, "pcm-capture-processor")` (lines 80, 107), but the worklet registers `"pcm-capture"`. The constructor throws, `start()` catches and logs it, and nothing is recorded (§6.3). The worklet nodes are also not connected to a destination.

### F-31 Global configuration page (`fe/components/ConfigPage.tsx`)

**Shell**:
- A centred blurred overlay holding a panel up to 680px wide (`fe/App.css:2936+`). It closes on a backdrop click, the X button or **Escape** (lines 156-161, 251-252, 267-271).
- Header: "Configuration" plus "Saving…" / "✓ Saved" (2s).
- **Every control saves immediately** with `PUT /api/config` (a partial `ConfigUpdate`), and the response replaces local state (lines 196-210). There is no Save button and no undo.
- Controls are disabled while a save is in flight.

**Load** (lines 95-149), on every closed→open transition:
1. `GET /api/config`.
2. In parallel: `GET /api/mcp/servers`, `GET /api/orchestrator/models`, `GET /api/orchestrator/voice/models`, `GET /api/config/voice/google/models?endpoint=<cfg.default_voice_endpoint>` (errors → `[]`), `GET /api/config/harness/qwen/models` (errors → `[]`) and `GET /api/config/providers`.

**Dynamic Google catalog**: when the dynamic list is non-empty it replaces the static `providers.google` list (lines 130-134). Changing the endpoint refetches the Google catalog (lines 167-188).

**Auto-correct [LOAD-BEARING, commit 6a4712f]**: if the voice provider is `google` and the saved `default_voice_model` is not in a non-empty discovered list, the page PUTs the discovered default model. It keeps the voice when the new model still offers it, otherwise uses the model's default voice. It then shows a yellow dismissible banner: "The previously-saved Gemini Live model X is no longer available from Google. Switched to Y." (lines 35-61, 278-293). This prevents WS 1008 failures from Google renaming model ids.

**Fields** (all on `/api/config`, defaults from `api/routes/config.py:109-163`):

| Section | Field (`AssistantConfig` key) | Control | Type and range | Default | Notes |
|---|---|---|---|---|---|
| Orchestrator › Text mode | provider (derived) | select | provider of the models in `/api/orchestrator/models` | provider of `default_model` | Changing it saves the **first model** of that provider. Labels: "Anthropic", "OpenAI", or raw |
| | `default_model` | select | model_id; options show display_name plus 🎤 when audio-capable and 👁 when vision-capable | `claude-sonnet-4-5-20250929` | "Can be changed mid-conversation" |
| Orchestrator › History summarizer | provider (derived) | select | same catalog | first provider | switching saves the first model |
| | `summarizer_model` | select | model_id (👁 badge) | `""` (backend `DEFAULT_SUMMARIZER_MODEL`) | an empty value shows the first option without saving |
| Orchestrator › Voice mode | `default_voice_provider` | select | keys of the voice models response; labels openai→"OpenAI", qwen→"Qwen (Alibaba)", google→"Google Gemini" | `openai` | "Cannot be changed mid-session" |
| | `default_voice_endpoint` | select (**google only**) | `vertex` "Vertex AI (recommended)" / `aistudio` "AI Studio (legacy)" | backend `_default_voice_endpoint()` | tooltip explains 1008 denials |
| | `default_voice_model` | select | `VoiceModelEntry.id`/label | `gpt-realtime` | falls back to the first entry when the saved one is missing |
| | `default_voice_name` | select | `voices[]`, "label — description" | model default voice | |
| | `default_voice_transcription_language` | select (only when the model lists languages) | ISO code; `""` = auto-detect | model default | tooltip on multilingual trade-offs |
| Orchestrator › Voice tuning | `voice_vad_threshold` | range | 0.15–0.50, step 0.01 | 0.28 | **PUT on every slider tick** (§6.2) |
| | `voice_vad_min_silence_ms` | range | 800–5000, step 100 | 2500 | same |
| | `voice_mic_gain` | range | 0.5–2.0, step 0.05 | 1.0 | labelled "(reserved — wiring lands in a later increment)" |
| Orchestrator › Voice recording | `voice_recording_enabled` | checkbox | bool | false | "stored in context/recordings/" |
| Session provider | `provider` | select | `/api/config/providers` `{id,label,description}`; the description is shown under the control | `claude` | affects new tabs only |
| | `harness_model.qwen` | select (**provider==qwen only**) | `""` = "CLI default", plus Qwen models with badges "NK ctx · thinking · vision · video" | `""` | hint when the list is empty: run `qwen` once. PUT `{harness_model:{qwen:id}}` (shallow-merged) |
| Working Directories | `working_directory`, `working_directory_history` | list editor (F-33) | | the project root | |
| Session Flags | `chrome_extension` | checkbox "Chrome Extension — Launch sessions with --chrome flag" | bool | false | Anthropic Claude-in-Chrome, **not** `browser-extension/` |
| MCP Servers | `enabled_mcps` | checkbox per server from `/api/mcp/servers` ("name" plus "command args") | string[] | `[]` | **backend: `[]` means all enabled**, but the UI shows everything unchecked (§6.2). Empty state: "No MCP servers configured in .claude.json" |

**Error display**: messages are shown in `.config-error`. Because `json()` throws `"<status> <statusText>"`, backend `detail` strings such as "Directory does not exist: …" are **lost** (`fe/api/rest.ts:5-9`; `ConfigPage.tsx:204-206`) (§6.2).

### F-32 Per-session configuration (`fe/components/SessionConfigPage.tsx`)

- Opened from the gear in a chat tab's input. It is keyed by the tab's **SDK session id**. Before the first turn it shows "Configuration will be available after the first message is sent…" (lines 170-176).
- Load: `GET /api/config`, `GET /api/sessions/{sdk}/config`, `GET /api/mcp/servers`, `GET /api/config/harness/qwen/models` (errors → `[]`) and `GET /api/config/providers` (lines 46-72).
- Save: `PUT /api/sessions/{sdk}/config` with a partial `SessionConfig` (`fe/api/rest.ts:340-366`). For every field, **`null` means inherit the global value**. Each section shows "(using global …)" / "· inherited" or a **Reset to global** button that PUTs `{field:null}` (lines 106-124).

| Field | Control | Semantics |
|---|---|---|
| `working_directory` | WD list radio; badge "global default" when inherited, else "selected" | Add, edit and delete in this panel change the **global** history (`PUT /api/config`), and a newly added or edited entry also becomes the session's directory (lines 180-195) |
| `provider` | select | per-session pin. Help text: switching CLIs behind an existing JSONL would corrupt it |
| `harness_model` | select (qwen only) + "Reset model" | `null` = inherit `global.harness_model[provider]`; `""` = explicit CLI default |
| `chrome_extension` | checkbox | |
| `enabled_mcps` | checkboxes | |

- **Footer**: the hint is "Send a message first…", "Session is stopped. Save and restart to apply changes." or "Stop the session to apply configuration changes on next restart.". The **Save and Restart** button is enabled when `canRestart` (tab status `idle` or `disconnected`). It calls `instance.restart()`, which re-sends `start` on the existing WS, then closes the panel (`ChatPanelContainer.tsx:497-508`).
- Caveat: `idle` is treated as "stopped", although an idle session is still alive in the pool. Whether `start` re-applies the config to a live pool entry is backend-defined; verify. There is no UI to actually stop a chat session (§6.2).
- Escape and backdrop click close the panel.

### F-33 Working directory editor (`fe/components/WorkingDirectoryList.tsx`)

- **List** (lines 113-169):
  - A radio sets the active directory.
  - Display name: label, else `host:path`, else `path`. SSH entries get an "SSH" badge and a `user@host · path` subtitle.
  - The active entry has a badge ("active", or the caller's label).
  - Edit (pencil) and delete (×) buttons. **Delete is disabled when only one entry is left**, with the tooltip "Cannot remove the only directory".
- **"+ Add directory"** opens the form (lines 172-293):
  - Local/SSH tabs.
  - SSH fields: Host* (placeholder 192.168.0.200) and User. Then Path* (labelled "Remote path *" for SSH).
  - SSH only: "SSH key (local path, optional)" and "CLAUDE_CONFIG_DIR on remote (optional, defaults to <path>/.claude_config)". The placeholder derives from the path.
  - Label (optional).
  - Inline error "SSH host is required". Submit is disabled until the required fields are filled.
- **id rule**: SSH entries use `${host}:${path}`, local entries use the path (line 71).
  - Adding an entry that already exists just selects it.
  - Editing replaces the entry by its old id; the id can change.
  - Add and edit **set the entry active** (`onHistoryChange(newHistory, id)`, line 97).
- **Defensive coercion** of undefined fields when editing (lines 41-58, commit 01b24e3). **[LOAD-BEARING]**: malformed history rows previously crashed the panel.
- **Server side**: local paths must exist (400 otherwise). SSH `claude_config_dir` is auto-derived. History is capped at 20. If the active entry is removed, the first entry becomes active (`api/routes/config.py:591-616`).

### F-34 MCP selection

- **Global** selection is in ConfigPage and per-session selection is in SessionConfigPage (F-31, F-32), both through `McpServersSection` (`fe/components/AgentSettings.tsx:126-178`). Data: `GET /api/mcp/servers` → `{servers:{name:{type?,command,args?,env?}}, project_dir}`.
- `McpSelectionModal.tsx` ("MCP Server Selection … Apply & Restart") and `useChatInstance.restartWithMcps` are **dead code**. Nothing renders or calls them (§6.4).

### F-35 Skills and agents

The web has **no skills or agents UI**. It was removed in commit 99eec7b ("disabled_skills/disabled_agents had no actual effect on the SDK"). The backend still exposes `GET /api/skills` and `GET /api/agents` (`api/routes/skills.py:38`, `api/routes/agents.py:37`); no web code calls them. Auto-memory notes mentioning a "MCPs/skills/agents" session panel are stale.

### F-36 Visualizations panel

> Detailed because this is being ported to Android.

**Data**: `GET /api/visualizations` → `VisualizationInfo[]` (`fe/types.ts:17-26`):

| Field | Meaning |
|---|---|
| `path` | relative to `context/public/`, POSIX; the identity and join key |
| `url` | `"/" + path`, served by the backend SPA catch-all |
| `title` | first hit of: `.titles.json["viz:<path>"]`, then the HTML `<title>` (first 8KB, whitespace-collapsed), then the prettified filename, where `index.html` borrows its parent folder name |
| `created` | ISO birth time via `stat %W`, falling back to mtime |
| `modified` | ISO mtime |
| `size` | bytes |

- The backend walks `context/public/**/*.html` and skips symlinks that escape the directory. The list is sorted by **mtime, newest first** (`api/routes/visualizations.py:1-175`). There is no backend watcher or cache.
- **Refresh triggers**: app mount, the sidebar Refresh button (Visuals section only), **every `turn_complete`** in any tab, and pool pushes (`fe/App.tsx:30-42`). Errors give `[]`.
- **List item** (`fe/components/VizItem.tsx`): reuses the session-row styling.
  - Title (or path).
  - Meta: relative `modified` time ("just now" / "Nm ago" / "Nh ago" / "Nd ago") and the **parent folder name**, or "public" for root files, with the full path as the tooltip (lines 84-92).
  - `active` when its tab is active; `tab-open` when a tab is open.
  - **Rename only**: the pencil reveals an input that uses autoFocus and select-on-focus; Enter or blur commits, Escape cancels. **No delete or duplicate**, by design (lines 9-14).
- **Rename** (`fe/App.tsx:52-60`): optimistically retitles the open viz tab (a viz tab's title is its own state, unlike chat tabs), then `PATCH /api/visualizations/rename {path, title}` (404 tolerated), then updates the list item (`useVisualizations.ts:36-41`). It writes `.titles.json` under `viz:<path>`.
- **Open**: `openVizTab(path, url, title)` dedupes by `viz:<path>`. The tab is `idle`/`connected` (no dot), cannot be renamed from the tab bar, and closing it does not call the backend.
- **VizPanel** (`fe/components/VizPanel.tsx`):
  - Toolbar: title (tooltip = URL), **Reload** (bumps a React `key` to remount the iframe, which works across origins and history states, lines 15-17), and **Open in new browser tab** (`<a target=_blank>`).
  - Body: a full-bleed `<iframe class="viz-frame" src=url>` with `sandbox="allow-scripts allow-same-origin allow-popups allow-forms allow-modals"` (line 60). Same-origin is required for visualizations that fetch the backend or use storage. Omitting `allow-top-navigation` and `allow-downloads` stops a framed page from navigating the app away or triggering downloads.
  - **[LOAD-BEARING]**: keep the iframe mounted while the tab is hidden, so interactive visualizations keep their state.
- Empty state: "No visualizations yet. HTML files under context/public/ appear here."
- Count badge in the sidebar switcher: "Visuals N".

### F-37 Memory tree and memory panel

> Detailed because this is being ported to Android.

**Data**: `GET /api/memory/tree` → `MemoryNode[]`, with nodes `{name, path (relative to context/memory/, POSIX), is_dir, children?}` (`fe/types.ts:29-35`). Backend rules (`api/routes/memory.py:21-110`):
- Only `.md` files are listed, matched case-insensitively.
- Dotfiles and dot-directories are skipped.
- Symlinks escaping the tree are skipped.
- **Directories with no markdown at any depth are dropped.**
- Directories sort before files, each alphabetical and case-insensitive.

**File content**: `GET /memory/<path>` returns the raw file. This is a static route, not under `/api` (`api/app.py:244-272`). It returns 404 for directories, and `/memory/` serves `MEMORY.md`. The client fetches it with `fetch("/memory/" + path)` **without URL-encoding** (`fe/api/rest.ts:57-61`) (§6.2).

**Refresh triggers**: app mount and the sidebar Refresh button (Memory section only). Unlike visualizations, it does **not** refresh on `turn_complete` (`fe/hooks/useMemoryTree.ts`).

**Tree UI** (`fe/components/MemoryTree.tsx`): recursive and flat-rendered.
- **Folders**: buttons with a rotating chevron and `aria-expanded`. **Top-level folders start expanded and deeper ones collapsed** (line 70). The expand state is local per folder and is lost on refresh or remount. Indent is `8 + depth*13` px.
- **Files**: buttons with a document icon and the name **without `.md`**, tooltip = path. Indent is `14 + depth*13` px. Class `active` marks the active tab's file and `tab-open` marks files open in any tab (`Sidebar.tsx:36-42`). CSS is at `fe/App.css:348-420`.
- Clicking a file runs `openMemoryTab(path, name without .md)` and closes the drawer (`Sidebar.tsx:220-223`).
- Count badge: "Memory N", the number of files (`countFiles`).
- Empty state: "No memory files. Markdown under context/memory/ appears here."
- There is no search, no filter, and no expand-all or collapse-all.

**MemoryPanel** (`fe/components/MemoryPanel.tsx`):
- The toolbar reuses the viz toolbar: title (tooltip = path), **Reload** (re-fetches; the old content stays visible until the new one resolves), and **Open raw file in new browser tab** (`/memory/<path>`).
- Body states: "Loading…" before the first load; "Could not load <path> — <error>" on failure; otherwise content.
- **Frontmatter handling** [LOAD-BEARING, lines 88-100]: a leading `---\n…\n---` YAML block (CRLF tolerant) is split off and shown verbatim in a collapsed `<details>` labelled "Frontmatter" (CSS `fe/App.css:422-445`). It is not parsed. Rendered inline, it would turn into a run-on paragraph between horizontal rules. Every memory file has frontmatter.
- The body renders through the shared `Markdown` component inside `.message-assistant`, so it gets the same prose styling, code highlighting and copy buttons as chat.
- **Relative links between memory files** (`[x](../folder/y.md)`, which the memory wiki uses throughout) are rendered as `target=_blank` anchors resolved against the **app URL**, not `/memory/<path>`. They are therefore broken: they open the SPA, or the wrong path, in a new browser tab. The rebuild, and Android, should resolve relative `.md` links against the current file's directory and open them as memory tabs (§6.2).
- The panel is read-only. There is no editing, no back-link list, no frontmatter field rendering, and no reaction to file changes.

### F-38 Remote console logging (`fe-root/index.html:5-26`, `fc/index.html:11-32`)

- An inline script wraps `console.log/warn/error/info`; `debug` is not wrapped. Each call goes to the original console **and** to `navigator.sendBeacon("/api/debug/log", JSON.stringify({level, msg, ts}))`. Arguments are stringified, objects through `JSON.stringify`, unserialisable values become `[unserializable]`.
- `window.error` is sent as level `uncaught` (message @ file:line:col) and `unhandledrejection` as `unhandledrejection`.
- The compat build prefixes messages with `[compat] `.
- Backend: appends `[ts] [LEVEL] msg` to `remote_console.log` at the repo root. `GET /api/debug/log` returns the whole file (`api/routes/debug.py:16-34`).
- This is **always on**, in every build and on desktop too. There is no rate limit or size cap, and the log file grows forever (§6.2). It is **[LOAD-BEARING]** for debugging devices without devtools (iPad, A300M). The rebuild should keep it, at least behind a flag that is on by default for compat.

### F-39 Debug and test hooks

- `window.__setShowConfig(bool)` opens or closes the config page from devtools or automation (`fe/App.tsx:148-150`).
- `?debug=voice|all` enables voice tracing (F-26).

### F-40 Empty states and misc

- No tabs open: "No session open — Start a new session or select one from the sidebar." (`ChatPanelContainer.tsx:449-461`).
- A tab with no messages: "Start a conversation" (F-11).
- Modal pattern (`fe/components/ConfirmModal.tsx`): an overlay click cancels; buttons are Cancel plus Confirm, styled primary or **danger**. **There is no Escape handling and no focus trap** (§6.2).

---

## 3. Endpoint and protocol reference (web usage only)

### 3.1 REST

| Method + path | Used by | Request → response |
|---|---|---|
| GET `/api/auth/status` | AuthGate | → `{authenticated, auth_url?, headless}` |
| POST `/api/auth/login` | AuthGate | → same |
| POST `/api/auth/credentials` | AuthGate | `{credentials_json}` → same |
| GET `/api/sessions` | useSessions | → `SessionInfo[]` |
| GET `/api/sessions/pool/live` | useReconnectPoolSessions | → `PoolSession[]` |
| GET `/api/sessions/{sdk}/messages?limit=50[&before=N]` | history and pagination | → `{messages: MessagePreview[], total_count, has_more, start_index}` |
| PATCH `/api/sessions/{sdk}/rename` | rename | `{title}` → 204 (404 tolerated) |
| DELETE `/api/sessions/{sdk}` | delete | → 204 (404 tolerated) |
| POST `/api/sessions/{sdk}/duplicate` | duplicate | → `{session_id}` |
| POST `/api/sessions/{sdk}/truncate` | rewind | `{drop_last_n}` → `{session_id}`; 409 while open |
| POST `/api/sessions/{sdk}/fork` | fork | `{drop_last_n}` → `{session_id}` |
| POST `/api/sessions/{local_id}/close` | tab close, rewind | → 204 (404 tolerated) |
| GET, PUT `/api/sessions/{sdk}/config` | SessionConfigPage | `SessionConfig` (nullable fields) |
| GET, PUT `/api/config` | ConfigPage, SessionConfigPage | `AssistantConfig` / partial `ConfigUpdate` → full config |
| GET `/api/config/providers` | config pages | → `{providers:[{id,label,description}]}` |
| GET `/api/config/harness/qwen/models` | config pages | → `{models: QwenModelInfo[]}` |
| GET `/api/config/voice/google/models[?endpoint=]` | ConfigPage | → `{models: VoiceModelEntry[]}` (60s server cache) |
| GET `/api/orchestrator/models` | ConfigPage, ChatPanelContainer (audio support) | → `{models: ModelInfo[], audio_capable_models, default_model}` |
| GET `/api/orchestrator/voice/models` | ConfigPage | → `{providers:{id: VoiceModelEntry[]}, default_provider, default_model}` |
| POST `/api/orchestrator/voice/session` | voice (WebRTC) | → `{client_secret:{value,expires_at}, model, voice, connection_info?}` |
| POST `<OpenAI callUrl>` | voice (WebRTC) | SDP offer (`application/sdp`, Bearer ephemeral) → SDP answer |
| GET `/api/mcp/servers` | config pages | → `{servers, project_dir}` |
| GET `/api/visualizations` | sidebar | → `VisualizationInfo[]` |
| PATCH `/api/visualizations/rename` | sidebar | `{path, title}` → 204 |
| GET `/api/memory/tree` | sidebar | → `MemoryNode[]` |
| GET `/memory/<path>` | MemoryPanel | → raw markdown |
| GET `/<viz path>` | VizPanel iframe | HTML |
| POST `/api/debug/log` (beacon) | console hook | `{level,msg,ts}` |

Defined in `fe/api/rest.ts` but **unused**: `getSession` (`GET /api/sessions/{id}`) and `getPreview` (`GET /api/sessions/{id}/preview`).

Backend endpoints with **no web usage**: `/api/uploads`, `/api/sessions/inject`, `/api/skills`, `/api/agents`, `/api/config/openai-key`, `/api/orchestrator/audio`, `/api/orchestrator/models/audio`, `/api/mcp/servers/{name}`, `/api/browser/*`.

### 3.2 WebSocket summary

- **Client → server**: see F-01 (Chat WS) and F-25/F-26 (Orchestrator WS). Every message is a JSON text frame.
- **Server → client**: the `ServerEvent` union is at `fe/types.ts:144-230`. Live events carry `seq` and `stream_id` (F-24). The server sends `ping` heartbeats every 15s on the orchestrator WS. The client never sends `ping` or `pong`.

---

## 4. Client-side message model (REFERENCE ALGORITHM)

The web ordering is considered **correct** and is the reference for Android. Source: `fe/hooks/useChatInstance.ts:131-493`.

### 4.1 Data model

```ts
ChatMessage = { id: string; role: "user"|"assistant"; blocks: MessageBlock[] }
MessageBlock =
  | {type:"text";     content; streaming}
  | {type:"thinking"; content; streaming}
  | {type:"compact";  content(summary); streaming:false}
  | {type:"tool_use"; toolUseId; toolName; toolInput; result?; isError?; complete; executing?}
```

- **IDs** come from a module-global counter `msg-N` (`useChatInstance.ts:135-138`), shared across all tabs. They are **not** stable across reloads and are not server ids. They are used only as React keys.
- **There are no server message ids, no timestamps on live messages, and no dedupe** beyond the permission `request_id` match. `MessagePreview.timestamp` is received from REST but discarded.
- Tool results are **merged into their `tool_use` block**. They never form separate messages.

### 4.2 Live event → state transitions (reducer)

Two helpers define the whole ordering behaviour:
- `ensureAssistantMessage(msgs)` (lines 140-144): if the **last** message is an assistant message, reuse it. Otherwise append a new empty assistant message.
- `updateLastAssistantBlock(msgs, fn)` (lines 146-154): run `ensureAssistantMessage`, then rewrite the **last** message's block array.

| Action (from event) | Rule |
|---|---|
| `USER_MESSAGE` (local `send`/`sendAudio`, or server `user_message`) | append a **new user message** `[text]`. Set `status:"processing"` optimistically (lines 234-250) |
| `TEXT_DELTA` | in the last assistant message: if the **last block** is `text` and `streaming`, append to it. **Otherwise push a new `text` block with `streaming:true`**. `status:"streaming"` (252-266) |
| `TEXT_COMPLETE` | if the last block is a streaming text block, **replace its content with the authoritative full text** and set `streaming:false`. Otherwise push a new finished text block (268-278) |
| `THINKING_DELTA` / `THINKING_COMPLETE` | identical rules for `thinking` blocks. `status:"thinking"` on delta (280-306) |
| `TOOL_USE` | push a new `tool_use` block `{complete:false}` onto the last assistant message. `status:"tool_use"`, clear stall (308-323) |
| `TOOL_RESULT` | in the **last** assistant message only, find the block with the same `toolUseId` and set `result`, `isError`, `complete:true`. Clear stall (325-336) |
| `TURN_COMPLETE` | `status:"idle"`, clear stall, `cost += cost`, `turns += num_turns ?? 1`, `contextTokens = input_tokens ?? usage.input_tokens ?? previous`. Messages are not touched. Streaming flags are **not** cleared, so a text block missing its `text_complete` stays `streaming:true` (338-347) |
| `COMPACT_COMPLETE` | append a new assistant message `[compact(summary)]` (349-361) |
| `PERMISSION_REQUEST` | set `pendingPermission`. For `ExitPlanMode` with a `plan`, also push a finished text block holding the plan onto the last assistant message (376-400) |
| `PERMISSION_RESOLVED` | clear pending only when `request_id` matches. **Append a user message** `[Who approved/rejected the request — msg]` (402-426) |
| `DISPLAY_MESSAGE` (voice user transcript) | append a new message with the given role (444-455) |
| `VOICE_ASSISTANT_DELTA` | same as `TEXT_DELTA` but **without changing status** (457-470) |
| `VOICE_ASSISTANT_COMPLETE` | if the last block is streaming text, finalise it with `text`, or **keep the existing content when `text` is empty** (Gemini `turnComplete`). Otherwise push a new block only when `text` is non-empty (472-488) |
| `STATUS` / `STALL` / `ERROR` / `SESSION_STARTED` / `SESSION_TERMINATED` | status, banners and window. Messages untouched |
| `RESET` | initial state |

**Resulting ordering semantics** (the properties Android must match):
1. **Blocks keep strict arrival order** inside one assistant message. Text, thinking and tool blocks interleave exactly as the events arrived, for example `thinking → text → tool_use(A) → tool_use(B) → text`.
2. A delta only extends the **immediately preceding** block of the same kind, and only while that block is still streaming. Any other block in between (tool_use, thinking, a completed text) **starts a new block**. Text after a tool call is therefore a new paragraph block below the tool, never merged into the text above it.
3. `*_complete` is authoritative: it **replaces** the streamed content and does not append to it. This repairs dropped or duplicated deltas.
4. **One assistant message per "run"**: everything from the first assistant event after a user message, until the next user message, is a single bubble. That includes multiple SDK assistant turns, tool loops and several text segments. A new assistant bubble starts only after an intervening user or compact message.
5. A tool result attaches to its `tool_use` by id **within the current (last) assistant message**. Results never create blocks or messages.
6. User messages are appended at the moment they are sent or announced. The optimistic local echo is not deduped against the server: the backend excludes the sender from the `user_message` broadcast (`api/pool.py:1060-1066`), so no echo arrives.

**Known defects of the reference** (preserve the ordering, fix these):
- **Interleaved user messages split runs.** If a user message lands mid-turn (a queued prompt typed while streaming, or the synthetic `PERMISSION_RESOLVED` note), any later deltas start a **new** assistant message below it. A later `TOOL_RESULT` for a tool that is in the earlier message is **lost**: the map runs on the new, empty last message, and the earlier tool block stays "running" forever. The ExitPlanMode approval is the common case: request, then the user note, then the result of `ExitPlanMode`. The rebuild should match `tool_result` by `toolUseId` across all messages, search from the end, and decide whether queued prompts render in place or after the run.
- An event that only calls `ensureAssistantMessage` can leave an empty assistant bubble. An example is `TOOL_RESULT` when the last message is a user message. The bubble still shows its ⋮ menu.
- `turn_complete` does not finalise blocks that are still marked streaming.

### 4.3 History (REST) → messages (`convertPreviews`, lines 156-211)

The REST API returns one `MessagePreview` **per JSONL user or assistant line**, with blocks `text`, `tool_use` and `tool_result`. Thinking blocks are dropped server-side, because `extract_blocks` reads `block.text`, which thinking blocks do not have (`manager/protocol.py:166-210`).

1. **First pass**: build `tool_use_id → {output, is_error}` from every `tool_result` block **in this page**.
2. **Second pass**: per preview:
   - **Skip user previews whose blocks are all `tool_result`** (protocol wrappers).
   - `text` blocks with non-empty text → finished text blocks.
   - `tool_use` blocks → `{complete:true, result: map[id]?.output ?? b.output, isError: map[id]?.isError ?? false}`. The tool shows as complete even when no result was found.
   - Stray `tool_result` blocks are ignored.
   - With no blocks but a non-empty `m.text`, fall back to one text block.
   - **Previews that end up with zero blocks are dropped.**
3. Each preview becomes its own `ChatMessage`. Consecutive assistant previews are **not merged**. History therefore shows several assistant bubbles for one run, while the same run seen live is one bubble. The visual difference is minor because assistant messages have no bubble chrome, but it matters for `dropLastN` (below).

`LOAD_HISTORY` **replaces** all messages (init and `replay_overflow`). `PREPEND_HISTORY` **prepends** a page, and each page resolves tool results independently: a `tool_use` on an older page whose `tool_result` sits on a newer page renders complete with no output.

### 4.4 History + live merge and replay

- **Fresh open**: REST loads the tail (50), then the WS `start` (`resume_from` only when sessionStorage holds a checkpoint for this `local_id`), then the live events are appended after the history.
- **Reconnect (same page)**: the history is **not** reloaded. Replayed events (seq > checkpoint) are appended through the same reducer, which is correct because they were never seen. On `replay_overflow`, history is fully reloaded through REST, replacing the live messages.
- **Page reload mid-turn**: REST loads the JSONL, which may already contain assistant lines of the in-flight turn, then the replay re-sends events after the stored checkpoint. **There is no dedupe between JSONL content and replayed events**, so overlap can duplicate text or tool blocks (§6.3). The orchestrator tab with `skipHistory` and no `resumeSdkId` cannot REST-reload at all, so `replay_overflow` silently loses the earlier content.
- **Rewind and fork indexing**: `dropLastN` counts **client-side messages below the clicked one**. The backend counts "visible" JSONL lines: assistant lines plus user lines that are not pure tool_result (`manager/protocol.py:112-140`). These agree only when:
  - the list came purely from REST, and
  - no preview was dropped client-side (for example a thinking-only assistant line), and
  - no client-only messages exist (permission notes, compact dividers, voice transcripts, merged live runs).
  
  After live streaming, one live bubble can stand for several JSONL lines, so rewinding from a live view can cut at the wrong point. The rebuild should compute the cut from server-provided indices (§6.3).

### 4.5 Status machine (tab status)

`connecting` (tab created) → `disconnected` (initial reducer state) → `idle` (`session_started`) → `processing` (optimistic, on send) → `streaming`/`thinking`/`tool_use` (by the last delta type) → `idle` (`turn_complete`).

Other transitions:
- `interrupted` comes from the server.
- `disconnected` comes from WS close, `session_stopped` or `session_terminated`.
- Unknown server values such as `retrying`, `connecting` or `summarizing` are stored as-is and display as "Ready" or the default dot.

"Busy" (`isStreaming`) means `streaming | thinking | tool_use | processing` (`ChatPanel.tsx:132`).

---

## 5. Compat build (`frontend-compat/`, served at `/compat/`)

### 5.1 How it builds and loads

- React **18.3**, Vite 5, `@vitejs/plugin-legacy` with `targets: ['safari >= 12','ios >= 12'], modernPolyfills: true, renderModernChunks: false` (legacy chunks only), and `base: '/compat/'` (`fc/vite.config.ts:6-15`). The dev port is 5433.
- **Code sharing**: the alias `@ → ../frontend/src`. `fc/src/App.tsx` is a hand-maintained copy of `fe/App.tsx` that imports shared components through `@/…`.
- **Module-replacement aliases** (`fc/vite.config.ts:16-25`):
  - `diff` → `node_modules/diff/libesm/index.js` (forces the ESM build; commit 8d9eee2).
  - `react-syntax-highlighter[/dist/{esm,cjs}/styles/prism]` → the shims.
  - `remark-gfm` → the shim.
  - `@/components/MessageList` → the shim. This one **does not take effect** (§5.3).
- Type-check gate (`fc/scripts-typecheck.sh`): runs `tsc --noEmit` and **fails only on errors under `frontend-compat/src/`**. Shared-tree errors from the older TS lib are tolerated (commit 58d19ae). `fc/tsconfig.json:20-26` excludes the voice hook and voice components from type-checking; they are still bundled.
- `fc/index.html`:
  - No manifest, no service worker, no PWA meta beyond `theme-color`, `mobile-web-app-capable` and the status-bar style.
  - Favicon `/compat/icon.svg`.
  - The same remote-console script with a `[compat]` prefix (lines 11-32).
- `fc/src/main.tsx` imports `@/index.css` plus `./gap-compat.css` and has the same low-end detection.
- The backend serves `frontend-compat/dist` at `/compat`, `/compat/` and `/compat/{path}` with an SPA fallback, and a no-cache `index.html` (`api/app.py:182-197`). **Both dists must be rebuilt and deployed together** (per memory).

### 5.2 Differences from the main `App.tsx`

| Main (`fe/App.tsx`) | Compat (`fc/src/App.tsx`) |
|---|---|
| `ConfigPage` lazy plus Suspense | imported eagerly (line 8) |
| `onSessionChange` = refresh sessions **and** visualizations | `onSessionChange={refresh}` (sessions only, line 157). Visualizations do not refresh on `turn_complete` |
| `window.__setShowConfig` debug hook | absent |
| everything else | equivalent: same sidebar props, pool sync, rewind and fork, busy overlay, orchestrator modal, delete confirm |

### 5.3 Shims and why

| Shim | Problem in Safari 12 | What it does | State |
|---|---|---|---|
| `fc/src/shims/react-syntax-highlighter.tsx` (+ `-style.ts`) | Prism grammars use **named capture groups**, which Safari 12 cannot parse | `Prism`/`Light`/default render a plain `<pre><code>` with `customStyle`. `oneDark` is `{}` | Works. **No syntax highlighting on compat** |
| `fc/src/shims/remark-gfm.ts` | remark-gfm uses **lookbehind regexes** | A table-only GFM implementation: it walks every top-level paragraph, rebuilds its markdown source from the inline AST (`reconstructInline`, lines 253-295, commit 95f25c6), finds `header | sep | rows` blocks, and builds `table`/`tableRow`/`tableCell` mdast nodes with an inline parser for code, bold, italic, links, `<br>` and escapes. Strikethrough, autolinks and task lists are unsupported | **Regression**: **every non-table paragraph is re-emitted as a single plain `text` node of reconstructed markdown** (lines 328-349), so `**bold**`, `` `code` `` and `[links](…)` in ordinary paragraphs show as **literal markup** on compat. The rebuild must keep the inline formatting of non-table paragraphs |
| `fc/src/shims/MessageList.tsx` | `-webkit-overflow-scrolling: touch` momentum makes programmatic `scrollTop` unreliable. `overflow-anchor` is unsupported, which blinks on prepend | `iosScrollTo()` sets `webkitOverflowScrolling='auto'`, assigns `scrollTop`, and restores `touch` on the next frame. `hideForFrame()` sets visibility hidden for one frame around the prepend (lines 29-46) (commit 5411f6f) | **Not wired**: `fe/components/ChatPanel.tsx:1` imports `./MessageList` **relatively**, so the `@/components/MessageList` alias never matches. The built `fc/dist` contains no `webkitOverflowScrolling`. Compat currently runs the main `MessageList` while `gap-compat.css` turns momentum scrolling **on** for `.message-list` (`fc/src/gap-compat.css:23-34`), the exact combination the shim was written to make safe |
| `fc/src/gap-compat.css` (335 lines) | **Flexbox `gap` is unsupported** in Safari before 14.1 | For every `gap:` rule in `App.css`, sets `gap:0` and adds `> * + *` margins: `margin-left` for rows, `margin-top` for columns. **[LOAD-BEARING]** gotcha: `* + *` does not match **text nodes**, so `<svg> + raw text` pairs need explicit `> svg { margin-right }` rules (commit 8b8fb9c; memory note `project_compat_gap_shim_text_node_trap.md`). Examples: `.sidebar-config-btn`, `.session-menu-item`, `.message-actions-item`, `.compact-divider-label`. Wrapping rows (`.model-dropdowns`) accept the extra margin. It also enables `-webkit-overflow-scrolling: touch` on scroll containers | Must be kept in sync by hand whenever `App.css` gains a `gap:`. `App.css` has 71 `gap:` declarations |

### 5.4 Safari 12 / iOS 12 constraints the rebuild must respect

These are for the iPad mini 2 (iOS 12.5 max).
1. **No flexbox `gap`** (Safari 14.1+). Use margins, or ship a compat stylesheet. This includes the M3 component library's internal gaps.
2. **No regex lookbehind and no named capture groups.** This rules out stock remark-gfm and Prism grammars, and many modern markdown or highlight libraries. Audit every dependency's regexes.
3. **CSS `inset` is unsupported** (Safari 14.1+), and there is no PostCSS step in compat. `App.css` uses `inset: 0` for `.modal-overlay`, `.busy-overlay`, `.config-overlay`, `.session-list-overlay` and `.sidebar-backdrop` (lines 248, 2516, 2530, 2842, 2938, 3618). On Safari 12 these overlays likely collapse because no `top/left/right/bottom` is set (§6.3; verify on the device). Use explicit `top:0;right:0;bottom:0;left:0`.
4. `backdrop-filter` needs the `-webkit-` prefix. `.modal-overlay` has only the unprefixed property. `mask-image` needs `-webkit-mask-image` (present for the user-text fold).
5. `overflow-anchor` is unsupported; manual scroll restoration is required. Momentum scrolling has the problem described in §5.3.
6. **No AudioWorklet** (Safari 14.1+). The WS voice transport (`audioWorklet.addModule`) cannot work on compat. WebRTC exists but uses older APIs. `MediaRecorder` is absent in Safari 12, so audio messages are unavailable. `content-visibility` and `dvh` are unsupported and ignored.
7. `crypto.randomUUID` is unavailable (also on HTTP origins). The `generateUUID` fallback is required.
8. iOS needs `font-size >= 16px` on inputs to avoid focus zoom (see §6.2: the ≤640px override breaks this).
9. Taps on bare inline elements inside momentum-scroll containers need `cursor:pointer; touch-action:manipulation` and positioning hints (`.md-link`, commit e6f2f53).
10. Legacy-only chunks (no modern ES modules) and the full polyfill set. Bundle size matters on an A7 CPU and 1GB of RAM, and the `low-end` class disables animations.

---

## 6. UX issues, bugs and dead code

### 6.1 Layout and ergonomics (known complaints)

- **The top tab bar is small and impractical**:
  - The close × is 18px and invisible until hover on desktop (`fe/App.css:115-130`).
  - Tabs are 33px tall inside a 52px bar.
  - Horizontal overflow scrolls on a 2px scrollbar with no overflow menu or tab list, and there is no keyboard tab switching.
  - On phones, tabs shrink to 56-140px with ellipsised titles.
  - The status dot is the only liveness cue.
- **No connection-state styling on tabs**: disconnected, connecting and error tabs simply lose their dot (`TabsContext.tsx:249-252`; there is no CSS for it). `TabStatusIcon` declares `waiting`/`error`/`loading`, which are never produced.
- **Focus stealing**: every `openTab` activates the new tab. That covers pool syncs on visibility change, sessions opened by the orchestrator (`agent_session_opened`) and sessions started on other devices. The user gets pulled away from what they were reading.
- **Hamburger height is fixed at 52px** while `--topbar-height` drops to 48px at ≤640px.
- The input row crowds up to five 48px buttons beside the textarea on phones.
- **No session search, filtering or date grouping** in the sidebar. The sidebar section (Sessions, Memory or Visuals) resets on reload.
- The global config page is one long scrolling panel with no navigation. Range sliders, auto-save and disabled-while-saving controls make it feel janky.

### 6.2 Small inconsistencies and accessibility

- **Fenced code without a language** renders as inline code with no block styling and no copy button (`fe/components/Markdown.tsx:29-41`).
- Auto-scroll is keyed on the last message's block count, not its content length (`MessageList.tsx:153-160`).
- **Range sliders PUT `/api/config` on every input event** while dragging (`ConfigPage.tsx:538-599`).
- **Backend error details are swallowed**. `json()` throws `"<status> <statusText>"`, so the config page shows "400 Bad Request" instead of "Directory does not exist…" (`fe/api/rest.ts:5-9`).
- **`enabled_mcps: []` means "all enabled"** on the backend (`api/routes/config.py:125`), but the UI shows every box unchecked.
- **The iOS zoom fix is defeated** at ≤640px: textarea `font-size: 0.92rem` (13.8px) overrides the 16px rule from ≤768px (`fe/App.css:2879` vs `3638-3640`).
- Error banner: shows raw codes such as `websocket_error`, has no dismiss, and is cleared only by `session_started`.
- Stall banner wording "No response from Claude" is used for Qwen and Gemini too.
- **Rewind confirm copy is wrong** ("reopen from the sidebar"), because the tab is auto-reopened.
- **Rename, rewind, fork and session config before the first turn silently do nothing**. The ⋮ menu and gear are still shown (`ChatPanelContainer.tsx:242-256`; `TabBar.tsx:65-69`).
- Tab rename hint shows only on the active tab. Double-click is the only rename affordance on tabs, and there is no long-press equivalent on touch.
- **Modals**: there is no Escape handling, no focus trap and no `role="dialog"`/`aria-modal` (`ConfirmModal`, `ConfirmCloseModal`, `OrchestratorModal`). The config panels do handle Escape.
- Icon-only buttons rely on `title`, with no `aria-label`: the tab close ×, send, interrupt, compact, gear and session row icons.
- Sidebar items are `div`s with `onClick`, so they are not keyboard-focusable. MemoryTree uses real buttons, which is good.
- The thinking block uses "+"/"−" text and tool blocks use "▶/▼" glyphs. These are inconsistent toggles without `aria-expanded` (except memory folders).
- A `Bash` tool block expanded before its result arrives shows nothing (`ToolUseBlock.tsx:1074`). `button` contains block `div` elements, which is invalid HTML, in `BashBlock` and `SendToAgentBlock`.
- `TodoWrite` and `Task` blocks cannot be collapsed. Long todo lists dominate the view.
- Memory links resolve against the app root (F-37). `/memory/<path>` is fetched without encoding (`fe/api/rest.ts:58`).
- `countFiles` counts a directory whose `children` is null as one file (`Sidebar.tsx:274-279`). The backend never sends such a directory, so this is latent.
- Remote console logging has no rate or size cap and is always on (F-38).
- `SessionConfigPage` treats `idle` as "stopped", and its "Save and Restart" only re-sends `start` (F-32). There is no UI to stop a chat session.
- The audio-record button checks whether **any** model supports audio, not the selected orchestrator model.
- `delete` does not close the pool session and does not close the tab (next section).

### 6.3 Behavioural bugs (fix in the rebuild; do not replicate)

1. **Passive voice viewers wedge** (F-29). Mirrored provider events flip a non-owner's `voiceStatus`, which hides its text input permanently. The guard named in the comment does not exist (`useVoiceOrchestrator.ts:874-895`).
2. **WebRTC voice recording never records**. Processor name mismatch: `"pcm-capture-processor"` vs `"pcm-capture"` (`fe/voice/AudioRecorder.ts:80,107`; `fe-root/public/pcm-capture-worklet.js:96`).
3. **Voice tool calls never complete in the transcript**: `dispatchToolResult` is never called (F-26).
4. **The VAD "listening Ns" indicator is never visible**: it lives on VoiceButton, which is hidden while voice is active (F-27).
5. **Tool results lost after an interleaved user message**, plus empty assistant bubbles (§4.2).
6. **`dropLastN` can mismatch** the backend's visible-message count once live-merged runs, client-only notes or dropped thinking-only previews are present (§4.4). Rewind or fork may cut at the wrong place.
7. **Reload mid-turn can duplicate content**: REST history plus WS replay with no dedupe (§4.4).
8. **Deleting an open session leaves its tab open**: `closeTab(sdkId)`, but tabs are keyed by `local_id` (`fe/App.tsx:83-89`).
9. **A terminated session's tab closes immediately**. The `session_stopped` that follows `session_terminated` triggers `onSessionClosed`, which defeats the "Continue in new tab" banner. The code comment says otherwise (`useChatInstance.ts:797-827`). Verify against the backend ordering; the intended behaviour is to keep the tab with the banner.
10. **Orchestrator voice start sends `stop` on the text WS**, which stops the orchestrator session server-side (`ChatPanelContainer.tsx:140-145` → `api/routes/orchestrator.py:339-353`). The next `voice_start` re-creates or resumes it, and `restart()` re-sends `start` afterwards. For a brand-new orchestrator with no `resumeSdkId`, continuity across this stop/start is questionable. Confirm the intended design ("voice is a re-armable mode of the same session") and drop the stop if it is vestigial.
11. **Clicking a past orchestrator conversation while any orchestrator tab is open** only switches to the open one. The past transcript cannot be viewed, and the "resume" `OrchestratorModal` path is unreachable (`Sidebar.tsx:59-69`).
12. **The compat `remark-gfm` shim strips inline formatting** from every non-table paragraph (§5.3).
13. **The compat MessageList shim is not applied** (alias vs relative import) while momentum scrolling is enabled (§5.3).
14. **Compat overlays likely broken** by the unsupported `inset` (§5.4).
15. The orchestrator `read_file` tool renders as generic JSON because of the Qwen name collision (F-05).
16. Thinking blocks are absent from history, so a reloaded conversation loses its "Thought" sections. This is a backend `extract_blocks` limitation, noted here for parity expectations.
17. A tool on an older history page shows "done" with empty output when its result is on a newer page (§4.3).

### 6.4 Dead code and stale artifacts

- `fe/hooks/useChat.ts` (484 lines): the old single-session hook. Never imported. It is the only place that handles `tool_executing` and `tool_progress` and sets `executing`.
- `MessageBlock.tool_use.executing` (`fe/types.ts:268-269`), and the `tool_executing` / `tool_progress` / `nested_session_event` server types: received but ignored.
- `fe/components/McpSelectionModal.tsx` (114 lines) plus the `.mcp-modal`/`.mcp-server-*` CSS (`fe/App.css:2711-2760`), plus `useChatInstance.restartWithMcps`, `selectedMcps` and the `mcpRestartingRef` logic.
- `useChatInstance` exports that nothing calls: `command`, `sendVoiceEvent`, `startVoiceMode`, `dispatchToolResult`.
- `useVoiceOrchestrator` fields that nothing reads: `voiceErrorDetails`, `isActive`, the `onSessionStarted`/`onStatusChange` options. `VoiceButton.MicMutedIcon` export and VoiceButton's `onStop` branch.
- `fe/api/rest.ts`: `getSession`, `getPreview`.
- `TabStatusIcon` values `waiting`/`error`/`loading`. `formatStatus` has no case for `retrying` or `summarizing`.
- `@tanstack/react-virtual` dependency (never imported). `.sidebar-overlay` CSS (`fe/App.css:3615`, `3673`; no element uses it). `fc/tsconfig.json` excludes `useVoiceSession.ts`, a file that no longer exists.
- `fc/src/shims/MessageList.tsx`: effectively dead (§5.3).
- `useAudioRecorder` `wav` format branch (MediaRecorder never yields wav).
- **Stale documentation**: CLAUDE.md mentions `PermissionModal` (now `PermissionBar`), `useVoiceSession.ts` (gone) and "plugin-legacy in main frontend" (compat only). The auto-memory "session config … skills/agents" note is outdated.

---

## 7. Load-bearing behaviours checklist (must preserve)

1. All open sessions stay live in the background (WS, voice, scroll, draft). Hidden panels are not unmounted (F-01).
2. `start` is re-sent on every WS open and on every visibility-visible event with an OPEN socket. Reconnect is 2s × 10 and paused while the page is hidden (F-01).
3. The per-tab sessionStorage `(stream_id, seq)` checkpoint, `resume_from`, `resume_state` seeding and the `replay_overflow` REST fallback (F-24).
4. The history-init effect must not re-run when `resumeSdkId` is assigned after the first turn (F-01).
5. The message ordering algorithm of §4.2: per-kind streaming-block extension, authoritative `*_complete`, and tool result merged by id.
6. The bottom-relative `dropLastN` contract and the rewind order close → truncate → reopen (F-08, F-09).
7. The freeze buffer while scrolled up, prepend scroll restoration, and the 150px / 80px thresholds (F-11).
8. Permission request_id matching. Typing a message counts as deny-with-feedback. The ExitPlanMode plan is rendered inline (F-14).
9. Tab titles derived from the session list (F-19, §1.6).
10. Pool sync on mount and visibility, plus the `agent_session_opened`/`closed` pushes (F-23).
11. Voice:
    - queue provider commands until the transport or data channel is ready;
    - forward `voice_session_update` only when initiator and WebRTC;
    - 30s connection-info wait;
    - send `response.cancel` only while a response is in flight;
    - local audio flush on barge-in;
    - Gemini transcript coalescing;
    - empty-text `turnComplete` finalisation;
    - 5s ending timeout;
    - passive viewers ignore lifecycle events and act only on `voice_owner_active` (F-26 to F-29).
12. Google voice model auto-correct (F-31).
13. Memory frontmatter split into a collapsed block (F-37). Viz iframe sandbox tokens and remount-to-reload (F-36).
14. The `generateUUID` fallback for non-secure origins. The low-end class and reduced-motion handling. Remote console logging for devtools-less devices.
15. Compat: margin-based gap shims (including the text-node trap), no lookbehind or named groups, a GFM table fallback that keeps inline markdown, safe programmatic scrolling under momentum scroll, explicit offsets instead of `inset`.
