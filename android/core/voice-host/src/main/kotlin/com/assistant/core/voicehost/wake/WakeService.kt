package com.assistant.core.voicehost.wake

import com.assistant.core.voicehost.HostTuning
import com.assistant.core.voicehost.ports.WakeEngineHandle
import com.assistant.core.voicehost.ports.WakeNotice
import com.assistant.core.voicehost.ports.WakeServiceConfig
import com.assistant.core.voicehost.ports.WakeServiceController
import com.assistant.core.voicehost.ports.WakeServiceDeps
import com.assistant.core.voicehost.ports.WakeStartDeduper
import com.assistant.core.voicehost.ports.WakeStartKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Wake start dedupe (old `AssistantService.shouldDedupeWakeStart`, `0b2cbb5`, inv04 §3.5):
 * key (talk, wake, gain) — NOT the server URL, NOT the talk sensitivity; strict `<`
 * [HostTuning.WAKE_START_DEDUPE_WINDOW_MS] on the monotonic clock. Only a call that goes through
 * records itself (a deduped call does not extend the window).
 */
class DefaultWakeStartDeduper : WakeStartDeduper {
    private var lastKey: WakeStartKey? = null
    private var lastAtMs = 0L

    @Synchronized
    override fun shouldDedupe(key: WakeStartKey, nowMs: Long): Boolean {
        val last = lastKey
        if (last != null && last == key && nowMs - lastAtMs < HostTuning.WAKE_START_DEDUPE_WINDOW_MS) return true
        lastKey = key
        lastAtMs = nowMs
        return false
    }

    @Synchronized
    override fun clear() {
        lastKey = null
        lastAtMs = 0L
    }
}

/**
 * The old `AssistantService` wake logic without Android (inv04 §3.5 FSM; RS-31…RS-34; R3).
 * Entry points may be called from any thread (service main thread, wake loop events); state sits
 * behind one lock and engine calls are made outside it.
 *
 * Differences from the old service, both required by the spec:
 *  - Screen-on re-arm never restarts an engine that is confirming or capturing (R3).
 *  - Mic-stalled is a [notice] state (the host renders it into the notification), not a direct
 *    `notify` call.
 */
class DefaultWakeServiceController(private val deps: WakeServiceDeps) : WakeServiceController {
    private val dedupe = DefaultWakeStartDeduper()
    private val lock = Any()

    // ── guarded by [lock] ───────────────────────────────────────────────────────────────────────
    private var engine: WakeEngineHandle? = null
    private var config: WakeServiceConfig = deps.store.load()
    private var voiceActive = false
    private var rearmJob: Job? = null

    private val _notice = MutableStateFlow(WakeNotice.NORMAL)
    override val notice: StateFlow<WakeNotice> = _notice.asStateFlow()

    override val voiceSessionActive: Boolean get() = synchronized(lock) { voiceActive }
    override val currentConfig: WakeServiceConfig get() = synchronized(lock) { config }

    /** The engine currently armed (or paused), for the host's health / phase surface. */
    val currentEngine: WakeEngineHandle? get() = synchronized(lock) { engine }

    override fun onConfigUpdate(config: WakeServiceConfig) {
        // Persist all six fields first so a process death restores exactly this (RS-32).
        deps.store.save(config)
        synchronized(lock) { this.config = config }
        if (config.enabled) startWakeWord(config) else stopWakeWord()
    }

    override fun onPauseForVoice(ack: CompletableDeferred<Unit>) {
        val e = synchronized(lock) {
            voiceActive = true
            engine
        }
        deps.log.d(TAG, "Pausing wake word detection for voice session")
        e?.pause()
        ack.complete(Unit)
    }

    override fun onResumeAfterVoice(ack: CompletableDeferred<Unit>) {
        val cfg = synchronized(lock) {
            if (!voiceActive) {
                // Duplicate resume (`495b5d9`): a second resume would tear down the fresh cycle.
                null
            } else {
                voiceActive = false
                // The user may have disabled the wake word during the call (RS-31): re-read the store.
                val enabledNow = deps.store.load().enabled
                config = config.copy(enabled = enabledNow)
                config
            }
        }
        if (cfg == null) {
            deps.log.d(TAG, "Resume wake word intent ignored — already resumed for this session")
        } else if (cfg.enabled) {
            deps.log.d(TAG, "Resuming wake word detection after voice session")
            // Always a full restart: the mic may have been held by WebRTC when the engine paused.
            startWakeWord(cfg)
        } else {
            deps.log.d(TAG, "Wake word disabled — skipping restart after voice session")
        }
        ack.complete(Unit)
    }

