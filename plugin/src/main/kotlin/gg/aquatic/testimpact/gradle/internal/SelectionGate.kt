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
    fun applies(test: Test, inputs: SelectionInputsService, baseline: Set<String>): Boolean =
        inputs.hasSelected(test.path) && !requested(test, baseline)

    /** An unreadable filter counts as a request, so the task runs untouched. */
    private fun requested(test: Test, baseline: Set<String>): Boolean = try {
        !baseline.containsAll(test.filter.includePatterns) ||
            (test.filter as? DefaultTestFilter)?.commandLineIncludePatterns?.isNotEmpty() ?: false
    } catch (_: Exception) {
        true
    } catch (_: LinkageError) {
        true
    }
}
