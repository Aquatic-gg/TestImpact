package gg.aquatic.testimpact.seed

/** How a node was reached from the changed nodes: [path] starts at a changed node, [kinds] are the seed edges on it. */
internal data class Reached(val path: List<String>, val kinds: List<SeedKind>)
