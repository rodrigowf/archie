package com.assistant.archie.tv.conversations

import androidx.compose.runtime.Immutable
import com.assistant.archie.feature.sessions.HistoryList
import com.assistant.archie.feature.sessions.NEEDS_FIRST_REPLY
import com.assistant.archie.feature.sessions.SessionTarget
import com.assistant.archie.feature.sessions.SessionTitles
import com.assistant.archie.tv.shell.TvNavigator
import com.assistant.archie.tv.shell.TvOption
import com.assistant.archie.tv.theme.SessionState
import com.assistant.core.data.ItemKind
import com.assistant.core.data.TabStatus
import com.assistant.core.data.WorkspaceItem
import com.assistant.core.design.icons.ArchieIcons
import com.assistant.core.model.SessionSummary
import com.assistant.core.protocol.MessagePreviewDto
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** A conversation as a Home card / Conversations row refers to it: how to open it, and what actions apply. */
@Immutable
data class ConvRef(
    val target: OpenTarget,
    val session: SessionTarget,
    /** The "Open now" item (Close applies), or null for a history row. */
    val item: WorkspaceItem?,
) {
    val archie: Boolean get() = session.isArchie

    companion object {
        fun of(w: WorkspaceItem) = ConvRef(
            target = OpenTarget.OpenItem(w.key, w.localId, w.sdkId, archie = w.kind == ItemKind.ARCHIE),
            session = SessionTarget.of(w),
            item = w,
        )
    }
}

/** ST-3 tab status → what a TV row shows. */
fun sessionState(s: TabStatus): SessionState = when (s) {
    TabStatus.WORKING -> SessionState.WORKING
    TabStatus.NEEDS_YOU -> SessionState.NEEDS_APPROVAL
    TabStatus.CONNECTING -> SessionState.CONNECTING
    TabStatus.DISCONNECTED -> SessionState.OFFLINE
    TabStatus.IDLE, TabStatus.STOPPED -> SessionState.IDLE
    TabStatus.NONE -> SessionState.NONE
}

@Immutable
data class ConvRowUi(
    val key: String,
    val ref: ConvRef,
    val title: String,
    /** "Archie", or the harness label. */
    val who: String,
    /** Open rows: the live state; history rows: null. */
    val state: SessionState?,
    /** "16:20", "Tue", "12 Sep" for history rows. */
    val time: String,
    val messageCount: Int?,
) {
    val archie: Boolean get() = ref.archie
    val sdkId: String? get() = ref.session.sdkId
    val provider: String? get() = ref.session.provider?.label
}

@Immutable
data class ConvSectionUi(val title: String, val rows: List<ConvRowUi>)

/** One line of the preview pane (mockup 5, right). */
@Immutable
sealed interface PreviewLine {
    data class User(val text: String) : PreviewLine
    data class Assistant(val text: String) : PreviewLine
    data class Steps(val count: Int, val names: List<String>) : PreviewLine
}

@Immutable
data class PreviewUi(
    val loading: Boolean = false,
    val lines: List<PreviewLine> = emptyList(),
    val workingDir: String? = null,
    val error: String? = null,
)

