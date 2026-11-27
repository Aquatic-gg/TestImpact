package gg.aquatic.testimpact.cache

import gg.aquatic.testimpact.cache.CacheCorpus.CountingParser
import gg.aquatic.testimpact.cache.internal.CacheCodec
import gg.aquatic.testimpact.cache.internal.Decoded
import gg.aquatic.testimpact.graph.ClassGraphResult
import gg.aquatic.testimpact.graph.ClassGraphScanner
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

internal class ClassGraphCacheTest {
    @TempDir
    lateinit var tmp: Path

    private val stamp = CacheStamp(CacheStamp.FORMAT_VERSION, "1.0")
    private val logs = ArrayList<String>()
    private lateinit var corpus: Path
    private lateinit var cacheDir: Path

    private fun prepare() {
        corpus = CacheCorpus.create(tmp)
        cacheDir = tmp.resolve("cache")
    }

    private fun cache(parser: CountingParser, stamp: CacheStamp = this.stamp, dir: Path = cacheDir) =
        ClassGraphCache(dir, stamp, parser, logs::add)

    private fun cacheFile(): Path = cacheDir.listDirectoryEntries("*.bin").single()

    private fun entriesOf(root: Path, stamp: CacheStamp = this.stamp): Int {
        val decoded = CacheCodec.decode(Files.readAllBytes(cacheFile()), stamp, CachingReader.rootKey(root))
        return assertIs<Decoded.Entries>(decoded).entries.size
    }

    private fun distinctClasses(root: Path): Int {
        val parser = CountingParser()
        cache(parser, dir = tmp.resolve("reference")).scan(listOf(root))
        return parser.count
    }

    @Test
    fun `cold scan parses everything and a warm scan parses nothing`() {
        prepare()
        val expected = ClassGraphScanner.scan(listOf(corpus))
        val cold = CountingParser()
        CacheCorpus.assertSameGraph(expected, cache(cold).scan(listOf(corpus)))
        assertTrue(cold.count > 0)
        val warm = CountingParser()
        CacheCorpus.assertSameGraph(expected, cache(warm).scan(listOf(corpus)))
        assertEquals(0, warm.count)
        assertEquals(cold.count, entriesOf(corpus))
    }

    @Test
    fun `a new class file is the only one parsed`() {
        prepare()
        cache(CountingParser()).scan(listOf(corpus))
        val before = entriesOf(corpus)
        val extra = checkNotNull(javaClass.classLoader.getResourceAsStream("gg/aquatic/testimpact/cache/CacheFilesTest.class")).use { it.readBytes() }
        corpus.resolve("k0/Extra.class").writeBytes(extra)
        val parser = CountingParser()
        val result = cache(parser).scan(listOf(corpus))
        assertEquals(1, parser.count)
        CacheCorpus.assertSameGraph(ClassGraphScanner.scan(listOf(corpus)), result)
        assertEquals(before + 1, entriesOf(corpus))
    }

    @Test
    fun `a deleted class file is pruned from the file`() {
        prepare()
        cache(CountingParser()).scan(listOf(corpus))
        val victim = Files.walk(corpus).use { s -> s.filter { it.name.endsWith(".class") }.sorted().findFirst().get() }
        victim.deleteExisting()
        val parser = CountingParser()
        val result = cache(parser).scan(listOf(corpus))
        assertEquals(0, parser.count)
        CacheCorpus.assertSameGraph(ClassGraphScanner.scan(listOf(corpus)), result)
        assertEquals(distinctClasses(corpus), entriesOf(corpus))
    }

    @Test
    fun `another plugin version parses everything again and rewrites the file`() {
        prepare()
        val first = CountingParser()
        cache(first, CacheStamp(1, "1.0")).scan(listOf(corpus))
        val second = CountingParser()
        val newer = CacheStamp(1, "2.0")
        val result = cache(second, newer).scan(listOf(corpus))
        assertEquals(first.count, second.count)
        assertTrue(logs.any { it.contains("plugin version") }, logs.toString())
        CacheCorpus.assertSameGraph(ClassGraphScanner.scan(listOf(corpus)), result)
        assertEquals(second.count, entriesOf(corpus, newer))
    }

    @Test
    fun `a damaged cache file is ignored and replaced`() {
        prepare()
        cache(CountingParser()).scan(listOf(corpus))
        val file = cacheFile()
        val good = Files.readAllBytes(file)
        assertTrue(good.size > 100)
        val expected = ClassGraphScanner.scan(listOf(corpus))
        for (damaged in listOf(good.copyOf(good.size / 2), ByteArray(300) { (it * 7).toByte() }, ByteArray(0))) {
            file.writeBytes(damaged)
            val parser = CountingParser()
            CacheCorpus.assertSameGraph(expected, cache(parser).scan(listOf(corpus)))
            assertTrue(parser.count > 0)
            assertEquals(parser.count, entriesOf(corpus))
        }
    }

