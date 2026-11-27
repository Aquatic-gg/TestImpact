package gg.aquatic.testimpact.escalation.internal

import gg.aquatic.testimpact.escalation.EscalationService
import org.gradle.api.Action
import org.gradle.api.Task
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.testing.Test

/**
 * Narrows the quick task to the red and selected classes just before it runs. It fails the build itself on red only
 * when it was asked for by name; as the first phase of a `test` run it reports through the service and leaves the stop
 * to the user's task.
 */
internal class QuickPhaseAction(
    private val state: Provider<RegularFile>,
    private val selection: Provider<RegularFile>,
    private val requestedByName: Boolean,
) : Action<Task> {
    override fun execute(task: Task) {
        val test = task as? Test ?: return
        val set = QuickSet.read(state.get().asFile.toPath(), selection.get().asFile.toPath())
        if (set.isEmpty) return
        test.setTestNameIncludePatterns(set.patterns())
        test.filter.isFailOnNoMatchingTests = false
        test.ignoreFailures = !requestedByName
        task.logger.lifecycle(
            "TestImpact ${task.path}: quick phase runs ${set.red.size} red and ${set.selected.size} selected classes before the full run",
        )
    }

    /** After a quick-only build: a green quick phase is no verdict, so the build says the full run is still owed. */
    internal class Done(
        private val service: Provider<EscalationService>,
        private val testPath: String,
        private val requestedByName: Boolean,
    ) : Action<Task> {
        override fun execute(task: Task) {
            if (!requestedByName) return
            val outcome = service.get().quickOutcome(testPath) ?: return
            if (!outcome.red) task.logger.lifecycle("TestImpact ${task.path}: the quick phase is green, the full run is still required")
        }
    }
}
