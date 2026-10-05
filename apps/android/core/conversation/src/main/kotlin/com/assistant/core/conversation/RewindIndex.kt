package com.assistant.core.conversation

import com.assistant.core.protocol.MessagePreviewDto

/**
 * `computeDropLastN` of spec 12 §6.5 (fixes W-6, A-4.4.3). The backend counts `drop_last_n` in
 * *visible JSONL lines*, which a rendered timeline cannot count, so the cut is anchored on **user
 * prompts** counted from the end and resolved against a fresh REST listing of the tail.
 *
 * Usage: `n = promptsNeeded(state, target)`; fetch tail pages (`limit=200`, `before=…`) until the
 * listing holds at least `n` prompt lines or `has_more` is false; then [compute].
 */
object RewindIndex {

    /** One REST line with its absolute index (`start_index + i`). */
    data class TailLine(val index: Int, val preview: MessagePreviewDto)

    sealed interface Result {
        /** The value for `truncate` / `fork`. `0` for the last entry = nothing to rewind. */
        data class DropLastN(val n: Int) : Result

        /** The view and the file disagree: canonical reload + "The conversation changed. Try again." */
        data object Abort : Result
    }

    /** Prompt lines the REST listing must contain: the prompts after the target, plus one. */
    fun promptsNeeded(state: ConversationState, targetEntryId: String): Int = promptsAfter(state, targetEntryId) + 1

    fun compute(state: ConversationState, targetEntryId: String, lines: List<TailLine>): Result {
        val ti = state.entries.indexOfFirst { it.id == targetEntryId }
        if (ti < 0) return Result.Abort
        val target = state.entries[ti]
        val after = state.entries.subList(ti + 1, state.entries.size)
        val k = after.count { it is UserEntry && it.state == UserState.SENT }
        val prompts = lines.filter { isPrompt(it.preview) }
        val cutFrom: Int
        if (target is UserEntry) {                                     // keep the prompt, drop its reply and everything after
            val j = prompts.size - 1 - k
            if (j < 0 || !matches(prompts[j], target)) return Result.Abort
            cutFrom = prompts[j].index + 1
        } else {                                                        // a run or notice: keep it, cut at the next prompt
            if (k == 0) return Result.DropLastN(0)
            val next = prompts.getOrNull(prompts.size - k) ?: return Result.Abort
            val firstUserAfter = after.first { it is UserEntry && it.state == UserState.SENT } as UserEntry
            if (!matches(next, firstUserAfter)) return Result.Abort
            cutFrom = next.index
        }
        return Result.DropLastN(lines.count { it.index >= cutFrom && isVisible(it.preview) })
    }

    private fun promptsAfter(state: ConversationState, targetEntryId: String): Int {
        val ti = state.entries.indexOfFirst { it.id == targetEntryId }
        if (ti < 0) return 0
        return state.entries.subList(ti + 1, state.entries.size).count { it is UserEntry && it.state == UserState.SENT }
    }

    /** A line the backend counts: assistant lines, and user lines that are not pure `tool_result` wrappers. */
    fun isVisible(p: MessagePreviewDto): Boolean =
        p.role == "assistant" || !(p.blocks.isNotEmpty() && p.blocks.all { it.type == "tool_result" })

    private fun isPrompt(p: MessagePreviewDto): Boolean =
        p.role == "user" && isVisible(p) && p.text.isNotEmpty() && HistoryMerger.classifyUserLine(p.text) is HistoryMerger.UserLine.User

    private enum class PromptClass { TYPED, INJECT, VOICE, AUDIO }

    private fun classOf(origin: UserOrigin) = when (origin) {
        UserOrigin.LOCAL, UserOrigin.ECHO, UserOrigin.HISTORY -> PromptClass.TYPED
        UserOrigin.INJECT -> PromptClass.INJECT
        UserOrigin.VOICE -> PromptClass.VOICE
        UserOrigin.AUDIO -> PromptClass.AUDIO
    }

    /** The classified origin class agrees; typed prompts and injects must also have the same normalised text. */
    private fun matches(line: TailLine, entry: UserEntry): Boolean {
        val c = HistoryMerger.classifyUserLine(line.preview.text) as? HistoryMerger.UserLine.User ?: return false
        val cls = classOf(c.origin)
        if (cls != classOf(entry.origin)) return false
        if (cls == PromptClass.TYPED || cls == PromptClass.INJECT) return norm(c.text) == norm(entry.text)
        return true
    }

    private val SPACES = Regex("\\s+")

    private fun norm(s: String) = s.trim().replace(SPACES, " ")
}
