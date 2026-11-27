package gg.aquatic.testimpact.seed.internal

/**
 * A test class name pattern. A star matches any sequence of characters, dots and dollars included. A pattern
 * without a dot matches the simple name (the part after the last dot), one with a dot matches the whole binary name.
 * Matching walks both strings once with a single backtrack point, so it never backtracks exponentially.
 */
internal class TestPattern(val pattern: String) {
    private val wholeName: Boolean = '.' in pattern

    fun matches(className: String): Boolean {
        val subject = if (wholeName) className else className.substringAfterLast('.')
        return wildcardMatch(pattern, subject)
    }

    private fun wildcardMatch(glob: String, text: String): Boolean {
        var g = 0
        var t = 0
        var starAt = -1
        var resume = 0
        while (t < text.length) {
            if (g < glob.length && glob[g] == '*') {
                starAt = g++
                resume = t
            } else if (g < glob.length && glob[g] == text[t]) {
                g++
                t++
            } else if (starAt >= 0) {
                g = starAt + 1
                t = ++resume
            } else {
                return false
            }
        }
        while (g < glob.length && glob[g] == '*') g++
        return g == glob.length
    }
}
