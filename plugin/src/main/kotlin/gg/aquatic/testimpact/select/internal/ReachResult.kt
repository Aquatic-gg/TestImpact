package gg.aquatic.testimpact.select.internal

import gg.aquatic.testimpact.graph.Edge
import gg.aquatic.testimpact.graph.EdgeKind
import gg.aquatic.testimpact.seed.SeedKind
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
    private val resourceNodes: List<String> = emptyList(),
    private val seedHops: Map<Int, SeedKind> = emptyMap(),
) {
    private fun nameOf(index: Int): String =
        if (index < classes.size) classes[index] else resourceNodes[index - classes.size]

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
        val hops = ArrayList<SeedKind?>()
        while (parent[node] >= 0) {
            edges.add(Edge(nameOf(node), nameOf(parent[node]), EdgeKind.entries[kind[node].toInt()]))
            hops.add(seedHops[node])
            node = parent[node]
        }
        edges.reverse()
        hops.reverse()
        val seedKinds = HashMap<Int, SeedKind>()
        for ((position, hop) in hops.withIndex()) if (hop != null) seedKinds[position] = hop
        return ReachPath(origins[origin[node]], nameOf(node), edges, seedKinds)
    }

    companion object {
        const val UNVISITED = -2
        const val ROOT = -1
    }
}
