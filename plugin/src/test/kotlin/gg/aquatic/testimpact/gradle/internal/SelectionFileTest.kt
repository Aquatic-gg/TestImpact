package gg.aquatic.testimpact.gradle.internal

import gg.aquatic.testimpact.gradle.SelectedEntry
import gg.aquatic.testimpact.gradle.TaskOutcome
import gg.aquatic.testimpact.gradle.TaskSelection
import gg.aquatic.testimpact.select.SelectReason
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SelectionFileTest {
    @TempDir
    lateinit var temp: Path

    private fun entry(name: String) = SelectedEntry(name, SelectReason.REACHED, true, "o", name, emptyList(), emptyList())

    private fun selection(outcome: TaskOutcome, vararg names: String) = TaskSelection(
        taskPath = ":test",
        mode = TestImpactMode.AUTO,
        outcome = outcome,
        changedFiles = null,
        candidateCount = 5,
        selected = names.map(::entry),
        fullRule = if (outcome == TaskOutcome.FULL) "mode-full" else null,
        fullDetail = null,
        analysisMillis = 7,
    )

    private fun roundTrip(selection: TaskSelection): List<String>? {
        val file = temp.resolve("sel")
        Files.writeString(file, SelectionFile.render(selection))
        return SelectionFile.read(file)
    }

    @Test
    fun `a selection lists its sorted distinct classes`() {
        assertEquals(listOf("a.A", "b.B"), roundTrip(selection(TaskOutcome.SELECTED, "b.B", "a.A", "b.B")))
    }

    @Test
    fun `an empty selection reads as no class`() {
        assertEquals(emptyList(), roundTrip(selection(TaskOutcome.EMPTY)))
    }

    @Test
    fun `a full selection reads as every class and drops a discarded partial list`() {
        assertNull(roundTrip(selection(TaskOutcome.FULL, "a.A")))
    }

    @Test
    fun `the file does not change with the timing or the reasons`() {
        val first = SelectionFile.render(selection(TaskOutcome.SELECTED, "a.A"))
        val second = SelectionFile.render(selection(TaskOutcome.SELECTED, "a.A").copy(analysisMillis = 900, changedFiles = listOf("x")))
        assertEquals(first, second)
    }

    @Test
    fun `a class name with a line break makes the task run in full`() {
        assertNull(roundTrip(selection(TaskOutcome.SELECTED, "a.A\nb.B")))
    }

    @Test
    fun `garbage and inconsistent files are rejected`() {
        val file = temp.resolve("bad")
        for (text in listOf("", "hello\n", "testimpact-selection 2\nfull\n", "testimpact-selection 1\nselected\n", "testimpact-selection 1\nempty\nA\n", "testimpact-selection 1\nfull\nA\n", "testimpact-selection 1\nmaybe\n")) {
            Files.writeString(file, text)
            assertFailsWith<IllegalArgumentException>(text) { SelectionFile.read(file) }
        }
    }
}
