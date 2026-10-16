package com.assistant.archie.tv.home

import androidx.compose.runtime.Immutable
import androidx.navigation3.runtime.NavKey
import com.assistant.archie.feature.sessions.SessionTarget
import com.assistant.archie.feature.sessions.SessionTitles
import com.assistant.archie.tv.conversations.ConvRef
import com.assistant.archie.tv.conversations.OpenTarget
import com.assistant.archie.tv.conversations.sessionState
import com.assistant.archie.tv.shell.MemoryDoc
import com.assistant.archie.tv.shell.Viewer
import com.assistant.archie.tv.theme.SessionState
import com.assistant.archie.tv.theme.TvFormat
import com.assistant.archie.tv.visuals.VisualKind
import com.assistant.core.conversation.AssistantEntry
import com.assistant.core.conversation.ConversationState
import com.assistant.core.conversation.TextBlock
import com.assistant.core.data.ContentStamp
import com.assistant.core.data.HistoryBucket
import com.assistant.core.data.HistoryGrouping
import com.assistant.core.data.ItemKind
import com.assistant.core.data.LoadState
import com.assistant.core.data.WorkspaceItem
import com.assistant.core.model.MemoryNode
import com.assistant.core.model.SessionSummary
import com.assistant.core.model.VisualInfo
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** The hero: the current Archie conversation (spec 15 §3.5 Home 1). */
@Immutable
data class HeroUi(
    val hasConversation: Boolean = false,
    val title: String = "Archie",
    val state: SessionState = SessionState.NONE,
    val stateLabel: String = "",
    /** Last activity, local "HH:mm" (or "Tue", "12 Sep"). */
    val time: String? = null,
    /** The end of the latest reply, plain text (the card shows two lines). */
    val lastReply: String = "",
)

/** An agent session in "Open now". */
@Immutable
data class AgentCardUi(val key: String, val ref: ConvRef, val provider: String?, val title: String, val state: SessionState, val lastLine: String)

@Immutable
data class VisualCardUi(
    val key: String,
    val title: String,
    val file: String,
    val updated: String?,
    val changed: Boolean,
    val target: NavKey,
    /** The same kind band as the Visuals grid (T3's [VisualKind], read from path + title). */
    val kind: VisualKind = VisualKind.PAGE,
)

@Immutable
data class MemoryCardUi(val key: String, val title: String, val folder: String, val detail: String, val target: NavKey)

@Immutable
data class RecentCardUi(val key: String, val ref: ConvRef, val archie: Boolean, val provider: String?, val title: String, val time: String, val detail: String)

@Immutable
data class HomeUiState(
    val hero: HeroUi = HeroUi(),
    val openNow: List<AgentCardUi> = emptyList(),
    val visuals: List<VisualCardUi> = emptyList(),
    val memory: List<MemoryCardUi> = emptyList(),
    val recent: List<RecentCardUi> = emptyList(),
    val loading: Boolean = false,
)

/** Graph → [HomeUiState] (pure, JVM-tested). */
object HomeModel {
    const val ROW_LIMIT = 12

