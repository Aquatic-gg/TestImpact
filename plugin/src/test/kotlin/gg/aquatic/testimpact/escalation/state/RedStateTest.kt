package gg.aquatic.testimpact.escalation.state

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RedStateTest {
    private val owed = RedState(true, setOf("a.A"), false, RunPhase.FULL, RunResult.RED)

    @Test
    fun `full green clears everything`() {
        val state = owed.copy(unknownFailure = true).finished(RunPhase.FULL, emptySet(), false)
        assertEquals(RedState(false, emptySet(), false, RunPhase.FULL, RunResult.GREEN), state)
        assertTrue(state.isVerdict())
    }

    @Test
    fun `full red replaces the red set and requires a full run`() {
        val state = RedState.NONE.finished(RunPhase.FULL, setOf("b.B", "a.A"), false)
        assertEquals(RedState(true, setOf("a.A", "b.B"), false, RunPhase.FULL, RunResult.RED), state)
        assertEquals(listOf("a.A", "b.B"), state.red.toList())
        assertFalse(state.isVerdict())
        assertEquals(setOf("c.C"), owed.finished(RunPhase.FULL, setOf("c.C"), false).red)
    }

    @Test
    fun `quick green keeps the red classes and the requirement`() {
        val state = owed.finished(RunPhase.QUICK, emptySet(), false)
        assertEquals(RedState(true, setOf("a.A"), false, RunPhase.QUICK, RunResult.GREEN), state)
        assertFalse(state.isVerdict())
    }

    @Test
    fun `quick red replaces the red set`() {
        val state = owed.finished(RunPhase.QUICK, setOf("b.B"), false)
        assertEquals(RedState(true, setOf("b.B"), false, RunPhase.QUICK, RunResult.RED), state)
    }

    @Test
    fun `partial red adds to the red set`() {
        val state = owed.finished(RunPhase.PARTIAL, setOf("b.B"), false)
        assertEquals(RedState(true, setOf("a.A", "b.B"), false, RunPhase.PARTIAL, RunResult.RED), state)
    }

    @Test
    fun `partial red requires a full run even when nothing was owed`() {
        val state = RedState.NONE.finished(RunPhase.PARTIAL, setOf("b.B"), false)
        assertTrue(state.fullRunRequired)
    }

    @Test
    fun `partial green keeps red and requirement`() {
        assertEquals(
            RedState(true, setOf("a.A"), false, RunPhase.PARTIAL, RunResult.GREEN),
            owed.finished(RunPhase.PARTIAL, emptySet(), false),
        )
        val clear = RedState(false, emptySet(), false, RunPhase.FULL, RunResult.GREEN)
        val after = clear.finished(RunPhase.PARTIAL, emptySet(), false)
        assertFalse(after.fullRunRequired)
        assertFalse(after.isVerdict())
    }

    @Test
    fun `an unknown failure requires a full run in every phase`() {
        for (phase in RunPhase.entries) {
            val state = RedState.NONE.finished(phase, emptySet(), true)
            assertTrue(state.fullRunRequired, "$phase")
            assertTrue(state.unknownFailure, "$phase")
            assertEquals(RunResult.RED, state.lastResult, "$phase")
            assertFalse(state.isVerdict(), "$phase")
        }
    }

    @Test
    fun `started keeps red and the requirement and is no verdict`() {
        val state = owed.started(RunPhase.QUICK)
        assertEquals(RedState(true, setOf("a.A"), false, RunPhase.QUICK, RunResult.RUNNING), state)
        val clear = RedState(false, emptySet(), false, RunPhase.FULL, RunResult.GREEN)
        assertTrue(clear.isVerdict())
        assertFalse(clear.started(RunPhase.FULL).isVerdict())
    }

    @Test
    fun `none and corrupt`() {
        assertFalse(RedState.NONE.fullRunRequired)
        assertFalse(RedState.NONE.isVerdict())
        assertTrue(RedState.CORRUPT.fullRunRequired)
        assertTrue(RedState.CORRUPT.unknownFailure)
        assertTrue(RedState.CORRUPT.isCorrupt())
        assertFalse(RedState.NONE.isCorrupt())
    }
}
