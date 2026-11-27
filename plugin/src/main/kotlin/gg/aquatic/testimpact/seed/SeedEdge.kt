package gg.aquatic.testimpact.seed

/** [from] depends on [to] for the reason [kind]. Nodes are binary class names or resource nodes (`/path`). */
internal data class SeedEdge(val from: String, val to: String, val kind: SeedKind)
