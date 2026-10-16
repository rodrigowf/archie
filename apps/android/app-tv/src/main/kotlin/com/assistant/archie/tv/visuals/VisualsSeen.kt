package com.assistant.archie.tv.visuals

import com.assistant.core.data.ContentStamp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * "Changed since you last opened it" (spec 15 §3.5 Visuals, mockup 10's dot). Per process: the live
 * change counter (spec 12 §9.3) each visualization had when the Viewer last showed it. A page whose
 * counter moved past that gets the dot; opening it clears the dot.
 */
class VisualsSeen {
    private val _seen = MutableStateFlow<Map<String, Int>>(emptyMap())
    val seen: StateFlow<Map<String, Int>> = _seen.asStateFlow()

    /** Also the "Find in Visuals" target: the Visuals grid focuses this path once on its next composition. */
    private val _focus = MutableStateFlow<String?>(null)
    val focus: StateFlow<String?> = _focus.asStateFlow()

    fun markOpened(path: String, version: Int) = _seen.update { if ((it[path] ?: 0) >= version) it else it + (path to version) }

    fun requestFocus(path: String) { _focus.value = path }
    fun consumeFocus(): String? = _focus.value.also { _focus.value = null }

    companion object {
        fun changed(path: String, stamps: Map<String, ContentStamp>, seen: Map<String, Int>): Boolean {
            val s = stamps[path] ?: return false
            return !s.deleted && s.version > (seen[path] ?: 0)
        }
    }
}
