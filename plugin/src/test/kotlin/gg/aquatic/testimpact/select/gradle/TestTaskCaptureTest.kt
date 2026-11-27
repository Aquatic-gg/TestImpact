package gg.aquatic.testimpact.select.gradle

import gg.aquatic.testimpact.select.Forced
import gg.aquatic.testimpact.select.Seed
import gg.aquatic.testimpact.select.SeedSet
import gg.aquatic.testimpact.select.Selection
import gg.aquatic.testimpact.select.SelectionUnknownReason
import gg.aquatic.testimpact.select.TestFrameworkKind
import gg.aquatic.testimpact.select.TestSelector
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.testing.Test
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TestTaskCaptureTest {

    @TempDir
    lateinit var dir: Path

    private fun project(): Project {
        val project = ProjectBuilder.builder().withProjectDir(dir.resolve("project").toFile().also { it.mkdirs() }).build()
        project.pluginManager.apply("java")
        return project
    }

    private fun testTask(project: Project): Test = project.tasks.named("test", Test::class.java).get()

    private fun capture(project: Project, test: Test) =
        TestTaskCapture.of(test, project.providers, project.objects, dir.resolve("gradle-home").toFile())

    @kotlin.test.Test
    fun `junit 4 is the default framework`() {
        val project = project()
        assertEquals(TestFrameworkKind.JUNIT4, capture(project, testTask(project)).framework.get())
    }

    @kotlin.test.Test
    fun `the framework is read late`() {
        val project = project()
        val test = testTask(project)
        val capture = capture(project, test)
        test.useJUnitPlatform()
        assertEquals(TestFrameworkKind.JUNIT_PLATFORM, capture.framework.get())
    }

    @kotlin.test.Test
    fun `classpath and test class directories reassigned after the capture are read`() {
        val project = project()
        val test = testTask(project)
        val capture = capture(project, test)
        val tests = Files.createDirectories(dir.resolve("classes/test"))
        val main = Files.createDirectories(dir.resolve("classes/main"))
        test.testClassesDirs = project.files(tests.toFile())
        test.classpath = project.files(tests.toFile())
        test.classpath += project.files(main.toFile())
        test.useJUnitPlatform()

        val input = capture.resolve()

        assertEquals(listOf(tests.toAbsolutePath().normalize()), input.testRoots)
        assertTrue(main.toAbsolutePath().normalize() in input.otherRoots)
    }

    @kotlin.test.Test
    fun `testng is recognised`() {
        val project = project()
        val test = testTask(project)
        val capture = capture(project, test)
        test.useTestNG()
        assertEquals(TestFrameworkKind.TESTNG, capture.framework.get())
    }

    @kotlin.test.Test
    fun `the capture holds no project or task`() {
        val forbidden = listOf(Project::class.java, Task::class.java)
        val fields = TestTaskCapture::class.java.declaredFields
        assertTrue(fields.isNotEmpty())
        for (field in fields) {
            assertFalse(forbidden.any { it.isAssignableFrom(field.type) }, "field ${field.name} holds ${field.type}")
        }
    }

    @kotlin.test.Test
    fun `excluded roots include the gradle user home`() {
        val project = project()
        val capture = capture(project, testTask(project))
        assertTrue(dir.resolve("gradle-home").toFile() in capture.excludedRoots.get())
    }

    @kotlin.test.Test
    fun `resolve reads engines and global registrations`() {
        val project = project()
        val test = testTask(project)
        val tests = Files.createDirectories(dir.resolve("classes/test"))
        val main = Files.createDirectories(dir.resolve("classes/main"))
        val services = Files.createDirectories(main.resolve("META-INF/services"))
        Files.writeString(services.resolve("org.junit.platform.engine.TestEngine"), "x.FakeEngine\n")
        Files.writeString(services.resolve("org.junit.platform.launcher.TestExecutionListener"), "x.Listener\n")
        test.testClassesDirs = project.files(tests.toFile())
        test.classpath = project.files(tests.toFile(), main.toFile(), File(dir.toFile(), "absent.jar"))
        test.useJUnitPlatform()

        val input = capture(project, test).resolve()

        assertEquals(":test", input.taskPath)
        assertEquals(TestFrameworkKind.JUNIT_PLATFORM, input.framework)
        assertEquals(listOf(tests.toAbsolutePath().normalize()), input.testRoots)
        assertTrue(main.toAbsolutePath().normalize() in input.otherRoots)
        assertEquals(listOf("x.FakeEngine"), input.engines)
        assertEquals(setOf("x.Listener"), input.globalClasses)
    }

    @kotlin.test.Test
    fun `an unreadable classpath archive makes the capture overload unknown`() {
        val project = project()
        val test = testTask(project)
        val tests = Files.createDirectories(dir.resolve("classes/test"))
        val corrupt = dir.resolve("corrupt.jar")
        Files.writeString(corrupt, "not a zip")
        test.testClassesDirs = project.files(tests.toFile())
        test.classpath = project.files(tests.toFile(), corrupt.toFile())
        test.useJUnitPlatform()
        val seeds = SeedSet(listOf(Seed("change.kt", listOf("m.Core"), emptyList())))

        val result = TestSelector.select(capture(project, test), seeds)

        val unknown = assertIs<Selection.Unknown>(result)
        assertEquals(SelectionUnknownReason.ANALYSIS_ERROR, unknown.reason)
        assertEquals(":test", unknown.taskPath)
    }

    @kotlin.test.Test
    fun `forced seeds are unknown without reading the classpath`() {
        val project = project()
        val test = testTask(project)
        test.classpath = project.files(dir.resolve("corrupt.jar").toFile())
        val forced = SeedSet(emptyList(), Forced(SelectionUnknownReason.CHANGES_UNKNOWN, "no git"))

        val unknown = assertIs<Selection.Unknown>(TestSelector.select(capture(project, test), forced))

        assertEquals(SelectionUnknownReason.CHANGES_UNKNOWN, unknown.reason)
    }
}
