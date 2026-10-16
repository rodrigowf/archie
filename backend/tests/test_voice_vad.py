"""Tests for orchestrator.voice_vad.

Silero is the source of truth for "is this speech?" — we don't try to
out-think it here. The tests focus on the things WE wrote:

- The carry buffer correctly handles arbitrary chunk sizes.
- 24 kHz → 16 kHz resampling produces sane window counts.
- The state machine respects min_speech / min_silence durations.
- Hysteresis prevents chatter when the probability dances around the
  threshold.
- An event's `bytes_consumed` field grows monotonically.

Speech tests use the real recording at
``tests/fixtures/voice_speech_24k.wav`` — Silero rejects synthetic
signals (pure tones, filtered noise) as non-speech because it's trained
on actual voice characteristics.
"""

from __future__ import annotations

import wave
from pathlib import Path

import numpy as np
import pytest

from orchestrator.voice_vad import VADEvent, VoiceVAD, is_enabled

_FIXTURE_DIR = Path(__file__).parent / "fixtures"
_SPEECH_WAV = _FIXTURE_DIR / "voice_speech_24k.wav"


def _pcm16_silence(seconds: float, sample_rate: int = 24000) -> bytes:
    n = int(seconds * sample_rate)
    return np.zeros(n, dtype=np.int16).tobytes()


def _load_speech_pcm(sample_rate: int = 24000) -> bytes:
    """Load the real speech fixture as raw PCM16 bytes at the requested SR.

    The fixture is recorded at 24 kHz; we linearly resample to other rates
    if the test asks for it. Skips the test if the fixture is missing.
    """
    if not _SPEECH_WAV.exists():
        pytest.skip(
            f"speech fixture missing: {_SPEECH_WAV}. "
            "Record one with: arecord -f S16_LE -r 24000 -c 1 -d 8 <path>"
        )
    with wave.open(str(_SPEECH_WAV)) as w:
        assert w.getframerate() == 24000, f"fixture must be 24kHz, got {w.getframerate()}"
        assert w.getnchannels() == 1, "fixture must be mono"
        raw = w.readframes(w.getnframes())
    if sample_rate == 24000:
        return raw
    audio = np.frombuffer(raw, dtype=np.int16).astype(np.float32)
    ratio = sample_rate / 24000
    n_out = int(len(audio) * ratio)
    x_old = np.arange(len(audio), dtype=np.float32)
    x_new = np.linspace(0.0, len(audio) - 1, num=n_out, dtype=np.float32)
    return np.interp(x_new, x_old, audio).astype(np.int16).tobytes()


def test_silence_emits_no_events():
    vad = VoiceVAD(input_sample_rate=24000)
    events = list(vad.feed_pcm16(_pcm16_silence(2.0)))
    assert events == []
    assert not vad.is_speech


def test_real_speech_emits_started_and_stopped():
    """Real speech fixture (8s WAV with silence-speech-silence) yields one start/stop pair."""
    vad = VoiceVAD(input_sample_rate=24000, min_silence_duration_ms=600, min_speech_duration_ms=100)
    audio = _load_speech_pcm(sample_rate=24000)
    events = list(vad.feed_pcm16(audio))
    kinds = [e.kind for e in events]
    assert kinds.count("speech_started") == 1, f"expected one start, got {kinds}"
    assert kinds.count("speech_stopped") == 1, f"expected one stop, got {kinds}"
    assert kinds.index("speech_started") < kinds.index("speech_stopped")
    assert not vad.is_speech


def test_chunk_size_invariant():
    """Same audio fed in different chunk sizes must yield identical event sequences."""
    audio = _load_speech_pcm(sample_rate=24000)

    def run(chunk_size: int) -> list[str]:
        vad = VoiceVAD(input_sample_rate=24000, min_silence_duration_ms=600, min_speech_duration_ms=100)
        events: list[VADEvent] = []
        for i in range(0, len(audio), chunk_size):
            events.extend(vad.feed_pcm16(audio[i:i + chunk_size]))
        return [e.kind for e in events]

    # 20ms chunks at 24k = 480 frames = 960 bytes — the real Android cadence.
    # Compared against 1-second chunks and the whole buffer in one call.
    short = run(960)
    medium = run(24000 * 2)
    whole = run(len(audio))
    assert short == medium == whole, f"chunk-size-dependent: short={short} medium={medium} whole={whole}"


def test_bytes_consumed_monotonic():
    vad = VoiceVAD(input_sample_rate=24000, min_silence_duration_ms=600, min_speech_duration_ms=100)
    audio = _load_speech_pcm(sample_rate=24000)
    events = list(vad.feed_pcm16(audio))
    consumed = [e.bytes_consumed for e in events]
    assert consumed == sorted(consumed), f"bytes_consumed not monotonic: {consumed}"
    # And they should be plausible byte offsets (positive, within input length).
    assert all(0 < c <= len(audio) for c in consumed), consumed


