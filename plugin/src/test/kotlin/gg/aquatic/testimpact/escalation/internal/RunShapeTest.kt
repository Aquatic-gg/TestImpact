package gg.aquatic.testimpact.escalation.internal

import gg.aquatic.testimpact.escalation.state.RunPhase
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RunShapeTest {
    @TempDir
    lateinit var temp: Path

    private val fixture by lazy { EscalationTestProject(temp) }

    @kotlin.test.Test
    fun `an untouched task is full`() {
        val task = fixture.task()
        assertTrue(RunShape.isFull(task, emptySet()))
        assertFalse(RunShape.userRequested(task, emptySet()))
        assertEquals(RunPhase.FULL, RunShape.phaseOf(task, emptySet()))
    }

    @kotlin.test.Test
    fun `command line patterns are a user request`() {
        val task = fixture.task()
        task.setTestNameIncludePatterns(listOf("app.CalcTest"))
        assertTrue(RunShape.userRequested(task, emptySet()))
        assertEquals(RunPhase.PARTIAL, RunShape.phaseOf(task, emptySet()))
    }

    @kotlin.test.Test
    fun `include patterns of the build script are not a request`() {
        val task = fixture.task()
        task.filter.includeTestsMatching("app.*")
        assertFalse(RunShape.userRequested(task, setOf("app.*")))
    }

    @kotlin.test.Test
    fun `include patterns added later are a request`() {
        val task = fixture.task()
        task.filter.includeTestsMatching("app.OtherTest")
        assertTrue(RunShape.userRequested(task, emptySet()))
    }
}
