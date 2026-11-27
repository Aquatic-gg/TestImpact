package gg.aquatic.testimpact.change

/**
 * One side of a changed file.
 *
 * @property path repo-relative path with `/` separators.
 * @property classes top-level binary class names declared by a source file, sorted. A nested class `N$...` belongs to `N`.
 * @property resourcePath path relative to its resource root, set for resources only.
 * @property classesResolved false when the declared classes could not be determined reliably.
 * @property classesCompiled false when a class of a source file outside the project's own source roots is not on the
 * class path of the task; the selector then checks the file against the task's class graph.
 */
internal data class FileSnapshot(
    val path: String,
    val kind: FileKind,
    val classes: List<String> = emptyList(),
    val resourcePath: String? = null,
    val classesResolved: Boolean = true,
    val classesCompiled: Boolean = true,
)
