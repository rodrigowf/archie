package com.assistant.core.wakeword

import com.assistant.core.audio.ports.AudioStream
import com.assistant.core.audio.ports.VoiceLog
import com.assistant.core.wakeword.loop.WakeLoop
import com.assistant.core.wakeword.policy.ActivityGate
import com.assistant.core.wakeword.policy.AdaptiveTalkVad
import com.assistant.core.wakeword.policy.AmbientFloor
import com.assistant.core.wakeword.policy.Clips
import com.assistant.core.wakeword.policy.PhraseMatcher
import com.assistant.core.wakeword.policy.SrAudioModeOwner
import com.assistant.core.wakeword.policy.SrHealthBook
import com.assistant.core.wakeword.policy.SrPlatformPolicy
import com.assistant.core.wakeword.policy.WakeRearmPolicy
import com.assistant.core.wakeword.policy.WhisperGateDecision
import com.assistant.core.wakeword.ports.AudioModeOwnership
import com.assistant.core.wakeword.ports.ClipPolicy
import com.assistant.core.wakeword.ports.NoiseFloorTracker
import com.assistant.core.wakeword.ports.OpenAiKeyProvider
import com.assistant.core.wakeword.ports.RearmPolicy
import com.assistant.core.wakeword.ports.RmsGate
import com.assistant.core.wakeword.ports.SrHealth
import com.assistant.core.wakeword.ports.TalkVad
import com.assistant.core.wakeword.ports.VariantMatcher
import com.assistant.core.wakeword.ports.VoskModelStore
import com.assistant.core.wakeword.ports.WakeConfig
import com.assistant.core.wakeword.ports.WakeEngineDeps
import com.assistant.core.wakeword.ports.WakeWordEngine
import com.assistant.core.wakeword.ports.WakewordCore
import com.assistant.core.wakeword.ports.WhisperDecision
import com.assistant.core.wakeword.ports.WhisperTranscriber
import com.assistant.core.wakeword.vosk.VoskModelFiles
import com.assistant.core.wakeword.whisper.WhisperClient
import okhttp3.OkHttpClient

/**
 * The `:core:wakeword` entry point (registered in `META-INF/services` for the parity harness; the
 * app graph may construct it directly). Pure: the Android adapters live in
 * `com.assistant.core.wakeword.platform` / `.vosk` / `.sr`.
 */
class DefaultWakewordCore : WakewordCore {
    override val variants: VariantMatcher get() = PhraseMatcher
    override val whisperDecision: WhisperDecision get() = WhisperGateDecision
    override val clips: ClipPolicy get() = Clips
    override val modelStore: VoskModelStore get() = VoskModelFiles

    override fun rmsGate(wakeGain: Float): RmsGate = ActivityGate(wakeGain)

    override fun noiseFloorTracker(seed: Double): NoiseFloorTracker = AmbientFloor(seed)

    override fun adaptiveVoiceThreshold(noiseFloor: Double, sensitivity: Double): Double =
        com.assistant.core.wakeword.policy.adaptiveVoiceThreshold(noiseFloor, sensitivity)

    override fun talkVad(startedAtMs: Long, seedFloor: Double, sensitivity: Float): TalkVad =
        AdaptiveTalkVad(startedAtMs, seedFloor, sensitivity)

    override fun rearmPolicy(): RearmPolicy = WakeRearmPolicy()

    override fun srHealth(): SrHealth = SrHealthBook()

    override fun srAudioModeOwnership(): AudioModeOwnership = SrAudioModeOwner()

    override fun srRecognizerExtras(packageName: String): Map<String, Any> = SrPlatformPolicy.recognizerExtras(packageName)

    override val srBeepStreams: List<AudioStream> get() = SrPlatformPolicy.beepStreams

    override fun srBeepMuteUsesAdjustStreamVolume(sdkInt: Int): Boolean = SrPlatformPolicy.muteUsesAdjustStreamVolume(sdkInt)

    override fun voskResultText(json: String): String = com.assistant.core.wakeword.policy.voskResultText(json)

    override fun whisperClient(http: OkHttpClient, endpointUrl: String, keys: OpenAiKeyProvider, log: VoiceLog): WhisperTranscriber =
        WhisperClient(http, endpointUrl, keys, log)

    override fun engine(deps: WakeEngineDeps, config: WakeConfig): WakeWordEngine = WakeLoop(deps, config)
}
