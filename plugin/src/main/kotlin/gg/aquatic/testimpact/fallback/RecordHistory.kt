package gg.aquatic.testimpact.fallback

import gg.aquatic.testimpact.gradle.SelectionInputsService
import gg.aquatic.testimpact.gradle.internal.SelectionFile
import gg.aquatic.testimpact.gradle.internal.SelectionGate
import org.gradle.api.Action
import org.gradle.api.Task
import org.gradle.api.file.RegularFile
import org.gradle.api.logging.Logging
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.testing.Test

/**
 * Runs after a Test task finished green and records that the task ran in full, which is what the history rules look
 * for. A task that was narrowed to a selection, or that the user restricted, leaves the record as it is. A red run
 * throws before this action, so it records nothing. It never fails the build.
 */
internal class RecordHistory(
    private val selection: Provider<RegularFile>,
    private val inputs: Provider<SelectionInputsService>,
    private val baseline: Set<String>,
    private val capture: FallbackCapture,
) : Action<Task> {
    override fun execute(task: Task) {
        val test = task as? Test ?: return
        val logger = Logging.getLogger(RecordHistory::class.java)
        val ranInFull = try {
            SelectionGate.verdict(test, inputs.get(), baseline) == SelectionGate.Verdict.APPLIES &&
                SelectionFile.read(selection.get().asFile.toPath()) == null
        } catch (e: Exception) {
            logger.warn("TestImpact {}: the selection could not be read, no history is recorded ({})", task.path, e.message)
            return
        }
        if (!ranInFull) return
        if (!RunHistory.write(capture.historyFile.toPath(), RunRecord(capture.currentToolchain()))) {
            logger.warn("TestImpact {}: the history could not be written to {}, the next run is a full run", task.path, capture.historyFile)
        }
    }
}
