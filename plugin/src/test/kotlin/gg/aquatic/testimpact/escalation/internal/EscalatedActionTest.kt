package gg.aquatic.testimpact.escalation.internal

import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.api.Task
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EscalatedActionTest {
    @TempDir
    lateinit var temp: Path

    private val fixture by lazy { EscalationTestProject(temp) }

    private class Counting : Action<Task> {
        var calls = 0
        override fun execute(task: Task) {
            calls++
        }
    }

    private fun action(state: String?, delegate: Counting) =
        EscalatedAction(fixture.state(state), fixture.service, emptySet(), ":testImpactQuickTest", delegate)

    @kotlin.test.Test
    fun `an owed full run skips the selection`() {
        val delegate = Counting()
        action(EscalationTestProject.REQUIRED, delegate).execute(fixture.task())
        assertEquals(0, delegate.calls)
    }

    @kotlin.test.Test
    fun `nothing owed runs the selection`() {
        val delegate = Counting()
        action(EscalationTestProject.CLEAN, delegate).execute(fixture.task())
        assertEquals(1, delegate.calls)
    }

    @kotlin.test.Test
    fun `a user request runs the selection although a run is owed`() {
        val delegate = Counting()
        val task = fixture.task()
        task.setTestNameIncludePatterns(listOf("app.OtherTest"))
        action(EscalationTestProject.REQUIRED, delegate).execute(task)
        assertEquals(1, delegate.calls)
    }

    @kotlin.test.Test
    fun `a red quick phase stops the build naming the classes and the way out`() {
        val delegate = Counting()
        val task = fixture.task()
        fixture.service.get().quickFinished(task.path, setOf("app.CalcTest", "app.AdderTest"), false)
        val failure = assertFailsWith<GradleException> { action(EscalationTestProject.REQUIRED, delegate).execute(task) }
        assertTrue("app.AdderTest, app.CalcTest" in failure.message.orEmpty(), failure.message)
        assertTrue("-x testImpactQuickTest" in failure.message.orEmpty(), failure.message)
        assertEquals(0, delegate.calls)
    }

    @kotlin.test.Test
    fun `more than ten classes are counted`() {
        val task = fixture.task()
        fixture.service.get().quickFinished(task.path, (1..12).map { "app.T%02d".format(it) }.toSet(), false)
        val failure = assertFailsWith<GradleException> { action(EscalationTestProject.REQUIRED, Counting()).execute(task) }
        assertTrue("and 2 more" in failure.message.orEmpty(), failure.message)
    }

    @kotlin.test.Test
    fun `a failure without a class also stops the build`() {
        val task = fixture.task()
        fixture.service.get().quickFinished(task.path, emptySet(), true)
        assertFailsWith<GradleException> { action(EscalationTestProject.REQUIRED, Counting()).execute(task) }
    }

    @kotlin.test.Test
    fun `a green quick phase lets the full run start`() {
        val task = fixture.task()
        fixture.service.get().quickFinished(task.path, emptySet(), false)
        action(EscalationTestProject.REQUIRED, Counting()).execute(task)
    }

    @kotlin.test.Test
    fun `a red quick phase does not stop a user request`() {
        val delegate = Counting()
        val task = fixture.task()
        task.setTestNameIncludePatterns(listOf("app.OtherTest"))
        fixture.service.get().quickFinished(task.path, setOf("app.CalcTest"), false)
        action(EscalationTestProject.REQUIRED, delegate).execute(task)
        assertEquals(1, delegate.calls)
    }
}
