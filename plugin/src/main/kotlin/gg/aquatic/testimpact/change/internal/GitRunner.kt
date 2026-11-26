package gg.aquatic.testimpact.change.internal

import gg.aquatic.testimpact.change.UnknownReason
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Runs git with stdin, stdout and stderr redirected to temp files, so no reader thread is needed and a full pipe
 * can never block the child.
 */
internal class GitRunner(
    private val executable: String = "git",
    private val timeout: Duration = Duration.ofSeconds(60),
    private val envOverrides: Map<String, String> = emptyMap(),
    private val baseArgs: List<String> = GIT_BASE_ARGS,
) {

    fun run(workDir: Path, args: List<String>, stdin: ByteArray? = null): GitResult {
        if (!Files.isDirectory(workDir)) {
            throw GitFailure(UnknownReason.GIT_FAILED, "working directory does not exist: $workDir")
        }
        val label = args.firstOrNull() ?: "git"
        val inFile = Files.createTempFile("testimpact-git", ".in")
        val outFile = Files.createTempFile("testimpact-git", ".out")
        val errFile = Files.createTempFile("testimpact-git", ".err")
        try {
            Files.write(inFile, stdin ?: ByteArray(0))
            val builder = ProcessBuilder(listOf(executable) + baseArgs + args)
                .directory(workDir.toFile())
                .redirectInput(inFile.toFile())
                .redirectOutput(outFile.toFile())
                .redirectError(errFile.toFile())
            configureEnvironment(builder.environment())
            val process = try {
                builder.start()
            } catch (e: IOException) {
                throw GitFailure(UnknownReason.GIT_UNAVAILABLE, "cannot start $executable: ${e.message}".take(MAX_DETAIL))
            }
            val finished = try {
                process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                destroy(process)
                Thread.currentThread().interrupt()
                throw GitFailure(UnknownReason.GIT_FAILED, "git $label interrupted")
            }
            if (!finished) {
                destroy(process)
                throw GitFailure(UnknownReason.GIT_FAILED, "git $label timed out after ${timeout.toMillis()} ms")
            }
            return GitResult(
                exitCode = process.exitValue(),
                stdout = Files.readAllBytes(outFile),
                detail = firstLine(Files.readAllBytes(errFile)),
            )
        } catch (e: IOException) {
            throw GitFailure(UnknownReason.GIT_FAILED, "git $label i/o error: ${e.message}".take(MAX_DETAIL))
        } finally {
            delete(inFile)
            delete(outFile)
            delete(errFile)
        }
    }

    private fun configureEnvironment(env: MutableMap<String, String>) {
        env["LC_ALL"] = "C"
        env["LANG"] = "C"
        env["GIT_TERMINAL_PROMPT"] = "0"
        env["GIT_OPTIONAL_LOCKS"] = "0"
        env["GIT_PAGER"] = "cat"
        env.putAll(envOverrides)
        for (name in REMOVED_ENV) env.remove(name)
    }

    private fun destroy(process: Process) {
        process.descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly()
        process.waitFor(DESTROY_WAIT_SECONDS, TimeUnit.SECONDS)
    }

    private fun firstLine(stderr: ByteArray): String =
        String(stderr, StandardCharsets.UTF_8).lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
            .orEmpty().take(MAX_DETAIL)

    private fun delete(file: Path) {
        try {
            Files.deleteIfExists(file)
        } catch (e: IOException) {
            file.toFile().deleteOnExit()
        }
    }

    private companion object {
        const val MAX_DETAIL = 500
        const val DESTROY_WAIT_SECONDS = 5L
        val GIT_BASE_ARGS = listOf("--no-pager", "-c", "core.quotepath=false", "-c", "color.ui=never")
        val REMOVED_ENV = listOf("GIT_DIR", "GIT_WORK_TREE", "GIT_INDEX_FILE", "GIT_PREFIX")
    }
}
