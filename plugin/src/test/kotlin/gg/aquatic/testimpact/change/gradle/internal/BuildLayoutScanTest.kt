package gg.aquatic.testimpact.change.gradle.internal

import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BuildLayoutScanTest {

    @TempDir
    lateinit var root: Path

    private fun project(relative: String, script: String = "build.gradle.kts") {
        val dir = root.resolve(relative)
        dir.createDirectories()
        dir.resolve(script).writeText("")
    }

    private fun dir(relative: String) = root.resolve(relative).createDirectories()

    private fun relative(paths: List<Path>) = paths.map { root.relativize(it).toString().replace('\\', '/') }

    @Test
    fun `collects resource and class directories of every project including a nested one`() {
        project("a")
        dir("a/src/main/resources")
        dir("a/src/test/resources")
        dir("a/src/main/java")
        dir("a/build/classes/java/main")
        dir("a/build/classes/kotlin/test")
        project("b", "build.gradle")
        dir("b/src/main/resources")
        project("b/inner")
        dir("b/inner/build/classes/java/main")
        dir("plain/src/main/resources")

        val layout = BuildLayoutScan.scan(root)

        assertEquals(listOf("a/src/main/resources", "a/src/test/resources", "b/src/main/resources"), relative(layout.resourceRoots))
        assertEquals(
            listOf("a/build/classes/java/main", "a/build/classes/kotlin/test", "b/inner/build/classes/java/main"),
            relative(layout.classOutputDirs),
        )
    }

    @Test
    fun `build, src, node_modules and hidden directories are not entered`() {
        project("a")
        project("a/build/nested")
        dir("a/build/nested/src/main/resources")
        project("node_modules/lib")
        dir("node_modules/lib/src/main/resources")
        project(".hidden/x")
        dir(".hidden/x/src/main/resources")
        project("a/src/deep")
        dir("a/src/deep/src/main/resources")

        val layout = BuildLayoutScan.scan(root)

        assertEquals(emptyList(), layout.resourceRoots)
        assertEquals(emptyList(), layout.classOutputDirs)
    }

    @Test
    fun `a directory with its own settings file is a nested build and nothing is collected from it`() {
        project("a")
        dir("a/src/main/resources")
        project("conventions")
        root.resolve("conventions/settings.gradle.kts").writeText("")
        dir("conventions/src/main/resources")
        dir("conventions/build/classes/kotlin/main")
        project("conventions/inner")
        dir("conventions/inner/src/main/resources")

        val layout = BuildLayoutScan.scan(root)

        assertEquals(listOf("a/src/main/resources"), relative(layout.resourceRoots))
        assertEquals(emptyList(), layout.classOutputDirs)
        assertEquals(listOf("conventions"), relative(layout.buildLogicRoots))
    }

    @Test
    fun `the settings file of the root itself does not make it a nested build`() {
        root.resolve("settings.gradle.kts").writeText("")
        project("a")
        dir("a/src/main/resources")

        val layout = BuildLayoutScan.scan(root)

        assertEquals(listOf("a/src/main/resources"), relative(layout.resourceRoots))
        assertEquals(emptyList(), layout.buildLogicRoots)
    }

    @Test
    fun `a directory that cannot be listed is skipped`() {
        val file = root.resolve("not-a-directory")
        file.writeText("")

        assertEquals(emptyList(), BuildLayoutScan.scan(file).resourceRoots)
    }

    @Test
    fun `the scan stops at the directory cap`() {
        for (name in listOf("a", "b", "c")) {
            project(name)
            dir("$name/src/main/resources")
        }

        val capped = BuildLayoutScan.scan(root, maxDirectories = 3)

        assertEquals(listOf("a/src/main/resources", "b/src/main/resources"), relative(capped.resourceRoots))
    }

    @Test
    fun `the scan stops at the depth cap`() {
        project("a/b/c")
        dir("a/b/c/src/main/resources")

        assertEquals(emptyList(), BuildLayoutScan.scan(root, maxDepth = 2).resourceRoots)
        assertEquals(listOf("a/b/c/src/main/resources"), relative(BuildLayoutScan.scan(root, maxDepth = 3).resourceRoots))
    }

    @Test
    fun `a symbolic link is not followed`() {
        val target = Files.createTempDirectory("layout-target")
        try {
            project("a")
            target.resolve("build.gradle.kts").writeText("")
            target.resolve("src/main/resources").createDirectories()
            try {
                Files.createSymbolicLink(root.resolve("link"), target)
            } catch (_: IOException) {
                return
            } catch (_: UnsupportedOperationException) {
                return
            }

            assertTrue(BuildLayoutScan.scan(root).resourceRoots.isEmpty())
        } finally {
            target.toFile().deleteRecursively()
        }
    }
}
