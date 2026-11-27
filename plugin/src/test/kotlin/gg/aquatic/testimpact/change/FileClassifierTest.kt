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
            "tools/scripts/run.sh", "buildSrc/x.kt",
        ).forEach { assertEquals(FileKind.BUILD_LOGIC, kind(it), it) }
    }

    @Test
    fun `build logic directories only count as the first path segment`() {
        assertEquals(FileKind.OTHER, kind("sub/buildSrc/X.java"))
        assertEquals(FileKind.OTHER, kind("sub/gradle/libs.toml"))
    }

    @Test
    fun `build logic roots below a nested settings directory win over its source root`() {
        val nested = FileClassifier(
            sourceRoots = listOf("sub", "sub/src/main/kotlin"),
            resourceRoots = emptyList(),
            buildLogicRoots = listOf("sub/buildSrc", "sub/build-logic", "sub/gradle", "sub/conventions"),
        )

        listOf(
            "sub/buildSrc/src/main/kotlin/A.kt", "sub/build-logic/src/main/kotlin/B.kt",
            "sub/gradle/libs.versions.toml", "sub/conventions/src/main/kotlin/C.kt",
        ).forEach { assertEquals(FileKind.BUILD_LOGIC, nested.classify(it).kind, it) }
        assertEquals(FileKind.SOURCE, nested.classify("sub/src/main/kotlin/D.kt").kind)
    }

    @Test
    fun `a source root beats the build logic folder rules`() {
        val snapshot = classifier.classify("plugin/src/main/kotlin/com/acme/gradle/Plugin.kt")
        assertEquals(FileKind.SOURCE, snapshot.kind)
        assertEquals(FileKind.SOURCE, kind("plugin/src/main/kotlin/buildSrc/A.kt"))
        assertEquals(FileKind.RESOURCE, kind("plugin/src/main/resources/gradle/x.txt"))
    }

    @Test
    fun `file name rules still win inside a source root`() {
        assertEquals(FileKind.BUILD_LOGIC, kind("plugin/src/main/kotlin/a/build.gradle.kts"))
        assertEquals(FileKind.BUILD_LOGIC, kind("plugin/src/main/kotlin/a/gradle.properties"))
    }

    @Test
    fun `a top level resource root does not swallow the gradle folder`() {
        val top = FileClassifier(emptyList(), listOf(""), emptyList())
        assertEquals(FileKind.BUILD_LOGIC, top.classify("gradle/libs.versions.toml").kind)
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
