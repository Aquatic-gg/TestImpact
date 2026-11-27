package gg.aquatic.testimpact.select

import gg.aquatic.testimpact.change.ChangeDetection
import gg.aquatic.testimpact.change.ChangeStatus
import gg.aquatic.testimpact.change.ChangedFile
import gg.aquatic.testimpact.change.FileKind
import gg.aquatic.testimpact.change.FileSnapshot
import gg.aquatic.testimpact.change.UnknownReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SeedsTest {

    private fun source(path: String, vararg classes: String, resolved: Boolean = true) =
        FileSnapshot(path, FileKind.SOURCE, classes.toList(), classesResolved = resolved)

    private fun known(vararg files: ChangedFile) = ChangeDetection.Known("main", "a", "b", files.toList())

    private fun modified(snapshot: FileSnapshot) = ChangedFile(ChangeStatus.MODIFIED, snapshot, snapshot)

    @Test
    fun `unknown detection forces CHANGES_UNKNOWN`() {
        val set = Seeds.from(ChangeDetection.Unknown(UnknownReason.SHALLOW_CLONE, "shallow"))
        assertEquals(SelectionUnknownReason.CHANGES_UNKNOWN, set.forced?.reason)
        assertEquals(emptyList(), set.seeds)
    }

    @Test
    fun `source file seeds its classes and source key`() {
        val set = Seeds.from(known(modified(source("src/main/kotlin/p/q/Foo.kt", "p.q.Foo", "p.q.FooKt"))))
        assertNull(set.forced)
        assertEquals(
            listOf(Seed("src/main/kotlin/p/q/Foo.kt", listOf("p.q.Foo", "p.q.FooKt"), listOf("p/q/Foo.kt"))),
            set.seeds,
        )
    }

    @Test
    fun `default package source key is the file name`() {
        val set = Seeds.from(known(modified(source("src/Top.java", "Top"))))
        assertEquals(listOf("Top.java"), set.seeds.single().sourceKeys)
    }

    @Test
    fun `deleted file uses the before classes`() {
        val file = ChangedFile(ChangeStatus.DELETED, source("a/Gone.kt", "a.Gone"), null)
        assertEquals(listOf("a.Gone"), Seeds.from(known(file)).seeds.single().classes)
    }

    @Test
    fun `added file uses the after classes`() {
        val file = ChangedFile(ChangeStatus.ADDED, null, source("a/New.kt", "a.New"))
        assertEquals(listOf("a.New"), Seeds.from(known(file)).seeds.single().classes)
    }

    @Test
    fun `rename keeps both sides and both source keys`() {
        val file = ChangedFile(ChangeStatus.RENAMED, source("a/Old.kt", "a.Old"), source("a/New.kt", "a.New"))
        val seed = Seeds.from(known(file)).seeds.single()
        assertEquals(listOf("a.New", "a.Old"), seed.classes)
        assertEquals(listOf("a/New.kt", "a/Old.kt"), seed.sourceKeys)
    }

    @Test
    fun `unresolved after side forces CHANGE_NOT_MAPPED`() {
        val file = ChangedFile(ChangeStatus.MODIFIED, source("a/X.kt", "a.X"), source("a/X.kt", resolved = false))
        assertEquals(SelectionUnknownReason.CHANGE_NOT_MAPPED, Seeds.from(known(file)).forced?.reason)
    }

    @Test
    fun `unresolved before side alone falls back to the after side`() {
        val file = ChangedFile(ChangeStatus.MODIFIED, source("a/X.kt", resolved = false), source("a/X.kt", "a.X"))
        val set = Seeds.from(known(file))
        assertNull(set.forced)
        assertEquals(listOf("a.X"), set.seeds.single().classes)
    }

    @Test
    fun `unresolved deleted file forces`() {
        val file = ChangedFile(ChangeStatus.DELETED, source("a/X.kt", resolved = false), null)
        assertNotNull(Seeds.from(known(file)).forced)
    }

    @Test
    fun `resource and build logic force, other is ignored`() {
        val resource = ChangedFile(
            ChangeStatus.MODIFIED,
            FileSnapshot("src/main/resources/a.properties", FileKind.RESOURCE, resourcePath = "a.properties"),
            FileSnapshot("src/main/resources/a.properties", FileKind.RESOURCE, resourcePath = "a.properties"),
        )
        val build = modified(FileSnapshot("build.gradle.kts", FileKind.BUILD_LOGIC))
        val other = modified(FileSnapshot("README.md", FileKind.OTHER))

        assertEquals("resource change: src/main/resources/a.properties", Seeds.from(known(resource)).forced?.detail)
        assertEquals(SelectionUnknownReason.CHANGE_NOT_MAPPED, Seeds.from(known(build)).forced?.reason)
        val ignored = Seeds.from(known(other))
        assertNull(ignored.forced)
        assertEquals(emptyList(), ignored.seeds)
    }

    @Test
    fun `seeds are sorted and deduplicated and the first problem wins`() {
        val b = modified(source("b/B.kt", "b.B"))
        val a = modified(source("a/A.kt", "a.A"))
        val build = modified(FileSnapshot("z.gradle.kts", FileKind.BUILD_LOGIC))
        val resource = modified(FileSnapshot("m/res.txt", FileKind.RESOURCE, resourcePath = "res.txt"))
        val set = Seeds.from(known(b, a, a, build, resource))
        assertEquals(listOf("a/A.kt", "b/B.kt"), set.seeds.map { it.origin })
        assertEquals("resource change: m/res.txt", set.forced?.detail)
    }
}
