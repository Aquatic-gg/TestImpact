package gg.aquatic.testimpact.cache

import gg.aquatic.testimpact.cache.CacheCorpus.CountingParser
import gg.aquatic.testimpact.cache.internal.CacheCodec
import gg.aquatic.testimpact.cache.internal.CacheFiles
import gg.aquatic.testimpact.cache.internal.Decoded
import gg.aquatic.testimpact.graph.ClassGraphScanner
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

internal class ConcurrentCacheStressTest {
    @TempDir
    lateinit var tmp: Path

    @Test
    fun `parallel scans with rewrites never expose a damaged file`() {
        val corpus = CacheCorpus.create(tmp)
        val cacheDir = tmp.resolve("cache")
        val expected = ClassGraphScanner.scan(listOf(corpus))
        val reference = CountingParser()
        ClassGraphCache(tmp.resolve("reference"), CacheStamp(1, "a"), reference).scan(listOf(corpus))
        val entries = reference.count
        val rootKey = CachingReader.rootKey(corpus)
        val stamps = listOf(CacheStamp(1, "a"), CacheStamp(1, "b"))

        val failures = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val scansDone = CountDownLatch(THREADS)
        val stop = AtomicBoolean(false)
        val decodes = AtomicInteger()
        val pool = Executors.newFixedThreadPool(THREADS + READERS)
        try {
            repeat(THREADS) { thread ->
                pool.execute {
                    try {
                        repeat(SCANS) {
                            val result = ClassGraphCache(cacheDir, stamps[thread % 2]).scan(listOf(corpus))
                            runCatching { CacheCorpus.assertSameGraph(expected, result) }
                                .onFailure { failures.add("scan differs: ${it.message}") }
                        }
                    } catch (e: Throwable) {
                        failures.add("scan thread failed: $e")
                    } finally {
                        scansDone.countDown()
                    }
                }
            }
            repeat(READERS) {
                pool.execute {
                    try {
                        while (!stop.get()) {
                            if (!cacheDir.exists()) continue
                            for (file in cacheDir.listDirectoryEntries("*" + CacheFiles.CACHE_SUFFIX)) {
                                val bytes = CacheFiles.read(file) ?: continue
                                for (stamp in stamps) {
                                    decodes.incrementAndGet()
                                    when (val decoded = CacheCodec.decode(bytes, stamp, rootKey)) {
                                        is Decoded.Entries -> if (decoded.entries.size != entries) failures.add("entries ${decoded.entries.size}")
                                        is Decoded.Rejected -> if (!decoded.reason.contains("plugin version")) failures.add("rejected: ${decoded.reason}")
                                    }
                                }
                            }
                        }
                    } catch (_: java.nio.file.NoSuchFileException) {
                    } catch (e: Throwable) {
                        failures.add("reader failed: $e")
                    }
                }
            }
            assertTrue(scansDone.await(120, TimeUnit.SECONDS), "scans did not finish")
        } finally {
            stop.set(true)
            pool.shutdown()
            pool.awaitTermination(30, TimeUnit.SECONDS)
        }
        assertEquals(emptyList(), failures.toList())
        assertTrue(decodes.get() > 0)
        assertEquals(emptyList(), cacheDir.listDirectoryEntries("*" + CacheFiles.TEMP_SUFFIX).map { it.name })
    }

    private companion object {
        const val THREADS = 8
        const val READERS = 2
        const val SCANS = 50
    }
}
