#!/usr/bin/env python3
"""G-01 lite script: scripted local backend (spec 14 §6.2). NEVER the live Jetson.

Serves on 0.0.0.0:<port> (the AVD reaches it at http://10.0.2.2:<port>):
  GET  /api/sessions/pool/live            one orchestrator in the live pool
  WS   /api/orchestrator/chat             `start` -> session_started; `voice_stop` -> voice_ended
  POST /api/orchestrator/voice/session    scripted error (503)  -> voice Error -> finalize
  GET  /api/config/openai-key             fake key for the Whisper client
  POST /v1/audio/transcriptions           Whisper stand-in (debug.archie.whisper_url points here)
  GET  /api/auth/status                   discovery probe
Every request/frame is printed (one JSON line) so the script can assert on it.

Run with the repo venv:  .venv/bin/python mock_backend.py --port 8799 --transcript "hello my friend"
"""
import argparse
import json
import sys
import time

import uvicorn
from fastapi import FastAPI, Request, WebSocket, WebSocketDisconnect
from fastapi.responses import JSONResponse

LOCAL_ID = "g01-orchestrator"
SDK_ID = "g01-jsonl"


def log(kind, **kw):
    print(json.dumps({"t": round(time.time(), 3), "kind": kind, **kw}), flush=True)


def build(transcript: str, voice_code: int) -> FastAPI:
    app = FastAPI()

    @app.middleware("http")
    async def trace(request: Request, call_next):
        log("http", method=request.method, path=request.url.path)
        return await call_next(request)

    @app.get("/api/sessions/pool/live")
    def pool():
        return [{"local_id": LOCAL_ID, "sdk_session_id": SDK_ID, "status": "idle", "is_orchestrator": True}]

    @app.post("/api/orchestrator/voice/session")
    def voice_session():
        return JSONResponse({"detail": "scripted failure (G-01)"}, status_code=voice_code)

    @app.get("/api/config/openai-key")
    def key():
        return {"api_key": "sk-g01-test"}

    @app.post("/v1/audio/transcriptions")
    async def whisper(request: Request):
        body = await request.body()
        log("whisper", bytes=len(body), answer=transcript)
        return {"text": transcript}

    @app.get("/api/auth/status")
    def auth():
        return {"authenticated": True, "headless": True}

    @app.websocket("/api/orchestrator/chat")
    async def chat(ws: WebSocket):
        await ws.accept()
        log("ws", event="open")
        try:
            while True:
                text = await ws.receive_text()
                try:
                    frame = json.loads(text)
                except ValueError:
                    continue
                kind = frame.get("type")
                if kind not in ("voice_audio_in", "voice_event"):
                    log("frame", type=kind)
                if kind == "start":
                    await ws.send_text(json.dumps({"type": "session_started", "session_id": LOCAL_ID, "jsonl_id": SDK_ID, "voice": False}))
                elif kind == "voice_stop":
                    await ws.send_text(json.dumps({"type": "voice_ended", "reason": "user"}))
        except WebSocketDisconnect:
            log("ws", event="close")

    return app


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8799)
    ap.add_argument("--transcript", default="hello my friend")
    ap.add_argument("--voice-code", type=int, default=503)
    a = ap.parse_args()
    log("start", port=a.port, transcript=a.transcript)
    uvicorn.run(build(a.transcript, a.voice_code), host="0.0.0.0", port=a.port, log_level="warning")
    sys.exit(0)
