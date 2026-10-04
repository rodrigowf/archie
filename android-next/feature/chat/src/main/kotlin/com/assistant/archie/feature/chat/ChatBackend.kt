package com.assistant.archie.feature.chat

import com.assistant.core.conversation.ConversationState
import com.assistant.core.data.ConversationEvent
import com.assistant.core.data.ConversationKey
import com.assistant.core.data.ConversationRepository
import com.assistant.core.data.CutResult
import com.assistant.core.data.UploadRepository
import com.assistant.core.model.SessionKind
import com.assistant.core.model.SessionRef
import com.assistant.core.model.UploadResult
import com.assistant.core.network.ApiResult
import com.assistant.core.network.SendResult
import com.assistant.core.network.UploadSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter

/**
 * Everything the conversation screen asks of the data layer, for one view. Production:
 * [RepositoryChatBackend] over B-03's [ConversationRepository]; tests: a fake that runs the real
 * reducer. Keeping this seam narrow is what lets the screen be tested without sockets.
 */
interface ChatBackend {
    val key: ConversationKey
    val state: StateFlow<ConversationState?>
    val events: Flow<ConversationEvent>

    /** §6.1/§6.2 (and deny-with-feedback while a permission is pending, §6.9). `null` = no view. */
    fun send(text: String): SendResult?

    /** Agent slash command (`command` frame). */
    fun command(text: String): SendResult?
    fun interrupt()
    fun compact()
    fun respondToPermission(requestId: String, allow: Boolean): SendResult?

    /**
     * Orchestrator "Agent approvals" (PM-5): answers on the agent's own socket when its view is open.
     * Returns false when it cannot be answered from here (the caller offers to open the session).
     */
    fun respondToAgentApproval(agentLocalId: String, requestId: String, allow: Boolean): Boolean

    /** The agent's view is open here, so its approval can be answered on its socket (T-8 otherwise). */
    fun canReachAgent(agentLocalId: String): Boolean
    fun loadOlder()
    fun reload()
    fun dismissBanner()

    /** Connection banner Retry: reconnect now (§6.13, retry-after-start-failure rule). */
    fun retry()
    suspend fun rewind(entryId: String): CutResult
    suspend fun fork(entryId: String): CutResult

    /** §6.13 "Continue in a new view" for a terminated session, kept in the same kind. */
    fun continueTerminated(target: ContinueTarget)

    /** §6.15: upload then inject the share line into the Archie conversation. */
    suspend fun upload(source: UploadSource, onProgress: (Long, Long) -> Unit): ApiResult<UploadResult>
    fun inject(text: String)

    /** The user looked at this view (agent socket LRU). */
    fun touch()
}

/** Where "Continue in a new view" goes; decided from the view's kind, never from the screen (bug 5). */
sealed interface ContinueTarget {
    val sdkId: String

    /** Resume through the orchestrator socket (`start{resume_sdk_id}`). */
    data class Archie(override val sdkId: String) : ContinueTarget

    /** Replace the agent view in place on the agent endpoint. */
    data class Agent(override val sdkId: String) : ContinueTarget

    companion object {
        /** `null` while the termination carries no `sdk_session_id` (the action is then disabled). */
        fun of(state: ConversationState): ContinueTarget? {
            val sdk = state.termination?.sdkSessionId ?: return null
            return if (state.kind == SessionKind.ORCHESTRATOR) Archie(sdk) else Agent(sdk)
        }
    }
}

/** The production backend over the process-scoped repositories (B-03). */
class RepositoryChatBackend(
    override val key: ConversationKey,
    private val repo: ConversationRepository,
    private val uploads: UploadRepository,
) : ChatBackend {
    override val state: StateFlow<ConversationState?> = repo.state(key)
    override val events: Flow<ConversationEvent> = repo.events.filter { it.key == key }

    override fun send(text: String) = repo.send(key, text)
    override fun command(text: String) = repo.command(key, text)
    override fun interrupt() { repo.interrupt(key) }
    override fun compact() { repo.compact(key) }
    override fun respondToPermission(requestId: String, allow: Boolean) = repo.respondToPermission(key, requestId, allow)

    override fun respondToAgentApproval(agentLocalId: String, requestId: String, allow: Boolean): Boolean {
        val agent = ConversationKey.agent(agentLocalId)
        if (repo.current(agent) == null) return false
        return repo.respondToPermission(agent, requestId, allow) == SendResult.SENT
    }

    override fun canReachAgent(agentLocalId: String) = repo.current(ConversationKey.agent(agentLocalId)) != null

    override fun loadOlder() = repo.loadOlder(key)
    override fun reload() { repo.reload(key) }
    override fun dismissBanner() { repo.dismissBanner(key) }
    override fun retry() = repo.retry(key)
    override suspend fun rewind(entryId: String) = repo.rewind(key, entryId)
    override suspend fun fork(entryId: String) = repo.fork(key, entryId)

    override fun continueTerminated(target: ContinueTarget) {
        when (target) {
            is ContinueTarget.Archie -> repo.resumeArchie(target.sdkId)
            is ContinueTarget.Agent -> repo.continueInNewView(key, target.sdkId)
        }
    }

    override suspend fun upload(source: UploadSource, onProgress: (Long, Long) -> Unit) = uploads.upload(source, onProgress)
    override fun inject(text: String) = repo.inject(text)
    override fun touch() = repo.touch(key)

    companion object {
        /** A fork opened by the caller (focused, user-initiated, §6.5). */
        fun forkedRef(r: CutResult): SessionRef? = (r as? CutResult.Done)?.ref
    }
}
