package gg.aquatic.testimpact.change.internal

/**
 * @property detail first non-blank stderr line, at most 500 characters, empty when git wrote nothing.
 */
internal class GitResult(
    val exitCode: Int,
    val stdout: ByteArray,
    val detail: String,
)
