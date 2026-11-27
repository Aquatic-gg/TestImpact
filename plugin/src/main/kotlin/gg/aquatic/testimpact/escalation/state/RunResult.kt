package gg.aquatic.testimpact.escalation.state

/** How the last recorded run ended; RUNNING stays when the build was stopped during the run. */
internal enum class RunResult(val text: String) {
    RUNNING("running"),
    GREEN("green"),
    RED("red"),
    ;

    companion object {
        fun fromText(text: String): RunResult? = entries.firstOrNull { it.text == text }
    }
}
