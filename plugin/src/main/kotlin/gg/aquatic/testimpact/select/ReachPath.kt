package gg.aquatic.testimpact.select

import gg.aquatic.testimpact.graph.Edge

/**
 * Shortest chain from a seed to a selected class.
 *
 * @property origin the seed origin, normally the changed file.
 * @property start the seed class the walk began at.
 * @property edges edges from the seed outward; each edge runs from the dependent to the class it depends on.
 * The last edge's `from` is the class the walk reached, which is the selected class or a class nested in it (and,
 * for a global registration, a class that triggers every test). Empty when the reached class is the seed itself.
 */
internal data class ReachPath(
    val origin: String,
    val start: String,
    val edges: List<Edge>,
)
