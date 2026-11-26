package gg.aquatic.testimpact.change

import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes

/**
 * A throwaway repository under a private temp directory, isolated from the user's and the system git configuration.
 */
internal class TempGitRepo private constructor(
    val base: Path,
    val env: Map<String, String>,
) : AutoCloseable {

    val dir: Path = base.resolve("repo")

    fun git(vararg args: String): String = gitIn(dir, *args)

    fun gitIn(workDir: Path, vararg args: String): String {
        val (code, output) = gitRaw(workDir, *args)
        check(code == 0) { "git ${args.joinToString(" ")} failed with $code: $output" }
        return output
    }

    fun gitRaw(workDir: Path, vararg args: String): Pair<Int, String> {
        val builder = ProcessBuilder(listOf("git") + args)
            .directory(workDir.toFile())
            .redirectErrorStream(true)
            .redirectInput(ProcessBuilder.Redirect.from(emptyInput))
        builder.environment().putAll(env)
        val outFile = Files.createTempFile(base, "out", ".txt")
        builder.redirectOutput(outFile.toFile())
        val process = builder.start()
        check(process.waitFor(60, TimeUnit.SECONDS)) { "git ${args.joinToString(" ")} timed out" }
        return process.exitValue() to String(Files.readAllBytes(outFile), StandardCharsets.UTF_8).trim()
    }

    fun write(relative: String, text: String, workDir: Path = dir) = writeBytes(relative, text.toByteArray(), workDir)

    fun writeBytes(relative: String, bytes: ByteArray, workDir: Path = dir) {
        val file = workDir.resolve(relative)
        file.parent.createDirectories()
        file.writeBytes(bytes)
    }

    fun commitAll(message: String, workDir: Path = dir): String {
        gitIn(workDir, "add", "-A")
        gitIn(workDir, "commit", "-q", "-m", message)
        return gitIn(workDir, "rev-parse", "HEAD")
    }

    fun sibling(name: String): Path = base.resolve(name)

    override fun close() {
        Files.walk(base).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { path ->
                path.toFile().setWritable(true)
                try {
                    Files.deleteIfExists(path)
                } catch (e: IOException) {
                    path.toFile().deleteOnExit()
                }
            }
        }
    }

    companion object {
        private val emptyInput: File =Files.createTempFile("testimpact-empty", ".in").toFile().also {
            it.deleteOnExit()
        }

        /**
         * @param init false leaves [dir] as an empty directory that is not a repository.
         * @param autocrlf value of `core.autocrlf`, left unset when null.
         */
        fun create(init: Boolean = true, autocrlf: Boolean? = null): TempGitRepo {
            val base = Files.createTempDirectory("testimpact-git").toRealPath()
            val home = base.resolve("home").also { it.createDirectories() }
            val globalConfig = Files.createFile(base.resolve("gitconfig"))
            val env = mapOf(
                "GIT_CONFIG_NOSYSTEM" to "1",
                "GIT_CONFIG_GLOBAL" to globalConfig.toString(),
                "HOME" to home.toString(),
                "USERPROFILE" to home.toString(),
                "XDG_CONFIG_HOME" to home.toString(),
                "GIT_CEILING_DIRECTORIES" to base.parent.toString(),
                "GIT_AUTHOR_NAME" to "Test",
                "GIT_AUTHOR_EMAIL" to "test@example.com",
                "GIT_COMMITTER_NAME" to "Test",
                "GIT_COMMITTER_EMAIL" to "test@example.com",
                "GIT_TERMINAL_PROMPT" to "0",
            )
            val repo = TempGitRepo(base, env)
            repo.dir.createDirectories()
            if (init) {
                repo.git("init", "-q", "-b", "main")
                repo.git("config", "commit.gpgsign", "false")
                if (autocrlf != null) repo.git("config", "core.autocrlf", autocrlf.toString())
            }
            return repo
        }
    }
}
