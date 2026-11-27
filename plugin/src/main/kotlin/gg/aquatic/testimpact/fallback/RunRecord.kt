package gg.aquatic.testimpact.fallback

/**
 * What the last green full run of a Test task left behind.
 *
 * @property toolchain identity of the Java launcher of that run, null when it was not known.
 */
internal data class RunRecord(val toolchain: String?)
