package gg.aquatic.testimpact.report

import java.io.IOException
import java.nio.file.Path

/**
 * Replaces absolute directory prefixes in free text, so a report never carries the machine's layout.
 * The longest prefix wins and a prefix only matches at a path boundary: `/r/a` does not match inside `/r/ab`.
 */
internal class PathScrubber(
    prefixes: List<Pair<String, String>>,
    private val ignoreCase: Boolean = false,
) {
    private val variants: List<Pair<String, String>> = prefixes
        .flatMap { (prefix, placeholder) -> separatorStyles(prefix).map { it to placeholder } }
        .distinct()
        .sortedByDescending { it.first.length }

    fun scrub(text: String): String {
        if (variants.isEmpty() || text.isEmpty()) return text
        val out = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val hit = if (startsAtBoundary(text, index)) variants.firstOrNull { matchesAt(text, index, it.first) } else null
            if (hit == null) {
                out.append(text[index])
                index++
            } else {
                out.append(hit.second)
                index += hit.first.length
            }
        }
        return out.toString()
    }

    private fun startsAtBoundary(text: String, index: Int): Boolean =
        index == 0 || !text[index - 1].let { it.isLetterOrDigit() || it == '_' }

    private fun matchesAt(text: String, index: Int, prefix: String): Boolean {
        val end = index + prefix.length
        if (end > text.length || !text.regionMatches(index, prefix, 0, prefix.length, ignoreCase)) return false
        return end == text.length || !text[end].let { it.isLetterOrDigit() || it == '_' || it == '-' }
    }

    private fun separatorStyles(prefix: String): List<String> {
        val trimmed = prefix.trimEnd('/', '\\')
        if (trimmed.length < 2) return emptyList()
        return listOf(trimmed, trimmed.replace('\\', '/'), trimmed.replace('/', '\\')).distinct()
    }

    companion object {
        const val ROOT = "<root>"
        const val GRADLE_USER_HOME = "<gradle-user-home>"
        const val HOME = "<home>"

        /** Both the given and the real path of each directory are replaced; a null directory is skipped. */
        fun of(settingsDir: Path, gradleUserHome: Path?, userHome: Path?, ignoreCase: Boolean): PathScrubber {
            val prefixes = ArrayList<Pair<String, String>>()
            fun add(directory: Path?, placeholder: String) {
                if (directory == null) return
                prefixes.add(directory.toString() to placeholder)
                try {
                    prefixes.add(directory.toRealPath().toString() to placeholder)
                } catch (_: IOException) {
                    // A directory that cannot be resolved is still scrubbed by its given path.
                }
            }
            add(settingsDir, ROOT)
            add(gradleUserHome, GRADLE_USER_HOME)
            add(userHome, HOME)
            return PathScrubber(prefixes, ignoreCase)
        }
    }
}
