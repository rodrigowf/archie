package com.assistant.archie.feature.chat.model

import androidx.compose.runtime.Immutable
import com.assistant.archie.feature.toolcards.AgentRun
import com.assistant.archie.feature.toolcards.AgentRunStatus
import com.assistant.core.conversation.ConversationState
import com.assistant.core.conversation.Subagent
import com.assistant.core.conversation.SubagentStatus
import com.assistant.core.network.Timestamps
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

/**
 * One subagent as the agent tabs, the Agents sheet and the inline card show it (spec 12 §4.8; plan
 * §4.4). Built from `ConversationState.subagents`, in spawn order.
 */
@Immutable
data class AgentUi(
    val id: String,
    val parentId: String?,
    /** 0 = spawned by the main agent; nested agents are indented / marked `↳` (SA-6). */
    val depth: Int,
    val type: String,
    val description: String,
    val status: SubagentStatus,
    val run: AgentRun,
    /** Its transcript is materialised (SA-5). */
    val open: Boolean,
) {
    private val shownType: String get() = type.takeUnless { it == "general-purpose" }.orEmpty()

    /** `Explore: Find scroll logic` (the Task card summary's wording). */
    val title: String get() = when {
        shownType.isNotEmpty() && description.isNotEmpty() -> "$shownType: $description"
        description.isNotEmpty() -> description
        type.isNotEmpty() -> type
        else -> "Agent"
    }

    /** The tab label: the task, else the agent type. */
    val tabLabel: String get() = description.ifEmpty { type.ifEmpty { "Agent" } }

    /** A breadcrumb segment (`Main › Explore › worker`). */
    val crumb: String get() = type.ifEmpty { description.ifEmpty { "Agent" } }

    val finished: Boolean get() = status.finished

    /** Accessible name of its tab / row: "Explore: Find scroll logic, running". */
    val spoken: String get() = "$title, ${run.spoken}" + if (depth > 0) ", nested agent" else ""
}

/** Everything the screen needs about the session's subagents. Empty = no tab row, no Agents button. */
@Immutable
data class AgentsUi(
    /** Every subagent, spawn order. */
    val all: ImmutableList<AgentUi> = persistentListOf(),
    /** The tab row after "Main": [all] minus the finished ones the user closed (the selected one always stays). */
    val tabs: ImmutableList<AgentUi> = persistentListOf(),
    /** The agent tab on screen; null = Main. */
    val selectedId: String? = null,
    val running: Int = 0,
    /** Finished agents that still have a tab ("Close finished" is enabled). */
    val closableFinished: Int = 0,
) {
    val any: Boolean get() = all.isNotEmpty()
}

/** The selected agent tab's body (plan §4.4 item 4). */
@Immutable
data class AgentViewUi(
    val agent: AgentUi,
    /** `Main › Explore › worker` segments for a nested agent; empty for a top-level one. */
    val path: ImmutableList<String>,
    val items: ImmutableList<ChatItem>,
    /** The first history page arrived (or failed). */
    val loaded: Boolean,
    /** SA-5: the history fetch failed; the transcript starts at the live tail. */
    val error: String?,
    val empty: Boolean,
    /** Only the latest page of its transcript is shown. */
    val truncated: Boolean = false,
)

/** Pure mapping from the data layer (unit-tested). */
object AgentUiMapper {
    private const val MAX_DEPTH = 8

    fun run(s: Subagent): AgentRun = AgentRun(
        id = s.toolUseId,
        status = when (s.status) {
            SubagentStatus.RUNNING -> AgentRunStatus.Running
            SubagentStatus.DONE -> AgentRunStatus.Done
            SubagentStatus.FAILED -> AgentRunStatus.Failed
            SubagentStatus.STOPPED -> AgentRunStatus.Stopped
        },
        activity = s.activity,
        toolUses = s.usage?.toolUses,
        tokens = s.usage?.tokens,
        startedAtMs = Timestamps.parseMillis(s.startedAt),
        endedAtMs = Timestamps.parseMillis(s.endedAt),
        durationMs = s.usage?.durationMs,
        summary = s.summary,
        background = s.background,
    )

    fun agent(s: Subagent, all: Map<String, Subagent>): AgentUi = AgentUi(
        id = s.toolUseId,
        parentId = s.parentToolUseId,
        depth = depth(s, all),
        type = s.agentType,
        description = s.description,
        status = s.status,
        run = run(s),
        open = s.open,
    )

    /** Ancestors in [all] (cycle-safe), nearest first. */
    fun ancestors(s: Subagent, all: Map<String, Subagent>): List<Subagent> {
        val out = ArrayList<Subagent>()
        var p = s.parentToolUseId
        while (p != null && out.size < MAX_DEPTH) {
            val a = all[p] ?: break
            if (a.toolUseId == s.toolUseId || out.any { it.toolUseId == a.toolUseId }) break
            out += a
            p = a.parentToolUseId
        }
        return out
    }

    fun depth(s: Subagent, all: Map<String, Subagent>): Int = if (s.parentToolUseId == null) 0 else maxOf(1, ancestors(s, all).size)

    fun agents(state: ConversationState, selected: String?, hidden: Set<String>): AgentsUi {
        val subs = state.subagents
        if (subs.isEmpty()) return AgentsUi(selectedId = null)
        val all = subs.values.map { agent(it, subs) }
        val tabs = all.filter { it.id !in hidden || it.id == selected }
        return AgentsUi(
            all = all.toImmutableList(),
            tabs = tabs.toImmutableList(),
            selectedId = selected?.takeIf { it in subs },
            running = all.count { it.status == SubagentStatus.RUNNING },
            closableFinished = tabs.count { it.finished },
        )
    }

    /** `Main › Explore › worker` for a nested agent, empty for a top-level one (SA-6). */
    fun path(s: Subagent, all: Map<String, Subagent>): List<String> {
        if (s.parentToolUseId == null) return emptyList()
        val chain = ancestors(s, all).asReversed().map { agent(it, all).crumb }
        return listOf("Main") + chain + agent(s, all).crumb
    }
}
