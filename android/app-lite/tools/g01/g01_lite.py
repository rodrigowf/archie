#!/usr/bin/env python3
"""G-01 lite script (spec 14 §6.2) on the A300M_API21 AVD, driven by `adb shell input` + logcat markers.

  arm the wake word -> inject speech through the emulator's virtual mic (host WAV) ->
  `Vosk match` -> `Whisper CONFIRMED` (real WhisperClient, URL redirected to the local mock by the
  debug-only `debug.archie.whisper_url` property) -> voice CONNECTING against the local mock backend ->
  scripted `POST /api/orchestrator/voice/session` 503 -> voice Error -> finalize -> wake re-armed
  >= 1,500 ms later (RS-09, RS-30).

Never touches the live Jetson: the AVD's OUTPUT to 192.168.0.0/16 and 100.64.0.0/10 is rejected
with iptables first (the app's default server is the Jetson).

Prerequisites (one emulator at a time, holding /tmp/archie-locks/testenv.lock):
  pw-loopback -m '[ MONO ]' \
    --capture-props='media.class=Audio/Sink node.name=archie_g01_sink' \
    --playback-props='media.class=Audio/Source node.name=archie_g01_src' &
  flock /tmp/archie-locks/testenv.lock env PULSE_SOURCE=archie_g01_src \
    emulator -avd A300M_API21 -no-snapshot-save -no-boot-anim -no-window -memory 1024 -cores 2 \
    -gpu swiftshader_indirect -allow-host-audio &
  adb install -r app-lite/build/outputs/apk/debug/app-lite-debug.apk   (built with -Parchie.emulatorAbis=true)

Run:  .venv/bin/python android/app-lite/tools/g01/g01_lite.py [--wav tests/fixtures/voice_speech_24k.wav]

Status (C-01, 2026-10-04): everything up to the injection runs green on the AVD (LAN block, mock,
UI configuration through Settings, wake armed on Vosk with the patched x86 libvosk). The injection
itself does NOT reach the guest: the emulator must run the windowed binary (`-qt-hide-window`; the
`-no-window` headless qemu has no PulseAudio backend: "Could not init `pa' audio driver"), the host
source then carries the WAV and is linked to qemu's input, but the API 21 goldfish input HAL never
hands speech to AudioRecord (no onset, no Vosk partials), and after an AudioRecord client dies
mediaserver can wedge in that HAL (AudioRecord.getMinBufferSize blocks in a binder call). Next
options: the emulator gRPC `injectAudio` API, an API 22/23 image, or run this sequence on the A300M.

No "wake up" recording exists in the repo (no TTS on the host), so by default the wake phrase is
set to "my friend" (and the talk phrase moved to "over and out") through the Settings UI, and the
recorded fixture "hello my friend how you doing" is injected. Pass --wake-phrase/--wav to use a real
"wake up" recording when one exists.
"""
import argparse
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "../../../.."))
ADB = os.path.expanduser("~/Android/Sdk/platform-tools/adb")
PKG = "com.assistant.peripheral"


def sh(*args, check=True, capture=True, timeout=60):
    r = subprocess.run(list(args), capture_output=capture, text=True, timeout=timeout)
    if check and r.returncode != 0:
        raise RuntimeError(f"{' '.join(args)} -> {r.returncode}: {r.stderr.strip()}")
    return r.stdout


def adb(*args, **kw):
    return sh(ADB, *args, **kw)


def step(msg):
    print(f"[g01] {msg}", flush=True)


# ── UI helpers (uiautomator dump + input tap) ──────────────────────────────────────────────────

def dump():
    adb("shell", "uiautomator", "dump", "/data/local/tmp/g01.xml", check=False)
    xml = adb("shell", "cat", "/data/local/tmp/g01.xml")
    return ET.fromstring(xml[xml.index("<"):])


def center(node):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
    return (x1 + x2) // 2, (y1 + y2) // 2


def find(pred, timeout=15):
    end = time.time() + timeout
    while time.time() < end:
        for n in dump().iter("node"):
            if pred(n):
                return n
        time.sleep(0.7)
    raise AssertionError("UI element not found")


def tap(node):
    x, y = center(node)
    adb("shell", "input", "tap", str(x), str(y))
    time.sleep(0.6)


def by_text(t):
    return lambda n: n.get("text") == t


def by_desc(t):
    return lambda n: (n.get("content-desc") or "") == t


def scroll_to(pred, max_swipes=8):
    for _ in range(max_swipes):
        for n in dump().iter("node"):
            if pred(n):
                return n
        adb("shell", "input", "swipe", "270", "800", "270", "300", "400")
        time.sleep(0.6)
    raise AssertionError("UI element not found after scrolling")


