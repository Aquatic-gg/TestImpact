package gg.aquatic.testimpact.select

import gg.aquatic.testimpact.graph.Edge

/**
 * Shortest chain from a seed to a selected class.
 *
 * @property origin the seed origin, normally the changed file.
 * @property start the class the walk began at: a class of the seed, a class nested in one, or a class compiled from
 * the same source file.
 * @property edges edges from [start] outward; each edge runs from the dependent to the class it depends on, so the
 * first edge's `to` is [start] and each next edge's `to` is the previous edge's `from`. The last edge's `from` is the
 * class the walk reached: the selected test class or a class nested in it, and for a global registration the
 * extension point class or a class nested in it, not the selected test class. Empty when [start] itself is the
 * reached class.
 */
internal data class ReachPath(
    val origin: String,
    val start: String,
    val edges: List<Edge>,
)
