package gg.aquatic.testimpact.select

import gg.aquatic.testimpact.graph.Edge

/**
 * Shortest chain from a seed to a selected class.
 *
 * @property origin the seed origin, normally the changed file.
 * @property start the seed class the walk began at.
 * @property edges edges from the seed outward; each edge runs from the dependent to the class it depends on,
 * so the last edge's `from` is the selected class. Empty when the selected class is the seed itself.
 */
internal data class ReachPath(
    val origin: String,
    val start: String,
    val edges: List<Edge>,
)
