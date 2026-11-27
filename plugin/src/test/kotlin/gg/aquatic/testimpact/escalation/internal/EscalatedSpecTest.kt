package gg.aquatic.testimpact.escalation.internal

import org.gradle.api.Task
import org.gradle.api.specs.Spec
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EscalatedSpecTest {
    @TempDir
    lateinit var temp: Path

    private val fixture by lazy { EscalationTestProject(temp) }

    private class Counting(private val answer: Boolean) : Spec<Task> {
        var calls = 0
        override fun isSatisfiedBy(element: Task): Boolean {
            calls++
            return answer
        }
    }

    @kotlin.test.Test
    fun `an owed full run runs without asking the selection`() {
        val delegate = Counting(false)
        assertTrue(EscalatedSpec(fixture.state(EscalationTestProject.REQUIRED), emptySet(), delegate).isSatisfiedBy(fixture.task()))
        assertEquals(0, delegate.calls)
    }

    @kotlin.test.Test
    fun `nothing owed asks the selection`() {
        val delegate = Counting(false)
        assertFalse(EscalatedSpec(fixture.state(EscalationTestProject.CLEAN), emptySet(), delegate).isSatisfiedBy(fixture.task()))
        assertEquals(1, delegate.calls)
    }

    @kotlin.test.Test
    fun `a user request asks the selection although a run is owed`() {
        val delegate = Counting(false)
        val task = fixture.task()
        task.setTestNameIncludePatterns(listOf("app.OtherTest"))
        assertFalse(EscalatedSpec(fixture.state(EscalationTestProject.REQUIRED), emptySet(), delegate).isSatisfiedBy(task))
        assertEquals(1, delegate.calls)
    }

    @kotlin.test.Test
    fun `an unreadable state counts as owed`() {
        val delegate = Counting(false)
        assertTrue(EscalatedSpec(fixture.state("garbage"), emptySet(), delegate).isSatisfiedBy(fixture.task()))
        assertEquals(0, delegate.calls)
    }
}