/** Pure list logic of the Conversations screen (JVM-tested). */
object ConversationsModel {
    /** Open now (the server's open set, Archie first) + history by local day, both filtered by [query] (same rules as the apps). */
    fun sections(
        items: List<WorkspaceItem>,
        sessions: List<SessionSummary>,
        query: String,
        now: Instant,
        zone: ZoneId,
        locale: Locale = Locale.getDefault(),
    ): List<ConvSectionUi> {
        val conv = items.filter { it.kind == ItemKind.ARCHIE || it.kind == ItemKind.AGENT }
        val open = HistoryList.openMatching(conv, query).map { w ->
            val archie = w.kind == ItemKind.ARCHIE
            ConvRowUi(
                key = "open:${w.key}",
                ref = ConvRef.of(w),
                title = SessionTitles.conversationTitle(w.title, archie),
                who = if (archie) "Archie" else w.provider?.label ?: "Agent",
                state = sessionState(w.status),
                time = "",
                messageCount = null,
            )
        }
        val history = HistoryList.groups(sessions, conv, query, now, zone, locale).map { g ->
            ConvSectionUi(
                g.bucket.label,
                g.rows.map { r ->
                    val s = r.summary
                    ConvRowUi(
                        key = "h:${s.sdkId}",
                        ref = ConvRef(OpenTarget.History(s), SessionTarget.of(s), item = null),
                        title = SessionTitles.conversationTitle(s.title, s.isOrchestrator),
                        who = if (s.isOrchestrator) "Archie" else s.provider?.label ?: "Agent",
                        state = null,
                        time = r.meta,
                        messageCount = s.messageCount,
                    )
                },
            )
        }
        return listOfNotNull(open.takeIf { it.isNotEmpty() }?.let { ConvSectionUi("Open now", it) }) + history
    }

    /** The meta line of a row: "Archie · idle", "Claude · working", "Codex · needs approval", "Claude · 16:20". */
    fun metaOf(r: ConvRowUi): String {
        val state = when (r.state) {
            null -> r.time
            SessionState.WORKING -> "working"
            SessionState.NEEDS_APPROVAL -> "needs approval"
            SessionState.CONNECTING -> "connecting"
            SessionState.OFFLINE -> "disconnected"
            SessionState.IDLE, SessionState.NONE -> "idle"
        }
        return listOf(r.who, state).filter { it.isNotEmpty() }.joinToString(" · ")
    }

    /**
     * The last [max] lines of a `GET /api/sessions/{id}/preview` page: user prompts, assistant text,
     * and runs of tool calls folded into "N steps" (tool results and empty turns are skipped).
     */
    fun previewLines(messages: List<MessagePreviewDto>, max: Int = 6): List<PreviewLine> {
        val out = ArrayList<PreviewLine>()
        fun addStep(name: String) {
            val last = out.lastOrNull()
            if (last is PreviewLine.Steps) out[out.lastIndex] = PreviewLine.Steps(last.count + 1, last.names + name)
            else out += PreviewLine.Steps(1, listOf(name))
        }
        for (m in messages) {
            val blocks = m.blocks
            if (m.role == "user") {
                val text = blocks.filter { it.type == "text" }.mapNotNull { it.text }.joinToString("\n").ifBlank { if (blocks.isEmpty()) m.text else "" }
                if (text.isNotBlank()) out += PreviewLine.User(text.trim().take(PREVIEW_CHARS))
                continue
            }
            if (blocks.isEmpty()) {
                if (m.text.isNotBlank()) out += PreviewLine.Assistant(m.text.trim().take(PREVIEW_CHARS))
                continue
            }
            for (b in blocks) {
                when (b.type) {
                    "text" -> b.text?.takeIf { it.isNotBlank() }?.let { out += PreviewLine.Assistant(it.trim().take(PREVIEW_CHARS)) }
                    "tool_use" -> addStep(toolLabel(b.toolName.orEmpty(), b.toolInput))
                    else -> Unit
                }
            }
        }
        return out.takeLast(max)
    }

    private fun toolLabel(name: String, input: kotlinx.serialization.json.JsonObject?): String {
        val base = if (name.startsWith("mcp__")) name.substringAfterLast("__") else name
        val hint = (input?.get("command") as? JsonPrimitive)?.content?.trim()?.substringBefore(' ')?.takeIf { base == "Bash" && it.isNotEmpty() }
        return hint ?: base.ifEmpty { "tool" }
    }

    /** "2 steps adb, whisper" summary: names in order of first use, ×N for repeats. */
    fun stepsSummary(names: List<String>): String {
        val counts = LinkedHashMap<String, Int>()
        names.forEach { counts[it] = (counts[it] ?: 0) + 1 }
        return counts.entries.joinToString(", ") { (n, c) -> if (c > 1) "$n ×$c" else n }
    }

