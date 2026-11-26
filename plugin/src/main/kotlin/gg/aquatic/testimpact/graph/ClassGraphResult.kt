package gg.aquatic.testimpact.graph

/**
 * Outcome of scanning class roots. [Known] is a complete graph. [Unknown] means at least one class could not be
 * read, so the graph would have gaps: callers must fall back to a full run and never use a partial graph.
 */
internal sealed interface ClassGraphResult {
    data class Known(val graph: DependencyGraph) : ClassGraphResult

    /** [problems] are sorted by location, then message. */
    data class Unknown(val problems: List<ClassProblem>) : ClassGraphResult
}
