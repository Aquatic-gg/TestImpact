package gg.aquatic.testimpact.fallback

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.testing.Test
import java.io.File

/**
 * What the history rules need from the build, captured at configuration time. It holds no project or task field, so it
 * is a configuration cache value; the files are read at execution time.
 *
 * @property historyFile the marker of the task in the build directory of its project.
 * @property toolchain identity of the Java launcher of the task, empty when it cannot be resolved.
 */
internal class FallbackCapture(
    val historyFile: File,
    private val toolchain: Provider<String>,
) {

    /** The launcher identity now, or null when it is not known. Never throws. */
    fun currentToolchain(): String? = try {
        toolchain.getOrElse("").takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }

    /** Never throws: what cannot be read counts as no history and an unknown launcher. */
    fun facts(): FallbackFacts = FallbackFacts(RunHistory.read(historyFile.toPath()), currentToolchain())

    companion object {
        fun of(project: Project, test: Test): FallbackCapture {
            val name = test.name.map { if (it.isLetterOrDigit() || it == '-' || it == '_' || it == '.') it else '_' }.joinToString("")
            val file = project.layout.buildDirectory.file("testimpact/history/$name.properties").get().asFile
            val launcher = test.javaLauncher.map { launcher ->
                val metadata = launcher.metadata
                "${metadata.languageVersion}|${metadata.vendor}|${metadata.javaRuntimeVersion}"
            }.orElse("")
            return FallbackCapture(file, launcher)
        }
    }
}
