package gg.aquatic.testimpact.cache

import gg.aquatic.testimpact.cache.internal.CacheCodec
import gg.aquatic.testimpact.cache.internal.CacheFiles
import gg.aquatic.testimpact.cache.internal.CorruptCacheException
import gg.aquatic.testimpact.cache.internal.Decoded
import gg.aquatic.testimpact.graph.ClassFacts
import gg.aquatic.testimpact.graph.ClassGraphResult
import gg.aquatic.testimpact.graph.ClassGraphScanner
import gg.aquatic.testimpact.graph.internal.classfile.ClassFileReader
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Scans class roots like [ClassGraphScanner] but parses only class files whose content it has not seen before.
 * Each root has its own file in [dir], holding exactly the facts of the last scan of that root. The cache never
 * changes a result and never fails a scan: a missing, damaged or foreign file means everything is parsed again.
 * Concurrent writers (other scans, other processes) race on whole files; the last one wins and a lost update only
 * costs a re-parse.
 */
internal class ClassGraphCache(
    private val dir: Path,
    private val stamp: CacheStamp = CacheStamp.current,
    private val parse: (ByteArray) -> ClassFacts = ClassFileReader::read,
    private val log: (String) -> Unit = {},
) {
    private val snapshots = ConcurrentHashMap<String, AtomicReference<RootEntries>>()
    private val housekept = AtomicBoolean(false)

    fun scan(roots: List<Path>, maxClassBytes: Int = ClassGraphScanner.DEFAULT_MAX_CLASS_BYTES): ClassGraphResult {
        val reader = CachingReader(::snapshot, parse)
        val result = ClassGraphScanner.scan(roots, maxClassBytes, reader)
        for (root in roots.distinctBy(CachingReader::rootKey)) {
            try {
                persist(reader.result(root))
            } catch (e: IOException) {
                log("class cache for ${CachingReader.rootKey(root)} not updated: ${e.javaClass.simpleName}: ${e.message}")
            } catch (e: RuntimeException) {
                log("class cache for ${CachingReader.rootKey(root)} not updated: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        if (housekept.compareAndSet(false, true)) CacheFiles.housekeep(dir, Instant.now())
        return result
    }

    private fun persist(result: CachingReader.RootResult) {
        val key = result.entries.rootKey
        val file = dir.resolve(fileName(key))
        if (!result.changed) {
            touchWhenOld(file)
            return
        }
        val bytes = CacheCodec.encode(stamp, key, result.entries.entries)
        CacheFiles.writeAtomically(dir, file.fileName.toString(), bytes, log)
        snapshots.getOrPut(key) { AtomicReference(result.entries) }.set(result.entries)
    }

    private fun snapshot(root: Path): RootEntries {
        val key = CachingReader.rootKey(root)
        snapshots[key]?.let { return it.get() }
        val loaded = load(key)
        return snapshots.getOrPut(key) { AtomicReference(loaded) }.get()
    }

    private fun load(key: String): RootEntries {
        val bytes = CacheFiles.read(dir.resolve(fileName(key)), log) ?: return RootEntries.empty(key)
        val decoded = try {
            CacheCodec.decode(bytes, stamp, key)
        } catch (e: CorruptCacheException) {
            Decoded.Rejected("malformed: ${e.message}")
        } catch (e: RuntimeException) {
            Decoded.Rejected("unreadable: ${e.javaClass.simpleName}: ${e.message}")
        }
        return when (decoded) {
            is Decoded.Entries -> RootEntries(key, decoded.entries)
            is Decoded.Rejected -> {
                log("class cache for $key ignored: ${decoded.reason}")
                RootEntries.empty(key)
            }
        }
    }

    private fun touchWhenOld(file: Path) {
        try {
            val modified = Files.getLastModifiedTime(file).toInstant()
            if (modified.plus(TOUCH_AFTER).isBefore(Instant.now())) {
                Files.setLastModifiedTime(file, FileTime.from(Instant.now()))
            }
        } catch (_: IOException) {
        }
    }

    private companion object {
        val TOUCH_AFTER: Duration = Duration.ofDays(1)

        fun fileName(rootKey: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(rootKey.toByteArray(Charsets.UTF_8))
            val hex = digest.copyOf(16).joinToString("") { "%02x".format(it) }
            return hex + CacheFiles.CACHE_SUFFIX
        }
    }
}
