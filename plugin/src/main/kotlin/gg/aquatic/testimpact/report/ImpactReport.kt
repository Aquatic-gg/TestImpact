package gg.aquatic.testimpact.report

/** Version of the `report.json` layout; bumped only when a field is removed, renamed or changes meaning. */
internal const val REPORT_SCHEMA_VERSION = 1

/**
 * What one project's test tasks would run for the current change, and why.
 *
 * @property project path of the project the report belongs to.
 * @property forced the rule that forces every task to a full run, null when changes are mapped.
 * @property tasks sorted by task path with an ordinal comparison.
 */
internal data class ImpactReport(
    val project: String,
    val changes: Changes,
    val forced: Rule?,
    val tasks: List<Task>,
    val totals: Totals,
    val durations: Durations,
) {

    enum class State { KNOWN, UNKNOWN }

    enum class Result { SELECTED, FULL }

    /**
     * @property reason name of the change detection failure, set when [state] is unknown.
     * @property files changed files sorted by path; empty when unknown.
     */
    data class Changes(
        val state: State,
        val baseRef: String?,
        val baseCommit: String?,
        val mergeBase: String?,
        val reason: String?,
        val detail: String?,
        val files: List<File>,
    )

    /** @property kind name of the file kind: source, resource, build logic or other. */
    data class File(
        val path: String,
        val status: String,
        val kind: String,
        val classes: List<String>,
    )

    /**
     * A fixed rule that forces a full run.
     *
     * @property reason name of the selection reason.
     * @property text sentence for the reason.
     */
    data class Rule(
        val reason: String,
        val text: String,
        val detail: String,
    )

    /**
     * @property rule set when [result] is full.
     * @property counts set when [result] is selected.
     * @property tests selected test classes sorted by name; empty for a full run.
     */
    data class Task(
        val path: String,
        val result: Result,
        val rule: Rule?,
        val counts: Counts?,
        val tests: List<Test>,
        val durationMs: Long,
    )

    data class Counts(
        val selected: Int,
        val recognised: Int,
        val candidates: Int,
    )

    /**
     * @property reason name of the select reason.
     * @property changedFile the file whose change started the path.
     * @property seedClass the class the walk began at.
     * @property reachedClass the class the walk reached: the test class or a class nested in it, and for a global
     * reason the class that registers the global extension.
     * @property path edges from [seedClass] outward; each edge runs from the dependent to what it depends on.
     */
    data class Test(
        val className: String,
        val reason: String,
        val recognised: Boolean,
        val changedFile: String,
        val seedClass: String,
        val reachedClass: String,
        val path: List<Step>,
    )

    /** @property kind name of the edge kind. @property seed name of the seed kind for a hop through a name, service or resource link, else null. */
    data class Step(
        val from: String,
        val to: String,
        val kind: String,
        val seed: String? = null,
    )

    data class Totals(
        val tasks: Int,
        val fullRunTasks: Int,
        val selectedTests: Int,
        val candidates: Int,
    )

    data class Durations(
        val detectionMs: Long,
        val selectionMs: Long,
        val totalMs: Long,
    )
}