    fun state(
        archie: ConversationState?,
        items: List<WorkspaceItem>,
        lastLine: (WorkspaceItem) -> String?,
        visuals: LoadState<List<VisualInfo>>,
        changedVisuals: Map<String, ContentStamp>,
        tree: LoadState<List<MemoryNode>>,
        changedMemory: Map<String, ContentStamp>,
        sessions: LoadState<List<SessionSummary>>,
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): HomeUiState {
        val list = sessions.value.orEmpty()
        val archieItem = items.firstOrNull { it.kind == ItemKind.ARCHIE }
        val metas = metaBySdk(list, now, zone, locale)
        return HomeUiState(
            hero = hero(archie, archieItem, metas),
            openNow = items.filter { it.kind == ItemKind.AGENT }.take(ROW_LIMIT).map { w ->
                AgentCardUi(
                    key = "open:${w.localId ?: w.sdkId ?: w.key}",
                    ref = ConvRef.of(w),
                    provider = w.provider?.label,
                    title = SessionTitles.conversationTitle(w.title, isArchie = false),
                    state = sessionState(w.status),
                    lastLine = lastLine(w)?.takeIf { it.isNotBlank() } ?: w.detail,
                )
            },
            visuals = visuals.value.orEmpty()
                .sortedByDescending { TvFormat.parse(it.modified) ?: Instant.MIN }
                .take(ROW_LIMIT)
                .map { v ->
                    val stamp = changedVisuals[v.path]
                    val title = v.title.ifBlank { TvFormat.titleOf(v.path).capitalized() }
                    VisualCardUi(
                        key = "viz:${v.path}",
                        title = title,
                        file = fileOf(v.path),
                        updated = TvFormat.ago(v.modified, now)?.let { "updated $it" },
                        changed = stamp != null && !stamp.deleted,
                        target = Viewer(v.path, v.title.ifBlank { null }),
                        kind = VisualKind.of(v.path, title),
                    )
                },
            memory = memoryCards(tree.value.orEmpty(), changedMemory),
            recent = list
                .sortedByDescending { TvFormat.parse(it.lastActivity ?: it.startedAt) ?: Instant.MIN }
                .take(ROW_LIMIT)
                .map { s ->
                    RecentCardUi(
                        key = "recent:${s.sdkId}",
                        ref = ConvRef(OpenTarget.History(s), SessionTarget.of(s), item = null),
                        archie = s.isOrchestrator,
                        provider = s.provider?.label,
                        title = SessionTitles.conversationTitle(s.title, s.isOrchestrator),
                        time = metas[s.sdkId].orEmpty(),
                        detail = if (s.messageCount == 1) "1 message" else "${s.messageCount} messages",
                    )
                },
            loading = visuals.loading || tree.loading || sessions.loading,
        )
    }

    private fun hero(archie: ConversationState?, item: WorkspaceItem?, metas: Map<String, String>): HeroUi {
        if (item == null && archie == null) return HeroUi()
        val state = item?.let { sessionState(it.status) } ?: SessionState.IDLE
        return HeroUi(
            hasConversation = true,
            title = SessionTitles.conversationTitle(item?.title, isArchie = true),
            state = state,
            stateLabel = when (state) {
                SessionState.WORKING -> "Working"
                SessionState.NEEDS_APPROVAL -> "Needs approval"
                SessionState.CONNECTING -> "Connecting"
                SessionState.OFFLINE -> "Disconnected"
                SessionState.IDLE, SessionState.NONE -> "Idle"
            },
            time = archie?.ref?.sdkId?.let { metas[it] },
            lastReply = archie?.let(::lastReply).orEmpty(),
        )
    }

    /** "Today 21:14" / "Yesterday 22:40" / "Tue" / "12 Sep" per history row (local day buckets, as the apps). */
    fun metaBySdk(list: List<SessionSummary>, now: Instant, zone: ZoneId, locale: Locale): Map<String, String> =
        HistoryGrouping.group(list, now, zone, locale).flatMap { g ->
            g.rows.map { r ->
                r.summary.sdkId to when (g.bucket) {
                    HistoryBucket.TODAY -> "Today ${r.meta}".trim()
                    HistoryBucket.YESTERDAY -> "Yesterday"
                    else -> r.meta
                }
            }
        }.toMap()

    /** The end of the latest assistant reply: its last paragraph, as plain text. */
    fun lastReply(s: ConversationState): String {
        for (e in s.entries.asReversed()) {
            if (e !is AssistantEntry) continue
            val text = e.blocks.filterIsInstance<TextBlock>().lastOrNull { it.text.isNotBlank() }?.text ?: continue
            return TextPreview.lastParagraph(text)
        }
        return ""
    }

