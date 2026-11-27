package gg.aquatic.testimpact.fallback

import gg.aquatic.testimpact.gradle.GitFixture
import gg.aquatic.testimpact.gradle.ImpactFixture
import groovy.json.JsonSlurper
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The full-run rules that come from the mode, the framework, the engines and the recognised test classes. */
class FallbackModeRulesFunctionalTest {

    @TempDir
    lateinit var projectDir: Path

    private val both = listOf("app.CalcTest", "app.OtherTest")

    private fun project(extra: String = "", files: Map<String, String> = emptyMap(), primed: List<String> = listOf("test")) {
        ImpactFixture.singleModule(projectDir, extra = extra)
        files.forEach { (path, content) -> ImpactFixture.file(projectDir, path, content) }
        GitFixture.init(projectDir)
        primed.forEach { FallbackFixture.primeHistory(projectDir, task = it) }
    }

    private fun changeCalc() = ImpactFixture.file(projectDir, "src/main/java/app/Calc.java", ImpactFixture.calc(2))

    private fun build(vararg args: String) = FallbackFixture.build(projectDir, *args)

    @Test
    fun `mode full runs everything and says so`() {
        project()
        changeCalc()

        FallbackFixture.assertFull(projectDir, build("test", "-PtestImpact=full"), "mode-full", both)
    }

    @Test
    fun `mode off leaves the task alone and the report names the rule`() {
        project()
        changeCalc()

        val result = build("test", "testImpactReport", "-PtestImpact=off")

        assertFalse(result.output.contains("TestImpact :test:"), result.output)
        assertEquals("MODE_OFF", rule(report(), ":test"))
        for (name in both) assertTrue(FallbackFixture.ran(projectDir, name), "$name did not run")
    }

    @Test
    fun `a task with an unsupported framework runs in full`() {
        project(
            extra = """
                tasks.register<Test>("ngTest") {
                    testClassesDirs = sourceSets.test.get().output.classesDirs
                    classpath = sourceSets.test.get().runtimeClasspath
                    useTestNG()
                }
            """.trimIndent(),
            primed = listOf("test", "ngTest"),
        )
        changeCalc()

        val result = build("testImpactSelectNgTest")

        assertContains(result.output, "TestImpact :ngTest:")
        assertContains(result.output, "full: unsupported-framework")
    }

    @Test
    fun `a JUnit Platform task without a known engine runs in full`() {
        project(
            extra = """
                tasks.register<Test>("bareTest") {
                    testClassesDirs = sourceSets.test.get().output.classesDirs
                    classpath = files()
                    useJUnitPlatform()
                }
            """.trimIndent(),
            primed = listOf("test", "bareTest"),
        )
        changeCalc()

        val result = build("testImpactSelectBareTest")

        assertContains(result.output, "TestImpact :bareTest:")
        assertContains(result.output, "full: unknown-engine")
    }

    @Test
    fun `a changed class that is no test is not recognised and runs everything`() {
        project(files = mapOf("src/test/java/app/Helper.java" to "package app;\n\npublic class Helper {\n    public int v() { return 1; }\n}\n"))
        ImpactFixture.file(projectDir, "src/test/java/app/Helper.java", "package app;\n\npublic class Helper {\n    public int v() { return 2; }\n}\n")

        FallbackFixture.assertFull(projectDir, build("test"), "not-recognised", both)
    }

    @Suppress("UNCHECKED_CAST")
    private fun report(): Map<String, Any?> =
        JsonSlurper().parseText(Files.readString(projectDir.resolve("build/reports/testimpact/report.json"))) as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun rule(json: Map<String, Any?>, path: String): Any? =
        ((json["tasks"] as List<Map<String, Any?>>).single { it["path"] == path }["rule"] as Map<String, Any?>)["reason"]
}
