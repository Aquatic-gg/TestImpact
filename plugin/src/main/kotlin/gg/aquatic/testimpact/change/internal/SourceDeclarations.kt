package gg.aquatic.testimpact.change.internal

/**
 * What a source file declares at its top level.
 *
 * @property packageName empty for the default package.
 * @property typeNames simple names of the top-level types, nested types belong to their outer type.
 * @property facade simple name of the class that Kotlin generates for top-level functions and properties, if any.
 */
internal data class SourceDeclarations(
    val packageName: String,
    val typeNames: List<String>,
    val facade: String?,
) {
    /** Sorted binary names of every top-level class the file produces. */
    val binaryNames: List<String>
        get() {
            val prefix = if (packageName.isEmpty()) "" else "$packageName."
            return (typeNames + listOfNotNull(facade)).map { prefix + it }.distinct().sorted()
        }
}
