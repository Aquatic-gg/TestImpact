package gg.aquatic.testimpact.escalation.state

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.TreeSet

/**
 * The text form of a [RedState], also the marker other tools may read:
 *
 * ```
 * testimpact-escalation 1
 * full-run-required=true
 * unknown-failure=false
 * last-phase=quick
 * last-result=green
 * red=app.CalcTest
 * ```
 *
 * Lines end with LF, keys are fixed, `red` lines are sorted. The file is untrusted input: anything that does not
 * parse, is too large, or holds a name that is not a plain class name reads as [RedState.CORRUPT].
 */
internal object RedStateFile {
    const val HEADER = "testimpact-escalation 1"

    internal const val MAX_BYTES = 1L shl 20
    internal const val MAX_RED = 10_000
    internal const val MAX_NAME = 1_024

    private const val RESERVED_BYTES = 256L
    private const val RED_LINE_OVERHEAD = 5

    fun render(state: RedState): String = buildString {
        append(HEADER).append('\n')
        append("full-run-required=").append(state.fullRunRequired).append('\n')
        append("unknown-failure=").append(state.unknownFailure).append('\n')
        state.lastPhase?.let { append("last-phase=").append(it.text).append('\n') }
        state.lastResult?.let { append("last-result=").append(it.text).append('\n') }
        for (name in state.red.sorted()) append("red=").append(name).append('\n')
    }

    /** Missing file: [RedState.NONE]; a file that cannot be trusted or read: [RedState.CORRUPT]. */
    fun read(file: Path): RedState {
        if (!Files.exists(file)) return RedState.NONE
        return try {
            if (Files.size(file) > MAX_BYTES) return RedState.CORRUPT
            parse(StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(Files.readAllBytes(file))).toString())
        } catch (_: IOException) {
            RedState.CORRUPT
        } catch (_: CharacterCodingException) {
            RedState.CORRUPT
        }
    }

    internal fun parse(text: String): RedState {
        val lines = text.split('\n').map { it.removeSuffix("\r") }.toMutableList()
        if (lines.lastOrNull() == "") lines.removeAt(lines.lastIndex)
        if (lines.firstOrNull() != HEADER) return RedState.CORRUPT

        var required: Boolean? = null
        var unknown: Boolean? = null
        var phase: RunPhase? = null
        var result: RunResult? = null
        var phaseSeen = false
        var resultSeen = false
        val red = LinkedHashSet<String>()

        for (line in lines.drop(1)) {
            val separator = line.indexOf('=')
            if (separator <= 0) return RedState.CORRUPT
            val key = line.substring(0, separator)
            val value = line.substring(separator + 1)
            when (key) {
                "full-run-required" -> {
                    if (required != null) return RedState.CORRUPT
                    required = bool(value) ?: return RedState.CORRUPT
                }
                "unknown-failure" -> {
                    if (unknown != null) return RedState.CORRUPT
                    unknown = bool(value) ?: return RedState.CORRUPT
                }
                "last-phase" -> {
                    if (phaseSeen) return RedState.CORRUPT
                    phaseSeen = true
                    phase = RunPhase.fromText(value) ?: return RedState.CORRUPT
                }
                "last-result" -> {
                    if (resultSeen) return RedState.CORRUPT
                    resultSeen = true
                    result = RunResult.fromText(value) ?: return RedState.CORRUPT
                }
                "red" -> {
                    if (!validName(value) || red.size >= MAX_RED) return RedState.CORRUPT
                    red.add(value)
                }
                else -> return RedState.CORRUPT
            }
        }
        return RedState(
            fullRunRequired = required ?: return RedState.CORRUPT,
            red = red.toSortedSet(),
            unknownFailure = unknown ?: return RedState.CORRUPT,
            lastPhase = phase,
            lastResult = result,
        )
    }

    /**
     * Splits [failures] into the names [read] accepts and whether any was left out (a name that is not a plain class name,
     * or more names than the limits allow). The caller records a left-out name as an unknown failure, so what is written
     * never reads as [RedState.CORRUPT].
     */
    fun fit(failures: Set<String>): Pair<Set<String>, Boolean> {
        val kept = TreeSet<String>()
        var bytes = RESERVED_BYTES
        var leftOut = false
        for (name in failures.sorted()) {
            val size = name.toByteArray(StandardCharsets.UTF_8).size + RED_LINE_OVERHEAD
            if (!validName(name) || kept.size >= MAX_RED || bytes + size > MAX_BYTES) {
                leftOut = true
                continue
            }
            kept.add(name)
            bytes += size
        }
        return kept to leftOut
    }

    private fun bool(value: String): Boolean? = when (value) {
        "true" -> true
        "false" -> false
        else -> null
    }

    private fun validName(name: String): Boolean =
        name.isNotEmpty() && name.length <= MAX_NAME && name.all { it == '.' || (Character.isJavaIdentifierPart(it) && !Character.isIdentifierIgnorable(it)) }
}
