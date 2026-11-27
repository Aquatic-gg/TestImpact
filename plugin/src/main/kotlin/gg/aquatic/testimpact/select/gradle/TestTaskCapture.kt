package gg.aquatic.testimpact.select.gradle

import gg.aquatic.testimpact.select.TestFrameworkKind
import gg.aquatic.testimpact.select.TestTaskInput
import gg.aquatic.testimpact.select.gradle.internal.ClasspathPartition
import gg.aquatic.testimpact.select.gradle.internal.ServiceFiles
import gg.aquatic.testimpact.select.internal.EngineCatalog
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.junit.JUnitOptions
import org.gradle.api.tasks.testing.junitplatform.JUnitPlatformOptions
import org.gradle.api.tasks.testing.testng.TestNGOptions
import java.io.File
import java.io.IOException

/**
 * What the build knows about one test task, captured at configuration time. The framework, the test class
 * directories and the classpath are providers read late: `useJUnitPlatform()`, `classpath += ...` or a new
 * `testClassesDirs` may come after the capture. The providers read the task, so the capture holds no project or
 * task field but is not a configuration cache value by itself.
 *
 * Threading: nothing here touches shared state. [resolve] reads files and archives, so call it at execution time.
 */
internal class TestTaskCapture private constructor(
    val taskPath: String,
    val testClassesDirs: Provider<Set<File>>,
    val classpath: Provider<Set<File>>,
    val framework: Provider<TestFrameworkKind>,
    val excludedRoots: Provider<List<File>>,
) {

    /**
     * Reads the classpath, the engines and the global extension registrations of the task.
     *
     * @throws IOException when a service file or archive cannot be read; the caller treats this as an unknown
     * selection.
     */
    @Throws(IOException::class)
    fun resolve(): TestTaskInput {
        val classpathPaths = classpath.get().map(File::toPath)
        val split = ClasspathPartition.split(
            testClassesDirs.get().map(File::toPath),
            classpathPaths,
            excludedRoots.get().map(File::toPath),
        )
        val everything = split.testRoots + classpathPaths
        val engines = ServiceFiles.read(everything, ENGINE_SERVICE)
        val own = split.testRoots + split.otherRoots
        val globals = EngineCatalog.globalServices.flatMap { ServiceFiles.read(own, it) }.toSet()
        return TestTaskInput(taskPath, split.testRoots, split.otherRoots, framework.get(), engines, globals)
    }

    companion object {
        private const val ENGINE_SERVICE = "org.junit.platform.engine.TestEngine"

        fun of(test: Test, providers: ProviderFactory, gradleUserHome: File): TestTaskCapture {
            val framework = providers.provider { test.options }.map { options ->
                when (options) {
                    is JUnitPlatformOptions -> TestFrameworkKind.JUNIT_PLATFORM
                    is JUnitOptions -> TestFrameworkKind.JUNIT4
                    is TestNGOptions -> TestFrameworkKind.TESTNG
                    else -> TestFrameworkKind.OTHER
                }
            }
            val mavenLocal = providers.systemProperty("maven.repo.local").map { File(it) }
                .orElse(providers.systemProperty("user.home").map { File(it, ".m2/repository") })
            val excluded = mavenLocal.map { listOf(gradleUserHome, it) }.orElse(listOf(gradleUserHome))
            val testClassesDirs = providers.provider { test.testClassesDirs.files }
            val classpath = providers.provider { test.classpath.files }
            return TestTaskCapture(test.path, testClassesDirs, classpath, framework, excluded)
        }
    }
}
