package gg.aquatic.testimpact.escalation

import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import java.util.concurrent.ConcurrentHashMap

/**
 * Carries the outcome of each quick phase to the Test task that follows it within one build. The quick task and the
 * Test task are ordered by `dependsOn`, so the outcome is in place before it is asked for; it is dropped when the build
 * ends.
 */
internal abstract class EscalationService : BuildService<BuildServiceParameters.None>, AutoCloseable {
    private val outcomes = ConcurrentHashMap<String, QuickOutcome>()

    /** Records how the quick phase of the Test task at [testPath] ended. */
    fun quickFinished(testPath: String, failures: Set<String>, unknown: Boolean) {
        outcomes[testPath] = QuickOutcome(failures.toSortedSet(), unknown)
    }

    /** The outcome of the quick phase of [testPath], or null when it did not run to its end in this build. */
    fun quickOutcome(testPath: String): QuickOutcome? = outcomes[testPath]

    /** The red classes of the quick phase of [testPath]; empty when it was green or did not run. */
    fun quickFailures(testPath: String): Set<String> = outcomes[testPath]?.failures ?: emptySet()

    override fun close() {
        outcomes.clear()
    }
}
