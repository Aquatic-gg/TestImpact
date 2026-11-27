package gg.aquatic.testimpact.escalation.state

/** What a recorded test run covered: the red follow-up (QUICK), a narrowed run (PARTIAL) or every class (FULL). */
internal enum class RunPhase(val text: String) {
    QUICK("quick"),
    PARTIAL("partial"),
    FULL("full"),
    ;

    companion object {
        fun fromText(text: String): RunPhase? = entries.firstOrNull { it.text == text }
    }
}
