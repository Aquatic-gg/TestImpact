package gg.aquatic.testimpact.escalation.internal

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotEscalatedTest {
    @TempDir
    lateinit var temp: Path

    private val fixture by lazy { EscalationTestProject(temp) }

    @kotlin.test.Test
    fun `an owed full run is never up to date or cached`() {
        assertFalse(NotEscalated(fixture.state(EscalationTestProject.REQUIRED), emptySet()).isSatisfiedBy(fixture.task()))
    }

    @kotlin.test.Test
    fun `no state and a verdict leave the task alone`() {
        assertTrue(NotEscalated(fixture.state(null), emptySet()).isSatisfiedBy(fixture.task()))
        assertTrue(NotEscalated(fixture.state(EscalationTestProject.CLEAN), emptySet()).isSatisfiedBy(fixture.task()))
    }

    @kotlin.test.Test
    fun `a damaged state is owed`() {
        assertFalse(NotEscalated(fixture.state("garbage"), emptySet()).isSatisfiedBy(fixture.task()))
    }

    @kotlin.test.Test
    fun `a user request leaves the task alone`() {
        val task = fixture.task()
        task.setTestNameIncludePatterns(listOf("app.OtherTest"))
        assertTrue(NotEscalated(fixture.state(EscalationTestProject.REQUIRED), emptySet()).isSatisfiedBy(task))
    }

    @kotlin.test.Test
    fun `the quick task is never satisfied`() = assertFalse(NotEscalated.Never.isSatisfiedBy(fixture.task()))
}
