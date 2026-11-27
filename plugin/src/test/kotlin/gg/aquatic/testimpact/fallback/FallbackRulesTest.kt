package gg.aquatic.testimpact.fallback

import gg.aquatic.testimpact.change.ChangeDetection
import gg.aquatic.testimpact.change.ChangeStatus
import gg.aquatic.testimpact.change.ChangedFile
import gg.aquatic.testimpact.change.FileKind
import gg.aquatic.testimpact.change.FileSnapshot
import gg.aquatic.testimpact.change.UnknownReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FallbackRulesTest {

    private fun file(path: String, kind: FileKind, classes: List<String> = emptyList(), resolved: Boolean = true): ChangedFile {
        val side = FileSnapshot(path, kind, classes, classesResolved = resolved)
        return ChangedFile(ChangeStatus.MODIFIED, side, side)
    }

    private fun known(vararg files: ChangedFile) = ChangeDetection.Known("main", "a", "b", files.toList())

    private fun ruleOf(vararg files: ChangedFile): FullRunRule? = FallbackRules.forChanges(known(*files))?.rule

    @Test
    fun `dependency files at any depth force dependency-change`() {
        assertEquals(FullRunRule.DEPENDENCY_CHANGE, ruleOf(file("gradle/libs.versions.toml", FileKind.BUILD_LOGIC)))
        assertEquals(FullRunRule.DEPENDENCY_CHANGE, ruleOf(file("sub/gradle.lockfile", FileKind.BUILD_LOGIC)))
        assertEquals(FullRunRule.DEPENDENCY_CHANGE, ruleOf(file("gradle/verification-metadata.xml", FileKind.BUILD_LOGIC)))
        assertEquals(FullRunRule.DEPENDENCY_CHANGE, ruleOf(file("gradle/dependency-locks/x.lockfile", FileKind.BUILD_LOGIC)))
    }

    @Test
    fun `a rename away from a lockfile counts through its old side`() {
        val renamed = ChangedFile(
            ChangeStatus.RENAMED,
            FileSnapshot("gradle.lockfile", FileKind.BUILD_LOGIC),
            FileSnapshot("docs/old.txt", FileKind.OTHER),
        )
        assertEquals(FullRunRule.DEPENDENCY_CHANGE, ruleOf(renamed))
    }

    @Test
    fun `toolchain files win over build logic`() {
        assertEquals(FullRunRule.TOOLCHAIN_CHANGE, ruleOf(file("gradle/wrapper/gradle-wrapper.properties", FileKind.BUILD_LOGIC)))
        assertEquals(FullRunRule.TOOLCHAIN_CHANGE, ruleOf(file("gradle/gradle-daemon-jvm.properties", FileKind.BUILD_LOGIC)))
        assertEquals(FullRunRule.TOOLCHAIN_CHANGE, ruleOf(file(".sdkmanrc", FileKind.OTHER)))
        assertEquals(FullRunRule.TOOLCHAIN_CHANGE, ruleOf(file(".java-version", FileKind.OTHER)))
        assertEquals(FullRunRule.TOOLCHAIN_CHANGE, ruleOf(file(".tool-versions", FileKind.OTHER)))
        assertEquals(FullRunRule.TOOLCHAIN_CHANGE, ruleOf(file("mise.toml", FileKind.OTHER)))
        assertEquals(
            FullRunRule.TOOLCHAIN_CHANGE,
            ruleOf(file("build.gradle.kts", FileKind.BUILD_LOGIC), file(".mise.toml", FileKind.OTHER)),
        )
    }

    @Test
    fun `other build logic forces build-logic`() {
        assertEquals(FullRunRule.BUILD_LOGIC, ruleOf(file("build.gradle.kts", FileKind.BUILD_LOGIC)))
        assertEquals(FullRunRule.BUILD_LOGIC, ruleOf(file("buildSrc/src/main/kotlin/X.kt", FileKind.BUILD_LOGIC)))
        assertEquals(FullRunRule.BUILD_LOGIC, ruleOf(file("gradle.properties", FileKind.BUILD_LOGIC)))
    }

    @Test
    fun `files the plugin cannot map force unknown-file-type`() {
        assertEquals(FullRunRule.UNKNOWN_FILE_TYPE, ruleOf(file("src/main/proto/a.proto", FileKind.OTHER)))
        assertEquals(FullRunRule.UNKNOWN_FILE_TYPE, ruleOf(file("src/test/data/x.json", FileKind.OTHER)))
        assertEquals(FullRunRule.UNKNOWN_FILE_TYPE, ruleOf(file("module/src/main/groovy/X.groovy", FileKind.OTHER)))
        assertEquals(FullRunRule.UNKNOWN_FILE_TYPE, ruleOf(file("scripts/Tool.java", FileKind.OTHER)))
    }

    @Test
    fun `files outside any source tree are ignored`() {
        assertNull(ruleOf(file("README.md", FileKind.OTHER), file("docs/a.md", FileKind.OTHER), file(".github/ci.yml", FileKind.OTHER)))
        assertNull(ruleOf(file("src/README.md", FileKind.OTHER)))
    }

    @Test
    fun `a mapped source file forces nothing`() {
        assertNull(ruleOf(file("src/main/kotlin/p/A.kt", FileKind.SOURCE, listOf("p.A"))))
    }

    @Test
    fun `a resource forces resource-change`() {
        assertEquals(FullRunRule.RESOURCE_CHANGE, ruleOf(file("src/main/resources/x.txt", FileKind.RESOURCE)))
    }

    @Test
    fun `a source file without resolved or any classes forces no-index`() {
        assertEquals(FullRunRule.NO_INDEX, ruleOf(file("src/main/kotlin/p/A.kt", FileKind.SOURCE, listOf("p.A"), resolved = false)))
        assertEquals(FullRunRule.NO_INDEX, ruleOf(file("src/main/java/p/package-info.java", FileKind.SOURCE)))
    }

    @Test
    fun `the strongest rule wins whatever the path order`() {
        val files = listOf(
            file("a/Z.kt", FileKind.SOURCE, resolved = false),
            file("b/x.txt", FileKind.RESOURCE),
            file("c/build.gradle.kts", FileKind.BUILD_LOGIC),
            file("d/libs.versions.toml", FileKind.BUILD_LOGIC),
        )
        assertEquals(FullRunRule.DEPENDENCY_CHANGE, FallbackRules.forChanges(known(*files.toTypedArray()))?.rule)
        assertEquals(FullRunRule.DEPENDENCY_CHANGE, FallbackRules.forChanges(known(*files.reversed().toTypedArray()))?.rule)
    }

    @Test
    fun `the detail names the first path and the number of others`() {
        val one = FallbackRules.forChanges(known(file("b.gradle.kts", FileKind.BUILD_LOGIC)))
        assertEquals("b.gradle.kts", one?.detail)
        val verdict = FallbackRules.forChanges(
            known(
                file("z/build.gradle.kts", FileKind.BUILD_LOGIC),
                file("a/build.gradle.kts", FileKind.BUILD_LOGIC),
                file("m/build.gradle.kts", FileKind.BUILD_LOGIC),
            ),
        )
        assertNotNull(verdict)
        assertEquals("a/build.gradle.kts and 2 more", verdict.detail)
        assertTrue(verdict.changeWide)
    }

    @Test
    fun `every detection failure maps to no-base or analysis-error`() {
        val expected = mapOf(
            UnknownReason.NOT_A_REPOSITORY to FullRunRule.NO_BASE,
            UnknownReason.GIT_UNAVAILABLE to FullRunRule.NO_BASE,
            UnknownReason.GIT_FAILED to FullRunRule.ANALYSIS_ERROR,
            UnknownReason.NO_HEAD_COMMIT to FullRunRule.NO_BASE,
            UnknownReason.DETACHED_HEAD to FullRunRule.NO_BASE,
            UnknownReason.SHALLOW_CLONE to FullRunRule.NO_BASE,
            UnknownReason.BASE_REF_NOT_FOUND to FullRunRule.NO_BASE,
            UnknownReason.NO_MERGE_BASE to FullRunRule.NO_BASE,
            UnknownReason.ANALYSIS_ERROR to FullRunRule.ANALYSIS_ERROR,
        )
        assertEquals(UnknownReason.entries.toSet(), expected.keys)
        for (reason in UnknownReason.entries) {
            val verdict = FallbackRules.forChanges(ChangeDetection.Unknown(reason, "why"))
            assertEquals(expected.getValue(reason), verdict?.rule, reason.name)
            assertEquals("$reason: why", verdict?.detail)
        }
    }

    @Test
    fun `no history forces first-run`() {
        val verdict = FallbackRules.forHistory(FallbackFacts(null, "17"))
        assertEquals(FullRunRule.FIRST_RUN, verdict?.rule)
        assertFalse(verdict!!.changeWide)
    }

    @Test
    fun `a record without a launcher passes`() {
        assertNull(FallbackRules.forHistory(FallbackFacts(RunRecord(null), "17")))
    }

    @Test
    fun `the same launcher passes and another forces toolchain-change`() {
        assertNull(FallbackRules.forHistory(FallbackFacts(RunRecord("17"), "17")))
        val verdict = FallbackRules.forHistory(FallbackFacts(RunRecord("17"), "21"))
        assertEquals(FullRunRule.TOOLCHAIN_CHANGE, verdict?.rule)
        assertTrue(verdict!!.detail.contains("17") && verdict.detail.contains("21"))
    }

    @Test
    fun `an unknown current launcher passes`() {
        assertNull(FallbackRules.forHistory(FallbackFacts(RunRecord("17"), null)))
        assertNull(FallbackRules.forHistory(FallbackFacts(RunRecord("17"), "")))
    }

    @Test
    fun `changes are judged before history`() {
        val facts = FallbackFacts(null, null)
        val changed = known(file("build.gradle.kts", FileKind.BUILD_LOGIC))
        assertEquals(FullRunRule.BUILD_LOGIC, FallbackRules.beforeSelection(changed, facts)?.rule)
        assertEquals(FullRunRule.FIRST_RUN, FallbackRules.beforeSelection(known(), facts)?.rule)
        assertNull(FallbackRules.beforeSelection(known(), FallbackFacts(RunRecord(null), null)))
    }
}
