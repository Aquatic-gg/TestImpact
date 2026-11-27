package gg.aquatic.testimpact.fallback

import gg.aquatic.testimpact.select.MultiModuleFixture
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertTrue

/** Writes the marker of a previous green full run, so a scenario does not start on the first-run fallback. */
internal object FallbackFixture {
    private val flags = listOf("--configuration-cache", "--warning-mode=fail", "--no-watch-fs", "--stacktrace")

    /** Runs a green build; [probe] puts the selection probe plugin on the classpath. */
    fun build(root: Path, vararg args: String, probe: Boolean = false): BuildResult {
        val base = if (probe) MultiModuleFixture.runner(root) else GradleRunner.create().withProjectDir(root.toFile()).withPluginClasspath()
        return base.withArguments(args.toList() + flags).forwardOutput().build()
    }

    fun ran(root: Path, className: String, task: String = "test"): Boolean =
        Files.exists(root.resolve("build/test-results/$task/TEST-$className.xml"))

    /** Asserts the task ran every one of [classes] and said `full: <id>` on its console line. */
    fun assertFull(root: Path, result: BuildResult, id: String, classes: List<String>, task: String = "test") {
        assertContains(result.output, "full: $id")
        for (name in classes) assertTrue(ran(root, name, task), "$name did not run: ${result.output}")
    }

    fun primeHistory(root: Path, projectDir: String = "", task: String = "test", toolchain: String? = null) {
        val project = if (projectDir.isEmpty()) root else root.resolve(projectDir)
        val file = project.resolve("build/testimpact/history/$task.properties")
        Files.createDirectories(file.parent)
        check(RunHistory.write(file, RunRecord(toolchain))) { "could not write the history marker $file" }
    }
}
