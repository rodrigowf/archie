package com.assistant.archie.tv.viewer

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import androidx.tv.material3.Text
import com.assistant.archie.feature.memory.MemoryRow
import com.assistant.archie.tv.memory.MemoryActions
import com.assistant.archie.tv.memory.MemoryDocActions
import com.assistant.archie.tv.memory.MemoryDocScreen
import com.assistant.archie.tv.memory.MemoryDocUi
import com.assistant.archie.tv.memory.MemoryPreviews
import com.assistant.archie.tv.memory.MemoryScreen
import com.assistant.archie.tv.memory.MemoryUi
import com.assistant.archie.tv.memory.ReadingScroller
import com.assistant.archie.tv.memory.TvMarkdown
import com.assistant.archie.tv.shell.Memory
import com.assistant.archie.tv.shell.MemoryDoc
import com.assistant.archie.tv.shell.TvFixtures
import com.assistant.archie.tv.shell.TvShell
import com.assistant.archie.tv.shell.TvShellController
import com.assistant.archie.tv.shell.TvToasts
import com.assistant.archie.tv.shell.Viewer
import com.assistant.archie.tv.shell.Visuals
import com.assistant.archie.tv.theme.TvTheme
import com.assistant.archie.tv.visuals.VisualCardUi
import com.assistant.archie.tv.visuals.VisualDialog
import com.assistant.archie.tv.visuals.VisualKind
import com.assistant.archie.tv.visuals.VisualsActions
import com.assistant.archie.tv.visuals.VisualsScreen
import com.assistant.archie.tv.visuals.VisualsUi
import com.assistant.core.markdown.InternalLinks
import com.assistant.core.markdown.MarkdownDocument
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/*
 * Roborazzi goldens of T3's screens at 960×540 dp (television, xhdpi), in the real shell, compared
 * by eye with mockups 10, 11/11b, 12, 13, 13b (docs/projects/tv-app/mockups). State-driven: the
 * Viewer's page is a stand-in (Robolectric has no Chromium).
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w960dp-h540dp-land-television-xhdpi", application = Application::class)
class T3Goldens {
    @get:Rule
    val compose = createComposeRule()

    private fun scene(name: String, vararg stack: NavKey, content: @Composable (NavKey) -> Unit) {
        val backStack = mutableStateListOf(*stack)
        val controller = TvShellController(backStack)
        compose.setContent {
            TvTheme(reduceMotion = true) {
                TvShell(TvFixtures.shell, controller, TvToasts(), entry = content)
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        captureScreenRoboImage("src/test/screenshots/$name.png")
    }

    @Test fun visuals() = scene("visuals", Visuals) { VisualsScreen(T3Fixtures.visuals, VisualsActions()) }

    @Test fun visualsLink() = scene("visuals-link", Visuals) {
        val card = T3Fixtures.visuals.cards.first()
        VisualsScreen(T3Fixtures.visuals, VisualsActions(), dialog = VisualDialog.Link(card, "https://192.168.0.200/visualizations/solar-dashboard.html"))
    }

    @Test fun viewerOptions() = scene("viewer-options", Visuals, Viewer("visualizations/solar-dashboard.html", "Solar dashboard", pushed = true)) {
        ViewerScreen(T3Fixtures.viewer, ViewerActions(), initialOptionsOpen = true) { FakeDashboard() }
    }

    @Test fun viewerPointer() = scene("viewer-pointer", Visuals, Viewer("visualizations/solar-dashboard.html", "Solar dashboard", pushed = true)) {
        ViewerScreen(
            T3Fixtures.viewer.copy(pointerMode = true, cursor = Offset(560f, 520f), toast = "Opened by Archie · Solar dashboard"),
            ViewerActions(),
        ) { FakeDashboard() }
    }

    @Test fun memory() = scene("memory", Memory) { MemoryScreen(T3Fixtures.memory, MemoryActions(), initialFocusPath = "people/rodrigo/rodrigo_personal_context.md") }

    @Test fun memoryDoc() = scene("memory-doc", Memory, MemoryDoc("archie/devices/fire-tv.md")) {
        val list = rememberLazyListState()
        val scope = rememberCoroutineScope()
        val scroller = remember { ReadingScroller(list, scope) }
        MemoryDocScreen(T3Fixtures.doc, MemoryDocActions(), scroller)
    }
}

/** A stand-in for the Solar dashboard page of mockup 11 (the real page is a WebView). */
@Composable
private fun FakeDashboard() {
    val bg = Color(0xFF0F1A2A)
    val panel = Color(0xFF16263B)
    val amber = Color(0xFFF2BC62)
    Column(Modifier.fillMaxSize().background(bg).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Solar", color = Color.White, style = TvTheme.type.headlineSmall)
                Text("Rooftop array · Fri 16 Oct · 21:12", color = Color(0xFF8FA3BF), style = TvTheme.type.labelMedium)
            }
            Row(Modifier.background(panel, RoundedCornerShape(10.dp)).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (t in listOf("Today", "Week", "Month")) Text(t, color = Color.White, style = TvTheme.type.labelLarge, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for ((l, v) in listOf("Producing now" to "0.0 kW", "Produced today" to "18.4 kWh", "Battery" to "76 %", "Exported" to "2.1 kWh")) {
                Column(Modifier.weight(1f).background(panel, RoundedCornerShape(12.dp)).padding(12.dp)) {
                    Text(l, color = Color(0xFF8FA3BF), style = TvTheme.type.labelMedium)
                    Text(v, color = Color.White, style = TvTheme.type.headlineSmall)
                }
            }
        }
        Row(Modifier.weight(1f).fillMaxWidth().background(panel, RoundedCornerShape(12.dp)).padding(16.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (h in listOf(3, 14, 31, 55, 76, 90, 100, 97, 86, 69, 48, 24, 7)) {
                Box(Modifier.weight(1f).fillMaxHeight(h / 100f).background(amber, RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)))
            }
        }
        Spacer(Modifier.height(0.dp).width(0.dp))
    }
}

