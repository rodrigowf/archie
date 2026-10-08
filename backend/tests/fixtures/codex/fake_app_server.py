"""A scripted stand-in for ``codex app-server`` (JSON-RPC over stdio).

Speaks the subset of the protocol the Codex harness uses, with message
shapes copied from a real 0.161 transcript.  The turn's behaviour is chosen
by a keyword in the prompt:

* ``hello``    reasoning summary + streamed answer
* ``ls``       a commandExecution item (``/bin/bash -lc 'ls'``) then an answer
* ``patch``    a fileChange item (one updated file)
* ``plan``     turn/plan/updated
* ``slow``     one delta, then waits for turn/interrupt → status interrupted
* ``fail``     error notification + failed turn
* ``approve``  asks the client for a command approval first
* ``crash``    the process exits mid-turn
* ``turnerr``  turn/start returns a JSON-RPC error

Every request received is appended (one JSON object per line) to the file
named by ``FAKE_CODEX_LOG``; argv goes on the first line.
"""

import json
import os
import sys
import threading
import time

LOG = os.environ.get("FAKE_CODEX_LOG")
THREAD = "01a118ca-0000-7000-8000-000000000001"
_lock = threading.Lock()
_pending: dict = {}
_interrupt = threading.Event()
_next_id = [1000]


def log(obj):
    if LOG:
        with open(LOG, "a") as f:
            f.write(json.dumps(obj) + "\n")


def send(obj):
    with _lock:
        sys.stdout.write(json.dumps(obj) + "\n")
        sys.stdout.flush()


def note(method, params):
    send({"method": method, "params": params})


def item(event, it, turn):
    note(f"item/{event}", {"item": it, "threadId": THREAD, "turnId": turn})


def finish(turn, status="completed", error=None):
    note("thread/tokenUsage/updated", {"threadId": THREAD, "turnId": turn, "tokenUsage": {
        "total": {"totalTokens": 120, "inputTokens": 100, "cachedInputTokens": 60, "outputTokens": 20, "reasoningOutputTokens": 5},
        "last": {"totalTokens": 120, "inputTokens": 100, "cachedInputTokens": 60, "outputTokens": 20, "reasoningOutputTokens": 5},
        "modelContextWindow": 258400}})
    note("turn/completed", {"threadId": THREAD, "turn": {
        "id": turn, "items": [], "status": status, "error": error}})


def agent(turn, text, item_id="msg_1"):
    item("started", {"type": "agentMessage", "id": item_id, "text": ""}, turn)
    for chunk in text.split(" "):
        note("item/agentMessage/delta", {"threadId": THREAD, "turnId": turn, "itemId": item_id, "delta": chunk + " "})
    item("completed", {"type": "agentMessage", "id": item_id, "text": text, "phase": "final_answer"}, turn)


def run_turn(turn, text):
    note("turn/started", {"threadId": THREAD, "turn": {"id": turn, "status": "inProgress"}})
    item("completed", {"type": "userMessage", "id": "u1", "content": [{"type": "text", "text": text}]}, turn)
    if "crash" in text:
        os._exit(3)
    if "hello" in text:
        rid = "rs_1"
        item("started", {"type": "reasoning", "id": rid, "summary": [], "content": []}, turn)
        note("item/reasoning/summaryPartAdded", {"threadId": THREAD, "turnId": turn, "itemId": rid, "summaryIndex": 0})
        note("item/reasoning/summaryTextDelta", {"threadId": THREAD, "turnId": turn, "itemId": rid, "delta": "**Greeting**", "summaryIndex": 0})
        note("item/reasoning/summaryPartAdded", {"threadId": THREAD, "turnId": turn, "itemId": rid, "summaryIndex": 1})
        note("item/reasoning/summaryTextDelta", {"threadId": THREAD, "turnId": turn, "itemId": rid, "delta": "second", "summaryIndex": 1})
        item("completed", {"type": "reasoning", "id": rid, "summary": ["**Greeting**", "second"], "content": []}, turn)
        agent(turn, "Hello there friend")
        finish(turn)
    elif "ls" in text:
        cid = "exec-1"
        item("started", {"type": "commandExecution", "id": cid, "command": "/bin/bash -lc 'ls -la'",
                         "cwd": "/tmp", "status": "inProgress", "aggregatedOutput": None, "exitCode": None}, turn)
        note("item/commandExecution/outputDelta", {"threadId": THREAD, "turnId": turn, "itemId": cid, "delta": "a.txt\n"})
        item("completed", {"type": "commandExecution", "id": cid, "command": "/bin/bash -lc 'ls -la'",
                           "cwd": "/tmp", "status": "completed", "aggregatedOutput": "a.txt\n", "exitCode": 0}, turn)
        agent(turn, "One entry", "msg_2")
        finish(turn)
    elif "patch" in text:
        fid = "exec-2"
        change = {"path": "/tmp/x.py", "kind": {"type": "update", "move_path": None},
                  "diff": "@@ -1,2 +1,2 @@\n a = 1\n-b = 2\n+b = 3\n"}
        item("started", {"type": "fileChange", "id": fid, "changes": [change], "status": "inProgress"}, turn)
        item("completed", {"type": "fileChange", "id": fid, "changes": [change], "status": "completed"}, turn)
        finish(turn)
    elif "plan" in text:
        note("turn/plan/updated", {"threadId": THREAD, "turnId": turn, "explanation": None, "plan": [
            {"step": "Read code", "status": "completed"}, {"step": "Fix bug", "status": "inProgress"},
            {"step": "Test", "status": "pending"}]})
        finish(turn)
    elif "slow" in text:
        item("started", {"type": "agentMessage", "id": "msg_s", "text": ""}, turn)
        note("item/agentMessage/delta", {"threadId": THREAD, "turnId": turn, "itemId": "msg_s", "delta": "1\n2\n"})
        _interrupt.wait(20)
        finish(turn, "interrupted")
    elif "fail" in text:
        note("error", {"threadId": THREAD, "turnId": turn, "willRetry": True, "error": {"message": "retrying"}})
        note("error", {"threadId": THREAD, "turnId": turn, "willRetry": False,
                       "error": {"message": "You've hit your usage limit.", "codexErrorInfo": "usageLimitExceeded"}})
        finish(turn, "failed", {"message": "You've hit your usage limit."})
    elif "approve" in text:
        rid = _next_id[0]
        _next_id[0] += 1
        ev = threading.Event()
        _pending[rid] = [ev, None]
        send({"id": rid, "method": "item/commandExecution/requestApproval",
              "params": {"threadId": THREAD, "turnId": turn, "itemId": "exec-9", "command": "rm -rf build"}})
        ev.wait(10)
        agent(turn, "decision " + json.dumps(_pending[rid][1]), "msg_a")
        finish(turn)
    else:
        agent(turn, "ok")
        finish(turn)


