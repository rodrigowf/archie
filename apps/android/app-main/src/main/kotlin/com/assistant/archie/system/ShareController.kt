package com.assistant.archie.system

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.assistant.archie.feature.chat.ConversationViewModel
import com.assistant.core.data.ConversationRepository
import com.assistant.core.data.ItemKey
import com.assistant.core.data.OpenSessionsRepository
import com.assistant.core.data.SharePayload
import com.assistant.core.data.ShareRepository
import com.assistant.core.data.UploadRepository
import com.assistant.core.network.ApiResult
import com.assistant.core.network.UploadSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okio.source
import java.io.File

/** Where a share goes: the Archie conversation (default) or an open agent session. */
data class ShareTarget(val key: ItemKey, val label: String)

/** What the share sheet shows (spec 14 §2.8). */
sealed interface ShareState {
    data object Idle : ShareState

    /** Asking for the target. */
    data class Choosing(val payload: SharePayload, val targets: List<ShareTarget>) : ShareState

    /** Uploading file [index] (0-based) of [count]; [total] = -1 when the size is unknown. */
    data class Uploading(val fileName: String, val index: Int, val count: Int, val sent: Long, val total: Long) : ShareState

    /** Stopped at file [nextIndex]; Retry resumes there (earlier files were already shared). */
    data class Failed(val payload: SharePayload, val target: ShareTarget, val nextIndex: Int, val message: String) : ShareState

    data class Done(val message: String) : ShareState
}

/**
 * The share target's logic (spec 14 §2.8; parity with `old/AssistantViewModel.kt:263-332`, plus
 * multiple files). Process-scoped: the payload comes from [ShareRepository] (a cold-launch share is
 * not lost) and an upload keeps going if the Activity is recreated.
 *  - Text → `[shared text] <subject>\n<text>`, injected into Archie (`inject_text`) or sent to an agent.
 *  - Files → each streamed through `POST /api/uploads` with progress (never read whole into memory),
 *    then the §6.15 share line with the server path. nginx's HTML 413 shows as "File is larger than
 *    the server's 1 MB upload limit" (`ApiResult.errorMessage`).
 */
class ShareController(
    private val resolver: ContentResolver,
    private val share: ShareRepository,
    private val conversations: ConversationRepository,
    private val uploads: UploadRepository,
    private val openSessions: OpenSessionsRepository,
    private val scope: CoroutineScope,
) {
    constructor(
        context: Context,
        share: ShareRepository,
        conversations: ConversationRepository,
        uploads: UploadRepository,
        openSessions: OpenSessionsRepository,
        scope: CoroutineScope,
    ) : this(context.contentResolver, share, conversations, uploads, openSessions, scope)

    /** Upload / result phase; [ShareState.Idle] while nothing runs. */
    private val work = MutableStateFlow<ShareState>(ShareState.Idle)
    private var job: Job? = null

    /**
     * The payload stays in [ShareRepository] until the user picks a target or cancels, so a share
     * survives Activity recreation and a cold launch (parity, `old/MainActivity.kt:86-108`).
     */
    val state: StateFlow<ShareState> = combine(work, share.pending) { w, p ->
        if (w is ShareState.Idle && p != null) ShareState.Choosing(p, targets()) else w
    }.stateIn(scope, SharingStarted.Eagerly, ShareState.Idle)

    /** Archie first, then the open agent sessions ("Open now"). */
    fun targets(): List<ShareTarget> {
        val items = openSessions.items.value
        val archie = items.firstOrNull { it.key == ItemKey.Archie }?.title?.takeIf { it.isNotBlank() } ?: "Archie"
        return listOf(ShareTarget(ItemKey.Archie, archie)) +
            items.filter { it.key is ItemKey.Agent }.map { ShareTarget(it.key, it.title.ifBlank { "Agent session" }) }
    }

    fun send(target: ShareTarget) {
        if (work.value !is ShareState.Idle) return
        val p = share.take() ?: return
        run(p, target, 0)
    }

    fun retry() {
        val s = work.value as? ShareState.Failed ?: return
        run(s.payload, s.target, s.nextIndex)
    }

    /** Cancel (discards the payload) or close a result. */
    fun dismiss() {
        if (work.value is ShareState.Idle) {
            share.take()
        } else {
            job?.cancel()
            work.value = ShareState.Idle
        }
    }

    private fun run(payload: SharePayload, target: ShareTarget, from: Int) {
        job?.cancel()
        if (payload is SharePayload.Text) {
            deliver(target, textLine(payload.text, payload.subject))
            finish(target, "Shared with ${target.label}")
            return
        }
        // Synchronously, so the sheet never blinks to Idle between "Share" and the first progress.
        work.value = ShareState.Uploading("…", from, (payload as SharePayload.Files).uris.size, 0, -1)
        job = scope.launch(Dispatchers.IO) {
            when (payload) {
                is SharePayload.Text -> Unit
                is SharePayload.Files -> {
                    val uris = payload.uris
                    for (i in from until uris.size) {
                        val source = sourceOf(Uri.parse(uris[i]))
                        if (source == null) {
                            work.value = ShareState.Failed(payload, target, i, "Can't read the shared file")
                            return@launch
                        }
                        work.value = ShareState.Uploading(source.fileName, i, uris.size, 0, source.contentLength)
                        val r = uploads.upload(source) { sent, total ->
                            work.value = ShareState.Uploading(source.fileName, i, uris.size, sent, total)
                        }
                        when (r) {
                            is ApiResult.Ok -> deliver(target, ConversationViewModel.shareLine(r.value, payload.subject))
                            else -> {
                                val msg = r.errorMessage() ?: "Upload failed"
                                Log.w(TAG, "upload ${source.fileName} failed: $msg")
                                work.value = ShareState.Failed(payload, target, i, msg)
                                return@launch
                            }
                        }
                    }
                    finish(target, if (uris.size == 1) "Shared with ${target.label}" else "Shared ${uris.size} files with ${target.label}")
                }
            }
        }
    }

    private fun deliver(target: ShareTarget, line: String) {
        when (val k = target.key) {
            is ItemKey.Agent -> conversations.send(k.conversation, line)
            else -> conversations.inject(line)
        }
    }

    private fun finish(target: ShareTarget, message: String) {
        // The user chose this target, so showing it is a user action (FOCUS-1 allows it).
        openSessions.select(target.key)
        work.value = ShareState.Done(message)
    }

    /** A streamed source over a content (or file) URI. */
    private fun sourceOf(uri: Uri): UploadSource? = try {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        var size = -1L
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            uri.path?.let { File(it) }?.let { f -> name = f.name; size = f.length() }
        } else {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        }
        UploadSource(name, resolver.getType(uri) ?: guessType(name), size) {
            (resolver.openInputStream(uri) ?: error("cannot open $uri")).source()
        }
    } catch (e: Exception) {
        Log.w(TAG, "cannot read $uri: ${e.message}")
        null
    }

    companion object {
        private const val TAG = "ArchieShare"

        /** `old/AssistantViewModel.kt:263-269`. */
        fun textLine(text: String, subject: String?): String {
            val header = subject?.takeIf { it.isNotBlank() }?.let { "[shared text] $it" } ?: "[shared text]"
            return "$header\n${text.trim()}"
        }

        private fun guessType(name: String): String? =
            android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
    }
}
