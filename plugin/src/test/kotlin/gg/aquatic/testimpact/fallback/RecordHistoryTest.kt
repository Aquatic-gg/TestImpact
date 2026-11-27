package gg.aquatic.testimpact.fallback

import gg.aquatic.testimpact.gradle.SelectionInputsService
import gg.aquatic.testimpact.gradle.TestImpactPlugin
import gg.aquatic.testimpact.gradle.internal.ServiceNames
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.testing.Test
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecordHistoryTest {
    @TempDir
    lateinit var temp: Path

    private val project by lazy { ProjectBuilder.builder().withProjectDir(temp.resolve("p").also(Files::createDirectories).toFile()).build() }

    private val inputs: Provider<SelectionInputsService> by lazy {
        project.plugins.apply(TestImpactPlugin::class.java)
        @Suppress("UNCHECKED_CAST")
        project.gradle.sharedServices.registrations.getByName(ServiceNames.selectionInputs).service as Provider<SelectionInputsService>
    }

    private val marker: Path get() = temp.resolve("history").resolve("test.properties")

    private fun testTask(): Test = project.tasks.findByName("test") as? Test ?: project.tasks.register("test", Test::class.java).get()

    private fun selectionFile(text: String?): Path = temp.resolve("selection").also { if (text != null) Files.writeString(it, text) }

    private fun record(task: Test, selection: Path, ran: Boolean = true, baseline: Set<String> = emptySet()) {
        if (ran) inputs.get().markSelected(task.path)
        val capture = FallbackCapture(marker.toFile(), project.provider { "17|vendor|17.0.9" })
        RecordHistory(project.provider { project.layout.projectDirectory.file(selection.toString()) }, inputs, baseline, capture).execute(task)
    }

    @kotlin.test.Test
    fun `a full selection writes the marker with the launcher`() {
        record(testTask(), selectionFile("testimpact-selection 1\nfull\n"))
        assertEquals(RunRecord("17|vendor|17.0.9"), RunHistory.read(marker))
    }

    @kotlin.test.Test
    fun `a selected or empty run writes nothing`() {
        record(testTask(), selectionFile("testimpact-selection 1\nselected\na.A\n"))
        assertFalse(Files.exists(marker))
        record(testTask(), selectionFile("testimpact-selection 1\nempty\n"))
        assertFalse(Files.exists(marker))
    }

    @kotlin.test.Test
    fun `a task restricted from the command line writes nothing`() {
        val task = testTask()
        task.setTestNameIncludePatterns(listOf("p.CalcTest"))
        record(task, selectionFile("testimpact-selection 1\nfull\n"))
        assertFalse(Files.exists(marker))
    }

    @kotlin.test.Test
    fun `a task whose selection did not run writes nothing`() {
        record(testTask(), selectionFile("testimpact-selection 1\nfull\n"), ran = false)
        assertFalse(Files.exists(marker))
    }

    @kotlin.test.Test
    fun `an unreadable selection writes nothing and does not throw`() {
        record(testTask(), selectionFile("garbage"))
        assertFalse(Files.exists(marker))
        record(testTask(), selectionFile(null))
        assertFalse(Files.exists(marker))
        assertNull(RunHistory.read(marker))
    }

    @kotlin.test.Test
    fun `a marker that cannot be written does not fail the task`() {
        val blocker = Files.writeString(temp.resolve("blocker"), "x")
        val task = testTask()
        inputs.get().markSelected(task.path)
        val capture = FallbackCapture(blocker.resolve("sub").resolve("test.properties").toFile(), project.provider { "17" })
        val selection = selectionFile("testimpact-selection 1\nfull\n")
        RecordHistory(project.provider { project.layout.projectDirectory.file(selection.toString()) }, inputs, emptySet(), capture).execute(task)
        assertTrue(Files.isRegularFile(blocker))
    }
}
