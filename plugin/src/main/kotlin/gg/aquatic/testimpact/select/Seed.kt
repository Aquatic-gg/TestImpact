package gg.aquatic.testimpact.select

/**
 * One changed file expressed as graph entry points.
 *
 * @property origin repo-relative path of the changed file, used to name the cause of a selection.
 * @property classes top-level binary class names the file declares or declared; nested classes follow via the graph.
 * @property sourceKeys package path plus file name (`p/Foo.kt`) that every class compiled from the file shares,
 * which also covers generated facades such as `@file:JvmName` classes.
 */
internal data class Seed(
    val origin: String,
    val classes: List<String>,
    val sourceKeys: List<String>,
)
