package com.assistant.archie.feature.memory

import com.assistant.core.data.LoadState
import com.assistant.core.data.MemoryRepository
import com.assistant.core.model.MemoryNode
import com.assistant.core.network.ApiResult
import kotlinx.coroutines.flow.StateFlow

/**
 * What the memory screens need from the app (spec 14 §2.2: the shell wires it from the graph).
 * Read-only (MEM-5): the tree, one document, the server origin for raw/external URLs, and a way
 * to open a URL outside the app (Custom Tab).
 */
interface MemoryDeps {
    val tree: StateFlow<LoadState<List<MemoryNode>>>

    /** `GET /api/memory/tree` again (screen entry, pull-to-refresh, Refresh). */
    fun refreshTree()

    /** Raw markdown of `GET /memory/<path>` (MEM-3: every segment encoded). */
    suspend fun document(path: String): ApiResult<String>

    /** `http(s)://host[:port]` of the current server, no trailing slash. */
    fun origin(): String

    /** Opens [url] outside the app (Custom Tab for http(s), the system handler otherwise). */
    fun openExternal(url: String)
}

/** [MemoryDeps] over B-03's [MemoryRepository]. */
class RepositoryMemoryDeps(
    private val repository: MemoryRepository,
    private val originOf: () -> String,
    private val external: (String) -> Unit,
) : MemoryDeps {
    override val tree: StateFlow<LoadState<List<MemoryNode>>> get() = repository.tree
    override fun refreshTree() = repository.refreshTree()
    override suspend fun document(path: String): ApiResult<String> = repository.document(path)
    override fun origin(): String = originOf().trimEnd('/')
    override fun openExternal(url: String) = external(url)
}
