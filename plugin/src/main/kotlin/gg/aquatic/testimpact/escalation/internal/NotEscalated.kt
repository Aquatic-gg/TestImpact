package gg.aquatic.testimpact.escalation.internal

import org.gradle.api.Task
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.specs.Spec
import org.gradle.api.tasks.testing.Test

/**
 * Satisfied while a Test task owes no full run. Used for `upToDateWhen` and `cacheIf`, so a task with red memory is
 * neither skipped as up to date nor loaded from or stored in the build cache.
 */
internal class NotEscalated(
    private val state: Provider<RegularFile>,
    private val baseline: Set<String>,
) : Spec<Task> {
    override fun isSatisfiedBy(element: Task): Boolean {
        val test = element as? Test ?: return true
        return !EscalatedState.isEscalated(test, state, baseline)
    }

    /** Never satisfied: for the quick task, whose result is no verdict and is neither skipped nor cached. */
    internal object Never : Spec<Task> {
        override fun isSatisfiedBy(element: Task): Boolean = false
    }
}
