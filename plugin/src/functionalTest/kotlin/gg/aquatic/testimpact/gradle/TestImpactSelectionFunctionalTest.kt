package gg.aquatic.testimpact.gradle

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TestImpactSelectionFunctionalTest {

    @TempDir
    lateinit var projectDir: Path

    @TempDir
    lateinit var cacheDir: Path

    @Test
    fun `a changed class in a single module runs only its test`() {
        ImpactFixture.singleModule(projectDir)
        GitFixture.init(projectDir)
        ImpactFixture.file(projectDir, "src/main/java/app/Calc.java", ImpactFixture.calc(2))

        val result = run("test")

        assertEquals(TaskOutcome.SUCCESS, result.task(":test")?.outcome)
        assertTrue(Files.exists(report("", "app.CalcTest")))
        assertFalse(Files.exists(report("", "app.OtherTest")))
        assertTrue(Regex("TestImpact :test: changed 1, selected 1 of 2 test classes").containsMatchIn(result.output), result.output)
    }

    @Test
    fun `a change in one module reaches dependants in java and kotlin and skips the rest`() {
        multiModuleChanged()

        val result = run("test")

        assertEquals(TaskOutcome.SUCCESS, result.task(":a:test")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":c:test")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":k:test")?.outcome)
        assertEquals(TaskOutcome.SKIPPED, result.task(":b:test")?.outcome)
        assertEquals(TaskOutcome.SKIPPED, result.task(":e:test")?.outcome)
        assertContains(result.output, "TestImpact :e:test: changed 1, selected 0 of 1 test classes")
        assertTrue(Files.exists(report("c/", "c.CoreUserTest\$Inner")), "the @Nested class must run")
        assertTrue(Files.exists(report("k/", "k.CallerTest")))
        assertFalse(Files.exists(report("e/", "e.UtilUserTest")))
    }

    @Test
    fun `tests on the command line win over the selection`() {
        ImpactFixture.singleModule(projectDir)
        GitFixture.init(projectDir)
        ImpactFixture.file(projectDir, "src/main/java/app/Calc.java", ImpactFixture.calc(2))

        val result = run("test", "--tests", "app.OtherTest")

        assertTrue(Files.exists(report("", "app.OtherTest")))
        assertFalse(Files.exists(report("", "app.CalcTest")))
        assertContains(result.output, "full: user-tests")
    }

    @Test
    fun `a test change in a dependency module does not force its dependants`() {
        ImpactFixture.multiModule(projectDir)
        GitFixture.init(projectDir)
        ImpactFixture.file(
            projectDir, "a/src/test/java/a/CoreTest.java",
            "package a;\n\nimport org.junit.jupiter.api.Test;\n\nclass CoreTest {\n    @Test\n    void runs() { new Core().value(); new Core().value(); }\n}\n",
        )

        val result = run("test")

        assertEquals(TaskOutcome.SUCCESS, result.task(":a:test")?.outcome)
        assertEquals(TaskOutcome.SKIPPED, result.task(":c:test")?.outcome)
        assertEquals(TaskOutcome.SKIPPED, result.task(":k:test")?.outcome)
        assertContains(result.output, "TestImpact :c:test: changed 1, selected 0 of 1 test classes")
    }

    @Test
    fun `excluding the selection task runs the whole test task`() {
        ImpactFixture.singleModule(projectDir)
        GitFixture.init(projectDir)
        ImpactFixture.file(projectDir, "src/main/java/app/Calc.java", ImpactFixture.calc(2))

        val result = run("test", "-x", "testImpactSelectTest")

        assertEquals(TaskOutcome.SUCCESS, result.task(":test")?.outcome)
        assertTrue(Files.exists(report("", "app.CalcTest")))
        assertTrue(Files.exists(report("", "app.OtherTest")))
        assertFalse(result.output.contains("TestImpact :test:"), result.output)
    }

    @Test
    fun `mode off through the gradle property leaves the build as without the plugin`() {
        offBuildIsStock(arguments = listOf("-PtestImpact=off"))
    }

    @Test
    fun `mode off through the environment leaves the build as without the plugin`() {
        offBuildIsStock(environment = mapOf("TEST_IMPACT" to "off"))
    }

    @Test
    fun `mode off in the build script leaves the build as without the plugin`() {
        offBuildIsStock(extra = "testImpact { mode = \"off\" }")
    }

    @Test
    fun `a resource change and a build script change run the full task`() {
        ImpactFixture.singleModule(projectDir)
        ImpactFixture.file(projectDir, "src/main/resources/app.properties", "a=1\n")
        GitFixture.init(projectDir)

        ImpactFixture.file(projectDir, "src/main/resources/app.properties", "a=2\n")
        val resource = run("test")
        assertContains(resource.output, "full: change-not-mapped: resource change")
        assertTrue(Files.exists(report("", "app.CalcTest")))
        assertTrue(Files.exists(report("", "app.OtherTest")))

        GitFixture.commitAll(projectDir, "resource")
        Files.writeString(projectDir.resolve("build.gradle.kts"), Files.readString(projectDir.resolve("build.gradle.kts")) + "\n// edited\n")
        val script = run("test")
        assertContains(script.output, "full: change-not-mapped: build logic change")
    }

    @Test
    fun `a build outside a git repository runs the full task and succeeds`() {
        ImpactFixture.singleModule(projectDir)

        val result = run("test")

        assertEquals(TaskOutcome.SUCCESS, result.task(":test")?.outcome)
        assertContains(result.output, "full: changes-unknown")
        assertTrue(Files.exists(report("", "app.CalcTest")))
    }

    @Test
    fun `a build script filter without a match in the selection does not fail the build`() {
        ImpactFixture.singleModule(
            projectDir,
            extra = "tasks.test { filter { includeTestsMatching(\"*IT\") } }",
        )
        ImpactFixture.file(projectDir, "src/test/java/app/OtherIT.java", ImpactFixture.test("OtherIT", "new Other().name();"))
        GitFixture.init(projectDir)
        ImpactFixture.file(projectDir, "src/main/java/app/Calc.java", ImpactFixture.calc(2))

        val result = run("test")

        assertNotNull(result.task(":test"))
        assertContains(result.output, "TestImpact :test: changed 1, selected 1 of 3 test classes")
    }

    @Test
    fun `a clean tree skips everything and always run classes still run`() {
        ImpactFixture.singleModule(projectDir)
        GitFixture.init(projectDir)

        val clean = run("test")
        assertEquals(TaskOutcome.SKIPPED, clean.task(":test")?.outcome)
        assertContains(clean.output, "changed 0, selected 0 of 2 test classes")

        ImpactFixture.file(projectDir, "src/test/java/app/SmokeTest.java", ImpactFixture.test("SmokeTest", "new Other().name();"))
        Files.writeString(
            projectDir.resolve("build.gradle.kts"),
            Files.readString(projectDir.resolve("build.gradle.kts")) + "\ntestImpact { alwaysRun.add(\"*SmokeTest\") }\n",
        )
        GitFixture.commitAll(projectDir, "smoke")
        val smoke = run("test")
        assertContains(smoke.output, "changed 0, selected 1 of 3 test classes")
        assertTrue(Files.exists(report("", "app.SmokeTest")))
        assertFalse(Files.exists(report("", "app.CalcTest")))
    }

    @Test
    fun `an invalid mode or property fails the build naming where it came from`() {
        ImpactFixture.singleModule(projectDir, extra = "testImpact { mode = \"bogus\" }")
        GitFixture.init(projectDir)
        val dsl = runAndFail("test")
        assertContains(dsl.output, "mode")
        assertContains(dsl.output, "bogus")

        val property = runAndFail("test", "-PtestImpact=bogus")
        assertContains(property.output, "testImpact")
        assertContains(property.output, "bogus")
    }

    @Test
    fun `the configuration cache is reused with the same console line`() {
        multiModuleChanged()

        val first = run("test")
        val second = run("test")

        assertContains(second.output, "Reusing configuration cache")
        assertEquals(lines(first), lines(second))
        assertTrue(lines(first).isNotEmpty())
    }

    @Test
    fun `isolated projects report no problem from the plugin`() {
        ImpactFixture.singleModule(projectDir)
        GitFixture.init(projectDir)
        ImpactFixture.file(projectDir, "src/main/java/app/Calc.java", ImpactFixture.calc(2))

        val result = run("test", "-Dorg.gradle.unsafe.isolated-projects=true")

        assertFalse(result.output.contains("gg.aquatic.testimpact"), result.output)
        assertContains(result.output, "TestImpact :test: changed 1")
    }

    @Test
    fun `the oldest supported gradle selects like the current one`() {
        ImpactFixture.singleModule(projectDir)
        GitFixture.init(projectDir)
        ImpactFixture.file(projectDir, "src/main/java/app/Calc.java", ImpactFixture.calc(2))

        val result = run("test", version = "9.0")

        assertEquals(TaskOutcome.SUCCESS, result.task(":test")?.outcome)
        assertTrue(Files.exists(report("", "app.CalcTest")))
        assertFalse(Files.exists(report("", "app.OtherTest")))
        assertContains(result.output, "TestImpact :test: changed 1, selected 1 of 2 test classes")
    }

    private fun multiModuleChanged() {
        ImpactFixture.multiModule(projectDir)
        GitFixture.init(projectDir)
        ImpactFixture.file(projectDir, "a/src/main/java/a/Core.java", ImpactFixture.core(2))
    }

    private fun offBuildIsStock(
        arguments: List<String> = emptyList(),
        environment: Map<String, String> = emptyMap(),
        extra: String = "",
    ) {
        val cache = cacheDir.toString().replace('\\', '/')
        val settingsExtra = "buildCache { local { directory = file(\"$cache\") } }\n"
        ImpactFixture.singleModule(projectDir, pluginLine = "", settingsExtra = settingsExtra)
        GitFixture.init(projectDir)
        val stock = run("test", "--build-cache")
        assertEquals(TaskOutcome.SUCCESS, stock.task(":test")?.outcome)

        ImpactFixture.singleModule(projectDir, extra = extra, settingsExtra = settingsExtra)
        val args = listOf("clean", "test", "--build-cache") + arguments
        val off = run(*args.toTypedArray(), environment = environment)
        assertEquals(TaskOutcome.FROM_CACHE, off.task(":test")?.outcome, off.output)
        assertFalse(off.output.contains("TestImpact"), off.output)

        val dry = run(*(listOf("test", "--dry-run") + arguments).toTypedArray(), environment = environment)
        assertFalse(dry.output.contains("testImpactSelect"), dry.output)
    }

    private fun report(module: String, className: String): Path =
        projectDir.resolve("${module}build/test-results/test/TEST-$className.xml")

    private fun lines(result: BuildResult): List<String> =
        result.output.lines().filter { it.startsWith("TestImpact ") }.sorted()

    private fun runner(
        args: Array<out String>,
        version: String?,
        environment: Map<String, String>,
    ): GradleRunner {
        val runner = GradleRunner.create()
            .withProjectDir(projectDir.toFile())
            .withPluginClasspath()
            .withArguments(args.toList() + listOf("--configuration-cache", "--warning-mode=fail", "--no-watch-fs", "--stacktrace"))
            .forwardOutput()
        if (version != null) runner.withGradleVersion(version)
        if (environment.isNotEmpty()) runner.withEnvironment(System.getenv() + environment)
        return runner
    }

    private fun run(vararg args: String, version: String? = null, environment: Map<String, String> = emptyMap()): BuildResult =
        runner(args, version, environment).build()

    private fun runAndFail(vararg args: String): BuildResult = runner(args, null, emptyMap()).buildAndFail()
}
