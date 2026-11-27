package gg.aquatic.testimpact.escalation

/** How the quick phase of one Test task ended in this build: the red outer classes, and a failure without a class. */
internal data class QuickOutcome(val failures: Set<String>, val unknown: Boolean) {
    val red: Boolean get() = failures.isNotEmpty() || unknown
}
