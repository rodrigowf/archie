package com.assistant.core.markdown.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.assistant.core.design.theme.ArchieTheme
import com.assistant.core.design.theme.ThemeMode
import com.assistant.core.markdown.CodeHighlighter
import com.assistant.core.markdown.Corpus
import com.assistant.core.markdown.Frontmatter
import com.assistant.core.markdown.MarkdownDocument
import com.assistant.core.markdown.MdNode
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi goldens of 12 markdown samples, dark and light, at Compact (the POCO, w443dp xxhdpi)
 * → src/test/screenshots/md-<sample>_compact_<theme>.png (spec 14 §6.4, B-02 DoD).
 *
 *   flock /tmp/archie-locks/gradle.lock ./gradlew :core:markdown:recordRoborazziDebug   record
 *   flock /tmp/archie-locks/gradle.lock ./gradlew :core:markdown:verifyRoborazziDebug   compare
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w443dp-h2400dp-xxhdpi")
class MarkdownGoldens(private val sample: String) {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun golden() {
        val content = SAMPLES.getValue(sample)
        var mode by mutableStateOf(ThemeMode.Dark)
        compose.setContent {
            ArchieTheme(mode = mode, reduceMotion = true) {
                Box(
                    Modifier
                        .testTag(TAG)
                        .fillMaxWidth()
                        .background(ArchieTheme.colors.surface)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) { content() }
            }
        }
        for (theme in listOf(ThemeMode.Dark, ThemeMode.Light)) {
            mode = theme
            compose.waitForIdle()
            compose.onNodeWithTag(TAG).captureRoboImage("$DIR/md-${sample}_compact_${theme.name.lowercase()}.png")
        }
    }

    companion object {
        private const val TAG = "md-sample"
        private const val DIR = "src/test/screenshots"

        /** Parses and pre-highlights (goldens must not race the background highlighter). */
        private fun nodes(md: String): List<MdNode> = MarkdownDocument.parse(md).also { list ->
            fun warm(n: MdNode) {
                when (n) {
                    is MdNode.CodeBlock -> CodeHighlighter.highlight(n.lang, n.code)
                    is MdNode.ListBlock -> n.items.forEach { it.children.forEach(::warm) }
                    is MdNode.Quote -> n.children.forEach(::warm)
                    else -> Unit
                }
            }
            list.forEach(::warm)
        }

        private fun doc(md: String): @Composable () -> Unit {
            val n = nodes(md)
            return { MarkdownNodes(n) }
        }

        private val PROSE = """
            Realtime voice runs browser ↔ OpenAI over **WebRTC**. The backend only *signals*, runs tools and
            saves transcripts — see `VoiceStateMachine` and [the voice notes](https://example.com/voice).

            Mixed ***bold italic***, ~~struck~~ text, a `longer.inline.code(call)` that wraps inside its
            background when the line ends, and a hard break\
            right here. Escaped \*stars\* stay literal.
        """.trimIndent()

        private val HEADINGS = """
            # Heading one
            Body text under the first heading.
            ## Heading two
            Body text.
            ### Heading three
            #### Heading four
            ##### Heading five
            ###### Heading six
            Closing paragraph.
        """.trimIndent()

        private val LISTS = """
            1. Extract the state machine
            2. Port the **parity** tests
               - voice tests
               - wake-word tests
                 1. confirm gate
                 2. capture
            3. Update `voice_subsystem.md`

            - A bullet with a long line of text that wraps onto a second line to show the hanging indent
            - Second bullet
              > with a quote inside
        """.trimIndent()

        private val CODE_KOTLIN = """
            The fix moves the restart into the state machine:

            ```kotlin
            @Synchronized
            fun restart(reason: String): Boolean {
                // Idempotent: a second call while restarting is a no-op.
                if (state == State.RESTARTING) return false
                val delayMs = 3_000L * attempts
                log("restart: ${'$'}reason after ${'$'}delayMs ms")
                return scheduler.post(delayMs) { open() }
            }
            ```
        """.trimIndent()

        private val CODE_PLAIN = """
            ```python
            def greet(name: str) -> str:
                return f"Hello, {name}"  # f-string
            ```

            ```bash
            ./gradlew :core:markdown:testDebugUnitTest --tests '*Streaming*' && echo "a very long line that wraps by default"
            ```

            ```
            plain fence without a language
            IDLE ──start──▶ LISTENING ◀──▶ SPEAKING
            ```
        """.trimIndent()

        private val QUOTE_RULE = """
            > **Note:** the Jetson has no Node, so build on the laptop.
            > - rsync `dist/`
            > - restart the service
            >
            > > nested quote

            ---

            Text after the rule.
        """.trimIndent()

        private val LINKS = Corpus.text("05-web-autolinks") +
            "\n\nAn image: ![architecture diagram](https://example.com/arch.png) and an empty alt ![](https://example.com/x.png).\n\n" +
            "Memory link [wakeword_subsystem.md](wakeword_subsystem.md) and anchor [Lifecycle](#lifecycle)."

        private fun memoryDoc(): @Composable () -> Unit {
            val split = Frontmatter.split(Corpus.text("06-web-memory-doc"))
            val body = nodes(split.body)
            return {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    FrontmatterCard(split.frontmatter!!)
                    FrontmatterCard(split.frontmatter!!, initiallyExpanded = true)
                    MarkdownNodes(body)
                }
            }
        }

        /** Mid-stream: a committed table, then an open fence (plain, wrapped CodeTail). */
        private fun streaming(): @Composable () -> Unit {
            val d = MarkdownDocument()
            val text = "Working on it — current status:\n\n| Step | State |\n|---|:---:|\n| build | **ok** |\n| test | `running` |\n\n" +
                "```kotlin\nfun restart() {\n    val delay = 3_000L // a long comment line that keeps going so it has to wrap in the tail\n    sched"
            text.chunked(9).forEach { d.append(it) }
            val n = d.snapshot.nodes
            return { MarkdownNodes(n) }
        }

        val SAMPLES: Map<String, @Composable () -> Unit> by lazy {
            linkedMapOf(
                "prose" to doc(PROSE),
                "headings" to doc(HEADINGS),
                "lists" to doc(LISTS),
                "tasks" to doc(Corpus.text("04-web-tasklists")),
                "code-kotlin" to doc(CODE_KOTLIN),
                "code-plain" to doc(CODE_PLAIN),
                "tables" to doc(Corpus.text("03-web-tables")),
                "quote-rule" to doc(QUOTE_RULE),
                "links-images" to doc(LINKS),
                "memory-doc" to memoryDoc(),
                "streaming" to streaming(),
                "assistant" to doc(Corpus.text("39-syn-assistant")),
            )
        }

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = SAMPLES.keys.map { arrayOf<Any>(it) }
    }
}
