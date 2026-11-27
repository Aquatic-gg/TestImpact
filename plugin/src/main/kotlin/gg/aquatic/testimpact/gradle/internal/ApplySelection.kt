package gg.aquatic.testimpact.gradle.internal

import org.gradle.api.Action
import org.gradle.api.Task
import org.gradle.api.file.RegularFile
import org.gradle.api.logging.Logging
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.testing.Test

/**
 * Restricts a Test task to its selected classes just before it runs. The patterns go through the same channel as
 * `--tests`, so they combine with a `filter { }` of the build script instead of replacing it. A selection that is
 * missing, unreadable or full leaves the task untouched.
 *
 * Classes whose selected name and the build script filter have nothing in common must not fail the build, so the task
 * is told not to fail for lack of matches; Gradle 9's check for no discovered tests is relaxed too while its value can
 * still change.
 */
internal class ApplySelection(private val selection: Provider<RegularFile>) : Action<Task> {
    override fun execute(task: Task) {
        val test = task as? Test ?: return
        val classes = try {
            SelectionFile.read(selection.get().asFile.toPath())
        } catch (e: Exception) {
            Logging.getLogger(ApplySelection::class.java)
                .warn("TestImpact {}: the selection could not be read, the task runs in full ({})", task.path, e.message)
            return
        }
        if (classes.isNullOrEmpty()) return
        test.setTestNameIncludePatterns(classes.flatMap { listOf(it, "$it$*") })
        test.filter.isFailOnNoMatchingTests = false
        try {
            test.failOnNoDiscoveredTests.set(false)
        } catch (e: IllegalStateException) {
            Logging.getLogger(ApplySelection::class.java)
                .info("TestImpact {}: failOnNoDiscoveredTests is already final", task.path)
        }
    }
}
