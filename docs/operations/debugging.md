---
name: debugging
category: archie/operations
tags: [debugging, logs, journalctl, logcat, remote-console, websocket, probe, jsonl, voice, adb, chrome-devtools]
created: 2026-04-27
modified: 2026-10-06
summary: Where every log lives, the direct WebSocket probe, reading JSONL, voice debugging signals, and the device and browser playbooks.
source: curated (consolidated from memory notes feedback_voice_debug_diagnose_before_patching.md, feedback_diagnose_via_direct_ws_probe.md, feedback_run_test_before_speculating.md, feedback_use_adb_input_for_device_tests.md, feedback_android_ws_keepalive_silent_drop.md, feedback_qwen_voice_dashscope_2026_05.md, project_qwen_voice_gate_no_staleness_clear.md (index line), project_voice_session_update_restart_race_2026_07_21.md (index line), assistant/infrastructure/server_hub_project.md, shared/skills/debug-app/SKILL.md, context/skills/server-management/SKILL.md; verified against code 2026-10-06)
references:
  - working-rules.md
  - troubleshooting.md
  - ../infrastructure/jetson-server.md
  - ../infrastructure/deployment.md
  - ../infrastructure/ssh-remote-execution.md
  - ../infrastructure/context-sync.md
  - ../architecture/agent-sessions.md
  - ../architecture/orchestrator.md
  - ../voice/architecture.md
  - ../voice/lifecycle.md
  - ../voice/qwen-omni.md
  - ../clients/web.md
  - ../clients/android.md
  - ../devices/devices.md
---

# Debugging

