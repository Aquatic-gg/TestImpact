package gg.aquatic.testimpact.change

import gg.aquatic.testimpact.change.internal.FileClassifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FileClassifierTest {

    private val classifier = FileClassifier(
        sourceRoots = listOf("plugin/src/main/kotlin", "plugin/src/main/java"),
        resourceRoots = listOf("plugin/src/main/resources"),
        buildLogicRoots = listOf("tools/scripts"),
    )

    private fun kind(path: String) = classifier.classify(path).kind

    @Test
    fun `build logic patterns win`() {
        listOf(
            "build.gradle", "plugin/build.gradle.kts", "gradle.properties", "settings.gradle.kts",
            "settings.gradle", "gradlew", "gradlew.bat", "gradle/libs.versions.toml",
            "gradle/wrapper/gradle-wrapper.properties", "plugin/dependencies.lockfile",
            "buildSrc/src/main/kotlin/A.kt", "build-logic/src/main/kotlin/B.kt",
            "sub/buildSrc/X.java", "tools/scripts/run.sh",
        ).forEach { assertEquals(FileKind.BUILD_LOGIC, kind(it), it) }
    }

    @Test
    fun `build logic beats a source root`() {
        assertEquals(FileKind.BUILD_LOGIC, kind("plugin/src/main/kotlin/buildSrc/A.kt"))
    }

    @Test
    fun `sources are kt and java files under a source root`() {
        assertEquals(FileKind.SOURCE, kind("plugin/src/main/kotlin/a/B.kt"))
        assertEquals(FileKind.SOURCE, kind("plugin/src/main/java/a/B.java"))
        assertEquals(FileKind.OTHER, kind("plugin/src/main/kotlin/a/notes.txt"))
        assertEquals(FileKind.OTHER, kind("other/src/B.kt"))
    }

    @Test
    fun `resources carry their path relative to the root`() {
        val snapshot = classifier.classify("plugin/src/main/resources/a/b.yml")

        assertEquals(FileKind.RESOURCE, snapshot.kind)
        assertEquals("a/b.yml", snapshot.resourcePath)
        assertNull(classifier.classify("plugin/src/main/kotlin/a/B.kt").resourcePath)
    }

    @Test
    fun `the longest root wins for nested roots`() {
        val nested = FileClassifier(
            sourceRoots = listOf("src"),
            resourceRoots = listOf("src/res"),
            buildLogicRoots = emptyList(),
        )

        assertEquals(FileKind.RESOURCE, nested.classify("src/res/A.kt").kind)
        assertEquals(FileKind.SOURCE, nested.classify("src/A.kt").kind)
        assertEquals("A.kt", nested.classify("src/res/A.kt").resourcePath)
    }

    @Test
    fun `a non source file in a source root falls back to the enclosing resource root`() {
        val shared = FileClassifier(listOf("src/a"), listOf("src"), emptyList())

        assertEquals(FileKind.RESOURCE, shared.classify("src/a/data.json").kind)
        assertEquals("a/data.json", shared.classify("src/a/data.json").resourcePath)
    }

    @Test
    fun `backslash and mixed roots and paths give the same result`() {
        val windows = FileClassifier(
            sourceRoots = listOf("plugin\\src/main\\kotlin"),
            resourceRoots = listOf("plugin\\src\\main\\resources\\"),
            buildLogicRoots = emptyList(),
        )

        assertEquals(classifier.classify("plugin/src/main/kotlin/a/B.kt"), windows.classify("plugin\\src\\main\\kotlin\\a\\B.kt"))
        assertEquals(
            classifier.classify("plugin/src/main/resources/a/b.yml"),
            windows.classify("plugin/src/main/resources/a/b.yml"),
        )
    }

    @Test
    fun `the top level root matches everything below it`() {
        val whole = FileClassifier(listOf(""), emptyList(), emptyList())

        assertEquals(FileKind.SOURCE, whole.classify("A.kt").kind)
        assertEquals(FileKind.OTHER, whole.classify("README.md").kind)
    }

    @Test
    fun `everything else is other`() {
        assertEquals(FileKind.OTHER, kind("README.md"))
        assertEquals(FileKind.OTHER, kind("docs/guide.md"))
    }
}
