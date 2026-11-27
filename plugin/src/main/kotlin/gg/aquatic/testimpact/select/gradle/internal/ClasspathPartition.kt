package gg.aquatic.testimpact.select.gradle.internal

import java.nio.file.Files
import java.nio.file.Path

/**
 * Splits a test runtime classpath into the task's own test classes and everything else worth scanning. Only jars below
 * an excluded root (Gradle user home, Maven local repository) are dropped: those are resolved external libraries.
 * Directories and every other jar, including project jars and file dependencies, stay in the graph.
 */
internal object ClasspathPartition {

    fun split(
        testClassesDirs: Collection<Path>,
        classpath: Collection<Path>,
        excludedRoots: Collection<Path>,
    ): ClasspathSplit {
        val testRoots = testClassesDirs.map(::normalise).filter { Files.isDirectory(it) }.distinct()
        val tests = testRoots.toSet()
        val excluded = excludedRoots.map(::normalise)
        val others = classpath.map(::normalise)
            .filter { it !in tests }
            .filter { Files.isDirectory(it) || excluded.none(it::startsWith) }
            .distinct()
        return ClasspathSplit(testRoots, others)
    }

    private fun normalise(path: Path): Path = path.toAbsolutePath().normalize()
}
