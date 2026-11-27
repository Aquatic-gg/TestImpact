package gg.aquatic.testimpact.fallback

import java.nio.file.Files
import java.nio.file.Path

/** Writes the marker of a previous green full run, so a scenario does not start on the first-run fallback. */
internal object FallbackFixture {
    fun primeHistory(root: Path, projectDir: String = "", task: String = "test", toolchain: String? = null) {
        val project = if (projectDir.isEmpty()) root else root.resolve(projectDir)
        val file = project.resolve("build/testimpact/history/$task.properties")
        Files.createDirectories(file.parent)
        check(RunHistory.write(file, RunRecord(toolchain))) { "could not write the history marker $file" }
    }
}
