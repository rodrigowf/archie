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

