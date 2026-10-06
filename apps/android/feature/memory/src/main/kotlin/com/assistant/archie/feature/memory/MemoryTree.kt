package com.assistant.archie.feature.memory

import androidx.compose.runtime.Immutable
import com.assistant.core.model.MemoryNode

/*
 * Memory tree model (spec 14 §4.1, spec 12 §9.2; web parity: frontend features/memory/tree.ts).
 * `GET /api/memory/tree` returns only folders that hold markdown at some depth, folders first, each
 * alphabetical. On top of that: file counts per folder, a search across every file's path,
 * `MEMORY.md` pinned first, and top-level folders expanded by default (MEM-4).
 */

/** Number of files under [node] (a file counts 1). A folder whose `children` is null counts 0 (inv02 §6.2). */
fun countFiles(node: MemoryNode): Int = if (!node.isDir) 1 else node.children.orEmpty().sumOf { countFiles(it) }

fun countAll(nodes: List<MemoryNode>): Int = nodes.sumOf { countFiles(it) }

private val ROOT_INDEX = Regex("^memory\\.md$", RegexOption.IGNORE_CASE)

/** The root `MEMORY.md` first (the index everyone starts from), then the backend order. */
fun pinIndexFirst(nodes: List<MemoryNode>): List<MemoryNode> {
    val idx = nodes.indexOfFirst { !it.isDir && ROOT_INDEX.matches(it.name) }
    if (idx <= 0) return nodes
    return listOf(nodes[idx]) + nodes.filterIndexed { i, _ -> i != idx }
}

/**
 * Files whose path matches every word of [query] (case-insensitive, any order), with the folders
 * that lead to them. A folder whose own path matches keeps all of its files.
 */
fun filterMemory(nodes: List<MemoryNode>, query: String): List<MemoryNode> {
    val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return nodes
    fun matches(path: String): Boolean = path.lowercase().let { p -> words.all { p.contains(it) } }
    fun walk(list: List<MemoryNode>): List<MemoryNode> = buildList {
        for (n in list) {
            when {
                !n.isDir -> if (matches(n.path)) add(n)
                matches(n.path) -> add(n)
                else -> walk(n.children.orEmpty()).takeIf { it.isNotEmpty() }?.let { add(n.copy(children = it)) }
            }
        }
    }
    return walk(nodes)
}

/** Paths of every folder, depth-first. */
fun folderIds(nodes: List<MemoryNode>): Set<String> = buildSet {
    fun walk(list: List<MemoryNode>) {
        for (n in list) if (n.isDir) { add(n.path); walk(n.children.orEmpty()) }
    }
    walk(nodes)
}

fun topFolderIds(nodes: List<MemoryNode>): Set<String> = nodes.filter { it.isDir }.map { it.path }.toSet()

/** Every file path in the tree (for the "Not found" check of links). */
fun filePaths(nodes: List<MemoryNode>): Set<String> = buildSet {
    fun walk(list: List<MemoryNode>) {
        for (n in list) if (n.isDir) walk(n.children.orEmpty()) else add(n.path)
    }
    walk(nodes)
}

/** One visible row of the flattened tree (one `LazyColumn` item, keyed by [path]). */
@Immutable
data class MemoryRow(
    val path: String,
    val name: String,
    val depth: Int,
    val isDir: Boolean,
    val expanded: Boolean = false,
    /** Files under a folder (its badge); 0 for files. */
    val count: Int = 0,
)

/** Flattens [nodes] into the visible rows: a folder's children follow it only when it is in [expanded]. */
fun flatten(nodes: List<MemoryNode>, expanded: Set<String>): List<MemoryRow> = buildList {
    fun walk(list: List<MemoryNode>, depth: Int) {
        for (n in list) {
            if (n.isDir) {
                val open = n.path in expanded
                add(MemoryRow(n.path, n.name, depth, isDir = true, expanded = open, count = countFiles(n)))
                if (open) walk(n.children.orEmpty(), depth + 1)
            } else {
                add(MemoryRow(n.path, n.name, depth, isDir = false))
            }
        }
    }
    walk(nodes, 0)
}

/** "assistant / architecture" for `assistant/architecture/voice.md`; "" at the root. */
fun folderCrumb(path: String): String = path.split('/').filter { it.isNotEmpty() }.dropLast(1).joinToString(" / ")

fun fileName(path: String): String = path.split('/').lastOrNull { it.isNotEmpty() } ?: path
