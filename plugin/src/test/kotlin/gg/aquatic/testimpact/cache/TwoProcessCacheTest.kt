package gg.aquatic.testimpact.cache

import gg.aquatic.testimpact.cache.CacheCorpus.CountingParser
import gg.aquatic.testimpact.cache.internal.CacheCodec
import gg.aquatic.testimpact.cache.internal.CacheFiles
import gg.aquatic.testimpact.cache.internal.Decoded
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.jupiter.api.io.TempDir

internal class TwoProcessCacheTest {
    @TempDir
    lateinit var tmp: Path

    @Test
    fun `two processes rewriting one cache file never see a damaged file`() {
        val corpus = CacheCorpus.create(tmp)
        val cacheDir = tmp.resolve("cache")
        val go = tmp.resolve("go")
        val argFile = tmp.resolve("java.args")
        val classPath = System.getProperty("java.class.path").replace('\\', '/')
        argFile.writeText("-cp \"$classPath\"\n")
        val java = Path.of(System.getProperty("java.home"), "bin", if (File.separatorChar == '\\') "java.exe" else "java")
        val processes = List(2) { index ->
            ProcessBuilder(
                java.toString(), "@${argFile.toString().replace('\\', '/')}",
                "gg.aquatic.testimpact.cache.CacheWriterMain",
                cacheDir.toString(), corpus.toString(), go.toString(), ROUNDS.toString(),
            ).redirectError(tmp.resolve("child$index.err").toFile()).start()
        }
        try {
            for (process in processes) {
                val line = process.inputStream.bufferedReader().readLine()
                assertEquals("ready", line, "child did not start; see ${tmp.listDirectoryEntries("*.err").map { it.toFile().readText() }}")
            }
            Files.createFile(go)
            for (process in processes) {
                if (!process.waitFor(120, TimeUnit.SECONDS)) fail("child did not finish in time")
            }
            val errors = tmp.listDirectoryEntries("*.err").joinToString { it.toFile().readText() }
            assertEquals(listOf(0, 0), processes.map { it.exitValue() }, errors)
        } finally {
            processes.forEach { if (it.isAlive) it.destroyForcibly() }
        }

        val reference = CountingParser()
        ClassGraphCache(tmp.resolve("reference"), CacheStamp(1, "a"), reference).scan(listOf(corpus))
        val bytes = checkNotNull(CacheFiles.read(cacheDir.listDirectoryEntries("*" + CacheFiles.CACHE_SUFFIX).single()))
        val rootKey = CachingReader.rootKey(corpus)
        val sizes = listOf("a", "b").mapNotNull {
            (CacheCodec.decode(bytes, CacheStamp(1, it), rootKey) as? Decoded.Entries)?.entries?.size
        }
        assertEquals(listOf(reference.count), sizes)
        assertTrue(cacheDir.listDirectoryEntries("*" + CacheFiles.TEMP_SUFFIX).isEmpty(), cacheDir.listDirectoryEntries().map { it.name }.toString())
    }

    private companion object {
        const val ROUNDS = 40
    }
}
