package gg.aquatic.testimpact.select.internal

import gg.aquatic.testimpact.graph.Edge
import gg.aquatic.testimpact.graph.EdgeKind
import gg.aquatic.testimpact.select.ReachPath

/**
 * Result of a reverse reachability walk. Arrays are indexed by position in the sorted class list of the graph;
 * `parent` is -2 for a class that was not reached and -1 for a seed class.
 */
internal class ReachResult(
    private val classes: List<String>,
    private val parent: IntArray,
    private val kind: ByteArray,
    private val origin: IntArray,
    private val origins: List<String>,
    private val selectedAt: Map<String, Int>,
) {
    /** Selected candidate names, sorted. */
    val selected: List<String> = selectedAt.keys.sorted()

    /** Shortest path to the class through which [candidate] was first reached; null for an unselected name. */
    fun pathTo(candidate: String): ReachPath? = selectedAt[candidate]?.let(::buildPath)

    /** Shortest path to any visited class; null when [name] was not visited. */
    fun pathToClass(name: String): ReachPath? {
        val index = classes.binarySearch(name)
        return if (index >= 0 && parent[index] != UNVISITED) buildPath(index) else null
    }

    private fun buildPath(start: Int): ReachPath {
        var node = start
        val edges = ArrayList<Edge>()
        while (parent[node] >= 0) {
            edges.add(Edge(classes[node], classes[parent[node]], EdgeKind.entries[kind[node].toInt()]))
            node = parent[node]
        }
        edges.reverse()
        return ReachPath(origins[origin[node]], classes[node], edges)
    }

    companion object {
        const val UNVISITED = -2
        const val ROOT = -1
    }
}
