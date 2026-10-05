package com.assistant.archie.feature.memory

import com.assistant.core.data.LoadState
import com.assistant.core.model.MemoryNode
import com.assistant.core.network.ApiResult
import kotlinx.coroutines.flow.MutableStateFlow

/** In-memory [MemoryDeps]: a tree, documents by path, and a record of what was opened outside. */
class FakeMemoryDeps(
    tree: List<MemoryNode>? = MemoryFixtures.mockTree,
    val docs: MutableMap<String, String> = mutableMapOf(),
) : MemoryDeps {
    override val tree = MutableStateFlow(LoadState(tree))
    val external = mutableListOf<String>()
    val loads = mutableListOf<String>()
    var refreshes = 0
    var failNext: String? = null

    override fun refreshTree() { refreshes++ }

    override suspend fun document(path: String): ApiResult<String> {
        loads += path
        failNext?.let { failNext = null; return ApiResult.HttpError(500, it) }
        return docs[path]?.let { ApiResult.Ok(it) } ?: ApiResult.HttpError(404, "Not Found")
    }

    override fun origin(): String = "http://192.168.0.200"
    override fun openExternal(url: String) { external += url }
}
