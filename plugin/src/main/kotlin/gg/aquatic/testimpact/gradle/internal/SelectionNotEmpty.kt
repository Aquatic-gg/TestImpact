package gg.aquatic.testimpact.gradle.internal

import gg.aquatic.testimpact.gradle.SelectionInputsService
import org.gradle.api.Task
import org.gradle.api.file.RegularFile
import org.gradle.api.logging.Logging
import org.gradle.api.provider.Provider
import org.gradle.api.specs.Spec
import org.gradle.api.tasks.testing.Test

/**
 * Lets a Test task run unless its selection is empty. A selection file that is missing or unreadable never skips the
 * task, nor does a task the selection does not apply to. It holds only providers, so it is a configuration cache value.
 */
internal class SelectionNotEmpty(
    private val selection: Provider<RegularFile>,
    private val inputs: Provider<SelectionInputsService>,
    private val baseline: Set<String>,
) : Spec<Task> {
    override fun isSatisfiedBy(element: Task): Boolean = try {
        val test = element as? Test
        if (test != null && !SelectionGate.applies(test, inputs.get(), baseline)) true
        else SelectionFile.read(selection.get().asFile.toPath())?.isNotEmpty() ?: true
    } catch (e: Exception) {
        Logging.getLogger(SelectionNotEmpty::class.java)
            .warn("TestImpact {}: the selection could not be read, the task runs ({})", element.path, e.message)
        true
    }
}
