package gg.aquatic.testimpact.compat

import gg.aquatic.testimpact.gradle.GitFixture
import gg.aquatic.testimpact.gradle.ImpactFixture
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GradleCompatibilityFunctionalTest {

    @TempDir
    lateinit var projectDir: Path

    @ParameterizedTest(name = "Gradle {0}")
    @MethodSource("versions")
    fun `configuration cache is reused and the selection follows the tree on Gradle`(version: String) {
        ImpactFixture.singleModule(projectDir)
        GitFixture.init(projectDir)
        ImpactFixture.file(projectDir, "src/main/java/app/Calc.java", ImpactFixture.calc(2))
        val selection = projectDir.resolve("build/testimpact/selection/test.sel")

        val first = run(version, "test", "testImpactReport")
        assertContains(first.output, "Configuration cache entry stored")
        assertContains(first.output, ONE_OF_TWO)
        assertEquals(TaskOutcome.SUCCESS, first.task(":test")?.outcome, first.output)
        assertTrue(Files.exists(report("app.CalcTest")))
        assertFalse(Files.exists(report("app.OtherTest")))
        assertFalse(first.output.contains("full:"), first.output)
        val firstSelection = Files.readAllBytes(selection)

        val second = run(version, "test", "testImpactReport")
        assertContains(second.output, "Reusing configuration cache")
        assertContains(second.output, ONE_OF_TWO)
        assertEquals(lines(first), lines(second))
        assertTrue(firstSelection.contentEquals(Files.readAllBytes(selection)), "the selection file changed without a change")
        assertEquals(TaskOutcome.SUCCESS, second.task(":testImpactSelectTest")?.outcome, second.output)
        assertEquals(TaskOutcome.UP_TO_DATE, second.task(":test")?.outcome, second.output)
        assertEquals(TaskOutcome.SUCCESS, second.task(":testImpactReport")?.outcome, second.output)

        ImpactFixture.file(projectDir, "src/main/java/app/Other.java", OTHER_CHANGED)
        val third = run(version, "test", "testImpactReport")
        assertContains(third.output, "Reusing configuration cache")
        assertContains(third.output, "TestImpact :test: changed 2, selected 2 of 2 test classes")
        assertEquals(TaskOutcome.SUCCESS, third.task(":test")?.outcome, third.output)
        assertTrue(Files.exists(report("app.OtherTest")))
        assertFalse(third.output.contains("full:"), third.output)
    }

    @Test
    fun `isolated projects with several modules on the newest Gradle`() {
        ImpactFixture.multiModule(projectDir)
        GitFixture.init(projectDir)
        ImpactFixture.file(projectDir, "a/src/main/java/a/Core.java", ImpactFixture.core(2))
        val newest = CompatMatrix.versionsFor(Runtime.version().feature()).last().version
        val isolated = "-Dorg.gradle.unsafe.isolated-projects=true"

        val first = run(newest, "test", isolated)
        assertEquals(TaskOutcome.SUCCESS, first.task(":a:test")?.outcome, first.output)
        assertEquals(TaskOutcome.SKIPPED, first.task(":b:test")?.outcome, first.output)

        val second = run(newest, "test", isolated)
        assertContains(second.output, "Reusing configuration cache")
        assertEquals(lines(first), lines(second))
        assertTrue(lines(first).isNotEmpty())
        assertEquals(TaskOutcome.UP_TO_DATE, second.task(":a:test")?.outcome, second.output)
        assertEquals(TaskOutcome.SKIPPED, second.task(":b:test")?.outcome, second.output)
    }

    @Test
    fun `the test JVM is the requested JDK`() {
        val requested = System.getProperty("testimpact.testJava")
        assumeTrue(requested != null, "testimpact.testJava is not set")
        assertEquals(requested.toInt(), Runtime.version().feature())
    }

    private fun report(className: String): Path = projectDir.resolve("build/test-results/test/TEST-$className.xml")

    private fun lines(result: BuildResult): List<String> =
        result.output.lines().filter { it.startsWith("TestImpact ") }.sorted()

    private fun run(version: String, vararg args: String): BuildResult =
        GradleRunner.create()
            .withProjectDir(projectDir.toFile())
            .withPluginClasspath()
            .withGradleVersion(version)
            .withArguments(args.toList() + listOf("--configuration-cache", "--warning-mode=fail", "--no-watch-fs", "--stacktrace"))
            .forwardOutput()
            .build()

    companion object {
        private const val ONE_OF_TWO = "TestImpact :test: changed 1, selected 1 of 2 test classes"
        private const val OTHER_CHANGED =
            "package app;\n\npublic class Other {\n    public String name() { return \"changed\"; }\n}\n"

        @JvmStatic
        fun versions(): List<String> = CompatMatrix.versionsFor(Runtime.version().feature()).map { it.version }
    }
}
