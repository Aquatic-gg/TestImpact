package gg.aquatic.testimpact.escalation.internal

import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.junit.JUnitOptions
import org.gradle.api.tasks.testing.junitplatform.JUnitPlatformOptions
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class QuickTaskSettingsTest {
    @TempDir
    lateinit var temp: Path

    private val project by lazy { ProjectBuilder.builder().withProjectDir(temp.resolve("p").also(Files::createDirectories).toFile()).build() }

    private fun pair(): Pair<Test, Test> = project.tasks.register("test", Test::class.java).get() to project.tasks.register("quick", Test::class.java).get()

    @kotlin.test.Test
    fun `fork options are copied`() {
        val (user, quick) = pair()
        user.jvmArgs("-Xss2m")
        user.systemProperty("a.b", "c")
        user.environment("TI_ENV", "1")
        user.maxHeapSize = "256m"
        user.maxParallelForks = 3
        user.setForkEvery(7L)
        QuickTaskSettings.copy(user, quick, emptySet())
        assertEquals(listOf("-Xss2m"), quick.jvmArgs)
        assertEquals("c", quick.systemProperties["a.b"])
        assertEquals("1", quick.environment["TI_ENV"])
        assertEquals("256m", quick.maxHeapSize)
        assertEquals(3, quick.maxParallelForks)
        assertEquals(7L, quick.forkEvery)
    }

    @kotlin.test.Test
    fun `junit platform tags are copied`() {
        val (user, quick) = pair()
        user.useJUnitPlatform {
            it.includeTags("fast")
            it.excludeTags("slow")
        }
        QuickTaskSettings.copy(user, quick, emptySet())
        val options = assertIs<JUnitPlatformOptions>(quick.options)
        assertEquals(setOf("fast"), options.includeTags)
        assertEquals(setOf("slow"), options.excludeTags)
    }

    @kotlin.test.Test
    fun `junit 4 options are copied`() {
        val (user, quick) = pair()
        user.useJUnit { it.includeCategories("a.Fast") }
        QuickTaskSettings.copy(user, quick, emptySet())
        assertEquals(setOf("a.Fast"), assertIs<JUnitOptions>(quick.options).includeCategories)
    }

    @kotlin.test.Test
    fun `includes excludes and filter patterns are copied`() {
        val (user, quick) = pair()
        user.setIncludes(listOf("**/A*"))
        user.setExcludes(listOf("**/Slow*"))
        user.filter.includeTestsMatching("*Unit")
        user.filter.excludeTestsMatching("*Flaky")
        user.isScanForTestClasses = false
        QuickTaskSettings.copy(user, quick, emptySet())
        assertEquals(setOf("**/A*"), quick.includes)
        assertEquals(setOf("**/Slow*"), quick.excludes)
        assertEquals(setOf("*Unit"), quick.filter.includePatterns)
        assertEquals(setOf("*Flaky"), quick.filter.excludePatterns)
        assertFalse(quick.isScanForTestClasses)
    }

    @kotlin.test.Test
    fun `classes and classpath are the same files`() {
        val (user, quick) = pair()
        val classes = project.files(temp.resolve("classes").toFile())
        val libs = project.files(temp.resolve("lib.jar").toFile())
        user.testClassesDirs = classes
        user.classpath = libs
        QuickTaskSettings.copy(user, quick, emptySet())
        assertEquals(classes.files, quick.testClassesDirs.files)
        assertEquals(libs.files, quick.classpath.files)
    }

    @kotlin.test.Test
    fun `user dependencies are copied but not the own ones`() {
        val startDb = project.tasks.register("startDb")
        val select: TaskProvider<*> = project.tasks.register("select")
        val (user, quick) = pair()
        val quickProvider = project.tasks.named("quick")
        user.dependsOn(startDb, select, quickProvider)
        QuickTaskSettings.copy(user, quick, setOf(select, quickProvider))
        val resolved = quick.taskDependencies.getDependencies(quick).map { it.name }
        assertTrue("startDb" in resolved, resolved.toString())
        assertFalse("select" in resolved, resolved.toString())
        assertFalse("quick" in resolved, resolved.toString())
    }

    @kotlin.test.Test
    fun `a junit platform chosen on the user task after the copy is not seen`() {
        val (user, quick) = pair()
        QuickTaskSettings.copy(user, quick, emptySet())
        user.useJUnitPlatform()
        assertIs<JUnitPlatformOptions>(user.options)
        assertFalse(quick.options is JUnitPlatformOptions)
    }
}
