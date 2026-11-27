package gg.aquatic.testimpact.graph

/**
 * [from] depends on [to]; both are binary class names, [to] may be a class that was not scanned. In a reach path
 * either end may also be a resource node (a name starting with `/`).
 */
internal data class Edge(val from: String, val to: String, val kind: EdgeKind)
