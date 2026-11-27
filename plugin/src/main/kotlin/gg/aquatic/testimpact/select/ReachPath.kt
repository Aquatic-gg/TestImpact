package gg.aquatic.testimpact.select

import gg.aquatic.testimpact.graph.Edge
import gg.aquatic.testimpact.seed.SeedKind

/**
 * Shortest chain from a seed to a selected class.
 *
 * @property origin the seed origin, normally the changed file.
 * @property start the seed class the walk began at.
 * @property edges edges from the seed outward; each edge runs from the dependent to the class it depends on.
 * The last edge's `from` is the class the walk reached, which is the selected class or a class nested in it (and,
 * for a global registration, a class that triggers every test). Empty when the reached class is the seed itself.
 * An edge may end or start at a resource node (a name starting with `/`).
 * @property seedKinds for an edge index in [edges] that is a hop through a name, service or resource link rather than
 * bytecode, why the two ends are linked. Empty for a path that follows bytecode references only.
 */
internal data class ReachPath(
    val origin: String,
    val start: String,
    val edges: List<Edge>,
    val seedKinds: Map<Int, SeedKind> = emptyMap(),
)
