package com.assistant.core.audio.ports

/*
 * Voice-stack primitives shared by :core:audio, :core:voice, :core:wakeword and :core:voice-host.
 * Interface-only file owned by A-04 (spec 14 §7). Changes go through the coordinator.
 *
 * Rules carried from inv04 §8 design rule 1: every timer and every "now" in the voice stack is
 * read from an injected [MonotonicClock]; nothing calls System.currentTimeMillis() directly. In
 * production the clock is SystemClock.elapsedRealtime(); in JVM tests it is bound to the
 * coroutine test scheduler (`FakeClock` in :core:testing), so virtual time drives the FSMs.
 */

/** Monotonic milliseconds. Production: `SystemClock.elapsedRealtime()`. */
fun interface MonotonicClock {
    fun nowMs(): Long
}

/**
 * Logger port. Field diagnosis greps logcat for the markers listed in inv04 §10.3 (`[MIC_STATE] DUCK`,
 * `RESTORE_DRAIN(`, `RESTORE_IMMEDIATE(`, `[MIC_PROBE]`, `Vosk match`, `Whisper CONFIRMED`, …), so the
 * marker text is part of the contract and the parity tests assert it through a recording fake.
 */
interface VoiceLog {
    fun d(tag: String, message: String)
    fun i(tag: String, message: String)
    fun w(tag: String, message: String)
    fun e(tag: String, message: String, error: Throwable? = null)
}
