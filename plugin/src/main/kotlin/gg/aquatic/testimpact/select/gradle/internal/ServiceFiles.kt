package gg.aquatic.testimpact.select.gradle.internal

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * Reads `META-INF/services/<service>` provider files from class directories and jars. The files are untrusted input:
 * a file over [MAX_FILE_BYTES], more than [MAX_NAMES] names in total, or an archive that cannot be read throws an
 * [IOException] instead of returning a partial answer, so the caller can fall back to a full run.
 */
internal object ServiceFiles {
    const val MAX_FILE_BYTES: Int = 64 * 1024
    const val MAX_NAMES: Int = 1024

    private const val DIRECTORY = "META-INF/services/"

    /** Provider class names of [service] found in [roots], sorted and without duplicates. Missing roots are skipped. */
    fun read(roots: List<Path>, service: String): List<String> {
        val entry = DIRECTORY + service
        val names = sortedSetOf<String>()
        for (root in roots) {
            val bytes = when {
                Files.isDirectory(root) -> readFile(root.resolve(entry))
                Files.isRegularFile(root) -> readJar(root, entry)
                else -> null
            } ?: continue
            parse(bytes, names)
            if (names.size > MAX_NAMES) throw IOException("more than $MAX_NAMES provider names for $service")
        }
        return names.toList()
    }

    private fun readFile(file: Path): ByteArray? {
        if (!Files.isRegularFile(file)) return null
        val bytes = Files.newInputStream(file).use { it.readNBytes(MAX_FILE_BYTES + 1) }
        if (bytes.size > MAX_FILE_BYTES) throw IOException("service file larger than $MAX_FILE_BYTES bytes: $file")
        return bytes
    }

    private fun readJar(jar: Path, entry: String): ByteArray? {
        try {
            ZipFile(jar.toFile()).use { zip ->
                val found = zip.getEntry(entry)
                if (found == null || found.isDirectory) return null
                val bytes = zip.getInputStream(found).use { it.readNBytes(MAX_FILE_BYTES + 1) }
                if (bytes.size > MAX_FILE_BYTES) {
                    throw IOException("service file larger than $MAX_FILE_BYTES bytes: $jar!/$entry")
                }
                return bytes
            }
        } catch (e: ZipException) {
            throw IOException("corrupt archive: $jar", e)
        }
    }

    private fun parse(bytes: ByteArray, into: MutableSet<String>) {
        val text = String(bytes, Charsets.UTF_8).removePrefix("﻿")
        for (line in text.lineSequence()) {
            val name = line.substringBefore('#').trim()
            if (name.isNotEmpty()) into.add(name)
        }
    }
}
