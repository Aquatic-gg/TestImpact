package gg.aquatic.testimpact.change.gradle.internal

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.TreeSet

/**
 * Finds the resource and compiled class directories of every project below a settings directory without asking Gradle
 * for other projects, which isolated projects forbid. A directory holding `build.gradle` or `build.gradle.kts` is a
 * project; the `resources` directory of each of its source sets and every directory two levels below its
 * `build/classes` are collected when they exist.
 *
 * The walk skips hidden directories, `build`, `src` and `node_modules`, never follows links and stops at
 * [MAX_DEPTH] levels or [MAX_DIRECTORIES] visited directories. What the scan misses stays unresolved in change
 * detection, which makes the affected tasks run in full, so a cap costs time and never soundness.
 */
internal object BuildLayoutScan {

    const val MAX_DEPTH = 12
    const val MAX_DIRECTORIES = 20_000

    private val SCRIPTS = listOf("build.gradle", "build.gradle.kts")
    private val PRUNED = setOf("build", "src", "node_modules")

    /** Both lists are sorted. */
    data class Layout(val resourceRoots: List<Path>, val classOutputDirs: List<Path>)

    fun scan(root: Path, maxDepth: Int = MAX_DEPTH, maxDirectories: Int = MAX_DIRECTORIES): Layout {
        val walk = Walk(maxDepth, maxDirectories)
        walk.visit(root, 0)
        return Layout(walk.resources.toList(), walk.classes.toList())
    }

    private class Walk(private val maxDepth: Int, private val maxDirectories: Int) {
        val resources = TreeSet<Path>()
        val classes = TreeSet<Path>()
        private var visited = 0

        fun visit(directory: Path, depth: Int) {
            if (visited >= maxDirectories) return
            visited++
            if (SCRIPTS.any { Files.isRegularFile(directory.resolve(it)) }) collect(directory)
            if (depth >= maxDepth) return
            for (child in children(directory)) {
                visit(child, depth + 1)
                if (visited >= maxDirectories) return
            }
        }

        private fun collect(project: Path) {
            for (set in directories(project.resolve("src"))) {
                val candidate = set.resolve("resources")
                if (Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) resources.add(candidate)
            }
            for (language in directories(project.resolve("build").resolve("classes"))) {
                classes.addAll(directories(language))
            }
        }

        private fun children(directory: Path): List<Path> =
            directories(directory).filter { child ->
                val name = child.fileName.toString()
                !name.startsWith(".") && name !in PRUNED
            }

        private fun directories(directory: Path): List<Path> {
            try {
                Files.newDirectoryStream(directory).use { stream ->
                    return stream.filter(::isPlainDirectory).sorted()
                }
            } catch (_: IOException) {
                return emptyList()
            }
        }

        private fun isPlainDirectory(path: Path): Boolean = try {
            val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            attributes.isDirectory && !attributes.isSymbolicLink
        } catch (_: IOException) {
            false
        }
    }
}