object T3Fixtures {
    private fun card(path: String, title: String, updated: String, changed: Boolean = false) =
        VisualCardUi(path, title, VisualKind.of(path, title), VisualKind.fileLabel(path), updated, changed)

    val visuals = VisualsUi(
        cards = listOf(
            card("visualizations/solar-dashboard.html", "Solar dashboard", "updated 5 min ago", changed = true),
            card("tv-app/index.html", "Archie TV plan", "updated 1 h ago"),
            card("visualizations/wakeword-timeline.html", "Wake word timeline", "updated yesterday"),
            card("visualizations/qvcm-sim.html", "QVCM simulation", "updated 2 days ago"),
            card("visualizations/jetson-thermals.html", "Jetson thermals", "updated 3 days ago"),
            card("tarot-review/index.html", "Tarot app screens", "updated 4 days ago"),
            card("visualizations/firetv-keys.html", "Fire TV remote keys", "updated 5 days ago"),
            card("home-automation/index.html", "Home automation", "updated 7 days ago"),
            card("visualizations/history-eval.html", "History search eval", "updated 8 days ago"),
            card("music1-review/index.html", "Music video cuts", "updated 14 days ago"),
            card("biography/index.html", "Biography outline", "updated 15 days ago"),
            card("visualizations/rio-model.html", "Election model, Rio", "updated 30 days ago"),
        ),
        total = 24,
    )

    val viewer = ViewerUi(
        title = "Solar dashboard",
        url = "https://192.168.0.200/visualizations/solar-dashboard.html",
    )

    private val docMd = """
        # Fire TV Stick 4K

        The living-room stick (AFTKM, Fire OS 8) runs the launcher and Archie TV. Its address comes from DHCP, so scripts find it by MAC; see [devices.md](devices.md) for the full device list.

        ## How Archie reaches it

        - Agents open pages through the TV app's control socket: `tv_cmd.py viz <path>`.
        - adb stays as the fallback for launching other apps and screenshots; see [connect-tv](/memory/archie/skills/connect-tv.md).
        - The mic button belongs to Archie once `tv_setup.sh` has turned off Alexa's voice UI; [Remote mic probe](/visualizations/remote-mic-probe.html) has the numbers.

        ## Display

        1920 × 1080 at 320 dpi, so 960 × 540 dp. Keep everything inside 48 dp left and right, 27 dp top and bottom.
    """.trimIndent()

    val doc = MemoryDocUi(
        path = "archie/devices/fire-tv.md",
        title = "Fire TV",
        blocks = TvMarkdown.blocks(MarkdownDocument.parse(docMd), InternalLinks.Context("https://192.168.0.200")),
        updated = true,
    )

    private val previewMd = """
        ---
        category: people
        modified: 2026-10-14
        ---
        # Rodrigo · personal context

        ## Work

        Senior full-stack developer and AI researcher in Rio de Janeiro. Works remotely for a U.S. SaaS company; Archie is his own project, built as a partner rather than a product.

        ## How he likes to work

        - Stays in control: direct the model to an exact result.
        - Runs several projects in parallel; expects state to be kept in files.
        - Plain answers, no filler.
    """.trimIndent()

    val memory = MemoryUi(
        rows = listOf(
            MemoryRow("MEMORY.md", "MEMORY.md", 0, isDir = false),
            MemoryRow("archie", "archie", 0, isDir = true, count = 94),
            MemoryRow("deployment", "deployment", 0, isDir = true, count = 7),
            MemoryRow("identity", "identity", 0, isDir = true, count = 5),
            MemoryRow("people", "people", 0, isDir = true, expanded = true, count = 12),
            MemoryRow("people/alice", "alice", 1, isDir = true, count = 3),
            MemoryRow("people/rodrigo", "rodrigo", 1, isDir = true, expanded = true, count = 2),
            MemoryRow("people/rodrigo/rodrigo_personal_context.md", "rodrigo_personal_context.md", 2, isDir = false),
            MemoryRow("people/rodrigo/routines.md", "routines.md", 2, isDir = false),
            MemoryRow("projects", "projects", 0, isDir = true, count = 31),
        ),
        preview = MemoryPreviews.of(
            "people/rodrigo/rodrigo_personal_context.md",
            previewMd,
            TvMarkdown.blocks(MarkdownDocument.parse(previewMd.substringAfter("---\n").substringAfter("---\n"))),
            updated = true,
        ),
    )
}