def test_sample_rate_16k_no_resample():
    """Native 16 kHz input bypasses the resampler and detects the same speech."""
    vad = VoiceVAD(input_sample_rate=16000, min_silence_duration_ms=600, min_speech_duration_ms=100)
    audio = _load_speech_pcm(sample_rate=16000)
    events = list(vad.feed_pcm16(audio))
    kinds = [e.kind for e in events]
    assert "speech_started" in kinds, kinds
    assert "speech_stopped" in kinds, kinds


def test_min_silence_duration_threshold_changes_event_count():
    """Tightening min_silence_duration_ms causes more stop/start cycles.

    Two copies of the speech fixture glued together = two distinct speech
    spans with the natural recording silence (~2.3s) between them. With
    a tight min_silence (300ms) Silero stops once per span. With a loose
    one (3000ms — longer than the gap) the gap is absorbed as in-utterance
    and we only see one stop.
    """
    audio = _load_speech_pcm(sample_rate=24000)
    doubled = audio + audio

    tight = VoiceVAD(input_sample_rate=24000, min_silence_duration_ms=300, min_speech_duration_ms=100)
    tight_events = list(tight.feed_pcm16(doubled))
    tight_stops = sum(1 for e in tight_events if e.kind == "speech_stopped")
    assert tight_stops == 2, f"tight gate should see two stops, got {[e.kind for e in tight_events]}"

    # Loose gate must exceed the inter-copy silence (trailing ~2.8s + leading
    # ~2.7s of the fixture). 6000ms is well above that and still within
    # Silero's documented [200, 6000] range for silence_duration_ms.
    loose = VoiceVAD(input_sample_rate=24000, min_silence_duration_ms=6000, min_speech_duration_ms=100)
    loose_events = list(loose.feed_pcm16(doubled))
    loose_stops = sum(1 for e in loose_events if e.kind == "speech_stopped")
    # Loose gate longer than the inter-copy gap: at most one stop (the very
    # end may or may not have enough trailing silence to fire).
    assert loose_stops <= 1, f"loose gate should absorb the gap, got {[e.kind for e in loose_events]}"


def test_is_enabled_env_var(monkeypatch: pytest.MonkeyPatch):
    # Default-on: unset env → enabled.
    monkeypatch.delenv("QWEN_MANUAL_VAD", raising=False)
    assert is_enabled() is True
    monkeypatch.setenv("QWEN_MANUAL_VAD", "1")
    assert is_enabled() is True
    # Only explicit "0" opts back into server VAD.
    monkeypatch.setenv("QWEN_MANUAL_VAD", "0")
    assert is_enabled() is False


# --- Silero context + gain stage (2026-10-14) -------------------------------
#
# A Qwen session on the A300M stopped hearing Rodrigo: his voice reached the
# relay at about -54 dBFS over a -66 dBFS room, and the VAD (fed bare
# 512-sample windows, no gain) never fired again. These pin the two fixes.


def _quiet_speech_pcm(drop_db: float, noise_dbfs: float = -67.0, copies: int = 3) -> bytes:
    """The speech fixture ``drop_db`` quieter over steady noise, repeated."""
    audio = np.frombuffer(_load_speech_pcm(sample_rate=24000), dtype=np.int16).astype(np.float32)
    rng = np.random.default_rng(0)
    noise = rng.normal(0.0, 1.0, len(audio)) * 32768 * 10 ** (noise_dbfs / 20)
    quiet = np.clip(audio * 10 ** (-drop_db / 20) + noise, -32768, 32767).astype(np.int16)
    return quiet.tobytes() * copies


def test_model_input_carries_64_sample_context():
    """Silero v5 gets each window with the previous window's last 64 samples
    prepended (576 samples), as the official OnnxWrapper does."""
    vad = VoiceVAD(input_sample_rate=16000, agc=False)
    seen: list[np.ndarray] = []
    real_run = vad._sess.run

    def spy(outputs, feed):
        seen.append(feed["input"].copy())
        return real_run(outputs, feed)

    vad._sess.run = spy  # type: ignore[method-assign]
    ramp = (np.arange(1024) % 1000).astype(np.int16)
    list(vad.feed_pcm16(ramp.tobytes()))
    assert [x.shape for x in seen] == [(1, 576), (1, 576)]
    assert np.all(seen[0][0, :64] == 0.0)  # first window: zero context
    np.testing.assert_array_equal(seen[1][0, :64], seen[0][0, -64:])


