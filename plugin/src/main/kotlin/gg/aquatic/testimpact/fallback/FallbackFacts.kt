package gg.aquatic.testimpact.fallback

/**
 * The state outside the change set that the history rules read.
 *
 * @property history the record of the last green full run, null when there is none or it is unreadable.
 * @property toolchain identity of the Java launcher of the task now, null when it is not known.
 */
internal data class FallbackFacts(val history: RunRecord?, val toolchain: String?)
