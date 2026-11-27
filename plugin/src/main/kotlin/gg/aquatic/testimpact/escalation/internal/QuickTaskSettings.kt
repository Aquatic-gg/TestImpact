package gg.aquatic.testimpact.escalation.internal

import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.junit.JUnitOptions
import org.gradle.api.tasks.testing.junitplatform.JUnitPlatformOptions
import org.gradle.api.tasks.testing.testng.TestNGOptions
import org.gradle.process.JavaForkOptions

/**
 * Fills the quick task from the user's Test task through public API only. A setting that is missed here can only make
 * the quick phase fail early; the verdict always comes from the user's task.
 *
 * Call it when the quick task is realized, which is when the build graph is made: it reads the test framework of
 * [from], and a `useJUnitPlatform()` the build script adds to [from] after that is not seen by the quick task.
 */
internal object QuickTaskSettings {
    /**
     * @param own the dependencies the quick task must not inherit: the selection task and the quick task itself, which
     * the user's task depends on
     */
    fun copy(from: Test, to: Test, own: Set<Any>) {
        from.copyTo(to as JavaForkOptions)
        to.javaLauncher.set(from.javaLauncher)
        to.testClassesDirs = from.testClassesDirs
        to.classpath = from.classpath
        copyFramework(from, to)
        to.setIncludes(from.includes)
        to.setExcludes(from.excludes)
        to.filter.setIncludePatterns(*from.filter.includePatterns.toTypedArray())
        to.filter.setExcludePatterns(*from.filter.excludePatterns.toTypedArray())
        to.isScanForTestClasses = from.isScanForTestClasses
        to.maxParallelForks = from.maxParallelForks
        to.setForkEvery(from.forkEvery)
        to.modularity.inferModulePath.set(from.modularity.inferModulePath)
        for (dependency in from.dependsOn) {
            if (dependency !in own) to.dependsOn(dependency)
        }
    }

    private fun copyFramework(from: Test, to: Test) {
        when (val options = from.options) {
            is JUnitPlatformOptions -> to.useJUnitPlatform { it.copyFrom(options) }
            is JUnitOptions -> to.useJUnit { it.copyFrom(options) }
            is TestNGOptions -> to.useTestNG { it.copyFrom(options) }
        }
    }
}
