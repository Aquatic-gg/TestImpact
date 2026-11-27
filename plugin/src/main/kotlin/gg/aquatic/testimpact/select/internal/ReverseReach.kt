package gg.aquatic.testimpact.select.internal

import gg.aquatic.testimpact.graph.DependencyGraph
import gg.aquatic.testimpact.select.Seed

/**
 * Multi-source breadth-first walk over the dependents of the seed classes. A class reached anywhere inside a
 * top-level class (nested, inner, lambda) selects that top-level class when it is a candidate. The walk is
 * index-based over the sorted class list, stops as soon as every candidate is selected, and is deterministic:
 * seeds are taken in the given order and dependents come sorted from the graph.
 */
internal object ReverseReach {

    /**
     * Seeds expand to the class itself, the classes nested in it and every class compiled from the same source file.
     * Seed class names that were not scanned are skipped.
     */
    fun reach(graph: DependencyGraph, seeds: List<Seed>, candidates: Set<String>): ReachResult {
        val classes = graph.classes
        val parent = IntArray(classes.size) { ReachResult.UNVISITED }
        val kind = ByteArray(classes.size)
        val origin = IntArray(classes.size)
        val selectedAt = HashMap<String, Int>()
        val origins = seeds.map { it.origin }
        val result = { ReachResult(classes, parent, kind, origin, origins, selectedAt) }
        if (candidates.isEmpty()) return result()

        val queue = IntArray(classes.size)
        var tail = 0
        var head = 0

        fun enqueue(index: Int, from: Int, edgeKind: Int, seed: Int) {
            parent[index] = from
            kind[index] = edgeKind.toByte()
            origin[index] = seed
            queue[tail++] = index
            val owner = graph.owner(classes[index])
            if (owner in candidates) selectedAt.putIfAbsent(owner, index)
        }

        val bySource = sourceIndex(graph)
        for ((seedIndex, seed) in seeds.withIndex()) {
            for (index in expand(graph, seed, bySource)) {
                if (parent[index] == ReachResult.UNVISITED) enqueue(index, ReachResult.ROOT, 0, seedIndex)
            }
        }

        while (head < tail && selectedAt.size < candidates.size) {
            val current = queue[head++]
            for (edge in graph.dependents(classes[current])) {
                val index = classes.binarySearch(edge.from)
                if (index < 0 || parent[index] != ReachResult.UNVISITED) continue
                enqueue(index, current, edge.kind.ordinal, origin[current])
            }
        }
        return result()
    }

    private fun sourceIndex(graph: DependencyGraph): Map<String, List<Int>> {
        val index = HashMap<String, MutableList<Int>>()
        for ((position, name) in graph.classes.withIndex()) {
            val key = graph.sourceOf(name) ?: continue
            index.getOrPut(key) { ArrayList() }.add(position)
        }
        return index
    }

    private fun expand(graph: DependencyGraph, seed: Seed, bySource: Map<String, List<Int>>): IntArray {
        val found = sortedSetOf<Int>()
        for (name in seed.classes) {
            if (graph.facts(name) == null) continue
            graph.classes.binarySearch(name).takeIf { it >= 0 }?.let(found::add)
            for (nested in graph.ownedBy(name)) graph.classes.binarySearch(nested).takeIf { it >= 0 }?.let(found::add)
        }
        for (key in seed.sourceKeys) bySource[key]?.let(found::addAll)
        return found.toIntArray()
    }
}
