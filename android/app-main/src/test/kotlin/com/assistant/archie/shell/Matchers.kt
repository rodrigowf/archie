package com.assistant.archie.shell

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText

/** Text inside the Compact top app bar (the closed drawer keeps the same titles composed off screen). */
fun titleInTopBar(text: String): SemanticsMatcher = hasText(text) and hasAnyAncestor(hasTestTag("top-app-bar"))
