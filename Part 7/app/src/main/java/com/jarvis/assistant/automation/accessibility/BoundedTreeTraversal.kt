package com.jarvis.assistant.automation.accessibility

import java.util.ArrayDeque
import java.util.IdentityHashMap
import kotlinx.coroutines.CancellationException

internal data class TreeTraversalResult(val visited: Int, val truncated: Boolean, val failed: Boolean)

/**
 * Borrow root; own/release every acquired child on completion, staleness or cancellation.
 * Retain the bounded set until traversal ends: pre-33 node pooling may otherwise reissue a
 * recycled object whose identity is still tracked, incorrectly pruning another branch.
 */
internal fun <N : Any> traverseBoundedTree(
    root: N, maxNodes: Int = 250, maxDepth: Int = 48, maxMillis: Long = 40, depthFirst: Boolean = false,
    now: () -> Long = { System.nanoTime() / 1_000_000 },
    childCount: (N) -> Int, child: (N, Int) -> N?, release: (N) -> Unit,
    visit: (N) -> Unit
): TreeTraversalResult {
    require(maxNodes > 0 && maxDepth >= 0 && maxMillis > 0)
    data class Entry<N>(val node: N, val depth: Int)
    val queue = ArrayDeque<Entry<N>>()
    val seen = IdentityHashMap<N, Boolean>()
    val owned = ArrayList<N>()
    queue.add(Entry(root, 0)); seen[root] = true
    val started = now()
    var visited = 0; var truncated = false; var failed = false
    fun dispose(node: N) {
        if (node !== root) try { release(node) } catch (_: Exception) { failed = true }
    }
    try {
        while (queue.isNotEmpty()) {
            if (now() - started >= maxMillis) { truncated = true; break }
            val (node, depth) = queue.removeFirst()
            try {
                visited++
                visit(node)
                val count = childCount(node).coerceAtLeast(0)
                if (depth >= maxDepth) { if (count > 0) truncated = true; continue }
                val remaining = (maxNodes - seen.size).coerceAtLeast(0)
                if (count > remaining) truncated = true
                val indices = if (depthFirst) (minOf(count, remaining) - 1 downTo 0) else (0 until minOf(count, remaining))
                for (i in indices) {
                    if (now() - started >= maxMillis) { truncated = true; break }
                    val next = child(node, i) ?: continue
                    if (!seen.containsKey(next)) {
                        seen[next] = true
                        owned += next
                        if (depthFirst) queue.addFirst(Entry(next, depth + 1)) else queue.addLast(Entry(next, depth + 1))
                    } else truncated = true
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed = true; break }
        }
    } finally {
        owned.forEach(::dispose)
        queue.clear()
    }
    return TreeTraversalResult(visited, truncated, failed)
}