    private const val PREVIEW_CHARS = 600
}

/**
 * The Menu options of a conversation (spec 15 §3.3 "Lists/rows" + §3.5 Conversations): Open,
 * Rename, Duplicate, Close (open ones), Delete — each through `:feature:sessions`' flows (the
 * dialogs ask before anything destructive). Actions that need the session's id say why when it is
 * not there yet (ID-3).
 */
fun conversationOptions(ref: ConvRef, sessions: TvSessions, nav: TvNavigator): List<TvOption> {
    val noSdk = ref.session.sdkId == null
    val why = if (noSdk) NEEDS_FIRST_REPLY else null
    return buildList {
        add(TvOption("conv:open", "Open", ArchieIcons.Forum, detail = ref.session.title) { sessions.open(ref.target, nav) })
        add(TvOption("conv:rename", "Rename", ArchieIcons.Edit, detail = why, enabled = !noSdk) { sessions.rename(ref.session) })
        add(TvOption("conv:duplicate", "Duplicate", ArchieIcons.ContentCopy, detail = why, enabled = !noSdk) { sessions.duplicate(ref.session) })
        ref.item?.let { item -> add(TvOption("conv:close", "Close", ArchieIcons.Close, detail = "Stops it on every device") { sessions.close(item) }) }
        add(TvOption("conv:delete", "Delete", ArchieIcons.Delete, detail = why ?: "Asks first", enabled = !noSdk) { sessions.delete(ref.session) })
    }
}

/**
 * Previews of conversation rows (B8): an entry is valid only for the row's [versionOf] (message
 * count + live state, so a conversation that moved on is fetched again), and an open row's for
 * [openTtlMs] at most (its message count is unknown while it runs). LRU of [max]. Thread-safe.
 */
class PreviewCache(
    private val now: () -> Long,
    private val openTtlMs: Long = 30_000,
    private val max: Int = 30,
) {
    private class Entry(val version: String, val ui: PreviewUi, val at: Long)

    private val map = object : LinkedHashMap<String, Entry>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > max
    }

    @Synchronized
    fun get(row: ConvRowUi): PreviewUi? {
        val sdk = row.sdkId ?: return null
        val e = map[sdk] ?: return null
        if (e.version != versionOf(row)) return null
        if (row.state != null && now() - e.at >= openTtlMs) return null
        return e.ui
    }

    @Synchronized
    fun put(row: ConvRowUi, ui: PreviewUi) {
        val sdk = row.sdkId ?: return
        map[sdk] = Entry(versionOf(row), ui, now())
    }

    companion object {
        fun versionOf(row: ConvRowUi): String = "${row.messageCount}|${row.state}"
    }
}

/**
 * Notices of closes this TV confirmed itself (B11): the repository reports every close of the agent
 * on screen as "<title> was closed elsewhere". [expect] is called on confirm with the session's
 * titles; [consume] swallows one matching notice within [windowMs] — any other session's notice,
 * or a later one, still shows. Thread-safe.
 */
class OwnCloseFilter(private val now: () -> Long, private val windowMs: Long = 8_000) {
    private class Pending(val titles: Set<String>, val at: Long)

    private val pending = ArrayList<Pending>()

    @Synchronized
    fun expect(titles: Collection<String>) {
        prune()
        val t = titles.filter { it.isNotBlank() }.toSet()
        if (t.isNotEmpty()) pending += Pending(t, now())
    }

    @Synchronized
    fun consume(message: String): Boolean {
        prune()
        val i = pending.indexOfFirst { p -> p.titles.any { message.startsWith("$it ") } }
        if (i < 0) return false
        pending.removeAt(i)
        return true
    }

    private fun prune() {
        val t = now()
        pending.removeAll { t - it.at >= windowMs }
    }
}
