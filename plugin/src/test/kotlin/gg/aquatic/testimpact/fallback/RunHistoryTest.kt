package gg.aquatic.testimpact.fallback

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RunHistoryTest {
    @TempDir
    lateinit var temp: Path

    private fun file(): Path = temp.resolve("history").resolve("test.properties")

    @Test
    fun `a written record reads back the same`() {
        assertTrue(RunHistory.write(file(), RunRecord("17|vendor|17.0.9")))
        assertEquals(RunRecord("17|vendor|17.0.9"), RunHistory.read(file()))
    }

    @Test
    fun `a record without a launcher reads back without one`() {
        assertTrue(RunHistory.write(file(), RunRecord(null)))
        assertEquals(RunRecord(null), RunHistory.read(file()))
    }

    @Test
    fun `a launcher that is not printable is not stored`() {
        assertTrue(RunHistory.write(file(), RunRecord("a\nb")))
        assertEquals(RunRecord(null), RunHistory.read(file()))
    }

    @Test
    fun `a missing file is no record`() {
        assertNull(RunHistory.read(file()))
    }

    @Test
    fun `a directory is no record`() {
        assertNull(RunHistory.read(Files.createDirectories(file())))
    }

    @Test
    fun `a file without the header is no record`() {
        Files.createDirectories(file().parent)
        Files.writeString(file(), "toolchain=17\n")
        assertNull(RunHistory.read(file()))
    }

    @Test
    fun `binary content is no record`() {
        Files.createDirectories(file().parent)
        Files.write(file(), byteArrayOf(0x66, 0x6f, 0x72, 0x6d, 0x61, 0x74, 0x3d, 0x31, 0x0a, 0xC3.toByte(), 0x28))
        assertNull(RunHistory.read(file()))
    }

    @Test
    fun `a file above 4 KiB is no record`() {
        Files.createDirectories(file().parent)
        Files.writeString(file(), "format=1\ntoolchain=17\n" + "x".repeat(5000))
        assertNull(RunHistory.read(file()))
    }

    @Test
    fun `control characters or an overlong launcher make the record void`() {
        Files.createDirectories(file().parent)
        Files.writeString(file(), "format=1\ntoolchain=a\u0007b\n")
        assertNull(RunHistory.read(file()))
        Files.writeString(file(), "format=1\ntoolchain=${"x".repeat(201)}\n")
        assertNull(RunHistory.read(file()))
    }

    @Test
    fun `writing below a regular file fails without throwing`() {
        val blocker = Files.writeString(temp.resolve("blocker"), "x")
        assertFalse(RunHistory.write(blocker.resolve("sub").resolve("test.properties"), RunRecord("17")))
    }
}
