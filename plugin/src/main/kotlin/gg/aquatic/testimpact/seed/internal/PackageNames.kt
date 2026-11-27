package gg.aquatic.testimpact.seed.internal

/** Package arithmetic on dotted names. */
internal object PackageNames {

    /** Package of a binary class name; empty for the default package. */
    fun of(className: String): String = className.substringBeforeLast('.', "")

    /** [packageName] followed by each enclosing package, ending with the empty package. */
    fun withAncestors(packageName: String): List<String> {
        val result = ArrayList<String>()
        var current = packageName
        while (current.isNotEmpty()) {
            result.add(current)
            current = current.substringBeforeLast('.', "")
        }
        result.add("")
        return result
    }

    /** Like [withAncestors] but only packages of at least two segments, the ones a string literal can name reliably. */
    fun namedAncestors(packageName: String): List<String> = withAncestors(packageName).filter { '.' in it }
}
