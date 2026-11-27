package gg.aquatic.testimpact.change.internal

import java.nio.file.Files
import java.nio.file.Path

/**
 * Finds the nested builds that hold a changed file: directories below the settings directory with their own settings
 * file, such as an included build. Only the ancestors of the changed paths are looked at, so the cost follows the size
 * of the change and not the size of the repository.
 */
internal object NestedBuilds {
    private val SETTINGS = listOf("settings.gradle", "settings.gradle.kts")

    /**
     * @param topLevel the repository root the [paths] are relative to.
     * @param settingsDir repo-relative settings directory, `""` for the repository root.
     * @param paths repo-relative changed paths with `/` separators.
     * @return repo-relative nested build directories, sorted.
     */
    fun containing(topLevel: Path, settingsDir: String, paths: Collection<String>): List<String> {
        val prefix = if (settingsDir.isEmpty()) "" else "$settingsDir/"
        val checked = HashMap<String, Boolean>()
        val found = sortedSetOf<String>()
        for (path in paths) {
            if (!path.startsWith(prefix)) continue
            val segments = path.removePrefix(prefix).split('/').dropLast(1)
            for (depth in 1..segments.size) {
                val directory = prefix + segments.take(depth).joinToString("/")
                val nested = checked.getOrPut(directory) {
                    SETTINGS.any { Files.isRegularFile(topLevel.resolve(directory).resolve(it)) }
                }
                if (nested) {
                    found.add(directory)
                    break
                }
            }
        }
        return found.toList()
    }
}
