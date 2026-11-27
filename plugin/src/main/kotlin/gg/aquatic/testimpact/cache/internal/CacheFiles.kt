package gg.aquatic.testimpact.cache.internal

import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ThreadLocalRandom

/** File handling of the cache directory: whole-file reads, atomic writes and removal of stale files. */
internal object CacheFiles {
    const val MAX_FILE_BYTES: Long = 64L * 1024 * 1024
    const val CACHE_SUFFIX: String = ".bin"
    const val TEMP_SUFFIX: String = ".tmp"

    private const val MOVE_ATTEMPTS = 3
    private const val BACKOFF_MILLIS = 25L
    private val STALE_TEMP: Duration = Duration.ofHours(1)
    private val STALE_CACHE: Duration = Duration.ofDays(7)

    /** The whole file, or null when it is missing, too large or unreadable; the latter two are logged. */
    fun read(path: Path, log: (String) -> Unit = {}): ByteArray? {
        return try {
            if (Files.size(path) > MAX_FILE_BYTES) {
                log("cache file $path is larger than $MAX_FILE_BYTES bytes")
                return null
            }
            Files.readAllBytes(path)
        } catch (_: NoSuchFileException) {
            null
        } catch (e: IOException) {
            log("cache file $path cannot be read: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /** Writes [bytes] to a unique temp file and moves it over `dir/name`; true when the file is in place. */
    fun writeAtomically(dir: Path, name: String, bytes: ByteArray, log: (String) -> Unit = {}): Boolean {
        val temp = dir.resolve("$name.${ProcessHandle.current().pid()}-${ThreadLocalRandom.current().nextLong().toULong().toString(16)}$TEMP_SUFFIX")
        try {
            Files.createDirectories(dir)
            Files.write(temp, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            var attempt = 1
            while (true) {
                try {
                    Files.move(temp, dir.resolve(name), StandardCopyOption.ATOMIC_MOVE)
                    return true
                } catch (e: AtomicMoveNotSupportedException) {
                    throw e
                } catch (e: IOException) {
                    if (attempt >= MOVE_ATTEMPTS) throw e
                    Thread.sleep(BACKOFF_MILLIS * attempt)
                    attempt++
                }
            }
        } catch (e: IOException) {
            log("cache file $name not written: ${e.javaClass.simpleName}: ${e.message}")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            log("cache file $name not written: interrupted")
        }
        try {
            Files.deleteIfExists(temp)
        } catch (_: IOException) {
        }
        return false
    }

    /** Deletes temp files older than an hour and cache files unused for seven days; failures are ignored. */
    fun housekeep(dir: Path, now: Instant) {
        try {
            Files.newDirectoryStream(dir).use { entries ->
                for (path in entries) {
                    val fileName = path.fileName.toString()
                    val limit = when {
                        fileName.endsWith(TEMP_SUFFIX) -> STALE_TEMP
                        fileName.endsWith(CACHE_SUFFIX) -> STALE_CACHE
                        else -> continue
                    }
                    try {
                        if (Files.isRegularFile(path) && Files.getLastModifiedTime(path).toInstant().plus(limit).isBefore(now)) {
                            Files.deleteIfExists(path)
                        }
                    } catch (_: IOException) {
                    }
                }
            }
        } catch (_: IOException) {
        }
    }
}
