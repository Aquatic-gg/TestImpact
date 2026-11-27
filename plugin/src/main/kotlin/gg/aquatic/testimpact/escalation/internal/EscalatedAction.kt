package gg.aquatic.testimpact.escalation.internal

import gg.aquatic.testimpact.escalation.EscalationService
import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.api.Task
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.testing.Test

/**
 * The first `doFirst` of a Test task that owes a full run. It stops the build when the quick phase of this build was
 * red; otherwise it lets the task run every class by not calling [delegate] (the selection). A task nobody escalated
 * runs [delegate] unchanged. The stop sits here, not in the quick task, because only here is the filter final: a test
 * launcher of an IDE adds its tests after configuration and is never stopped by the memory.
 */
internal class EscalatedAction(
    private val state: Provider<RegularFile>,
    private val service: Provider<EscalationService>,
    private val baseline: Set<String>,
    private val quickPath: String,
    private val delegate: Action<Task>,
) : Action<Task> {
    override fun execute(task: Task) {
        val test = task as? Test ?: return delegate.execute(task)
        if (!EscalatedState.isEscalated(test, state, baseline)) return delegate.execute(task)
        val outcome = service.get().quickOutcome(task.path)
        if (outcome != null && outcome.red) throw GradleException(message(task.path, outcome.failures, outcome.unknown))
        task.logger.lifecycle("TestImpact ${task.path}: runs every test class, a red run is not yet followed by a green full run")
    }

    private fun message(testPath: String, failures: Set<String>, unknown: Boolean): String {
        val shown = failures.take(MAX_NAMED).joinToString(", ")
        val more = if (failures.size > MAX_NAMED) " and ${failures.size - MAX_NAMED} more" else ""
        val what = when {
            failures.isEmpty() -> "a failure without a test class"
            unknown -> "$shown$more and a failure without a test class"
            else -> "$shown$more"
        }
        return "TestImpact $testPath: the quick phase is red, the full run is not started: $what " +
            "(see the report of $quickPath). Fix it, or skip the quick phase with -x ${quickPath.substringAfterLast(':')}."
    }

    private companion object {
        const val MAX_NAMED = 10
    }
}