How to find out what actually happened. The rule behind all of this is in
[working-rules.md](working-rules.md#diagnosing): get the real log for the failure window, from every
side involved, before proposing a fix.

## Where the logs are

| Log | Location | How to read |
|---|---|---|
| Backend, Jetson | stdout of `agentic-backend.service` → journald | `sudo journalctl -u agentic-backend.service --since "HH:MM" --no-pager` (remotely: `echo "$SERVER_PASSWORD" \| sudo -S journalctl …` inside an SSH heredoc) |
| Backend, laptop | `logs/api_<YYYYMMDD_HHMMSS>.log` (written by `./start.sh` and the `/debug-app` launch pattern) | newest file in `logs/` |
| Web dev server | `logs/frontend_<ts>.log` | Vite compile/HMR errors |
| Remote console | `logs/remote_console.log` — lines POSTed to `/api/debug/log` by the web app (inline `apps/web/scripts/remote-console.js`, API in `apps/web/src/platform/remoteLog.ts`; on by default in the compat build, toggle in Settings → This device; errors always sent) and by Android (`ArchieApi` in `apps/android/core/network`) | `GET /api/debug/log`, or read the file on the machine that served the client. The way to see the iPad's console |
| Voice sessions | `logs/voice/<ts>_<session>.log` per relay voice session (`backend/orchestrator/voice_relay.py`); with `VOICE_DEBUG_DUMP_MIC=1` also `logs/voice/<ts>_<sid>.mic.wav`; `VOICE_DEBUG_QWEN_BODIES=1` / `VOICE_DEBUG_GEMINI_BODIES=1` dump provider setup bodies next to the log | on the backend machine |
| CLI stderr | backend log lines `claude CLI stderr [<local_id>]: …` | journald / `api_*.log` |
| Indexer | backend log: `History indexer started`, `Session files changed, re-indexing...`, `Indexer … failed` with stdout/stderr tails | journald |
| context-sync | `journalctl --user -u context-sync -f` (both machines) | see [context-sync.md](../infrastructure/context-sync.md#operating-it) |
| Jetson system | `sudo journalctl -t fb0-blank`, `sudo journalctl -k`, nginx logs in `/home/rodrigo/logs/`, watchdog lines in the journal | see [jetson-server.md](../infrastructure/jetson-server.md) |
| Android | `adb -s <serial> logcat -d` (see tags below) | |
| Browser | DevTools console; via Chrome DevTools MCP `list_console_messages` | `/debug-app` |

journald does **not** log WebSocket frames. When the question is "what did the client receive or
send", the answer is in the device logcat or browser console, or in a direct probe.

## Direct WebSocket probe

Backend session state (`is_voice`, voice provider, voice lifecycle state, ownership) is not exposed
over REST — `/api/sessions/pool/live` reports only summary fields. The authoritative view is the
`session_started` frame the backend sends every new subscriber. A one-off Python client shows
exactly what every device is being told:

```python
# probe.py — run with the venv: context/scripts/run.sh probe.py
import asyncio, json, ssl, websockets
URL = "ws://127.0.0.1:8765/api/orchestrator/chat"   # on the Jetson; chat tabs: /api/sessions/chat
async def main():
    async with websockets.connect(URL, max_size=None) as ws:
        await ws.send(json.dumps({"type": "start", "local_id": "<local_id>", "resume_sdk_id": "<sdk_id>"}))
        end = asyncio.get_event_loop().time() + 45
        while asyncio.get_event_loop().time() < end:
            try:
                print(await asyncio.wait_for(ws.recv(), timeout=5)[:2000])
            except asyncio.TimeoutError:
                pass
asyncio.run(main())
```

- Run it on the Jetson against loopback (`127.0.0.1:8765`), or from the LAN through nginx
  (`wss://192.168.0.200/api/orchestrator/chat` with certificate verification disabled — the cert is
  self-signed).
- Take `local_id` / SDK id from `GET /api/sessions` or `/api/sessions/pool/live`.
- Ghost-voice signature: `voice: true, voice_initiator: false` plus a `voice_session_update` arriving
  on a plain `start` (not `voice_start`) — the backend thinks voice is active though nobody is
  connected. Listen passively ~45 s: a live voice session emits transcripts/audio; a ghost goes
  silent.
- Cheapest remediation once confirmed: `POST /api/sessions/{local_id}/close` (drops the pool entry,
  keeps the JSONL).

## Inspecting conversation JSONL

| Files | Harness |
|---|---|
| `context/<uuid>.jsonl` | Claude Code sessions and orchestrator sessions (`OrchestratorSession._get_jsonl_path`) |
| `context/chats/*.jsonl` | Qwen (`<uuid>.jsonl`) and Gemini (`session-<ts>-<id>.jsonl`) |
| `context/.titles.json` | UUID → title — find a session by topic |

Line shapes differ: Claude entries carry SDK event types (`user`, `assistant`, `file-history-snapshot`,
…); Qwen uses `{uuid, parentUuid, sessionId, message: {role, parts}}`; Gemini files start with a
`{sessionId, projectHash, kind}` header. Orchestrator voice turns carry `"source": "voice_transcription"`
/ `"voice_response"`, user transcripts start with `[voice]`, interruptions are `voice_interrupted`
entries, and background agent completions are `background_notification` entries tied to the
`tool_use_id`. Useful passes: `jq -c 'select(.type=="assistant")' <file>`, `tail -n 50 <file> | jq .`,
or `/recall` / `search_history` for semantic lookup. Remember `context/` is invisible to a root-level
grep ([working-rules.md](working-rules.md#before-acting)).

For SSH-remote sessions check the `init` event (`cwd`, `memory_paths.auto`, `slash_commands`) —
[ssh-remote-execution.md](../infrastructure/ssh-remote-execution.md#debugging).

## Voice debugging signals

| Signal | Where | Meaning |
|---|---|---|
| `voice_session_closed session_id=… ` + reason/close code | backend log (`voice_relay.py`) | The relay closed. "Stopped suddenly mid-call" almost always shows here — read it first |
| `voice_command_deferred session=… type=… reason=provider_gate` | backend log (`backend/api/routes/orchestrator.py`) | A command was parked behind the provider gate |
| `voice_command_drain session=… type=…` | backend log | A parked command was sent |
| `pendingCommands=N` in `[VM] ===== SESSION START =====` | Android logcat (`WebRtcTransport`, `apps/android/core/voice`) | Commands queued on the data-channel gate at session start |

Many `voice_command_deferred` with **zero** `voice_command_drain` is the signature of a parked
`response.create` whose only drain trigger is an event that sending it would have caused — a
recurring bug class; any new provider or queue must have a drain path that does not depend on its own
output. A restart that opens the OpenAI session on bare defaults (wrong voice, no memory) is
visible on-device as `pendingCommands=0` where the session config should have been queued. Provider
quirks: Qwen (DashScope) WS 1011 "Parse RealtimeEvent error" is a server-side validator — inspect the
`VOICE_DEBUG_QWEN_BODIES` dump and bisect payloads; a mid-monologue cut at ~30–40 s is server VAD
force-commit, handled by client-side VAD ([qwen-omni.md](../voice/qwen-omni.md)). Lifecycle details:
[lifecycle.md](../voice/lifecycle.md).

## Device playbook (Android)

1. **Reproduce with a log**, don't theorize: `adb -s <serial> logcat -c`, reproduce, then
   `adb -s <serial> logcat -d > /tmp/run.log` (bookended by marker lines
   `adb shell "log -t MARKER 'phase X'"`). Prefer `-c` + `-d` over a backgrounded streamer you later
   have to kill (and never `pkill -f` it — it kills your shell).
2. Filter by app pid (`logcat -d --pid=$(adb shell pidof <package>)`) or by tags. Useful tags in the
   current apps: `ArchieVoice`, `VoiceController`, `VoiceHost`, `VoiceDelivery`, `OpenAIVoiceProvider`,
   `AudioRouter`, `EchoDuck`, `PcmSink`, `WakeWordDetector`, `VoskRecogEngine`, `WhisperConfirmer`,
   `TriggerRouter`, `AssistantService`, `ArchieLite`, `LiteMain`, plus `AndroidRuntime` and `FATAL`
   for crashes. Native crashes: also pull the tombstone (`adb shell ls /data/tombstones`, or the
   `DEBUG` tag in logcat).
3. Drive the UI with `adb shell input` + `uiautomator dump` ([working-rules.md](working-rules.md#testing-on-devices)).
4. Correlate with the backend log for the same minutes (Jetson journald, or the laptop `api_*.log`
   when testing against the laptop backend).
5. Package names and serials: [android.md](../clients/android.md) and
   [devices.md](../devices/devices.md).

**Chat stopped updating mid-session** — the classic cause was a WebSocket closed by okhttp
(`1011 keepalive ping timeout`, common after Doze) followed by a reconnect that never re-sent
`start`, so the socket was not subscribed and events were silently dropped. Check the log for a
close, a reconnect, and whether a `start` with `resume_from` follows. Clients must re-send `start`
with their resume checkpoint on **every** (re)connect; the backend replays the `(stream_id, seq)`
ring or sends `replay_overflow`, after which the client refetches over REST. The current app pings
every 30 s (`NetworkTuning.WS_PING_INTERVAL_MS`). Verify a fix by toggling airplane mode briefly.

## Browser playbook (`/debug-app`)

The `/debug-app` skill (`shared/skills/debug-app/SKILL.md`) starts the backend and the Vite dev
server detached with `setsid` (logs to `logs/api_<ts>.log`, `logs/frontend_<ts>.log`) and drives a
throwaway Chrome through the Chrome DevTools MCP: `new_page` (https://localhost:5450 when
`context/certs/` has a cert, else http), `take_snapshot` before every interaction (element uids
change), `click`/`fill`/`wait_for`, `list_console_messages`, `list_network_requests`,
`take_screenshot`. The backend also serves the last production build at `http://localhost:8765/`
(and `/compat/`). For Rodrigo's real logged-in Chrome use `/browser-control` instead.

## Performance mysteries

- One core pinned, nothing visible: `top -b -n 1 -H -p <pid>` to find the thread, then repeated
  `py-spy dump --pid <pid>`; `_deliver_cancellation` in the main stack = an uncancellable worker
  thread (see the 2026-05 indexer incident in [jetson-server.md](../infrastructure/jetson-server.md#history)).
- Heat/fan with low `%CPU`: sort by accumulated CPU time (`ps -eo pid,comm,time --sort=-time`);
  check IRQ rates in `/proc/interrupts`.
- Memory: `free -h` (watch **available**), `ps --sort=-rss`, `/proc/<pid>/smaps` for the search
  server.
