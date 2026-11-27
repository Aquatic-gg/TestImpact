package gg.aquatic.testimpact.escalation.internal

import gg.aquatic.testimpact.escalation.state.RunPhase
import org.gradle.api.internal.tasks.testing.filter.DefaultTestFilter
import org.gradle.api.tasks.testing.Test

/**
 * What a Test task is about to run. It runs every class when no include pattern came through the command line channel
 * (`--tests`, which the selection also uses) and the include patterns are only those the build script set ([baseline]).
 * An unreadable filter counts as narrowed, so a green run never clears the memory by mistake.
 */
internal object RunShape {
    fun isFull(test: Test, baseline: Set<String>): Boolean = try {
        (test.filter as? DefaultTestFilter)?.commandLineIncludePatterns.isNullOrEmpty() &&
            baseline.containsAll(test.filter.includePatterns)
    } catch (_: Exception) {
        false
    } catch (_: LinkageError) {
        false
    }

    fun phaseOf(test: Test, baseline: Set<String>): RunPhase = if (isFull(test, baseline)) RunPhase.FULL else RunPhase.PARTIAL
}
