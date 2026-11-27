package gg.aquatic.testimpact.escalation.internal

import gg.aquatic.testimpact.escalation.EscalationService
import gg.aquatic.testimpact.escalation.state.RunPhase
import org.gradle.api.Action
import org.gradle.api.Task
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.testing.AbstractTestTask
import org.gradle.api.tasks.testing.Test

/**
 * Attaches a [RedListener] to a Test task just before it runs. The listener is created at execution time, so the task
 * action holds only values the configuration cache can store: the state file, the service, the test path and the
 * build script's own include patterns. A fixed [phase] is for the quick task; without one the phase is read from the
 * filter when the run starts, after the selection has narrowed it.
 */
internal class RedRecorder(
    private val state: Provider<RegularFile>,
    private val service: Provider<EscalationService>,
    private val testPath: String,
    private val phase: RunPhase?,
    private val baseline: Set<String>,
) : Action<Task> {
    override fun execute(task: Task) {
        val testTask = task as? AbstractTestTask ?: return
        val logger = task.logger
        val phaseOf: () -> RunPhase = when {
            phase != null -> ({ phase })
            testTask is Test -> ({ RunShape.phaseOf(testTask, baseline) })
            else -> ({ RunPhase.PARTIAL })
        }
        testTask.addTestListener(
            RedListener(state.get().asFile.toPath(), phaseOf, testPath, service.get()) { logger.warn(it) },
        )
    }
}
