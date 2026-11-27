package gg.aquatic.testimpact.fallback

import gg.aquatic.testimpact.change.ChangeDetection
import gg.aquatic.testimpact.change.ChangeStatus
import gg.aquatic.testimpact.change.ChangedFile
import gg.aquatic.testimpact.change.FileKind
import gg.aquatic.testimpact.change.FileSnapshot
import gg.aquatic.testimpact.select.Seeds
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SeedsBackstopTest {

    private fun modified(path: String, kind: FileKind, classes: List<String> = emptyList(), resolved: Boolean = true) =
        FileSnapshot(path, kind, classes, classesResolved = resolved).let { ChangedFile(ChangeStatus.MODIFIED, it, it) }

    private val shapes = listOf(
        modified("build.gradle.kts", FileKind.BUILD_LOGIC),
        modified("buildSrc/src/main/kotlin/X.kt", FileKind.BUILD_LOGIC),
        modified("src/main/resources/x.txt", FileKind.RESOURCE),
        modified("src/main/kotlin/p/A.kt", FileKind.SOURCE, listOf("p.A"), resolved = false),
        modified("src/main/kotlin/p/Types.kt", FileKind.SOURCE),
        modified("src/main/java/p/package-info.java", FileKind.SOURCE),
        modified("tools/Tool.java", FileKind.OTHER),
        modified("tools/Tool.kt", FileKind.OTHER),
        ChangedFile(
            ChangeStatus.RENAMED,
            FileSnapshot("src/main/kotlin/p/Old.kt", FileKind.SOURCE, listOf("p.Old")),
            FileSnapshot("src/main/kotlin/p/New.kt", FileKind.SOURCE, emptyList()),
        ),
        ChangedFile(ChangeStatus.DELETED, FileSnapshot("src/main/kotlin/p/Gone.kt", FileKind.SOURCE), null),
        ChangedFile(ChangeStatus.ADDED, null, FileSnapshot("src/main/kotlin/p/Fresh.kt", FileKind.SOURCE, classesResolved = false)),
    )

    @Test
    fun `the change stage fires for every shape the seed mapping would force`() {
        for (file in shapes) {
            val detection = ChangeDetection.Known("main", "a", "b", listOf(file))
            if (Seeds.from(detection).forced == null) continue
            assertNotNull(FallbackRules.forChanges(detection), "not covered: $file")
        }
    }

    @Test
    fun `the backstop shapes include forced ones`() {
        val forced = shapes.count { Seeds.from(ChangeDetection.Known("main", "a", "b", listOf(it))).forced != null }
        assertTrue(forced >= 8, "only $forced shapes are forced")
    }
}
