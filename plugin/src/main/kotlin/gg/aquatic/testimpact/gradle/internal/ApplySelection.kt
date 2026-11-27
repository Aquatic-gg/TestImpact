package gg.aquatic.testimpact.gradle.internal

import gg.aquatic.testimpact.gradle.SelectionInputsService
import org.gradle.api.Action
import org.gradle.api.Task
import org.gradle.api.file.RegularFile
import org.gradle.api.logging.Logging
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.testing.Test

/**
 * Restricts a Test task to its selected classes just before it runs. The patterns go through the same channel as
 * `--tests`, so they combine with a `filter { }` of the build script instead of replacing it. A selection that is
 * missing, unreadable or full leaves the task untouched, as does a task that already has command line include patterns
 * or whose selection task did not run.
 *
 * Classes whose selected name and the build script filter have nothing in common must not fail the build, so the task
 * is told not to fail for lack of matches.
 */
internal class ApplySelection(
    private val selection: Provider<RegularFile>,
    private val inputs: Provider<SelectionInputsService>,
    private val baseline: Set<String>,
    private val lifecycle: (String) -> Unit = { Logging.getLogger(ApplySelection::class.java).lifecycle(it) },
) : Action<Task> {
    override fun execute(task: Task) {
        val test = task as? Test ?: return
        val service = inputs.get()
        when (SelectionGate.verdict(test, service, baseline)) {
            SelectionGate.Verdict.APPLIES -> Unit
            SelectionGate.Verdict.LATE_FILTER -> {
                lifecycle(
                    "TestImpact ${task.path}: the test filter was changed after configuration, the task is not narrowed and runs as requested",
                )
                return
            }
            SelectionGate.Verdict.NOT_SELECTED, SelectionGate.Verdict.COMMAND_LINE -> return
        }
        val classes = try {
            SelectionFile.read(selection.get().asFile.toPath())
        } catch (e: Exception) {
            Logging.getLogger(ApplySelection::class.java).warn("TestImpact {}: the selection could not be read, the task runs in full ({})", task.path, e.message)
            return
        }
        if (classes.isNullOrEmpty()) return
        test.setTestNameIncludePatterns(classes.flatMap { listOf(it, "$it$*") })
        test.filter.isFailOnNoMatchingTests = false
    }
}
