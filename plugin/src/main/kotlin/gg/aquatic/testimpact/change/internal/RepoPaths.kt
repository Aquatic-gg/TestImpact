package gg.aquatic.testimpact.change.internal

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Converts filesystem paths to repo-relative strings with `/` separators, so Windows and Linux agree.
 */
internal class RepoPaths(topLevel: Path) {

    private val root: Path = resolve(topLevel)

    /** The repo-relative path, an empty string for the top-level itself, or null when [path] lies outside the repo. */
    fun relativize(path: Path): String? {
        val resolved = resolve(path)
        if (!resolved.startsWith(root)) return null
        val relative = root.relativize(resolved)
        return (0 until relative.nameCount)
            .map { relative.getName(it).toString() }
            .filter { it.isNotEmpty() }
            .joinToString("/")
    }

    private fun resolve(path: Path): Path {
        if (Files.exists(path)) {
            try {
                return path.toRealPath()
            } catch (_: IOException) {
                // the lexical form below is the best remaining answer
            }
        }
        return path.toAbsolutePath().normalize()
    }
}
