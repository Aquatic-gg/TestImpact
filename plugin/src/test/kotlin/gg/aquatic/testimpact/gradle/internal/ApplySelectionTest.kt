package gg.aquatic.testimpact.gradle.internal

import gg.aquatic.testimpact.gradle.SelectionInputsService
import gg.aquatic.testimpact.gradle.TestImpactPlugin
import org.gradle.api.Task
import org.gradle.api.provider.Provider
import org.gradle.api.internal.tasks.testing.filter.DefaultTestFilter
import org.gradle.api.tasks.testing.Test
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApplySelectionTest {
    @TempDir
    lateinit var temp: Path

    private val project by lazy { ProjectBuilder.builder().withProjectDir(temp.resolve("p").also(Files::createDirectories).toFile()).build() }

    private fun testTask(): Test = project.tasks.register("test", Test::class.java).get()

    private fun file(text: String?): Path = temp.resolve("selection").also { if (text != null) Files.writeString(it, text) }

    private val inputs: Provider<SelectionInputsService> by lazy {
        project.plugins.apply(TestImpactPlugin::class.java)
        @Suppress("UNCHECKED_CAST")
        project.gradle.sharedServices.registrations.getByName(ServiceNames.selectionInputs).service as Provider<SelectionInputsService>
    }

    private fun baseline(task: Task): Set<String> = (task as? Test)?.filter?.includePatterns.orEmpty().toSet()

    private fun apply(task: Task, file: Path, ran: Boolean = true, baseline: Set<String> = baseline(task)) {
        if (ran) inputs.get().markSelected(task.path)
        ApplySelection(project.provider { project.layout.projectDirectory.file(file.toString()) }, inputs, baseline)
            .execute(task)
    }

    private fun satisfied(task: Task, file: Path, ran: Boolean = true, baseline: Set<String> = baseline(task)): Boolean {
        if (ran) inputs.get().markSelected(task.path)
        return SelectionNotEmpty(project.provider { project.layout.projectDirectory.file(file.toString()) }, inputs, baseline)
            .isSatisfiedBy(task)
    }

    @kotlin.test.Test
    fun `command line include patterns win over the selection`() {
        val task = testTask()
        task.setTestNameIncludePatterns(listOf("p.CalcTest"))
        val selection = file("testimpact-selection 1\nselected\na.A\n")
        apply(task, selection)
        assertEquals(setOf("p.CalcTest"), (task.filter as DefaultTestFilter).commandLineIncludePatterns)
        assertTrue(task.filter.isFailOnNoMatchingTests)
        assertTrue(satisfied(task, file("testimpact-selection 1\nempty\n")))
    }

    @kotlin.test.Test
    fun `include patterns added after configuration win over the selection`() {
        val task = testTask()
        task.filter.includeTestsMatching("*IT")
        val configured = baseline(task)
        task.filter.includeTest("p.CalcTest", null)
        apply(task, file("testimpact-selection 1\nselected\na.A\n"), baseline = configured)
        assertTrue((task.filter as DefaultTestFilter).commandLineIncludePatterns.isEmpty())
        assertEquals(setOf("*IT", "p.CalcTest"), task.filter.includePatterns)
        assertTrue(satisfied(task, file("testimpact-selection 1\nempty\n"), baseline = configured))
    }

    @kotlin.test.Test
    fun `a selection that is not applied because of a late filter logs one lifecycle line`() {
        val task = testTask()
        val configured = baseline(task)
        task.filter.includeTest("p.CalcTest", null)
        inputs.get().markSelected(task.path)
        val lines = ArrayList<String>()
        val selection = project.provider { project.layout.projectDirectory.file(file("testimpact-selection 1\nselected\na.A\n").toString()) }
        ApplySelection(selection, inputs, configured, lines::add).execute(task)
        assertEquals(1, lines.size)
        assertTrue(lines.single().startsWith("TestImpact ${task.path}:"), lines.single())
    }

    @kotlin.test.Test
    fun `a task whose selection did not run is silent`() {
        val task = testTask()
        val lines = ArrayList<String>()
        val selection = project.provider { project.layout.projectDirectory.file(file("testimpact-selection 1\nselected\na.A\n").toString()) }
        ApplySelection(selection, inputs, baseline(task), lines::add).execute(task)
        assertEquals(emptyList(), lines)
    }

    @kotlin.test.Test
    fun `a task whose selection did not run is neither filtered nor skipped`() {
        val task = testTask()
        apply(task, file("testimpact-selection 1\nselected\na.A\n"), ran = false)
        assertTrue((task.filter as DefaultTestFilter).commandLineIncludePatterns.isEmpty())
        assertTrue(satisfied(task, file("testimpact-selection 1\nempty\n"), ran = false))
    }

    @kotlin.test.Test
    fun `selected classes become include patterns with their nested classes`() {
        val task = testTask()
        apply(task, file("testimpact-selection 1\nselected\na.A\nb.B\n"))
        assertEquals(setOf("a.A", "a.A$*", "b.B", "b.B$*"), (task.filter as DefaultTestFilter).commandLineIncludePatterns)
        assertFalse(task.filter.isFailOnNoMatchingTests)
    }

    @kotlin.test.Test
    fun `the build script filter stays in place`() {
        val task = testTask()
        task.filter.includeTestsMatching("*IT")
        apply(task, file("testimpact-selection 1\nselected\na.A\n"))
        assertEquals(setOf("*IT"), task.filter.includePatterns)
    }

    @kotlin.test.Test
    fun `full empty missing and garbage files leave the task untouched`() {
        for (text in listOf("testimpact-selection 1\nfull\n", "testimpact-selection 1\nempty\n", null, "nonsense")) {
            val task = ProjectBuilder.builder().build().tasks.register("test", Test::class.java).get()
            apply(task, file(text).also { Files.deleteIfExists(it); if (text != null) Files.writeString(it, text) })
            assertTrue((task.filter as DefaultTestFilter).commandLineIncludePatterns.isEmpty(), text)
            assertTrue(task.filter.isFailOnNoMatchingTests, text)
        }
    }

    @kotlin.test.Test
    fun `a task that is not a Test task is ignored`() {
        apply(project.tasks.register("other").get(), file("testimpact-selection 1\nselected\na.A\n"))
    }

    @kotlin.test.Test
    fun `only an empty selection skips the task`() {
        val task = testTask()
        assertFalse(satisfied(task, file("testimpact-selection 1\nempty\n")))
        assertTrue(satisfied(task, file("testimpact-selection 1\nselected\na.A\n")))
        assertTrue(satisfied(task, file("testimpact-selection 1\nfull\n")))
    }

    @kotlin.test.Test
    fun `a missing or garbage file does not skip the task`() {
        val task = testTask()
        assertTrue(satisfied(task, temp.resolve("absent")))
        assertTrue(satisfied(task, file("nonsense")))
    }
}
