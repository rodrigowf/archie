package com.assistant.archie.feature.visuals.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.assistant.archie.feature.visuals.VisualsDeps
import com.assistant.core.design.components.ArchieIconButton
import com.assistant.core.design.icons.ArchieIcons
import com.assistant.core.design.theme.ArchieTheme
import com.assistant.core.model.VisualInfo
import kotlinx.coroutines.launch

/**
 * The visuals list's sections: [starred] first, then [others]; each in server order (`modified`
 * desc), no visual in both. Both are filtered by the search words (title + path, all must match).
 */
internal data class VisualSections(val starred: List<VisualInfo>, val others: List<VisualInfo>) {
    val isEmpty: Boolean get() = starred.isEmpty() && others.isEmpty()
}

internal fun visualSections(items: List<VisualInfo>, query: String): VisualSections {
    val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val shown = items.filter { v -> "${v.title} ${v.path}".lowercase().let { t -> words.all { t.contains(it) } } }
    val (starred, others) = shown.partition { it.favorite }
    return VisualSections(starred, others)
}

/** The star toggle: filled (primary) when starred, outline otherwise; a 48 dp target. "Star"/"Unstar". */
@Composable
internal fun StarButton(starred: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier, size: Dp = 48.dp) {
    val c = ArchieTheme.colors
    ArchieIconButton(
        if (starred) ArchieIcons.StarFilled else ArchieIcons.Star,
        if (starred) "Unstar" else "Star",
        onToggle,
        modifier,
        size = size,
        iconSize = 22.dp,
        checked = starred,
        tint = if (starred) c.primary else c.onSurfaceVariant,
    )
}

/** Star / unstar [item] (optimistic in the list); a failure shows on [snackbar]. */
internal suspend fun toggleStar(deps: VisualsDeps, item: VisualInfo, snackbar: SnackbarHostState) {
    val on = !item.favorite
    deps.setFavorite(item.path, on).errorMessage()?.let {
        snackbar.showSnackbar("Could not ${if (on) "star" else "unstar"} the visual: $it")
    }
}

/** The viewer's star action for the open visual; absent until the visual is in the list. */
@Composable
internal fun StarAction(deps: VisualsDeps, item: VisualInfo?, snackbar: SnackbarHostState) {
    if (item == null) return
    val scope = rememberCoroutineScope()
    StarButton(item.favorite, { scope.launch { toggleStar(deps, item, snackbar) } }, Modifier.testTag("visual-star"))
}
