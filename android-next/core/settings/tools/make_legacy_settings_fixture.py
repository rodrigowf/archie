#!/usr/bin/env python3
"""Writes src/test/resources/legacy/settings.preferences_pb: an old-app (`com.assistant.peripheral`)
Preferences DataStore file with EVERY key of old/settings/SettingsRepository.kt:325-343 plus two
`ws_resume_checkpoint:<id>` keys (inv03 §2.2). The protobuf is encoded by hand here, independently
of the new reader, from androidx.datastore `PreferencesProto`:

  message PreferenceMap { map<string, Value> preferences = 1; }
  message Value { oneof { bool boolean = 1; float float = 2; int32 integer = 3; int64 long = 4;
                          string string = 5; StringSet string_set = 6; double double = 7; bytes bytes = 8; } }
Values are non-default so a test can tell "read" from "defaulted".
"""
import struct, pathlib

def varint(n):
    out = b""
    while True:
        b = n & 0x7F; n >>= 7
        if n: out += bytes([b | 0x80])
        else: return out + bytes([b])

def ld(field, payload):  # length-delimited
    return varint((field << 3) | 2) + varint(len(payload)) + payload

def value(v):
    if isinstance(v, bool): return varint((1 << 3) | 0) + varint(1 if v else 0)
    if isinstance(v, float): return varint((2 << 3) | 5) + struct.pack("<f", v)
    if isinstance(v, str): return ld(5, v.encode())
    raise TypeError(v)

ENTRIES = [
    ("server_url", "ws://192.168.0.123:8765"),
    ("saved_servers", "Jetson\tws://192.168.0.200:80|Laptop\tws://192.168.0.28:8765"),
    ("auto_connect", False),
    ("enable_wake_word", False),
    ("turn_talk_word", "hey buddy, my friend"),
    ("realtime_wake_word", "wake up, archie"),
    ("theme_mode", "DARK"),
    ("mic_gain_level", 1.3),
    ("wake_word_mic_gain_level", 0.7),
    ("talk_silence_sensitivity", 3.5),
    ("speaker_volume_level", 0.4),
    ("echo_ducking_gain", 0.08),
    ("audio_output", "EARPIECE"),
    ("enable_button_trigger", True),
    ("orchestrator_local_id", "3540ff69-0000-4000-8000-000000000001"),
    ("ws_resume_checkpoint:aaaa", "stream-1|42"),
    ("ws_resume_checkpoint:bbbb", "stream-2|7"),
]

blob = b"".join(ld(1, ld(1, k.encode()) + ld(2, value(v))) for k, v in ENTRIES)
out = pathlib.Path(__file__).resolve().parent.parent / "src/test/resources/legacy/settings.preferences_pb"
out.write_bytes(blob)
print(f"wrote {out} ({len(blob)} bytes, {len(ENTRIES)} keys)")
