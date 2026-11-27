package gg.aquatic.testimpact.change.internal

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class NestedBuildsTest {

    @TempDir
    lateinit var root: Path

    private fun settings(directory: String, name: String = "settings.gradle.kts") {
        val dir = root.resolve(directory)
        Files.createDirectories(dir)
        Files.writeString(dir.resolve(name), "")
    }

    @Test
    fun `a file inside an included build names the build`() {
        settings("conventions")

        val found = NestedBuilds.containing(root, "", listOf("conventions/src/main/kotlin/A.kt", "app/src/main/java/B.java"))

        assertEquals(listOf("conventions"), found)
    }

    @Test
    fun `the outermost nested build wins and groovy settings count`() {
        settings("tools", "settings.gradle")
        settings("tools/inner")

        assertEquals(listOf("tools"), NestedBuilds.containing(root, "", listOf("tools/inner/src/A.kt")))
    }

    @Test
    fun `the settings directory itself and paths outside it are not nested builds`() {
        settings("build")
        settings("other")

        val found = NestedBuilds.containing(root, "build", listOf("build/settings.gradle.kts", "build/a/B.kt", "other/C.kt"))

        assertEquals(emptyList(), found)
    }
}
