package gg.aquatic.testimpact.gradle.internal

import gg.aquatic.testimpact.gradle.TaskOutcome
import gg.aquatic.testimpact.gradle.TaskSelection
import java.nio.file.Files
import java.nio.file.Path

/**
 * The small file a Test task reads: the decision and the classes to run, nothing else. It is an input of the Test task,
 * so it holds no timings or reasons that would change the task's cache key without changing what runs.
 */
internal object SelectionFile {
    private const val HEADER = "testimpact-selection 1"
    private const val SELECTED = "selected"
    private const val EMPTY = "empty"
    private const val FULL = "full"

    fun render(selection: TaskSelection): String {
        val names = selection.selected.map { it.className }.distinct().sorted()
        val usable = names.none { name -> name.any(Char::isISOControl) }
        val (status, listed) = when {
            selection.outcome == TaskOutcome.EMPTY -> EMPTY to emptyList()
            selection.outcome == TaskOutcome.SELECTED && usable -> SELECTED to names
            else -> FULL to emptyList()
        }
        return buildString {
            append(HEADER).append('\n').append(status).append('\n')
            for (name in listed) append(name).append('\n')
        }
    }

    /**
     * @return the class names to run, an empty list when no class runs, or null when every class runs
     * @throws IllegalArgumentException when the text is not a selection file
     * @throws java.io.IOException when the file cannot be read
     */
    fun read(file: Path): List<String>? {
        val lines = Files.readAllLines(file)
        require(lines.size >= 2 && lines[0] == HEADER) { "not a selection file: $file" }
        val names = lines.drop(2)
        return when (lines[1]) {
            SELECTED -> names.also { require(it.isNotEmpty() && it.none(String::isEmpty)) { "selection file lists no class: $file" } }
            EMPTY -> emptyList<String>().also { require(names.isEmpty()) { "selection file has stray lines: $file" } }
            FULL -> null.also { require(names.isEmpty()) { "selection file has stray lines: $file" } }
            else -> throw IllegalArgumentException("unknown selection status '${lines[1].take(20)}': $file")
        }
    }
}
