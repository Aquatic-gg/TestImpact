package gg.aquatic.testimpact.gradle.internal

import gg.aquatic.testimpact.change.ChangeDetection
import gg.aquatic.testimpact.gradle.TaskSelection
import gg.aquatic.testimpact.select.SeedSet
import gg.aquatic.testimpact.select.Selection

/**
 * What [SelectionRun] found for one Test task.
 *
 * @property decision what the task runs: the selection task writes it, the report renders it.
 * @property detection the changes as this task sees them; null when the decision came before reading git.
 * @property seeds the seeds of [detection], null with it.
 * @property selection the selection before the decision; null when it did not run.
 * @property detectionMillis the part of `decision.analysisMillis` spent until the changes were known.
 * @property failure the exception that turned the decision into a plugin error, else null.
 */
internal data class SelectionOutcome(
    val decision: TaskSelection,
    val detection: ChangeDetection? = null,
    val seeds: SeedSet? = null,
    val selection: Selection? = null,
    val detectionMillis: Long = 0,
    val failure: Exception? = null,
)
