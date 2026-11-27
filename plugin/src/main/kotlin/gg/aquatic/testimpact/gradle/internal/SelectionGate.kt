package gg.aquatic.testimpact.gradle.internal

import gg.aquatic.testimpact.gradle.SelectionInputsService
import org.gradle.api.internal.tasks.testing.filter.DefaultTestFilter
import org.gradle.api.tasks.testing.Test

/**
 * Tells whether the selection may touch a Test task. It may not when the selection task did not run in this build
 * (`-x`), or when a request of the user names the tests: command line include patterns (`--tests`), or include
 * patterns beyond [baseline], the ones the build script set. The test launcher of an IDE or the Tooling API adds
 * those after the project is configured, and such a request wins over the selection.
 */
internal object SelectionGate {
    enum class Verdict { APPLIES, NOT_SELECTED, COMMAND_LINE, LATE_FILTER }

    fun applies(test: Test, inputs: SelectionInputsService, baseline: Set<String>): Boolean =
        verdict(test, inputs, baseline) == Verdict.APPLIES

    fun verdict(test: Test, inputs: SelectionInputsService, baseline: Set<String>): Verdict = when {
        !inputs.hasSelected(test.path) -> Verdict.NOT_SELECTED
        else -> requested(test, baseline) ?: Verdict.APPLIES
    }

    /** An unreadable filter counts as a late filter, so the task runs untouched. */
    private fun requested(test: Test, baseline: Set<String>): Verdict? = try {
        if ((test.filter as? DefaultTestFilter)?.commandLineIncludePatterns?.isNotEmpty() == true) Verdict.COMMAND_LINE
        else if (!baseline.containsAll(test.filter.includePatterns)) Verdict.LATE_FILTER
        else null
    } catch (_: Exception) {
        Verdict.LATE_FILTER
    } catch (_: LinkageError) {
        Verdict.LATE_FILTER
    }
}
