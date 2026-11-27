package gg.aquatic.testimpact.escalation

import gg.aquatic.testimpact.escalation.internal.EscalatedAction
import gg.aquatic.testimpact.escalation.internal.EscalatedSpec
import gg.aquatic.testimpact.escalation.internal.NotEscalated
import gg.aquatic.testimpact.escalation.internal.RedRecorder
import org.gradle.api.Action
import org.gradle.api.Task
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.specs.Spec
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.testing.Test

/**
 * What one Test task needs to follow up a red run: its state file, the per-build service and its quick task. It hands
 * out the wrapped selection hooks and attaches the red-run behaviour to the task.
 */
internal class Escalation(
    private val state: Provider<RegularFile>,
    private val service: Provider<EscalationService>,
    private val quick: TaskProvider<Test>,
    private val quickPath: String,
) {
    /** The `onlyIf` of the task: a run owed in full is never skipped for an empty selection. */
    fun spec(delegate: Spec<Task>, baseline: Set<String>): Spec<Task> = EscalatedSpec(state, baseline, delegate)

    /** The selection hook of the task, wrapped: it stops on a red quick phase, and leaves the task unnarrowed. */
    fun action(delegate: Action<Task>, baseline: Set<String>): Action<Task> =
        EscalatedAction(state, service, baseline, quickPath, delegate)

    /** Orders the quick task first, records the run, and keeps a task with red memory out of up-to-date and the cache. */
    fun configure(test: Test, baseline: Set<String>) {
        test.dependsOn(quick)
        test.usesService(service)
        test.doFirst(RedRecorder(state, service, test.path, null, baseline))
        val notEscalated = NotEscalated(state, baseline)
        test.outputs.upToDateWhen(notEscalated)
        test.outputs.cacheIf("TestImpact: no red run is waiting for a full run", notEscalated)
    }
}
