package gg.aquatic.testimpact.fallback

import gg.aquatic.testimpact.gradle.GitFixture
import gg.aquatic.testimpact.gradle.ImpactFixture
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The rules that depend on the run history, the toolchain, the class index and failures of the analysis. */
class FallbackHistoryFunctionalTest {

    @TempDir
    lateinit var projectDir: Path

    private val both = listOf("app.CalcTest", "app.OtherTest")

    private fun build(vararg args: String, probe: Boolean = false) = FallbackFixture.build(projectDir, *args, probe = probe)

    private fun marker(task: String = "test"): Path = projectDir.resolve("build/testimpact/history/$task.properties")

    private fun changeCalc() = ImpactFixture.file(projectDir, "src/main/java/app/Calc.java", ImpactFixture.calc(2))

    @Test
    fun `a build without history runs everything, writes the marker and the next build selects`() {
        ImpactFixture.singleModule(projectDir)
        GitFixture.init(projectDir)

        FallbackFixture.assertFull(projectDir, build("test"), "first-run", both)
        assertTrue(Files.exists(marker()))

        changeCalc()
        val partial = build("test")

        assertFalse(partial.output.contains("full:"), partial.output)
        assertContains(partial.output, "selected 1 of 2 test classes")
        assertTrue(FallbackFixture.ran(projectDir, "app.CalcTest"))
        assertFalse(FallbackFixture.ran(projectDir, "app.OtherTest"))

        val again = build("test")
        assertContains(again.output, "Reusing configuration cache")
        assertFalse(again.output.contains("full:"), again.output)
    }

    @Test
    fun `a new test set without a marker runs in full while the test task selects`() {
        val suite = """
            testing {
                suites {
                    register<JvmTestSuite>("integrationTest") {
                        useJUnitJupiter("5.14.4")
                        dependencies { implementation(project()) }
                    }
                }
            }
        """.trimIndent()
        ImpactFixture.singleModule(projectDir, extra = suite)
        ImpactFixture.file(projectDir, "src/integrationTest/java/app/CalcIT.java", ImpactFixture.test("CalcIT", "new Calc().add(1, 2);"))
        GitFixture.init(projectDir)
        FallbackFixture.primeHistory(projectDir)
        changeCalc()

        val result = build("test", "integrationTest")

        assertTrue(Regex("TestImpact :integrationTest:.*full: first-run").containsMatchIn(result.output), result.output)
        assertTrue(Regex("TestImpact :test: changed 1, selected 1 of 2 test classes").containsMatchIn(result.output), result.output)
        assertTrue(FallbackFixture.ran(projectDir, "app.CalcIT", "integrationTest"))
    }

    @Test
    fun `a changed toolchain file runs everything`() {
        ImpactFixture.singleModule(projectDir)
        ImpactFixture.file(projectDir, ".java-version", "21\n")
        GitFixture.init(projectDir)
        FallbackFixture.primeHistory(projectDir)
        ImpactFixture.file(projectDir, ".java-version", "17\n")

        FallbackFixture.assertFull(projectDir, build("test"), "toolchain-change", both)
    }

    @Test
    fun `a marker of another launcher runs everything`() {
        ImpactFixture.singleModule(projectDir)
        GitFixture.init(projectDir)
        FallbackFixture.primeHistory(projectDir, toolchain = "other-jdk")
        changeCalc()

        FallbackFixture.assertFull(projectDir, build("test"), "toolchain-change", both)
    }

    @Test
    fun `a class file the graph cannot read runs everything`() {
        ImpactFixture.singleModule(projectDir)
        Files.write(projectDir.resolve("src/test/resources/Bad.class").also { Files.createDirectories(it.parent) }, byteArrayOf(1, 2, 3))
        GitFixture.init(projectDir)
        FallbackFixture.primeHistory(projectDir)
        changeCalc()

        FallbackFixture.assertFull(projectDir, build("test"), "no-index", both)
    }

    @Test
    fun `a failing selection runs everything and the build stays green`() {
        ImpactFixture.singleModule(projectDir, pluginLine = "id(\"gg.aquatic.testimpact\")\n    id(\"gg.aquatic.testimpact.probe\")")
        GitFixture.init(projectDir)
        FallbackFixture.primeHistory(projectDir)
        changeCalc()

        val result = build("test", "-Ptestimpact.probe.failSelection=true", probe = true)

        FallbackFixture.assertFull(projectDir, result, "analysis-error", both)
    }

    @Test
    fun `an unreadable repository runs everything`() {
        ImpactFixture.singleModule(projectDir)
        GitFixture.init(projectDir)
        FallbackFixture.primeHistory(projectDir)
        Files.writeString(projectDir.resolve(".git/index"), "not an index")
        changeCalc()

        val result = build("test")

        FallbackFixture.assertFull(projectDir, result, "analysis-error", both)
    }

    @Test
    fun `a base ref that does not exist runs everything`() {
        ImpactFixture.singleModule(projectDir, extra = "testImpact { baseRef.set(\"missing-ref\") }")
        GitFixture.init(projectDir)
        FallbackFixture.primeHistory(projectDir)
        changeCalc()

        FallbackFixture.assertFull(projectDir, build("test"), "no-base", both)
    }
}
