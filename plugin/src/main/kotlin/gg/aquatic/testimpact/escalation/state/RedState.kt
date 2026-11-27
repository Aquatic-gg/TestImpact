package gg.aquatic.testimpact.escalation.state

import java.util.TreeSet

/**
 * What a Test task remembers between builds: whether a green run of every class is still owed, which classes were
 * red, and how the last run ended. Only a green full run clears the memory; a failure that no class can be named for
 * ([unknownFailure], for example a crashed test JVM) also keeps a full run required.
 */
internal data class RedState(
    val fullRunRequired: Boolean,
    val red: Set<String>,
    val unknownFailure: Boolean,
    val lastPhase: RunPhase?,
    val lastResult: RunResult?,
) {
    /** The state while a run of [phase] is in progress: what was owed stays owed. */
    fun started(phase: RunPhase): RedState = copy(lastPhase = phase, lastResult = RunResult.RUNNING)

    /** The state after a run of [phase] ended with [failures] (outer class names) and maybe a failure without a class. */
    fun finished(phase: RunPhase, failures: Set<String>, unknown: Boolean): RedState {
        val isRed = failures.isNotEmpty() || unknown
        val result = if (isRed) RunResult.RED else RunResult.GREEN
        return when (phase) {
            RunPhase.FULL ->
                if (isRed) RedState(true, sorted(failures), unknown, phase, result)
                else RedState(false, emptySet(), false, phase, result)
            RunPhase.QUICK ->
                if (isRed) RedState(true, sorted(failures), unknownFailure || unknown, phase, result)
                else RedState(true, red, unknownFailure, phase, result)
            RunPhase.PARTIAL ->
                if (isRed) {
                    val (merged, leftOut) = RedStateFile.fit(red + failures)
                    RedState(true, merged, unknownFailure || unknown || leftOut, phase, result)
                } else copy(lastPhase = phase, lastResult = result)
        }
    }

    /** True only for a green run of every class with nothing owed: the one state that counts as a verdict. */
    fun isVerdict(): Boolean =
        !fullRunRequired && lastPhase == RunPhase.FULL && lastResult == RunResult.GREEN

    /** True for the state a damaged file reads as. */
    fun isCorrupt(): Boolean = this === CORRUPT

    companion object {
        /** Nothing remembered: no file yet. */
        val NONE = RedState(false, emptySet(), false, null, null)

        /** A file that cannot be trusted: a full run is required. */
        val CORRUPT = RedState(true, emptySet(), true, null, null)

        private fun sorted(names: Set<String>): Set<String> = TreeSet(names)
    }
}
