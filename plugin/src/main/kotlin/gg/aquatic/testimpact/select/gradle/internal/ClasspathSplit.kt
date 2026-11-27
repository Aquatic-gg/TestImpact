package gg.aquatic.testimpact.select.gradle.internal

import java.nio.file.Path

/**
 * @property testRoots existing class directories of the test task, in the order Gradle lists them.
 * @property otherRoots every other classpath entry that is scanned, in classpath order.
 */
internal data class ClasspathSplit(
    val testRoots: List<Path>,
    val otherRoots: List<Path>,
)
