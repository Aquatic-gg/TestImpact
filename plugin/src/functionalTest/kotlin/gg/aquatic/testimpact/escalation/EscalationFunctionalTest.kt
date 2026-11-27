package gg.aquatic.testimpact.escalation

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EscalationFunctionalTest {

    @TempDir
    lateinit var projectDir: Path

    @TempDir
    lateinit var testKitDir: Path

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
