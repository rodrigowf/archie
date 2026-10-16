package com.assistant.archie.tv.visuals

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import com.assistant.archie.tv.theme.TvTheme
import com.assistant.core.design.theme.ArchieTheme

/**
 * The kind band and label of a visualization card (mockup 10). The backend has no "kind" field, so
 * it is read from the page's path and title: a folder page (`x/index.html`) is a project page;
 * words in the name pick dashboard / review / model / explainer; anything else is a plain page.
 * Colors are the tool-category tokens the mockup uses (`--md-ext-color-tool-*`).
 */
enum class VisualKind(val label: String) {
    DASHBOARD("Dashboard"),
    PROJECT("Project page"),
    EXPLAINER("Explainer"),
    REVIEW("Review"),
    MODEL("Model"),
    PAGE("Page"),
    ;

    val color: Color
        @Composable @ReadOnlyComposable get() {
            val x = ArchieTheme.extended
            return when (this) {
                DASHBOARD -> x.toolCapture.color
                PROJECT -> x.toolWrite.color
                EXPLAINER -> x.toolSearch.color
                REVIEW -> x.toolExecute.color
                MODEL -> x.toolNavigate.color
                PAGE -> TvTheme.colors.primary
            }
        }

    companion object {
        private val R_DASH = Regex("\\b(?:dashboard|stats|status|monitor|metrics|thermal|tracker|board|report|eval)")
        private val R_REVIEW = Regex("\\b(?:review|compare|picker|cuts|screens|mockup|gallery|choose|pick)")
        private val R_MODEL = Regex("\\b(?:model|sim|simulat|calc|playground|solver)")
        private val R_EXPLAIN = Regex("\\b(?:explain|timeline|how|guide|keys|walkthrough|tour|intro|primer|story)")

        fun of(path: String, title: String): VisualKind {
            val name = path.substringBeforeLast('.').lowercase() + " " + title.lowercase()
            return when {
                R_DASH.containsMatchIn(name) -> DASHBOARD
                R_REVIEW.containsMatchIn(name) -> REVIEW
                R_MODEL.containsMatchIn(name) -> MODEL
                R_EXPLAIN.containsMatchIn(name) -> EXPLAINER
                path.endsWith("/index.html", ignoreCase = true) -> PROJECT
                else -> PAGE
            }
        }

        /** What the card prints as the path: `solar-dashboard.html`, or `tv-app/` for a folder page. */
        fun fileLabel(path: String): String {
            val parts = path.split('/').filter { it.isNotEmpty() }
            if (parts.size >= 2 && parts.last().equals("index.html", ignoreCase = true)) return parts[parts.size - 2] + "/"
            return parts.lastOrNull() ?: path
        }
    }
}
