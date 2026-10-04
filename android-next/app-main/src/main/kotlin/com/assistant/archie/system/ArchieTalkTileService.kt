package com.assistant.archie.system

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import com.assistant.archie.graph.GraphOwner
import com.assistant.core.voice.ports.SessionPhase
import com.assistant.core.voicehost.VoiceHost
import com.assistant.core.voicehost.VoiceUiState
import com.assistant.core.voicehost.ports.Trigger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Quick Settings "Talk" tile (spec 14 §2.8). Active while voice is live on this
 * device. Tap when off → the trampoline (`startActivityAndCollapse(PendingIntent)`, required on
 * API 34+), which starts voice from a foreground context; tap when on → `stopVoice()`. Long-press
 * opens the app (`ACTION_QS_TILE_PREFERENCES` → MainActivity). It follows the host state while the
 * panel shows it. (Not `ACTIVE_TILE`: on the API 36 AVD `requestListeningState` never rebound the
 * tile after a voice change, so the active-tile mode left it stale.)
 */
class ArchieTalkTileService : TileService() {
    private val host: VoiceHost? get() = (application as? GraphOwner)?.graph?.voiceHost

    private var scope: CoroutineScope? = null

    /** The QS panel is showing the tile: follow the voice state live until it closes. */
    override fun onStartListening() {
        super.onStartListening()
        render()
        val h = host ?: return
        scope?.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { sc ->
            sc.launch { h.state.map { voiceLive(it) }.distinctUntilChanged().collect { render(it) } }
        }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val h = host ?: return
        if (voiceLive(h)) {
            Log.i(TAG, "tile: voice on → stop")
            h.stopVoice()
            render(forceActive = false)
        } else {
            Log.i(TAG, "tile: voice off → trampoline")
            if (isSecure && isLocked) unlockAndRun { launchTrampoline() } else launchTrampoline()
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun launchTrampoline() {
        val intent = VoiceTrampolineActivity.talkIntent(this, Trigger.TILE)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, REQUEST_TALK, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun render(forceActive: Boolean? = null) {
        val tile = qsTile ?: return
        val active = forceActive ?: host?.let(::voiceLive) ?: false
        tile.state = if (host == null) Tile.STATE_UNAVAILABLE else if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Talk"
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = if (active) "Voice on" else "Archie"
        tile.contentDescription = if (active) "End voice with Archie" else "Talk to Archie"
        tile.updateTile()
        lastRenderedState = tile.state
    }

    companion object {
        private const val TAG = "ArchieTile"
        private const val REQUEST_TALK = 7001

        /** The last state the tile rendered (instrumented tests; the tile is otherwise unobservable). */
        @Volatile var lastRenderedState: Int = -1
            internal set

        fun voiceLive(h: VoiceHost): Boolean = voiceLive(h.state.value)

        /** Voice runs on this device (owner, any live phase; CONNECTING before ownership is known). */
        fun voiceLive(s: VoiceUiState): Boolean {
            val p = s.session
            return p.isOwner && p.phase != SessionPhase.OFF && p.phase != SessionPhase.ERROR ||
                p.phase == SessionPhase.CONNECTING
        }
    }
}
