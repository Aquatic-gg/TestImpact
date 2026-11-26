package gg.aquatic.testimpact.change

/**
 * One side of a changed file.
 *
 * @property path repo-relative path with `/` separators.
 * @property classes top-level binary class names declared by a source file, sorted. A nested class `N$...` belongs to `N`.
 * @property resourcePath path relative to its resource root, set for resources only.
 * @property classesResolved false when the declared classes could not be determined reliably.
 */
internal data class FileSnapshot(
    val path: String,
    val kind: FileKind,
    val classes: List<String> = emptyList(),
    val resourcePath: String? = null,
    val classesResolved: Boolean = true,
)
