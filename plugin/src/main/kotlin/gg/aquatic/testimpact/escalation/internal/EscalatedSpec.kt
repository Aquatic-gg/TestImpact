package gg.aquatic.testimpact.escalation.internal

import org.gradle.api.Task
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.specs.Spec
import org.gradle.api.tasks.testing.Test

/** The `onlyIf` of a Test task that owes a full run: the task runs, without asking [delegate] (the selection). */
internal class EscalatedSpec(
    private val state: Provider<RegularFile>,
    private val baseline: Set<String>,
    private val delegate: Spec<Task>,
) : Spec<Task> {
    override fun isSatisfiedBy(element: Task): Boolean {
        val test = element as? Test ?: return delegate.isSatisfiedBy(element)
        return EscalatedState.isEscalated(test, state, baseline) || delegate.isSatisfiedBy(element)
    }
}
