package gg.aquatic.testimpact.change

import gg.aquatic.testimpact.change.internal.RepoPaths
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RepoPathsTest {

    private lateinit var root: Path

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("repopaths").toRealPath()
    }

    @AfterTest
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `existing files become slash separated relative paths`() {
        val file = root.resolve("a").resolve("b").createDirectories().resolve("C.kt").createFile()

        assertEquals("a/b/C.kt", RepoPaths(root).relativize(file))
    }

    @Test
    fun `missing paths are resolved lexically`() {
        val missing = root.resolve("src").resolve("..").resolve("gone").resolve("D.kt")

        assertEquals("gone/D.kt", RepoPaths(root).relativize(missing))
    }

    @Test
    fun `the top level itself is the empty string`() {
        assertEquals("", RepoPaths(root).relativize(root))
        assertEquals("", RepoPaths(root).relativize(root.resolve("x").resolve("..")))
    }

    @Test
    fun `paths outside the repo are ignored`() {
        val sibling = root.resolveSibling(root.fileName.toString() + "-other")

        assertNull(RepoPaths(root).relativize(sibling))
        assertNull(RepoPaths(root).relativize(root.resolve("..")))
    }

    @Test
    fun `a relative top level is made absolute`() {
        val file = root.resolve("E.kt").createFile()

        assertEquals("E.kt", RepoPaths(root.resolve("x").resolve("..")).relativize(file))
    }
}
