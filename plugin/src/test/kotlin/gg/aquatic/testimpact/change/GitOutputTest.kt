package gg.aquatic.testimpact.change

import gg.aquatic.testimpact.change.internal.DiffEntry
import gg.aquatic.testimpact.change.internal.GitOutput
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class GitOutputTest {

    private fun z(vararg tokens: String): ByteArray =
        tokens.joinToString("") { it + "\u0000" }.toByteArray(Charsets.UTF_8)

    @Test
    fun parsesEveryStatusLetter() {
        val entries = GitOutput.nameStatusZ(
            z("M", "a.kt", "A", "b.kt", "D", "c.kt", "R087", "old.kt", "new.kt", "C100", "src.kt", "copy.kt", "T", "t.kt", "U", "u.kt"),
        )
        assertEquals(
            listOf(
                DiffEntry(ChangeStatus.MODIFIED, "a.kt", "a.kt"),
                DiffEntry(ChangeStatus.ADDED, null, "b.kt"),
                DiffEntry(ChangeStatus.DELETED, "c.kt", null),
                DiffEntry(ChangeStatus.RENAMED, "old.kt", "new.kt"),
                DiffEntry(ChangeStatus.ADDED, null, "copy.kt"),
                DiffEntry(ChangeStatus.MODIFIED, "t.kt", "t.kt"),
                DiffEntry(ChangeStatus.MODIFIED, "u.kt", "u.kt"),
            ),
            entries,
        )
    }

    @Test
    fun keepsSpacesUnicodeAndNewlinesInPaths() {
        val entries = GitOutput.nameStatusZ(z("M", "dir with space/ünï cödé.kt", "A", "odd\nname.kt"))
        assertEquals("dir with space/ünï cödé.kt", entries[0].newPath)
        assertEquals("odd\nname.kt", entries[1].newPath)
    }

    @Test
    fun emptyOutputIsEmpty() {
        assertEquals(emptyList(), GitOutput.nameStatusZ(ByteArray(0)))
        assertEquals(emptyList(), GitOutput.nulList(ByteArray(0)))
    }

    @Test
    fun rejectsTruncatedAndUnknownRecords() {
        assertFailsWith<IllegalArgumentException> { GitOutput.nameStatusZ(z("M")) }
        assertFailsWith<IllegalArgumentException> { GitOutput.nameStatusZ(z("R100", "only-old")) }
        assertFailsWith<IllegalArgumentException> { GitOutput.nameStatusZ(z("X", "a")) }
        assertFailsWith<IllegalArgumentException> { GitOutput.nameStatusZ(z("MM", "a")) }
        assertFailsWith<IllegalArgumentException> { GitOutput.nameStatusZ(z("R", "a", "b")) }
        assertFailsWith<IllegalArgumentException> { GitOutput.nameStatusZ("M\u0000a".toByteArray()) }
    }

    @Test
    fun rejectsInvalidUtf8() {
        val bytes = byteArrayOf('M'.code.toByte(), 0, 0xC3.toByte(), 0x28, 0)
        assertFailsWith<IllegalArgumentException> { GitOutput.nameStatusZ(bytes) }
    }

    @Test
    fun nulListSplitsEntries() {
        assertEquals(listOf("a b", "ü", "c"), GitOutput.nulList(z("a b", "ü", "c")))
    }

    private fun batch(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        parts.forEach { out.write(it) }
        return out.toByteArray()
    }

    private fun blob(content: ByteArray): ByteArray =
        batch("0123456789abcdef0123456789abcdef01234567 blob ${content.size}\n".toByteArray(), content, "\n".toByteArray())

    @Test
    fun catFileBatchReturnsBlobsByteExact() {
        val crlf = "a\r\nb\r\n".toByteArray()
        val binary = byteArrayOf(0, 1, 2, 10, 13, -1, -128, 10)
        val result = GitOutput.catFileBatch(
            batch(blob(crlf), "abc123:missing/path missing\n".toByteArray(), blob(binary), blob(ByteArray(0))),
            4,
        )
        assertContentEquals(crlf, result[0])
        assertNull(result[1])
        assertContentEquals(binary, result[2])
        assertContentEquals(ByteArray(0), result[3])
    }

    @Test
    fun catFileBatchTreatsNonBlobAsMissing() {
        val commit = batch("0123456789abcdef0123456789abcdef01234567 commit 3\n".toByteArray(), "abc".toByteArray(), "\n".toByteArray())
        assertNull(GitOutput.catFileBatch(commit, 1)[0])
    }

    @Test
    fun catFileBatchRejectsMalformedOutput() {
        assertFailsWith<IllegalArgumentException> { GitOutput.catFileBatch(ByteArray(0), 1) }
        assertFailsWith<IllegalArgumentException> { GitOutput.catFileBatch("x blob 10\nabc\n".toByteArray(), 1) }
        assertFailsWith<IllegalArgumentException> { GitOutput.catFileBatch("x blob zz\nabc\n".toByteArray(), 1) }
        assertFailsWith<IllegalArgumentException> { GitOutput.catFileBatch("x blob 3\nabcX".toByteArray(), 1) }
        assertFailsWith<IllegalArgumentException> { GitOutput.catFileBatch(batch(blob(ByteArray(1)), blob(ByteArray(1))), 1) }
    }
}
