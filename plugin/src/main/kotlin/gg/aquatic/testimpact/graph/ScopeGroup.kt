package gg.aquatic.testimpact.graph

/**
 * The classes a constant or typealias definer reaches as one unit. Definers with the same [key] and [kind] reach the
 * same classes, so a walk over the graph expands [members] once per key instead of once per definer. [members] may
 * contain the definer itself.
 */
internal class ScopeGroup(
    val key: String,
    val kind: EdgeKind,
    val members: () -> List<String>,
)
