package com.assistant.core.voice.ports

import kotlinx.serialization.json.JsonObject

/*
 * `session.update` delivery sub-machine (inv04 §3.2; RS-01…RS-04; fixes B2). Interface-only (A-04).
 *
 * RECEIVED → cached → (no provider) QUEUED in the [CommandRelay] → drained into the provider at
 * attach → PROVIDER_PENDING in the [DataChannelCommandGate] (never cleared by connect, `9515576`)
 * → SENT at data-channel open, or SELF_HEAL from the cache, or DEFAULTS (logged error) → a
 * `session.updated` echo while nothing was sent self-heals once → [DataChannelCommandGate.reset]
 * on cleanup.
 */

fun interface ProviderCommandSink {
    fun handleBackendCommand(command: JsonObject)
}

/**
 * Pre-provider command queue + `session.update` cache (old `VoiceManager.pendingBackendCommands`
 * / `lastSessionUpdate`). [onBackendCommand] is called from the socket thread while [attach] runs
 * on another: no command may be lost or delivered twice (RS-04).
 * Log marker on a non-empty drain: `start: draining <n> pre-provider backend command(s)`.
 */
interface CommandRelay {
    /** Caches `session.update` (type field), then forwards to the attached sink or queues. Any thread. */
    fun onBackendCommand(command: JsonObject)

    /** Drains the queue FIFO into [sink], then forwards directly. Returns the number drained. */
    fun attach(sink: ProviderCommandSink): Int

    /** Drops queued commands; the relay stays usable for the next session. */
    fun detach()

    val cachedSessionUpdate: JsonObject?
    val queuedCount: Int
}

/**
 * WebRTC data-channel command gate (old `OpenAIVoiceProvider.pendingCommands` + `sessionUpdateSent`),
 * thread-safe: [send] runs on the socket thread, [onOpen] on the WebRTC data-channel thread (B2).
 *  - [send]: transmit when open (marking `sessionUpdateSent` for a `session.update`), else pend.
 *  - [onOpen]: drain pending FIFO; then if no `session.update` was sent, transmit the fallback
 *    (log contains `session.update missing at DC_OPEN`), or log an error when there is none.
 *  - [onSessionUpdatedEcho]: if still not sent, transmit the fallback once.
 *  - [reset]: closed, pending cleared, `sessionUpdateSent = false`.
 */
interface DataChannelCommandGate {
    val isOpen: Boolean
    val sessionUpdateSent: Boolean
    val pendingCount: Int
    fun send(command: JsonObject)
    fun onOpen()
    fun onSessionUpdatedEcho()
    fun reset()
}
