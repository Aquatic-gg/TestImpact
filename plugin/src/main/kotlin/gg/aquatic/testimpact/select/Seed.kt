package gg.aquatic.testimpact.select

/**
 * One changed file expressed as graph entry points.
 *
 * @property origin repo-relative path of the changed file, used to name the cause of a selection.
 * @property classes top-level binary class names the file declares or declared; nested classes follow via the graph.
 * @property sourceKeys package path plus file name (`p/Foo.kt`) that every class compiled from the file shares,
 * which also covers generated facades such as `@file:JvmName` classes.
 * @property unverified true when the classes are not on the class path of the task; the selector drops the seed when
 * the task's graph has neither a class nor a source file of it, and cannot map the change when it has only part.
 */
internal data class Seed(
    val origin: String,
    val classes: List<String>,
    val sourceKeys: List<String>,
    val unverified: Boolean = false,
)
