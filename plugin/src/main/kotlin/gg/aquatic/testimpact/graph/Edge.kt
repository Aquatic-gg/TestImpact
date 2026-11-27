package gg.aquatic.testimpact.graph

/** [from] depends on [to]; both are binary class names, [to] may be a class that was not scanned. */
internal data class Edge(val from: String, val to: String, val kind: EdgeKind)