def main():
    log({"argv": sys.argv[1:], "env_home": os.environ.get("CODEX_HOME"),
         "has_openai_key": "OPENAI_API_KEY" in os.environ})
    turn_n = 0
    for raw in sys.stdin:
        raw = raw.strip()
        if not raw:
            continue
        msg = json.loads(raw)
        if "method" not in msg:
            # Response to one of our server requests.
            slot = _pending.get(msg.get("id"))
            if slot:
                slot[1] = msg.get("result", msg.get("error"))
                slot[0].set()
            continue
        log(msg)
        method, mid, params = msg["method"], msg.get("id"), msg.get("params") or {}
        if mid is None:
            continue
        if method == "initialize":
            send({"id": mid, "result": {"userAgent": "fake/0.161.0", "codexHome": os.environ.get("CODEX_HOME"),
                                        "platformFamily": "unix", "platformOs": "linux"}})
        elif method == "model/list":
            send({"id": mid, "result": {"data": [
                {"id": "gpt-6-luna", "model": "gpt-6-luna", "displayName": "GPT-6-Luna", "hidden": False,
                 "supportedReasoningEfforts": [{"reasoningEffort": e} for e in ("low", "medium", "high")],
                 "defaultReasoningEffort": "medium", "inputModalities": ["text", "image"], "isDefault": True},
                {"id": "gpt-5.6-terra", "model": "gpt-5.6-terra", "displayName": "GPT-5.6-Terra", "hidden": False,
                 "supportedReasoningEfforts": [{"reasoningEffort": e} for e in ("low", "medium", "high", "ultra")],
                 "defaultReasoningEffort": "medium", "inputModalities": ["text"], "isDefault": False},
            ], "nextCursor": None}})
        elif method in ("thread/start", "thread/resume", "thread/fork"):
            tid = params.get("threadId", THREAD) if method == "thread/resume" else THREAD
            if method == "thread/resume" and tid == "missing":
                send({"id": mid, "error": {"code": -32600, "message": "no rollout found for thread id missing"}})
                continue
            send({"id": mid, "result": {"thread": {"id": tid, "status": {"type": "idle"}},
                                        "model": params.get("model") or "gpt-6-luna"}})
        elif method == "turn/start":
            text = (params.get("input") or [{}])[0].get("text", "")
            if "turnerr" in text:
                send({"id": mid, "error": {"code": -32600, "message": "model not supported"}})
                continue
            turn_n += 1
            turn = f"turn-{turn_n}"
            _interrupt.clear()
            send({"id": mid, "result": {"turn": {"id": turn, "status": "inProgress"}}})
            threading.Thread(target=run_turn, args=(turn, text), daemon=True).start()
        elif method == "turn/interrupt":
            _interrupt.set()
            send({"id": mid, "result": {}})
        else:
            send({"id": mid, "error": {"code": -32601, "message": f"unknown method {method}"}})


if __name__ == "__main__":
    main()