def type_text(text):
    adb("shell", "input", "text", text.replace(" ", "%s"))


def replace_field(node, text):
    tap(node)
    adb("shell", "input", "keyevent", "KEYCODE_MOVE_END")
    for _ in range(len(node.get("text") or "") + 2):
        adb("shell", "input", "keyevent", "KEYCODE_DEL")
    type_text(text)
    adb("shell", "input", "keyevent", "KEYCODE_ENTER")  # IME Done → commit (spec 14 §5.3)
    time.sleep(0.8)


# ── logcat ─────────────────────────────────────────────────────────────────────────────────────

class Logcat:
    def __init__(self, path):
        adb("logcat", "-c", check=False)
        self.path = path
        self.f = open(path, "w")
        self.p = subprocess.Popen([ADB, "logcat", "-v", "threadtime"], stdout=self.f, stderr=subprocess.STDOUT)

    def lines(self):
        with open(self.path, errors="replace") as f:
            return f.read().splitlines()

    def wait(self, pattern, timeout, after=0):
        rx = re.compile(pattern)
        end = time.time() + timeout
        while time.time() < end:
            for i, line in enumerate(self.lines()):
                if i >= after and rx.search(line):
                    return i, line
            time.sleep(0.25)
        raise AssertionError(f"logcat: no /{pattern}/ within {timeout}s")

    def close(self):
        self.p.terminate()
        self.f.close()


