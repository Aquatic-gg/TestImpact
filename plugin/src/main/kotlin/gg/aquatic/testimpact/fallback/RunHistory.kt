package gg.aquatic.testimpact.fallback

import gg.aquatic.testimpact.gradle.internal.AtomicFile
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * The marker file of the last green full run. It is read from the build directory, so it is untrusted: anything that
 * does not look exactly like a marker counts as no marker.
 */
internal object RunHistory {
    private const val HEADER = "format=1"
    private const val TOOLCHAIN_KEY = "toolchain="
    private const val MAX_BYTES = 4096
    private const val MAX_TOOLCHAIN = 200

    /** @return the record, or null when the file is missing, oversized, not text or not a marker. Never throws. */
    fun read(file: Path): RunRecord? = try {
        Files.newInputStream(file).use { it.readNBytes(MAX_BYTES + 1) }.takeIf { it.size <= MAX_BYTES }?.let(::parse)
    } catch (_: IOException) {
        null
    } catch (_: RuntimeException) {
        null
    }

    /** @return false when the marker could not be written. Never throws. */
    fun write(file: Path, record: RunRecord): Boolean = try {
        val toolchain = record.toolchain?.takeIf(::valid)
        AtomicFile.write(file, buildString {
            append(HEADER).append('\n')
            if (toolchain != null) append(TOOLCHAIN_KEY).append(toolchain).append('\n')
        })
        true
    } catch (_: IOException) {
        false
    } catch (_: RuntimeException) {
        false
    }

    private fun parse(bytes: ByteArray): RunRecord? {
        val text = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            return null
        }
        val lines = text.lines()
        if (lines.firstOrNull() != HEADER) return null
        val value = lines.drop(1).firstOrNull { it.startsWith(TOOLCHAIN_KEY) }?.removePrefix(TOOLCHAIN_KEY)
        if (value == null) return RunRecord(null)
        return if (valid(value)) RunRecord(value) else null
    }

    private fun valid(toolchain: String): Boolean =
        toolchain.isNotEmpty() && toolchain.length <= MAX_TOOLCHAIN && toolchain.none(Char::isISOControl)
}
