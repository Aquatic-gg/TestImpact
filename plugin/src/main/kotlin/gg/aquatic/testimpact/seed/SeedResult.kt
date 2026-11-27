package gg.aquatic.testimpact.seed

import gg.aquatic.testimpact.graph.ClassProblem

/**
 * Outcome of scanning roots for seed facts. [Known] is complete. [Unknown] means something could not be read, so
 * the facts would have gaps: callers must fall back to a full run and never use partial facts.
 */
internal sealed interface SeedResult {
    data class Known(val inputs: SeedInputs) : SeedResult

    /** [problems] are sorted by location, then message. */
    data class Unknown(val problems: List<ClassProblem>) : SeedResult
}
