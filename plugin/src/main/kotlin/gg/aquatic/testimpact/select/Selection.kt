package gg.aquatic.testimpact.select

/**
 * Outcome of selection for one test task. [Unknown] means the task has to run in full.
 */
internal sealed interface Selection {
    val taskPath: String

    /**
     * @property tests selected classes sorted by name with an ordinal comparison.
     * @property recognisedCount candidate classes that framework markers identified as tests.
     * @property candidateCount all concrete top-level classes of the test roots.
     */
    data class Known(
        override val taskPath: String,
        val tests: List<SelectedTest>,
        val recognisedCount: Int,
        val candidateCount: Int,
    ) : Selection

    data class Unknown(
        override val taskPath: String,
        val reason: SelectionUnknownReason,
        val detail: String,
    ) : Selection
}
