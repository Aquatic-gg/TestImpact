package gg.aquatic.testimpact.escalation.internal

import gg.aquatic.testimpact.escalation.state.RedStateFile
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.testing.Test

/**
 * Whether a Test task owes a full run and nobody named its tests: then it runs every class, whatever the selection
 * says. A state file that cannot be trusted or read counts as owed.
 */
internal object EscalatedState {
    fun isEscalated(test: Test, state: Provider<RegularFile>, baseline: Set<String>): Boolean {
        if (RunShape.userRequested(test, baseline)) return false
        return try {
            RedStateFile.read(state.get().asFile.toPath()).fullRunRequired
        } catch (_: Exception) {
            true
        }
    }
}
