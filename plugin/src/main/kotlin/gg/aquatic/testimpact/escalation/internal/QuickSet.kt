package gg.aquatic.testimpact.escalation.internal

import gg.aquatic.testimpact.escalation.state.RedState
import gg.aquatic.testimpact.escalation.state.RedStateFile
import gg.aquatic.testimpact.gradle.internal.SelectionFile
import java.nio.file.Path
import java.util.TreeSet

/**
 * The classes of the quick phase: the remembered red ones plus the selected ones. A selection that is full, empty,
 * missing or unreadable adds nothing, so the quick phase then follows up the red classes only.
 */
internal class QuickSet(val state: RedState, val red: Set<String>, val selected: Set<String>) {
    val classes: Set<String> get() = TreeSet(red + selected)

    val isEmpty: Boolean get() = red.isEmpty() && selected.isEmpty()

    /** Include patterns for the test filter: each class and its nested classes. */
    fun patterns(): List<String> = classes.flatMap { listOf(it, "$it$*") }

    companion object {
        fun read(stateFile: Path, selectionFile: Path): QuickSet {
            val state = RedStateFile.read(stateFile)
            if (!state.fullRunRequired) return QuickSet(state, emptySet(), emptySet())
            val selected = try {
                SelectionFile.read(selectionFile).orEmpty()
            } catch (_: Exception) {
                emptyList()
            }
            return QuickSet(state, state.red, selected.toSet() - state.red)
        }
    }
}
