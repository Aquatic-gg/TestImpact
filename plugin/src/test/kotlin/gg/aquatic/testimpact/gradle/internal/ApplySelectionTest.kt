package gg.aquatic.testimpact.gradle.internal

import org.gradle.api.Task
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

    private fun apply(task: Task, file: Path) = ApplySelection(project.provider { project.layout.projectDirectory.file(file.toString()) }).execute(task)

    private fun satisfied(task: Task, file: Path) =
        SelectionNotEmpty(project.provider { project.layout.projectDirectory.file(file.toString()) }).isSatisfiedBy(task)

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
