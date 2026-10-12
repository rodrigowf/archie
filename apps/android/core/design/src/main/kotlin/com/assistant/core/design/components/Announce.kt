package com.assistant.core.design.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * A polite live region that announces [text] whenever it changes (state changes such as "Explore
 * finished", never streamed content). It draws nothing; it is 1 dp so accessibility services keep
 * the node. [assertive] for failures and needs-input only.
 */
@Composable
fun LiveAnnouncement(text: String, modifier: Modifier = Modifier, assertive: Boolean = false) {
    Box(
        modifier.size(1.dp).semantics {
            liveRegion = if (assertive) LiveRegionMode.Assertive else LiveRegionMode.Polite
            contentDescription = text
        },
    )
}