def stamp(line):
    """threadtime 'MM-DD HH:MM:SS.mmm' -> seconds of day."""
    m = re.match(r"\d\d-\d\d (\d\d):(\d\d):(\d\d)\.(\d\d\d)", line)
    h, mi, s, ms = map(int, m.groups())
    return h * 3600 + mi * 60 + s + ms / 1000


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--wav", default=os.path.join(REPO, "tests/fixtures/voice_speech_24k.wav"))
    ap.add_argument("--wake-phrase", default="my friend")
    ap.add_argument("--talk-phrase", default="over and out")
    ap.add_argument("--transcript", default="hello my friend", help="what the mock Whisper answers")
    ap.add_argument("--port", type=int, default=8799)
    ap.add_argument("--sink", default="archie_g01_sink")
    ap.add_argument("--out", default="/tmp/g01-lite")
    ap.add_argument("--attempts", type=int, default=3)
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)

    devices = [l for l in adb("devices").splitlines()[1:] if l.strip().endswith("device")]
    assert len(devices) == 1 and devices[0].startswith("emulator-"), f"exactly one emulator expected: {devices}"
    assert adb("shell", "getprop", "ro.build.version.sdk").strip() == "21", "not the API 21 AVD"

    step("safety: block the LAN / Tailscale from the AVD (never the live Jetson)")
    adb("root", check=False)
    time.sleep(2)
    adb("wait-for-device")
    for net in ("192.168.0.0/16", "100.64.0.0/10"):
        if net not in adb("shell", "iptables", "-S", "OUTPUT"):
            adb("shell", "iptables", "-I", "OUTPUT", "-d", net, "-j", "REJECT")

    step(f"mock backend on :{a.port} (AVD sees it at 10.0.2.2)")
    mock_log = open(os.path.join(a.out, "mock.log"), "w")
    mock = subprocess.Popen([os.path.join(REPO, ".venv/bin/python"), os.path.join(HERE, "mock_backend.py"),
                             "--port", str(a.port), "--transcript", a.transcript], stdout=mock_log, stderr=subprocess.STDOUT)
    time.sleep(2)
    adb("shell", "setprop", "debug.archie.whisper_url", f"http://10.0.2.2:{a.port}/v1/audio/transcriptions")

    # Keep the virtual mic flowing: a loopback source with nothing playing delivers no frames, and
    # the emulator's AudioRecord.read() then blocks forever (a real mic never stops delivering).
    silence = subprocess.Popen(["pw-cat", "--playback", "--target", a.sink, "--rate", "16000", "--channels", "1",
                                "--format", "s16", "-"], stdin=open("/dev/zero", "rb"), stdout=subprocess.DEVNULL,
                               stderr=subprocess.DEVNULL)
    log = Logcat(os.path.join(a.out, "logcat.txt"))
    result = 1
    try:
        step("launch + configure through the Settings UI (adb shell input)")
        adb("shell", "am", "force-stop", PKG)
        adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
        log.wait(r"\[FACE\]", 30)
        tap(find(by_desc("Settings")))
        tap(find(by_text("Add server")))
        is_add = lambda n: (n.get("text") or "").lower() == "add"  # dialog buttons are all-caps on API 21
        find(is_add)
        # Re-dump before every tap: the IME moves the dialog, and a tap outside it cancels it.
        def dialog_fields():
            return [n for n in dump().iter("node") if n.get("class") == "android.widget.EditText"
                    and (n.get("text") or "") not in ("wake up", "my friend")]
        tap(dialog_fields()[0]); type_text("G01mock")
        tap(dialog_fields()[1]); type_text(f"ws://10.0.2.2:{a.port}")
        tap(find(is_add))  # re-found: the IME moved the dialog
        log.wait(r"Archie/orch: connect ws://10\.0\.2\.2", 20)
        # The two phrase fields, top to bottom: wake ("Realtime conversation"), talk ("Voice message").
        def phrase_fields():
            scroll_to(by_text("Wake sensitivity"))
            eds = [n for n in dump().iter("node") if n.get("class") == "android.widget.EditText"]
            eds.sort(key=lambda n: center(n)[1])
            assert len(eds) == 2, f"expected the 2 phrase fields, got {len(eds)}"
            return eds
        replace_field(phrase_fields()[1], a.talk_phrase)   # talk first, so the two never collide
        replace_field(phrase_fields()[0], a.wake_phrase)
        adb("shell", "input", "keyevent", "KEYCODE_BACK")  # hide IME
        adb("shell", "input", "keyevent", "KEYCODE_BACK")  # Settings → face
        i_ready, _ = log.wait(r"\[FACE\] Ready", 30)
        log.wait(rf'Wake word detection started — talk: "{re.escape(a.talk_phrase)}", wake: "{re.escape(a.wake_phrase)}"', 30)
        log.wait(r"Engine selected: Vosk", 60)
        sh("bash", "-c", f"{ADB} exec-out screencap -p > {a.out}/1-armed.png")
        step("armed (Vosk) — injecting speech through the virtual mic")

        start = len(log.lines())
        matched = None
        for attempt in range(1, a.attempts + 1):
            sh("pw-play", "--target", a.sink, a.wav, timeout=60)
            try:
                _, matched = log.wait(r"Vosk match: .*realtime=true", 12, after=start)
                break
            except AssertionError:
                step(f"attempt {attempt}: no realtime Vosk match yet")
                time.sleep(4)
        assert matched, "no Vosk match"
        step(f"✓ {matched.split(': ', 1)[-1]}")
        i, line = log.wait(r"Whisper CONFIRMED \(realtime=true\)", 20, after=start); step("✓ Whisper CONFIRMED")
        i, line = log.wait(r"Trigger \(wake word\) → starting realtime voice session", 10, after=i); step("✓ trigger → voice")
        log.wait(r"\[FACE\] Connecting", 10, after=start); step("✓ face: Connecting")
        sh("bash", "-c", f"sleep 0.3; {ADB} exec-out screencap -p > {a.out}/2-connecting.png")
        i_err, err = log.wait(r"Voice error: ", 20, after=i); step("✓ voice Error: " + err.split("Voice error: ", 1)[1])
        log.wait(r"\[FACE\] Error", 10, after=i); step("✓ face: Error")
        sh("bash", "-c", f"{ADB} exec-out screencap -p > {a.out}/3-error.png")
        i_res, res = log.wait(r"Resuming wake word detection after voice session", 10, after=i_err)
        gap = stamp(res) - stamp(err)
        step(f"✓ wake re-armed {gap * 1000:.0f} ms after the error")
        assert gap >= 1.45, f"wake re-armed too early ({gap:.3f}s < 1.5 s, RS-30)"
        log.wait(r"Silence monitor started|Engine selected: Vosk", 15, after=i_res); step("✓ wake loop running again")
        with open(os.path.join(a.out, "mock.log")) as f:
            m = f.read()
        assert '"path": "/api/orchestrator/voice/session"' in m and '"kind": "whisper"' in m and '"type": "voice_start"' in m
        step("✓ mock saw whisper + voice_start + POST voice/session")
        step("G-01 lite script: PASS")
        result = 0
    except AssertionError as e:
        step(f"FAIL: {e}")
    finally:
        log.close()
        adb("shell", "setprop", "debug.archie.whisper_url", "''", check=False)
        mock.terminate()
        mock_log.close()
        silence.terminate()
        step(f"artifacts in {a.out}")
    sys.exit(result)


if __name__ == "__main__":
    main()
