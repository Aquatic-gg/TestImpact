package gg.aquatic.testimpact.change

import gg.aquatic.testimpact.change.internal.GitFailure
import gg.aquatic.testimpact.change.internal.GitRunner
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GitRunnerTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("testimpact-runner")
        dir.resolve("Fixture.java").writeText(FIXTURE)
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun runner(
        timeout: Duration = Duration.ofSeconds(60),
        env: Map<String, String> = emptyMap(),
        executable: String = Path.of(System.getProperty("java.home"), "bin", "java").toString(),
    ) = GitRunner(executable, timeout, env, baseArgs = emptyList())

    private fun fixture(vararg args: String) = listOf("Fixture.java") + args

    @Test
    fun timeoutKillsTheProcessAndFailsWithGitFailed() {
        val started = System.nanoTime()
        val failure = assertFailsWith<GitFailure> { runner(Duration.ofSeconds(3)).run(dir, fixture("sleep")) }
        assertEquals(UnknownReason.GIT_FAILED, failure.reason)
        assertTrue(failure.detail.contains("timed out"), failure.detail)
        assertTrue(Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(40))
    }

    @Test
    fun failingExitCodeKeepsFirstStderrLine() {
        val result = runner().run(dir, fixture("fail"))
        assertEquals(128, result.exitCode)
        assertEquals("fatal: something broke", result.detail)
    }

    @Test
    fun missingExecutableIsGitUnavailable() {
        val failure = assertFailsWith<GitFailure> {
            runner(executable = dir.resolve("no-such-git").toString()).run(dir, listOf("status"))
        }
        assertEquals(UnknownReason.GIT_UNAVAILABLE, failure.reason)
    }

    @Test
    fun missingWorkingDirectoryIsGitFailed() {
        val failure = assertFailsWith<GitFailure> { runner().run(dir.resolve("absent"), fixture("echo")) }
        assertEquals(UnknownReason.GIT_FAILED, failure.reason)
    }

    @Test
    fun stdinIsPassedByteExact() {
        val input = byteArrayOf(0, 13, 10, -1, 65, 10)
        assertContentEquals(input, runner().run(dir, fixture("echo"), input).stdout)
    }

    @Test
    fun emptyStdinIsEndOfInput() {
        assertEquals(0, runner().run(dir, fixture("echo")).stdout.size)
    }

    @Test
    fun largeOutputOnBothStreamsDoesNotBlock() {
        val result = runner().run(dir, fixture("big"))
        assertEquals(0, result.exitCode)
        assertEquals(2_000_000, result.stdout.size)
    }

    @Test
    fun environmentIsPinnedAndRepositoryOverridesAreRemoved() {
        val env = mapOf("GIT_DIR" to "x", "GIT_WORK_TREE" to "x", "GIT_INDEX_FILE" to "x", "GIT_PREFIX" to "x", "LC_ALL" to "x")
        fun value(name: String) = String(runner(env = env).run(dir, fixture("env", name)).stdout).trim()
        assertEquals("null", value("GIT_DIR"))
        assertEquals("null", value("GIT_WORK_TREE"))
        assertEquals("null", value("GIT_INDEX_FILE"))
        assertEquals("null", value("GIT_PREFIX"))
        assertEquals("x", value("LC_ALL"))
        assertEquals("0", String(runner().run(dir, fixture("env", "GIT_OPTIONAL_LOCKS")).stdout).trim())
        assertEquals("C", String(runner().run(dir, fixture("env", "LC_ALL")).stdout).trim())
        assertEquals("0", String(runner().run(dir, fixture("env", "GIT_TERMINAL_PROMPT")).stdout).trim())
    }

    @Test
    fun everyRepositoryLocalVariableIsRemoved() {
        val local = listOf(
            "GIT_ALTERNATE_OBJECT_DIRECTORIES", "GIT_CONFIG", "GIT_CONFIG_PARAMETERS", "GIT_CONFIG_COUNT",
            "GIT_OBJECT_DIRECTORY", "GIT_DIR", "GIT_WORK_TREE", "GIT_IMPLICIT_WORK_TREE", "GIT_GRAFT_FILE",
            "GIT_INDEX_FILE", "GIT_NO_REPLACE_OBJECTS", "GIT_REPLACE_REF_BASE", "GIT_PREFIX", "GIT_SHALLOW_FILE",
            "GIT_COMMON_DIR",
        )
        val env = local.associateWith { "poison" }
        for (name in local) {
            assertEquals("null", String(runner(env = env).run(dir, fixture("env", name)).stdout).trim(), name)
        }
    }

    @Test
    fun extraEnvironmentIsAppliedAfterTheRemoval() {
        val result = runner().run(dir, fixture("env", "GIT_INDEX_FILE"), extraEnv = mapOf("GIT_INDEX_FILE" to "copy"))
        assertEquals("copy", String(result.stdout).trim())
    }

    private companion object {
        const val FIXTURE = """
import java.util.Arrays;

public class Fixture {
    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "sleep":
                Thread.sleep(120000);
                break;
            case "fail":
                System.err.println();
                System.err.println("fatal: something broke");
                System.err.println("second line");
                System.exit(128);
                break;
            case "echo":
                System.out.write(System.in.readAllBytes());
                System.out.flush();
                break;
            case "env":
                System.out.print(System.getenv(args[1]));
                break;
            case "big":
                byte[] chunk = new byte[1000];
                Arrays.fill(chunk, (byte) 'x');
                for (int i = 0; i < 2000; i++) {
                    System.out.write(chunk);
                    System.err.write(chunk);
                }
                System.out.flush();
                System.err.flush();
                break;
            default:
                System.exit(2);
        }
    }
}
"""
    }
}
