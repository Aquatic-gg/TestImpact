package gg.aquatic.testimpact.change.internal

import gg.aquatic.testimpact.change.ChangeStatus
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Parsers for git's machine-readable output. Every malformed input throws [IllegalArgumentException].
 */
internal object GitOutput {

    /** Parses `git diff -z --name-status`. A copy is reported as an addition of its new path. */
    fun nameStatusZ(bytes: ByteArray): List<DiffEntry> {
        val tokens = nulList(bytes)
        val entries = ArrayList<DiffEntry>()
        var index = 0
        fun next(): String {
            require(index < tokens.size) { "truncated name-status record" }
            return tokens[index++]
        }
        while (index < tokens.size) {
            val status = next()
            require(status.isNotEmpty()) { "empty name-status code" }
            when (status[0]) {
                'A' -> entries += DiffEntry(ChangeStatus.ADDED, null, single(status, next()))
                'M', 'T', 'U' -> {
                    val path = single(status, next())
                    entries += DiffEntry(ChangeStatus.MODIFIED, path, path)
                }
                'D' -> entries += DiffEntry(ChangeStatus.DELETED, single(status, next()), null)
                'R' -> {
                    scored(status)
                    val old = next()
                    entries += DiffEntry(ChangeStatus.RENAMED, old, next())
                }
                'C' -> {
                    scored(status)
                    next()
                    entries += DiffEntry(ChangeStatus.ADDED, null, next())
                }
                else -> throw IllegalArgumentException("unknown name-status code '$status'")
            }
        }
        return entries
    }

    /** Splits a NUL-terminated list, such as `git ls-files -z`. */
    fun nulList(bytes: ByteArray): List<String> {
        if (bytes.isEmpty()) return emptyList()
        require(bytes.last() == NUL) { "output is not NUL-terminated" }
        val result = ArrayList<String>()
        var start = 0
        for (i in bytes.indices) {
            if (bytes[i] == NUL) {
                result += decode(bytes, start, i)
                start = i + 1
            }
        }
        return result
    }

    /**
     * Parses `git cat-file --batch` output for [expected] queries: the blob bytes in order, null for a missing
     * object or one that is not a blob.
     */
    fun catFileBatch(bytes: ByteArray, expected: Int): List<ByteArray?> {
        val result = ArrayList<ByteArray?>(expected)
        var pos = 0
        repeat(expected) {
            val end = indexOf(bytes, NEWLINE, pos)
            require(end >= 0) { "truncated cat-file header" }
            val header = String(bytes, pos, end - pos, StandardCharsets.ISO_8859_1)
            pos = end + 1
            if (header.endsWith(" missing")) {
                result += null
                return@repeat
            }
            val parts = header.split(' ')
            require(parts.size >= 3) { "malformed cat-file header" }
            val size = parts.last().toIntOrNull()
            require(size != null && size >= 0) { "malformed cat-file size" }
            require(pos.toLong() + size < bytes.size) { "truncated cat-file body" }
            require(bytes[pos + size] == NEWLINE) { "cat-file body not terminated" }
            result += if (parts[parts.size - 2] == "blob") bytes.copyOfRange(pos, pos + size) else null
            pos += size + 1
        }
        require(pos == bytes.size) { "unexpected trailing cat-file output" }
        return result
    }

    /**
     * Parses `git cat-file --batch-check` output for [expected] queries: the size of each blob in order, null for a
     * missing object or one that is not a blob.
     */
    fun batchCheck(bytes: ByteArray, expected: Int): List<Long?> {
        val lines = String(bytes, StandardCharsets.ISO_8859_1).split('\n')
        require(lines.size == expected + 1 && lines.last().isEmpty()) { "unexpected cat-file check output" }
        return lines.dropLast(1).map { line ->
            if (line.endsWith(" missing")) return@map null
            val parts = line.split(' ')
            require(parts.size == 3) { "malformed cat-file check line" }
            val size = parts[2].toLongOrNull()
            require(size != null && size >= 0) { "malformed cat-file size" }
            if (parts[1] == "blob") size else null
        }
    }

    private fun single(status: String, path: String): String {
        require(status.length == 1) { "malformed name-status code '$status'" }
        return path
    }

    private fun scored(status: String) {
        require(status.length > 1 && status.substring(1).all { it in '0'..'9' }) { "malformed name-status code '$status'" }
    }

    private fun indexOf(bytes: ByteArray, value: Byte, from: Int): Int {
        for (i in from until bytes.size) if (bytes[i] == value) return i
        return -1
    }

    private fun decode(bytes: ByteArray, from: Int, to: Int): String =
        try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, from, to - from))
                .toString()
        } catch (e: IOException) {
            throw IllegalArgumentException("path is not valid UTF-8", e)
        }

    private const val NUL: Byte = 0
    private const val NEWLINE: Byte = '\n'.code.toByte()
}
