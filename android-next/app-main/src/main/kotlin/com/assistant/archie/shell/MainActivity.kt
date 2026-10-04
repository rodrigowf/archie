package com.assistant.archie.shell

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation3.runtime.rememberNavBackStack
import com.assistant.archie.graph.GraphOwner
import com.assistant.archie.graph.MainAppGraph
import com.assistant.core.data.SharePayload
import com.assistant.core.design.theme.ArchieTheme
import com.assistant.archie.feature.settings.ui.AuthGate
import com.assistant.archie.feature.settings.ui.ProvideTextSize

/**
 * The single Activity (spec 14 §2.8). It holds no domain state: everything lives in the
 * process-scoped [MainAppGraph], so finishing or recreating the Activity never closes a session
 * (decision P-1) and never loses chat or voice state (inv03 §0).
 *
 * Seams kept for B-09 (inv03 §1.1): share intents land in `graph.share` (a StateFlow, so a cold-launch
 * share is not lost); wake-word callbacks and the screen-on flags for wake triggers go through the
 * voice host, not this Activity; the launch effects (auto-connect, scan on the default URL) run in
 * `ConnectionRepository.start()`.
 */
class MainActivity : ComponentActivity() {
    private val graph: MainAppGraph get() = (application as GraphOwner).graph

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Nobody acts on default settings (inv04 B4): keep the splash until DataStore has loaded.
        splash.setKeepOnScreenCondition { graph.settings.settings.value == null }
        graph.connection.start()
        if (savedInstanceState == null) handleIntent(intent)
        setContent { ArchieApp(graph) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        ShellIntents.parseShare(intent)?.let { graph.share.offer(it) }
    }
}

/** The app's root composable: theme from device settings, the shell, and its ViewModel. */
@Composable
fun ArchieApp(graph: MainAppGraph) {
    val vm: ShellViewModel = viewModel(factory = viewModelFactory { initializer { ShellViewModel(graph) } })
    val state by vm.state.collectAsStateWithLifecycle()
    val backStack = rememberNavBackStack(Workspace)
    val destinations = remember(graph, vm) { GraphDestinations(graph, vm.sessions) }
    // B-08: Appearance → text size / reduce motion, and the AuthGate over the shell.
    val settings = rememberSettingsFeature(graph)
    val appearance by settings.device.appearance.collectAsStateWithLifecycle()
    ArchieTheme(mode = state.themeMode.toDesign(), reduceMotion = appearance.reduceMotion) {
        ProvideTextSize(appearance.textSize) {
            AuthGate(settings) { ArchieShell(state, vm::onAction, backStack, destinations) }
        }
    }
}

/** Intent parsing for the shell (pure enough to unit-test with Robolectric intents). */
object ShellIntents {
    /** `SEND` text → text; `SEND`/`SEND_MULTIPLE` streams → files (inv03 §1.8 adds multiple files). */
    fun parseShare(intent: Intent?): SharePayload? {
        intent ?: return null
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
        return when (intent.action) {
            Intent.ACTION_SEND -> {
                val stream = intent.streamExtra()
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                when {
                    stream != null -> SharePayload.Files(listOf(stream.toString()), subject)
                    !text.isNullOrBlank() -> SharePayload.Text(text, subject)
                    else -> null
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = intent.streamListExtra().map { it.toString() }
                if (uris.isEmpty()) null else SharePayload.Files(uris, subject)
            }
            else -> null
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.streamExtra(): Uri? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun Intent.streamListExtra(): List<Uri> =
        (if (Build.VERSION.SDK_INT >= 33) getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else getParcelableArrayListExtra(Intent.EXTRA_STREAM))
            .orEmpty()
}
