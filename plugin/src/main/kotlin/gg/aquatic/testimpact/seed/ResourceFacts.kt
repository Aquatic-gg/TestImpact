package gg.aquatic.testimpact.seed

/**
 * What the seed analysis keeps of one resource file.
 *
 * - [path]: path relative to its root with forward slashes, no leading slash.
 * - [tokens]: sorted, distinct dotted identifier tokens found in the bytes; empty unless [descriptor].
 * - [descriptor]: the file was content-scanned because it is under `META-INF/` or has a text extension.
 */
internal data class ResourceFacts(
    val path: String,
    val tokens: List<String>,
    val descriptor: Boolean,
)