    /**
     * Memory notes (MemoryNode has no dates): notes changed while the app runs (`memory_changed`,
     * newest first), then the tree's start-here files (root files, then each folder's INDEX.md).
     */
    fun memoryCards(tree: List<MemoryNode>, changed: Map<String, ContentStamp>): List<MemoryCardUi> {
        val out = LinkedHashMap<String, MemoryCardUi>()
        for ((path, stamp) in changed.entries.reversed()) {
            if (stamp.deleted || !path.endsWith(".md", ignoreCase = true)) continue
            out[path] = memoryCard(path, "Changed while you watched")
        }
        for (path in keyFiles(tree)) if (path !in out) out[path] = memoryCard(path, "Start here")
        return out.values.take(ROW_LIMIT)
    }

    /** MEMORY.md (the root index), every top-level folder's INDEX.md, then the other root files (ARCHIVE.md is retired). */
    fun keyFiles(tree: List<MemoryNode>): List<String> = buildList {
        val rootFiles = tree.filter { !it.isDir && it.name.endsWith(".md", ignoreCase = true) && !it.name.equals("ARCHIVE.md", ignoreCase = true) }
        rootFiles.firstOrNull { it.name.equals("MEMORY.md", ignoreCase = true) }?.let { add(it.path) }
        tree.filter { it.isDir }.forEach { d ->
            d.children.orEmpty().firstOrNull { !it.isDir && it.name.equals("INDEX.md", ignoreCase = true) }?.let { add(it.path) }
        }
        rootFiles.filterNot { it.name.equals("MEMORY.md", ignoreCase = true) }.forEach { add(it.path) }
    }

    private fun memoryCard(path: String, detail: String): MemoryCardUi {
        val folder = TvFormat.folderOf(path)
        val name = path.substringAfterLast('/')
        val title = when {
            name.equals("INDEX.md", ignoreCase = true) && folder.isNotEmpty() -> "${folder.substringAfterLast('/').capitalized()} index"
            name.equals("MEMORY.md", ignoreCase = true) -> "Memory index"
            else -> TvFormat.titleOf(path).capitalized()
        }
        return MemoryCardUi("mem:$path", title, folder.ifEmpty { "memory" }, detail, MemoryDoc(path))
    }

    /** Memory files, depth-first, skipping index files (T0 helper, kept for the Memory screen tests). */
    fun files(nodes: List<MemoryNode>): List<MemoryNode> = buildList {
        fun walk(list: List<MemoryNode>) {
            for (n in list) if (n.isDir) walk(n.children.orEmpty()) else if (!n.name.equals("INDEX.md", ignoreCase = true)) add(n)
        }
        walk(nodes)
    }

    /** "solar-dashboard.html", or "tv-app/" for a folder page. */
    fun fileOf(path: String): String {
        val p = path.removePrefix("visualizations/")
        return if (p.endsWith("/index.html")) p.removeSuffix("index.html") else p.substringAfterLast('/')
    }

    private fun String.capitalized() = replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
}

/** Plain-text previews of markdown (cards, preview lines). Pure. */
object TextPreview {
    private val FENCE = Regex("```[\\s\\S]*?```")
    private val LINK = Regex("\\[([^\\]]*)]\\([^)]*\\)")
    private val MARKS = Regex("[*_`~]+")
    private val PREFIX = Regex("^\\s{0,3}(#{1,6}\\s+|>\\s?|[-*+]\\s+|\\d+[.)]\\s+)")

    /** The last non-empty paragraph of [md], plain. */
    fun lastParagraph(md: String): String {
        val noCode = FENCE.replace(md, " ")
        val paras = noCode.split(Regex("\\n\\s*\\n")).map { plain(it) }.filter { it.isNotBlank() }
        return paras.lastOrNull().orEmpty()
    }

    /** Markdown → one line of plain text (links keep their text, marks and list/heading prefixes go). */
    fun plain(md: String): String = md.lineSequence()
        .map { PREFIX.replace(it, "") }
        .map { LINK.replace(it) { m -> m.groupValues[1] } }
        .map { MARKS.replace(it, "") }
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString(" ")
}
