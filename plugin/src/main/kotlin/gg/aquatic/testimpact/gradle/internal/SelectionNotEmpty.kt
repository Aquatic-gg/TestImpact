package gg.aquatic.testimpact.gradle.internal

import org.gradle.api.Task
import org.gradle.api.file.RegularFile
import org.gradle.api.logging.Logging
import org.gradle.api.provider.Provider
import org.gradle.api.specs.Spec

/**
 * Lets a Test task run unless its selection is empty. A selection file that is missing or unreadable never skips the
 * task. It holds only the file provider, so it is a configuration cache value.
 */
internal class SelectionNotEmpty(private val selection: Provider<RegularFile>) : Spec<Task> {
    override fun isSatisfiedBy(element: Task): Boolean = try {
        SelectionFile.read(selection.get().asFile.toPath())?.isNotEmpty() ?: true
    } catch (e: Exception) {
        Logging.getLogger(SelectionNotEmpty::class.java)
            .warn("TestImpact {}: the selection could not be read, the task runs ({})", element.path, e.message)
        true
    }
}
