package gg.aquatic.testimpact.select.internal

import gg.aquatic.testimpact.graph.DependencyGraph
import gg.aquatic.testimpact.graph.EdgeKind
import gg.aquatic.testimpact.seed.SeedEdge
import gg.aquatic.testimpact.seed.SeedGraph
import gg.aquatic.testimpact.seed.SeedKind
import gg.aquatic.testimpact.select.Seed

/**
 * Multi-source breadth-first walk over the dependents of the seed classes. A class reached anywhere inside a
 * top-level class (nested, inner, lambda) selects that top-level class when it is a candidate. The walk is
 * index-based over the sorted class list, stops as soon as every candidate is selected, and is deterministic:
 * seeds are taken in the given order, the explicit dependents of a class come sorted from the graph, followed by the
 * classes of each constant or typealias scope it defines. A scope (everything, one package, one source file) is
 * expanded once per walk: later definers of the same scope would only meet classes that are already visited.
 */
internal object ReverseReach {
    private const val MIN_QUEUE = 8

    /**
     * Seeds expand to the class itself, the classes nested in it and every class compiled from the same source file.
     * Seed class names that were not scanned are skipped. With [extra] every dequeued node also follows the links
     * bytecode does not show (names in constants, service files, resources, reflection); a resource reached that way
     * gets a node after the classes, and its hop is recorded as a reference edge plus its seed kind.
     */
    fun reach(graph: DependencyGraph, seeds: List<Seed>, candidates: Set<String>, extra: SeedGraph? = null): ReachResult {
        val classes = graph.classes
        val count = classes.size
        var parent = IntArray(count) { ReachResult.UNVISITED }
        var kind = ByteArray(count)
        var origin = IntArray(count)
        var queue = IntArray(count)
        val selectedAt = HashMap<String, Int>()
        val origins = seeds.map { it.origin }
        val resourceNodes = ArrayList<String>()
        val resourceIndex = HashMap<String, Int>()
        val seedHops = HashMap<Int, SeedKind>()
        val result = { ReachResult(classes, parent, kind, origin, origins, selectedAt, resourceNodes, seedHops) }
        if (candidates.isEmpty()) return result()

        var tail = 0
        var head = 0

        fun enqueue(index: Int, from: Int, edgeKind: Int, seed: Int) {
            parent[index] = from
            kind[index] = edgeKind.toByte()
            origin[index] = seed
            if (tail == queue.size) queue = queue.copyOf(maxOf(MIN_QUEUE, tail * 2))
            queue[tail++] = index
            if (index >= count) return
            val owner = graph.owner(classes[index])
            if (owner in candidates) selectedAt.putIfAbsent(owner, index)
        }

        val expandedScopes = HashSet<String>()

        fun visit(name: String, from: Int, edgeKind: Int) {
            val index = classes.binarySearch(name)
            if (index >= 0 && parent[index] == ReachResult.UNVISITED) enqueue(index, from, edgeKind, origin[from])
        }

        fun visitSeedEdge(edge: SeedEdge, from: Int) {
            val index = if (edge.from.startsWith("/")) {
                resourceIndex.getOrPut(edge.from) {
                    val created = count + resourceNodes.size
                    resourceNodes.add(edge.from)
                    if (created >= parent.size) {
                        val size = maxOf(created + 1, parent.size * 2)
                        parent = parent.copyOf(size).also { grown -> grown.fill(ReachResult.UNVISITED, parent.size, size) }
                        kind = kind.copyOf(size)
                        origin = origin.copyOf(size)
                    }
                    created
                }
            } else {
                classes.binarySearch(edge.from)
            }
            if (index < 0 || parent[index] != ReachResult.UNVISITED) return
            seedHops[index] = edge.kind
            enqueue(index, from, EdgeKind.REFERENCE.ordinal, origin[from])
        }

        val bySource = sourceIndex(graph)
        for ((seedIndex, seed) in seeds.withIndex()) {
            for (index in expand(graph, seed, bySource)) {
                if (parent[index] == ReachResult.UNVISITED) enqueue(index, ReachResult.ROOT, 0, seedIndex)
            }
        }

        while (head < tail && selectedAt.size < candidates.size) {
            val current = queue[head++]
            if (current >= count) {
                extra?.dependents(resourceNodes[current - count])?.forEach { visitSeedEdge(it, current) }
                continue
            }
            val name = classes[current]
            for (edge in graph.explicitDependentsOf(name)) visit(edge.from, current, edge.kind.ordinal)
            for (group in graph.scopeGroups(name)) {
                if (!expandedScopes.add(group.kind.name + group.key)) continue
                for (member in group.members()) visit(member, current, group.kind.ordinal)
            }
            extra?.dependents(name)?.forEach { visitSeedEdge(it, current) }
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
