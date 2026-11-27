package gg.aquatic.testimpact.cache

import gg.aquatic.testimpact.cache.internal.CacheFiles
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

internal class CacheFilesTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `write creates the directory and leaves no temp file`() {
        val target = dir.resolve("a/b")
        assertTrue(CacheFiles.writeAtomically(target, "x.bin", byteArrayOf(1, 2, 3)))
        assertContentEquals(byteArrayOf(1, 2, 3), CacheFiles.read(target.resolve("x.bin")))
        assertEquals(listOf("x.bin"), target.listDirectoryEntries().map { it.name })
    }

    @Test
    fun `a second write replaces the first`() {
        CacheFiles.writeAtomically(dir, "x.bin", byteArrayOf(1))
        assertTrue(CacheFiles.writeAtomically(dir, "x.bin", byteArrayOf(2, 2)))
        assertContentEquals(byteArrayOf(2, 2), CacheFiles.read(dir.resolve("x.bin")))
        assertEquals(1, dir.listDirectoryEntries().size)
    }

    @Test
    fun `an unusable directory returns false without a temp file`() {
        val file = Files.createFile(dir.resolve("plain"))
        val messages = ArrayList<String>()
        assertFalse(CacheFiles.writeAtomically(file, "x.bin", byteArrayOf(1), messages::add))
        assertTrue(messages.isNotEmpty())
        assertEquals(listOf("plain"), dir.listDirectoryEntries().map { it.name })
    }

    @Test
    fun `a missing file reads as null and a directory is logged`() {
        val messages = ArrayList<String>()
        assertNull(CacheFiles.read(dir.resolve("none.bin"), messages::add))
        assertTrue(messages.isEmpty())
        val asDirectory = Files.createDirectory(dir.resolve("d.bin"))
        assertNull(CacheFiles.read(asDirectory, messages::add))
        assertEquals(1, messages.size)
    }

    @Test
    fun `housekeeping removes stale temp and cache files only`() {
        val now = Instant.now()
        fun file(name: String, age: Duration): Path =
            Files.createFile(dir.resolve(name)).also { Files.setLastModifiedTime(it, FileTime.from(now.minus(age))) }
        val oldTemp = file("a.1-2.tmp", Duration.ofHours(2))
        val freshTemp = file("b.1-2.tmp", Duration.ofMinutes(5))
        val oldBin = file("c.bin", Duration.ofDays(8))
        val freshBin = file("d.bin", Duration.ofDays(6))
        val other = file("notes.txt", Duration.ofDays(30))

        CacheFiles.housekeep(dir, now)

        assertFalse(oldTemp.exists())
        assertFalse(oldBin.exists())
        assertTrue(freshTemp.exists() && freshBin.exists() && other.exists())
    }

    @Test
    fun `housekeeping of a missing directory is silent`() {
        CacheFiles.housekeep(dir.resolve("absent"), Instant.now())
    }
}