def test_quiet_far_speech_is_detected_with_gain_stage():
    """30 dB below the fixture over room noise: missed without the gain
    stage, one start/stop per utterance with it."""
    audio = _quiet_speech_pcm(drop_db=30)
    plain = VoiceVAD(input_sample_rate=24000, min_silence_duration_ms=600, min_speech_duration_ms=100, agc=False)
    assert list(plain.feed_pcm16(audio)) == []

    vad = VoiceVAD(input_sample_rate=24000, min_silence_duration_ms=600, min_speech_duration_ms=100)
    kinds = [e.kind for e in vad.feed_pcm16(audio)]
    assert kinds == ["speech_started", "speech_stopped"] * 3, kinds
    assert vad.agc_speech_dbfs is not None and vad.agc_speech_dbfs < -50
    assert 15.0 < vad.agc_gain_db <= 30.0


def test_gain_stage_leaves_normal_level_speech_nearly_alone():
    vad = VoiceVAD(input_sample_rate=24000, min_silence_duration_ms=600, min_speech_duration_ms=100)
    kinds = [e.kind for e in vad.feed_pcm16(_load_speech_pcm(sample_rate=24000) * 2)]
    assert kinds == ["speech_started", "speech_stopped"] * 2, kinds
    assert vad.agc_gain_db < 10.0


def test_gain_never_lifts_noise_above_cap():
    """Room noise alone (no speech yet): the gain stops where the noise floor
    would cross -45 dBFS, so noise at -50 dBFS allows at most +5 dB."""
    vad = VoiceVAD(input_sample_rate=16000)
    noise = np.random.default_rng(1).normal(0.0, 32768 * 10 ** (-50 / 20), 16000 * 5)
    events = list(vad.feed_pcm16(noise.astype(np.int16).tobytes()))
    assert events == []
    assert vad.agc_gain_db <= 5.5


def test_digital_silence_does_not_open_the_gain():
    """Ducked/zeroed mic audio is not the room's noise floor: after it the
    gain is still bounded by the real noise heard before."""
    vad = VoiceVAD(input_sample_rate=16000)
    noise = np.random.default_rng(2).normal(0.0, 32768 * 10 ** (-55 / 20), 16000 * 3).astype(np.int16)
    list(vad.feed_pcm16(noise.tobytes()))
    list(vad.feed_pcm16(_pcm16_silence(10.0, sample_rate=16000)))
    # cap: -45 - (-55) = 10 dB, plus the floor tracker's ~1-2 dB
    # minimum-statistics bias below the noise's mean level. Letting the
    # zeros into the floor would open the gain to the 30 dB maximum.
    assert vad.agc_gain_db <= 12.5


def test_gain_is_frozen_while_in_speech():
    vad = VoiceVAD(input_sample_rate=24000, min_silence_duration_ms=600, min_speech_duration_ms=100)
    audio = _quiet_speech_pcm(drop_db=20, copies=1)
    gains_in_speech: list[float] = []
    for i in range(0, len(audio), 960):
        list(vad.feed_pcm16(audio[i:i + 960]))
        if vad.is_speech:
            gains_in_speech.append(vad.agc_gain_db)
    assert gains_in_speech, "fixture never reached speech"
    assert max(gains_in_speech) - min(gains_in_speech) < 0.25  # one window's slew at most


# --- Echo-ducked mic (2026-10-16) -------------------------------------------
#
# While the agent speaks, the phones scale the mic down (echo ducking) and
# mark the chunks ``ducked``. The A300M's ducked echo reached the relay at
# about -80 dBFS; the gain stage, primed at ~+28 dB by Rodrigo's far voice,
# lifted it back to speech level and the VAD barged in on the agent's own
# voice. Ducked chunks are therefore fed with ``boost=False``.


def _primed_vad() -> VoiceVAD:
    vad = VoiceVAD(input_sample_rate=24000, min_silence_duration_ms=600, min_speech_duration_ms=100)
    list(vad.feed_pcm16(_quiet_speech_pcm(drop_db=30, copies=2)))
    assert vad.agc_gain_db > 20.0
    return vad


def test_ducked_echo_is_heard_only_when_boosted():
    echo = _quiet_speech_pcm(drop_db=30, noise_dbfs=-95.0, copies=1)
    boosted = _primed_vad()
    assert "speech_started" in [e.kind for e in boosted.feed_pcm16(echo)]
    ducked = _primed_vad()
    assert list(ducked.feed_pcm16(echo, boost=False)) == []


def test_ducked_windows_leave_the_gain_stage_alone():
    vad = _primed_vad()
    gain, speech, noise = vad.agc_gain_db, vad.agc_speech_dbfs, vad._agc_noise_dbfs
    list(vad.feed_pcm16(_quiet_speech_pcm(drop_db=0, noise_dbfs=-95.0, copies=1), boost=False))
    assert (vad.agc_gain_db, vad.agc_speech_dbfs, vad._agc_noise_dbfs) == (gain, speech, noise)