    override fun onStickyRestart() {
        val cfg = deps.store.load()
        synchronized(lock) { config = cfg }
        deps.log.d(
            TAG,
            "Sticky restart — restored config from prefs: enabled=${cfg.enabled}, talk=\"${cfg.talkWord}\", " +
                "wake=\"${cfg.wakeWord}\", gain=${cfg.wakeGain}, talkSilenceSensitivity=${cfg.talkSilenceSensitivity}",
        )
        if (cfg.enabled) startWakeWord(cfg)
    }

    override fun onScreenOnOrUserPresent() {
        synchronized(lock) {
            rearmJob?.cancel()
            rearmJob = deps.scope.launch {
                delay(HostTuning.SCREEN_REARM_DEBOUNCE_MS)
                rearm()
            }
        }
    }

    private fun rearm() {
        val (cfg, e) = synchronized(lock) {
            if (voiceActive) {
                deps.log.d(TAG, "Screen on during voice session — skipping wake word rearm")
                return
            }
            // Reload: the in-memory copy can be stale in a fresh process.
            config = deps.store.load()
            config to engine
        }
        if (!cfg.enabled) return
        when {
            e == null -> startWakeWord(cfg)
            e.isPaused -> e.resume()
            !e.isActive -> startWakeWord(cfg)
            // R3: the screen-on may have been caused by this very utterance; never kill it.
            e.isBusy -> deps.log.d(TAG, "Screen on while the wake word is confirming/capturing — leaving it alone (R3)")
            // Looks active, but the monitor may have silently failed (mic busy at start): clean restart.
            else -> startWakeWord(cfg)
        }
    }

    override fun onRecognizerUnhealthy() {
        val cfg = synchronized(lock) { if (voiceActive || !config.enabled) return else config }
        deps.log.d(TAG, "Recognizer unhealthy — rebuilding wake-word detector")
        startWakeWord(cfg)
    }

    override fun onMicUnavailable() {
        if (_notice.value != WakeNotice.MIC_STALLED) {
            deps.log.w(TAG, "Mic unavailable — updating notification")
            _notice.value = WakeNotice.MIC_STALLED
        }
    }

    override fun onMicAvailable() {
        if (_notice.value != WakeNotice.NORMAL) {
            deps.log.d(TAG, "Mic available again — clearing notification warning")
            _notice.value = WakeNotice.NORMAL
        }
    }

    override fun destroy() {
        val e = synchronized(lock) {
            rearmJob?.cancel()
            rearmJob = null
            engine.also { engine = null }
        }
        e?.stop()
    }

    private fun startWakeWord(cfg: WakeServiceConfig) {
        val key = WakeStartKey(cfg.talkWord, cfg.wakeWord, cfg.wakeGain)
        if (dedupe.shouldDedupe(key, deps.clock.nowMs())) {
            deps.log.d(TAG, "startWakeWord() dedupe — same key within ${HostTuning.WAKE_START_DEDUPE_WINDOW_MS}ms")
            return
        }
        val old = synchronized(lock) { engine }
        old?.stop()
        val fresh = deps.engines.create(cfg)
        synchronized(lock) { engine = fresh }
        fresh.start()
        deps.log.d(
            TAG,
            "Wake word detection started — talk: \"${cfg.talkWord}\", wake: \"${cfg.wakeWord}\", " +
                "gain=${cfg.wakeGain}, talkSilenceSensitivity=${cfg.talkSilenceSensitivity}",
        )
    }

    private fun stopWakeWord() {
        val e = synchronized(lock) { engine.also { engine = null } }
        e?.stop()
        // A re-enable with the same key within 3 s is a real restart, not a redelivery.
        dedupe.clear()
        deps.log.d(TAG, "Wake word detection stopped")
    }

    private companion object {
        const val TAG = "AssistantService"
    }
}
