package gg.aquatic.testimpact.graph.internal.kotlin

/** The elements of a `kotlin.Metadata` annotation as stored in the class file; [kind] defaults to 1 like the annotation. */
internal class RawKotlinMetadata(
    val kind: Int,
    val data1: List<String>,
    val data2: List<String>,
    val extraString: String?,
)
