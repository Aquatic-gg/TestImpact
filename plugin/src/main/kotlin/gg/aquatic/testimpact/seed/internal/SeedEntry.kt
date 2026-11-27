package gg.aquatic.testimpact.seed.internal

import java.io.InputStream

/** One file of a root: [name] is relative with forward slashes, [location] names it in a problem message. */
internal class SeedEntry(
    val location: String,
    val name: String,
    val open: () -> InputStream,
) {
    val isClass: Boolean get() = name.endsWith(".class")
}
