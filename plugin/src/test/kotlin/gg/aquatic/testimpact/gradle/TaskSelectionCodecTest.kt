package gg.aquatic.testimpact.gradle

import gg.aquatic.testimpact.gradle.internal.TaskSelectionCodec
import gg.aquatic.testimpact.gradle.internal.TestImpactMode
import gg.aquatic.testimpact.select.SelectReason
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TaskSelectionCodecTest {

    private val hostile = "tab\there\nnew\rline\\back\\n\\\\ 😀 end"

    private fun entry(name: String) = SelectedEntry(
        className = name,
        reason = SelectReason.REACHED,
        recognised = true,
        origin = hostile,
        start = "a.Start",
        edges = listOf("a.Start>b.Mid:REFERENCE", "b.Mid>/res/$hostile:REFERENCE"),
        seedKinds = listOf("1:RESOURCE_NAME"),
    )

    private fun sample() = TaskSelection(
        taskPath = ":app:test",
        mode = TestImpactMode.AUTO,
        outcome = TaskOutcome.SELECTED,
        changedFiles = listOf("src/main/$hostile.kt", "b.kt"),
        candidateCount = 5,
        selected = listOf(entry("a.OneTest"), entry("a.TwoTest").copy(edges = emptyList(), seedKinds = emptyList())),
        fullRule = null,
        fullDetail = null,
        analysisMillis = 42,
    )

    @Test
    fun `a selection round trips with hostile strings`() {
        val selection = sample()

        assertEquals(selection, TaskSelectionCodec.read(TaskSelectionCodec.write(selection)))
    }

    @Test
    fun `a full selection with unknown changes round trips`() {
        val selection = TaskSelection(
            taskPath = ":test",
            mode = TestImpactMode.FULL,
            outcome = TaskOutcome.FULL,
            changedFiles = null,
            candidateCount = null,
            selected = emptyList(),
            fullRule = "changes-unknown",
            fullDetail = hostile,
            analysisMillis = 0,
        )

        assertEquals(selection, TaskSelectionCodec.read(TaskSelectionCodec.write(selection)))
    }

    @Test
    fun `an empty selection with no changed files round trips`() {
        val selection = sample().copy(outcome = TaskOutcome.EMPTY, changedFiles = emptyList(), selected = emptyList())

        assertEquals(selection, TaskSelectionCodec.read(TaskSelectionCodec.write(selection)))
    }

    @Test
    fun `a hostile value cannot add a line`() {
        val text = TaskSelectionCodec.write(sample())

        assertEquals(text.count { it == '\n' }, text.lines().size - 1)
        assertEquals(1, text.lines().count { it.startsWith("taskPath\t") })
    }

    @Test
    fun `the file round trips through a path`() {
        val file = Files.createTempFile("selection", ".txt")
        try {
            Files.writeString(file, TaskSelectionCodec.write(sample()))

            assertEquals(sample(), TaskSelectionCodec.read(file))
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `a wrong version throws`() {
        val text = TaskSelectionCodec.write(sample()).replaceFirst("testimpact-selection\t1", "testimpact-selection\t2")

        assertFailsWith<IllegalArgumentException> { TaskSelectionCodec.read(text) }
    }

    @Test
    fun `a truncated file throws at every cut`() {
        val text = TaskSelectionCodec.write(sample())

        for (cut in 0 until text.length - 1) {
            assertFailsWith<IllegalArgumentException>("cut at $cut") { TaskSelectionCodec.read(text.substring(0, cut)) }
        }
    }

    @Test
    fun `garbage throws`() {
        assertFailsWith<IllegalArgumentException> { TaskSelectionCodec.read("") }
        assertFailsWith<IllegalArgumentException> { TaskSelectionCodec.read("not a selection\n") }
        assertFailsWith<IllegalArgumentException> { TaskSelectionCodec.read("\u0000\u0001\n\n") }
    }

    @Test
    fun `an unknown escape or enum value throws`() {
        val text = TaskSelectionCodec.write(sample())

        assertFailsWith<IllegalArgumentException> { TaskSelectionCodec.read(text.replace("taskPath\t:app:test", "taskPath\t\\q")) }
        assertFailsWith<IllegalArgumentException> { TaskSelectionCodec.read(text.replace("mode\tAUTO", "mode\tNOPE")) }
    }

    @Test
    fun `a huge count throws without allocating`() {
        val text = TaskSelectionCodec.write(sample()).replace("selected\t2", "selected\t2000000000")

        assertFailsWith<IllegalArgumentException> { TaskSelectionCodec.read(text) }
    }

    @Test
    fun `data after the end line throws`() {
        assertFailsWith<IllegalArgumentException> { TaskSelectionCodec.read(TaskSelectionCodec.write(sample()) + "extra\n") }
    }
}
