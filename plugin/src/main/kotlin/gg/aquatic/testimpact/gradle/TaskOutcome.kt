package gg.aquatic.testimpact.gradle

/** What a Test task does after selection. */
internal enum class TaskOutcome {
    /** Only the selected test classes run. */
    SELECTED,

    /** No test class is affected, so the task is skipped. */
    EMPTY,

    /** Every test class runs; the full rule says why. */
    FULL,
}
