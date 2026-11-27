package gg.aquatic.testimpact.escalation.internal

import org.gradle.api.file.RegularFile
import org.gradle.api.internal.tasks.testing.filter.DefaultTestFilter
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.testing.Test
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuickPhaseActionTest {
    @TempDir
    lateinit var temp: Path

    private val project by lazy { ProjectBuilder.builder().withProjectDir(temp.resolve("p").also(Files::createDirectories).toFile()).build() }

    private fun task(): Test = project.tasks.register("quick", Test::class.java).get()

    private fun file(name: String, text: String?): Provider<RegularFile> {
        val path = temp.resolve(name)
        if (text != null) Files.writeString(path, text) else Files.deleteIfExists(path)
        return project.provider { project.layout.projectDirectory.file(path.toString()) }
    }

    private val required = "testimpact-escalation 1\nfull-run-required=true\nunknown-failure=false\nlast-phase=full\nlast-result=red\nred=app.CalcTest\n"

    private fun patterns(task: Test) = (task.filter as DefaultTestFilter).commandLineIncludePatterns

    @kotlin.test.Test
    fun `red and selected classes run with nested classes`() {
        val task = task()
        QuickPhaseAction(file("state", required), file("selection", "testimpact-selection 1\nselected\napp.OtherTest\n")).execute(task)
        assertEquals(setOf("app.CalcTest", "app.CalcTest$*", "app.OtherTest", "app.OtherTest$*"), patterns(task))
        assertFalse(task.filter.isFailOnNoMatchingTests)
    }

    @kotlin.test.Test
    fun `a red class that is also selected runs once`() {
        val task = task()
        QuickPhaseAction(file("state", required), file("selection", "testimpact-selection 1\nselected\napp.CalcTest\n")).execute(task)
        assertEquals(setOf("app.CalcTest", "app.CalcTest$*"), patterns(task))
    }

    @kotlin.test.Test
    fun `a full empty missing or unreadable selection runs the red classes only`() {
        for ((index, text) in listOf("testimpact-selection 1\nfull\n", "testimpact-selection 1\nempty\n", null, "garbage").withIndex()) {
            val task = project.tasks.register("quick$index", Test::class.java).get()
            QuickPhaseAction(file("state", required), file("selection$index", text)).execute(task)
            assertEquals(setOf("app.CalcTest", "app.CalcTest$*"), patterns(task), "selection $text")
        }
    }

    @kotlin.test.Test
    fun `nothing to run leaves the task untouched`() {
        val task = task()
        QuickPhaseAction(file("state", null), file("selection", null)).execute(task)
        assertTrue(patterns(task).isEmpty())
        assertTrue(task.filter.isFailOnNoMatchingTests)
    }

    @kotlin.test.Test
    fun `the action leaves ignore failures as the wiring set it`() {
        val ignored = task()
        ignored.ignoreFailures = true
        QuickPhaseAction(file("state", required), file("selection", null)).execute(ignored)
        assertTrue(ignored.ignoreFailures)
        val named = project.tasks.register("named", Test::class.java).get()
        named.ignoreFailures = false
        QuickPhaseAction(file("state", required), file("selection", null)).execute(named)
        assertFalse(named.ignoreFailures)
    }
}