    @Test
    fun `a directory in place of the cache file does not fail the scan`() {
        prepare()
        cache(CountingParser()).scan(listOf(corpus))
        val file = cacheFile()
        file.deleteExisting()
        Files.createDirectory(file)
        val result = cache(CountingParser()).scan(listOf(corpus))
        CacheCorpus.assertSameGraph(ClassGraphScanner.scan(listOf(corpus)), result)
        assertTrue(Files.isDirectory(file))
    }

    @Test
    fun `an unwritable cache directory does not fail the scan`() {
        prepare()
        val blocker = tmp.resolve("blocker")
        blocker.writeBytes(byteArrayOf(1))
        val result = cache(CountingParser(), dir = blocker.resolve("cache")).scan(listOf(corpus))
        CacheCorpus.assertSameGraph(ClassGraphScanner.scan(listOf(corpus)), result)
        assertTrue(logs.any { it.contains("not written") || it.contains("not updated") }, logs.toString())
    }

    @Test
    fun `a malformed class gives unknown and the readable classes are still cached`() {
        prepare()
        val broken = corpus.resolve("k0/Broken.class")
        broken.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        val cold = CountingParser()
        assertIs<ClassGraphResult.Unknown>(cache(cold).scan(listOf(corpus)))
        val stored = entriesOf(corpus)
        assertEquals(distinctClasses(corpus.also { broken.deleteExisting() }), stored)
        val warm = CountingParser()
        assertIs<ClassGraphResult.Known>(cache(warm).scan(listOf(corpus)))
        assertEquals(0, warm.count)
    }

    @Test
    fun `a jar root is cached like a directory root`() {
        prepare()
        val jar = CacheCorpus.jar(corpus, tmp.resolve("classes.jar"))
        val expected = ClassGraphScanner.scan(listOf(jar))
        val cold = CountingParser()
        CacheCorpus.assertSameGraph(expected, cache(cold).scan(listOf(jar)))
        val warm = CountingParser()
        CacheCorpus.assertSameGraph(expected, cache(warm).scan(listOf(jar)))
        assertTrue(cold.count > 0)
        assertEquals(0, warm.count)
    }

    @Test
    fun `a warm unchanged scan leaves the file alone`() {
        prepare()
        cache(CountingParser()).scan(listOf(corpus))
        val file = cacheFile()
        val bytes = Files.readAllBytes(file)
        val modified = Files.getLastModifiedTime(file)
        val parser = CountingParser()
        cache(parser).scan(listOf(corpus))
        assertEquals(0, parser.count)
        assertContentEquals(bytes, Files.readAllBytes(file))
        assertEquals(modified, Files.getLastModifiedTime(file))
        assertEquals(listOf(file.name), cacheDir.listDirectoryEntries().map { it.name })
    }

    @Test
    fun `a warm scan refreshes an old file so housekeeping keeps it`() {
        prepare()
        cache(CountingParser()).scan(listOf(corpus))
        val file = cacheFile()
        val bytes = Files.readAllBytes(file)
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().minus(Duration.ofDays(8))))
        val parser = CountingParser()
        cache(parser).scan(listOf(corpus))
        assertEquals(0, parser.count)
        assertTrue(file.exists())
        assertContentEquals(bytes, Files.readAllBytes(file))
        assertTrue(Files.getLastModifiedTime(file).toInstant().isAfter(Instant.now().minus(Duration.ofHours(1))))
    }

    @Test
    fun `housekeeping removes stale files and keeps fresh ones`() {
        prepare()
        Files.createDirectories(cacheDir)
        val staleTemp = cacheDir.resolve("a.bin.1-2.tmp").also { it.writeBytes(byteArrayOf(1)) }
        val freshTemp = cacheDir.resolve("b.bin.1-3.tmp").also { it.writeBytes(byteArrayOf(1)) }
        val staleCache = cacheDir.resolve("old.bin").also { it.writeBytes(byteArrayOf(1)) }
        val freshCache = cacheDir.resolve("recent.bin").also { it.writeBytes(byteArrayOf(1)) }
        val now = Instant.now()
        Files.setLastModifiedTime(staleTemp, FileTime.from(now.minus(Duration.ofHours(2))))
        Files.setLastModifiedTime(staleCache, FileTime.from(now.minus(Duration.ofDays(8))))
        Files.setLastModifiedTime(freshCache, FileTime.from(now.minus(Duration.ofDays(6))))
        cache(CountingParser()).scan(listOf(corpus))
        assertFalse(staleTemp.exists())
        assertFalse(staleCache.exists())
        assertTrue(freshTemp.exists())
        assertTrue(freshCache.exists())
    }
}
