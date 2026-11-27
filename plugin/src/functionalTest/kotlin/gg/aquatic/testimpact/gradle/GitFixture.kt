package gg.aquatic.testimpact.gradle

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Runs git against a fixture directory without reading the developer's global or system git configuration. */
internal object GitFixture {
    private val emptyConfig: Path by lazy {
        Files.createTempFile("testimpact-gitconfig", "").also { it.toFile().deleteOnExit() }
    }

    fun init(root: Path) {
        git(root, "init", "-q", "-b", "main")
        commitAll(root, "initial")
    }

    fun commitAll(root: Path, message: String) {
        git(root, "add", "-A")
        git(root, "commit", "-q", "-m", message)
    }

    fun git(root: Path, vararg args: String): String {
        val command = listOf("git", "-c", "core.autocrlf=false", "-c", "commit.gpgsign=false") + args
        val builder = ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true)
        builder.environment().apply {
            put("GIT_CONFIG_GLOBAL", emptyConfig.toString())
            put("GIT_CONFIG_NOSYSTEM", "1")
            put("GIT_AUTHOR_NAME", "Fixture")
            put("GIT_AUTHOR_EMAIL", "fixture@example.invalid")
            put("GIT_COMMITTER_NAME", "Fixture")
            put("GIT_COMMITTER_EMAIL", "fixture@example.invalid")
        }
        val process = builder.start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(60, TimeUnit.SECONDS)) { "git ${args.joinToString(" ")} timed out" }
        check(process.exitValue() == 0) { "git ${args.joinToString(" ")} failed: $output" }
        return output
    }
}
