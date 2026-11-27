package gg.aquatic.testimpact.escalation

import gg.aquatic.testimpact.gradle.ImpactFixture
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.gradle.tooling.BuildException
import org.gradle.tooling.GradleConnector
import org.gradle.util.GradleVersion
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EscalationFunctionalTest {

    @TempDir
    lateinit var projectDir: Path

    @TempDir
    lateinit var testKitDir: Path

    @TempDir
    lateinit var scriptDir: Path

    @Test
    fun `a red full run is followed by a quick phase and a full run that clears it`() {
        EscalationFixture.singleModule(projectDir)
        EscalationFixture.calc(projectDir, 1)

        val red = runAndFail("-PtestImpact=full", "test")
        assertEquals(TaskOutcome.FAILED, red.task(":test")?.outcome, red.output)
        val state = EscalationFixture.state(projectDir)
        assertContains(state, "full-run-required=true")
        assertContains(state, "red=app.CalcTest")
        assertFalse(state.contains("red=app.OtherTest"), state)

        val again = runAndFail("-PtestImpact=full", "test")
        assertContains(again.output, "Reusing configuration cache")
        assertEquals(TaskOutcome.FAILED, again.task(":test")?.outcome, again.output)

        EscalationFixture.calc(projectDir, 0)
        EscalationFixture.clearResults(projectDir)
        val fixed = run("test")

        assertEquals(TaskOutcome.SUCCESS, fixed.task(":testImpactQuickTest")?.outcome, fixed.output)
        assertEquals(TaskOutcome.SUCCESS, fixed.task(":test")?.outcome, fixed.output)
        assertTrue(EscalationFixture.hasReport(projectDir, "app.CalcTest", "testImpactQuickTest"))
        assertFalse(EscalationFixture.hasReport(projectDir, "app.OtherTest", "testImpactQuickTest"))
        assertTrue(EscalationFixture.hasReport(projectDir, "app.CalcTest"))
        assertTrue(EscalationFixture.hasReport(projectDir, "app.OtherTest"))
        assertVerdict(EscalationFixture.state(projectDir))

        val repeat = run("test")
        assertContains(repeat.output, "Reusing configuration cache")
        assertVerdict(EscalationFixture.state(projectDir))
    }

    @Test
    fun `a quick phase that is still red stops the build before the full run`() {
        EscalationFixture.singleModule(projectDir)
        EscalationFixture.calc(projectDir, 1)
        runAndFail("-PtestImpact=full", "test")

        EscalationFixture.calc(projectDir, 2)
        EscalationFixture.clearResults(projectDir)
        val result = runAndFail("test")

        assertEquals(TaskOutcome.FAILED, result.task(":test")?.outcome, result.output)
        assertContains(result.output, "the quick phase is red")
        assertContains(result.output, "app.CalcTest")
        assertContains(result.output, "-x testImpactQuickTest")
        assertFalse(EscalationFixture.hasReport(projectDir, "app.OtherTest"), "the full run must not start")
        assertContains(EscalationFixture.state(projectDir), "full-run-required=true")

        val again = runAndFail("test")
        assertContains(again.output, "Reusing configuration cache")
        assertEquals(TaskOutcome.FAILED, again.task(":test")?.outcome, again.output)
    }

    @Test
    fun `a test that kills its jvm leaves a failure without a class and a full run owed`() {
        EscalationFixture.singleModule(projectDir)
        EscalationFixture.crashTest(projectDir)

        val result = runAndFail("-PtestImpact=full", "test")

        assertEquals(TaskOutcome.FAILED, result.task(":test")?.outcome, result.output)
        val state = EscalationFixture.state(projectDir)
        assertContains(state, "full-run-required=true")
        assertContains(state, "unknown-failure=true")
    }

    @Test
    fun `a build that dies between the quick phase and the full run leaves the run owed and the next build does both`() {
        EscalationFixture.singleModule(projectDir)
        EscalationFixture.calc(projectDir, 1)
        runAndFail("-PtestImpact=full", "test")
        EscalationFixture.calc(projectDir, 0)

        val killed = runCatching { runner("test", "-Phalt", kitDir = testKitDir).build() }
        assertTrue(killed.isFailure, "the build must not survive the halt")
        val state = EscalationFixture.state(projectDir)
        assertContains(state, "full-run-required=true")
        assertContains(state, "last-phase=quick")
        assertContains(state, "last-result=green")

        EscalationFixture.clearResults(projectDir)
        val next = run("test")

        assertEquals(TaskOutcome.SUCCESS, next.task(":testImpactQuickTest")?.outcome, next.output)
        assertEquals(TaskOutcome.SUCCESS, next.task(":test")?.outcome, next.output)
        assertTrue(EscalationFixture.hasReport(projectDir, "app.OtherTest"))
        assertVerdict(EscalationFixture.state(projectDir))
    }

    @Test
    fun `the quick task alone passes, says the full run is still required and leaves it required`() {
        EscalationFixture.singleModule(projectDir)
        EscalationFixture.calc(projectDir, 1)
        runAndFail("-PtestImpact=full", "test")
        EscalationFixture.calc(projectDir, 0)
        EscalationFixture.clearResults(projectDir)

        val result = run("testImpactQuickTest")

        assertEquals(TaskOutcome.SUCCESS, result.task(":testImpactQuickTest")?.outcome, result.output)
        assertContains(result.output, "the full run is still required")
        assertNullTask(result, ":test")
        val state = EscalationFixture.state(projectDir)
        assertContains(state, "full-run-required=true")
        assertContains(state, "last-phase=quick")
        assertContains(state, "last-result=green")

        val repeat = run("testImpactQuickTest")
        assertContains(repeat.output, "Reusing configuration cache")
        assertEquals(TaskOutcome.SUCCESS, repeat.task(":testImpactQuickTest")?.outcome, repeat.output)
    }

    @Test
    fun `the quick task asked for by name fails the build while the class is still red`() {
        EscalationFixture.singleModule(projectDir)
        EscalationFixture.calc(projectDir, 1)
        runAndFail("-PtestImpact=full", "test")

        val result = runAndFail("testImpactQuickTest")

        assertEquals(TaskOutcome.FAILED, result.task(":testImpactQuickTest")?.outcome, result.output)
        assertContains(EscalationFixture.state(projectDir), "full-run-required=true")
    }

    @Test
    fun `the quick task asked for by an abbreviation fails the build while the class is still red`() {
        EscalationFixture.singleModule(projectDir)
        EscalationFixture.calc(projectDir, 1)
        runAndFail("-PtestImpact=full", "test")

        val result = runAndFail("tIQT")

        assertEquals(TaskOutcome.FAILED, result.task(":testImpactQuickTest")?.outcome, result.output)
        assertNullTask(result, ":test")
        val again = runAndFail("tIQT")
        assertContains(again.output, "Reusing configuration cache")
        assertEquals(TaskOutcome.FAILED, again.task(":testImpactQuickTest")?.outcome, again.output)
    }

    @Test
    fun `two projects under parallel and continue stop and finish independently`() {
        EscalationFixture.twoModules(projectDir)
        EscalationFixture.bug(projectDir, "a", 1)
        EscalationFixture.bug(projectDir, "b", 1)
        val red = runAndFail("-PtestImpact=full", "--parallel", "--continue", "test")
        assertEquals(TaskOutcome.FAILED, red.task(":a:test")?.outcome, red.output)
        assertEquals(TaskOutcome.FAILED, red.task(":b:test")?.outcome, red.output)

        EscalationFixture.bug(projectDir, "b", 0)
        val result = runAndFail("--parallel", "--continue", "test")

        assertEquals(TaskOutcome.FAILED, result.task(":a:test")?.outcome, result.output)
        assertContains(result.output, "the quick phase is red")
        assertEquals(TaskOutcome.SUCCESS, result.task(":b:testImpactQuickTest")?.outcome, result.output)
        assertEquals(TaskOutcome.SUCCESS, result.task(":b:test")?.outcome, result.output)
        val a = EscalationFixture.state(projectDir.resolve("a"))
        assertContains(a, "full-run-required=true")
        assertContains(a, "red=a.CoreTest")
        assertVerdict(EscalationFixture.state(projectDir.resolve("b")))

        val again = runAndFail("--parallel", "--continue", "test")
        assertContains(again.output, "Reusing configuration cache")
        assertEquals(TaskOutcome.FAILED, again.task(":a:test")?.outcome, again.output)
        assertVerdict(EscalationFixture.state(projectDir.resolve("b")))
    }

    @Test
    fun `the build cache never serves a full run owed or the quick phase`() {
        EscalationFixture.singleModule(projectDir, settingsExtra = EscalationFixture.localCache(scriptDir))
        EscalationFixture.calc(projectDir, 0)
        val stored = run("-PtestImpact=full", "--build-cache", "test")
        assertEquals(TaskOutcome.SUCCESS, stored.task(":test")?.outcome, stored.output)

        EscalationFixture.calc(projectDir, 1)
        runAndFail("-PtestImpact=full", "--build-cache", "test")
        EscalationFixture.calc(projectDir, 0)
        val served = run("-PtestImpact=full", "--build-cache", "test")
        assertEquals(TaskOutcome.SUCCESS, served.task(":test")?.outcome, served.output)
        assertVerdict(EscalationFixture.state(projectDir))

        EscalationFixture.calc(projectDir, 1)
        runAndFail("-PtestImpact=full", "--build-cache", "test")
        EscalationFixture.calc(projectDir, 0)
        EscalationFixture.clearResults(projectDir)
        val followUp = run("--build-cache", "test")
        assertEquals(TaskOutcome.SUCCESS, followUp.task(":testImpactQuickTest")?.outcome, followUp.output)
        assertEquals(TaskOutcome.SUCCESS, followUp.task(":test")?.outcome, followUp.output)
        assertVerdict(EscalationFixture.state(projectDir))

        val rerun = run("--build-cache", "test")
        assertContains(rerun.output, "Reusing configuration cache")
        assertEquals(TaskOutcome.SKIPPED, rerun.task(":testImpactQuickTest")?.outcome, rerun.output)
        assertEquals(TaskOutcome.UP_TO_DATE, rerun.task(":test")?.outcome, rerun.output)
        assertVerdict(EscalationFixture.state(projectDir))
    }

    @Test
    fun `a test launched by name from the tooling api runs with a full run owed and leaves it owed`() {
        EscalationFixture.singleModule(projectDir, pluginLine = "", extra = "defaultTasks(\"build\")\n")
        EscalationFixture.calc(projectDir, 1)
        val script = ImpactFixture.initScript(scriptDir)
        val arguments = arrayOf("--init-script", script.toString(), "--no-watch-fs", "--configuration-cache")

        GradleConnector.newConnector().forProjectDirectory(projectDir.toFile())
            .useGradleVersion(GradleVersion.current().version)
            .connect()
            .use { connection ->
                assertFailsWith<BuildException> {
                    connection.newBuild().forTasks("test").withArguments("-PtestImpact=full", *arguments).run()
                }
            }
        assertContains(EscalationFixture.state(projectDir), "red=app.CalcTest")
        EscalationFixture.clearResults(projectDir)

        val outputs = (1..2).map {
            val output = ByteArrayOutputStream()
            try {
                GradleConnector.newConnector().forProjectDirectory(projectDir.toFile())
                    .useGradleVersion(GradleVersion.current().version)
                    .connect()
                    .use { connection ->
                        connection.newTestLauncher()
                            .withTaskAndTestClasses(":test", listOf("app.OtherTest"))
                            .withArguments(*arguments)
                            .setStandardOutput(output)
                            .setStandardError(output)
                            .run()
                    }
            } catch (e: Exception) {
                throw AssertionError("the launch failed: $output", e)
            }
            output.toString()
        }

        assertTrue(EscalationFixture.hasReport(projectDir, "app.OtherTest"), outputs[0])
        assertFalse(EscalationFixture.hasReport(projectDir, "app.CalcTest"))
        assertContains(outputs[1], "Reusing configuration cache")
        val state = EscalationFixture.state(projectDir)
        assertContains(state, "full-run-required=true")
        assertContains(state, "red=app.CalcTest")
    }

    @Test
    fun `mode full and a named test skip the quick phase and mode off adds no quick task`() {
        EscalationFixture.singleModule(projectDir)
        EscalationFixture.calc(projectDir, 1)
        runAndFail("-PtestImpact=full", "test")
        EscalationFixture.calc(projectDir, 0)
        EscalationFixture.clearResults(projectDir)

        val named = run("test", "--tests", "app.OtherTest")
        assertEquals(TaskOutcome.SKIPPED, named.task(":testImpactQuickTest")?.outcome, named.output)
        assertEquals(TaskOutcome.SUCCESS, named.task(":test")?.outcome, named.output)
        assertTrue(EscalationFixture.hasReport(projectDir, "app.OtherTest"))
        assertFalse(EscalationFixture.hasReport(projectDir, "app.CalcTest"))
        assertContains(EscalationFixture.state(projectDir), "full-run-required=true")

        EscalationFixture.clearResults(projectDir)
        val full = run("-PtestImpact=full", "test")
        assertEquals(TaskOutcome.SKIPPED, full.task(":testImpactQuickTest")?.outcome, full.output)
        assertEquals(TaskOutcome.SUCCESS, full.task(":test")?.outcome, full.output)
        assertTrue(EscalationFixture.hasReport(projectDir, "app.CalcTest"))
        assertTrue(EscalationFixture.hasReport(projectDir, "app.OtherTest"))
        assertVerdict(EscalationFixture.state(projectDir))

        val repeat = run("-PtestImpact=full", "test")
        assertContains(repeat.output, "Reusing configuration cache")
    }

    @Test
    fun `mode off adds no quick task and no state`() {
        EscalationFixture.singleModule(projectDir)
        EscalationFixture.calc(projectDir, 0)

        val result = run("-PtestImpact=off", "test")

        assertNullTask(result, ":testImpactQuickTest")
        assertEquals(TaskOutcome.SUCCESS, result.task(":test")?.outcome, result.output)
        assertFalse(Files.exists(projectDir.resolve("build/testimpact/escalation")))
        val repeat = run("-PtestImpact=off", "test")
        assertContains(repeat.output, "Reusing configuration cache")
    }

    private fun assertVerdict(state: String) {
        assertContains(state, "full-run-required=false")
        assertContains(state, "last-phase=full")
        assertContains(state, "last-result=green")
        assertFalse(state.lines().any { it.startsWith("red=") }, state)
    }

    private fun assertNullTask(result: BuildResult, path: String) {
        assertEquals(null, result.task(path), "$path must not run: ${result.output}")
    }

    private fun runner(vararg args: String, kitDir: Path? = null): GradleRunner {
        val runner = GradleRunner.create()
            .withProjectDir(projectDir.toFile())
            .withPluginClasspath()
            .withArguments(args.toList() + listOf("--configuration-cache", "--warning-mode=fail", "--no-watch-fs", "--stacktrace"))
            .forwardOutput()
        if (kitDir != null) runner.withTestKitDir(kitDir.toFile())
        return runner
    }

    private fun run(vararg args: String): BuildResult = runner(*args).build()

    private fun runAndFail(vararg args: String): BuildResult = runner(*args).buildAndFail()
}
