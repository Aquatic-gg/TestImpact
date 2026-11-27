package gg.aquatic.testimpact.change.internal

import gg.aquatic.testimpact.change.FileKind
import gg.aquatic.testimpact.change.FileSnapshot

/**
 * Pure classification of repo-relative paths. Roots are repo-relative strings, `""` being the repo top-level.
 */
internal class FileClassifier(
    sourceRoots: List<String>,
    resourceRoots: List<String>,
    buildLogicRoots: List<String>,
    private val conventionalRoots: Boolean = false,
) {
    private val sourceRoots = sourceRoots.map(::normalizeRoot).distinct()
    private val resourceRoots = resourceRoots.map(::normalizeRoot).distinct()
    private val buildLogicRoots = buildLogicRoots.map(::normalizeRoot).distinct()

    /** Returns a snapshot with kind and resource path; the classes of a source file are filled in by the caller. */
    fun classify(rawPath: String): FileSnapshot {
        val path = normalizePath(rawPath)
        if (isBuildLogic(path)) return FileSnapshot(path, FileKind.BUILD_LOGIC)

        val candidates = sourceRoots.filter { under(path, it) }.map { it to true } +
            resourceRoots.filter { under(path, it) }.map { it to false }
        val ordered = candidates.sortedWith(
            compareByDescending<Pair<String, Boolean>> { it.first.length }.thenByDescending { it.second },
        )
        for ((root, isSource) in ordered) {
            if (isSource) {
                if (path.endsWith(".kt") || path.endsWith(".java")) return FileSnapshot(path, FileKind.SOURCE)
            } else {
                val relative = if (root.isEmpty()) path else path.substring(root.length + 1)
                return FileSnapshot(path, FileKind.RESOURCE, resourcePath = relative)
            }
        }
        if (conventionalRoots) conventional(path)?.let { return it }
        return FileSnapshot(path, FileKind.OTHER)
    }

    private fun conventional(path: String): FileSnapshot? {
        val segments = path.split('/')
        for (i in 0 until segments.size - 3) {
            if (segments[i] != "src") continue
            val directory = segments[i + 2]
            when (directory) {
                "java", "kotlin" ->
                    if (path.endsWith(".kt") || path.endsWith(".java")) return FileSnapshot(path, FileKind.SOURCE)
                "resources" ->
                    return FileSnapshot(path, FileKind.RESOURCE, resourcePath = segments.drop(i + 3).joinToString("/"))
            }
        }
        return null
    }

    private fun isBuildLogic(path: String): Boolean {
        val segments = path.split('/')
        val name = segments.last()
        val directories = segments.dropLast(1)
        if (name.endsWith(".gradle") || name.endsWith(".gradle.kts") || name.endsWith(".lockfile")) return true
        if (name == "gradle.properties" || name.startsWith("settings.gradle") || name.startsWith("gradlew")) return true
        if (buildLogicRoots.any { under(path, it) }) return true
        val inRoot = (sourceRoots + resourceRoots).any { it.isNotEmpty() && under(path, it) }
        return !inRoot && directories.firstOrNull() in BUILD_LOGIC_DIRECTORIES
    }

    private fun under(path: String, root: String): Boolean = root.isEmpty() || path.startsWith("$root/")

    private fun normalizeRoot(root: String): String = normalizePath(root).trim('/')

    private fun normalizePath(path: String): String = path.replace('\\', '/').trimStart('/')

    private companion object {
        val BUILD_LOGIC_DIRECTORIES = setOf("gradle", "buildSrc", "build-logic")
    }
}
