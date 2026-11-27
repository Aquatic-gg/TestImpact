package gg.aquatic.testimpact.escalation.internal

import org.gradle.api.Task
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.specs.Spec
import org.gradle.api.tasks.testing.Test

/**
 * Whether the quick phase runs: only in mode auto, when the build names no tests, the task's own filter holds nothing
 * beyond the build script's ([baseline]), a full run is owed, and there is a red or selected class to run first.
 * Evaluated when the task is about to run, after the selection task has written its file.
 */
internal class QuickPhaseSpec(
    private val state: Provider<RegularFile>,
    private val selection: Provider<RegularFile>,
    private val autoMode: Boolean,
    private val userTests: Boolean,
    private val baseline: Set<String>,
) : Spec<Task> {
    override fun isSatisfiedBy(task: Task): Boolean {
        if (!autoMode || userTests) return false
        val test = task as? Test ?: return false
        if (!RunShape.isFull(test, baseline)) return false
        val set = QuickSet.read(state.get().asFile.toPath(), selection.get().asFile.toPath())
        return set.state.fullRunRequired && !set.isEmpty
    }

    companion object {
        const val REASON = "TestImpact: no red run to follow up"
    }
}
