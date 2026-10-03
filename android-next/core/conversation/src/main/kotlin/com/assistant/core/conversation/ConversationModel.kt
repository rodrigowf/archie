package com.assistant.core.conversation

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.serialization.json.JsonObject

/*
 * The rendered timeline of spec 12 §2.4. Every entry and block carries a client-generated id that
 * is stable for its whole life (A-4.4.2): live items get counter ids (`e12`, `b40`), items built from
 * a REST page get ids derived from the absolute history index (`h:<sdkId>:<index>[:<block>]`), so a
 * re-fetch of the same page yields the same keys (spec 14 §3.1). See [HistoryMerger] for how a
 * replace keeps the live ids of content it already holds.
 */

enum class UserOrigin(val wire: String) {
    /** Typed or sent by this client. */
    LOCAL("local"),
    /** `user_message` from another client / REST inject. */
    ECHO("echo"),
    /** Speech transcript (live, or a `[voice] …` history line). */
    VOICE("voice"),
    /** Talk-mode audio message (`send_audio`, `[audio:fmt] …` history line). */
    AUDIO("audio"),
    /** Shared text / uploaded file link. */
    INJECT("inject"),
    /** Any other user line loaded from REST. */
    HISTORY("history"),
}

enum class UserState(val wire: String) {
    SENT("sent"),
    /** A local inject awaiting its echo (§7.9). */
    PENDING("pending"),
}

enum class NoticeKind(val wire: String) {
    ERROR("error"),
    INTERRUPTED("interrupted"),
    COMPACTION("compaction"),
    BACKGROUND("background"),
    COMMAND("command"),
}

/** `turn` content ends at `endTurn`, `voice` content at `endVoice` (I-7). */
enum class BlockScope { TURN, VOICE }

enum class BlockOrigin { LIVE, HISTORY }

enum class ToolStatus(val wire: String) {
    RUNNING("running"),
    DONE("done"),
    ERROR("error"),
    /** Not final: a later result upgrades it (R-6). */
    NO_RESULT("no_result"),
}

enum class PermissionState(val wire: String) { PENDING("pending"), ALLOWED("allowed"), DENIED("denied") }

sealed interface Entry {
    val id: String
}

data class UserEntry(
    override val id: String,
    val text: String,
    val origin: UserOrigin,
    val state: UserState = UserState.SENT,
    /** True only while a Gemini transcript is still growing. */
    val streaming: Boolean = false,
) : Entry

/** One assistant "run". Never empty (I-10). */
data class AssistantEntry(
    override val id: String,
    val blocks: PersistentList<Block> = persistentListOf(),
) : Entry

data class NoticeEntry(
    override val id: String,
    val notice: NoticeKind,
    /** error: detail || code; compaction: summary or ""; others: "" or the raw history line. */
    val text: String = "",
    /** e.g. `code`, `trigger`, `tokens_before`, `tokens_after`. */
    val data: Map<String, String?> = emptyMap(),
) : Entry

sealed interface Block {
    val id: String
    val scope: BlockScope
    val origin: BlockOrigin
}

/** Text and thinking share the streaming rules (I-4, I-5). */
sealed interface StreamedBlock : Block {
    val text: String
    val streaming: Boolean
    /** Closed because another block was pushed after it (used by the §5.4 dedupe). */
    val implicitlyClosed: Boolean
    /** Text already shown in the block this one continues (split by an interleaved entry, I-5). */
    val continuationPrefix: String?
}

data class TextBlock(
    override val id: String,
    override val text: String,
    override val streaming: Boolean = false,
    override val scope: BlockScope = BlockScope.TURN,
    override val origin: BlockOrigin = BlockOrigin.LIVE,
    override val implicitlyClosed: Boolean = false,
    override val continuationPrefix: String? = null,
) : StreamedBlock

data class ThinkingBlock(
    override val id: String,
    override val text: String,
    override val streaming: Boolean = false,
    override val scope: BlockScope = BlockScope.TURN,
    override val origin: BlockOrigin = BlockOrigin.LIVE,
    override val implicitlyClosed: Boolean = false,
    override val continuationPrefix: String? = null,
) : StreamedBlock

data class ToolProgressInfo(val elapsedSeconds: Double?, val message: String?)

/** A tool call and its result, merged (the result is never a separate block). */
data class ToolBlock(
    override val id: String,
    /** May be `""` (G-20); only non-empty ids are indexed. */
    val toolUseId: String,
    /** Raw wire name; display mapping is [ToolNameNormalizer]'s job. */
    val toolName: String,
    val toolInput: JsonObject,
    val status: ToolStatus = ToolStatus.RUNNING,
    /** `null` until a result arrives. */
    val output: String? = null,
    /** Attached by position (R-4); shows a "matched by position" hint (TC-3). */
    val inferred: Boolean = false,
    val executing: Boolean = false,
    val progress: ToolProgressInfo? = null,
    override val scope: BlockScope = BlockScope.TURN,
    override val origin: BlockOrigin = BlockOrigin.LIVE,
) : Block

data class PermissionBlock(
    override val id: String,
    val requestId: String,
    val toolName: String,
    val toolInput: JsonObject,
    val state: PermissionState = PermissionState.PENDING,
    /** `user` | `orchestrator` | `system`. */
    val responder: String? = null,
    val message: String? = null,
    override val scope: BlockScope = BlockScope.TURN,
    override val origin: BlockOrigin = BlockOrigin.LIVE,
) : Block

enum class ResultOrigin { LIVE, HISTORY, RECONCILE }

/** A tool result that is not (yet) on a card: orphans (R-2) and unattributed results (R-4). */
data class PendingResult(
    val output: String,
    val isError: Boolean,
    val origin: ResultOrigin,
)

enum class QueueOwner(val wire: String) { LOCAL("local"), REMOTE("remote") }

/** A prompt queued behind a running turn, shown in the tray (I-12). */
data class QueuedPrompt(val text: String, val owner: QueueOwner)
