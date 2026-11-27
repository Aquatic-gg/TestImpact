package gg.aquatic.testimpact.report

import gg.aquatic.testimpact.gradle.GitFixture
import gg.aquatic.testimpact.gradle.ImpactFixture
import groovy.json.JsonSlurper
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The report follows the selection of a build: `alwaysRun`, the extension's base and links through resources. */
class ReportRulesFunctionalTest {

    @TempDir
    lateinit var projectDir: Path

    @Test
    fun `the report applies always run rules and follows a service file link like the selection`() {
        ImpactFixture.singleModule(projectDir, extra = "testImpact { alwaysRun.add(\"*CalcTest\") }")
        ImpactFixture.file(projectDir, "src/main/java/app/Svc.java", "package app;\n\npublic interface Svc {\n    int id();\n}\n")
        ImpactFixture.file(projectDir, "src/main/java/app/Impl.java", impl(1))
        ImpactFixture.file(projectDir, "src/main/resources/META-INF/services/app.Svc", "app.Impl\n")
        ImpactFixture.file(
            projectDir, "src/test/java/app/OtherTest.java",
            ImpactFixture.test("OtherTest", "Svc.class.getName(); new Other().name();"),
        )
        GitFixture.init(projectDir)
        ImpactFixture.file(projectDir, "src/main/java/app/Impl.java", impl(2))

        GradleRunner.create()
            .withProjectDir(projectDir.toFile())
            .withPluginClasspath()
            .withArguments("testImpactReport", "--configuration-cache", "--warning-mode=fail", "--no-watch-fs", "--stacktrace")
            .forwardOutput()
            .build()

        val report = JsonSlurper().parseText(Files.readString(projectDir.resolve("build/reports/testimpact/report.json")))
        @Suppress("UNCHECKED_CAST")
        val task = ((report as Map<String, Any?>)["tasks"] as List<Map<String, Any?>>).single { it["path"] == ":test" }
        assertEquals("selected", task["result"])
        @Suppress("UNCHECKED_CAST")
        val tests = (task["tests"] as List<Map<String, Any?>>).associateBy { it["className"] }
        assertEquals(setOf("app.CalcTest", "app.OtherTest"), tests.keys)
        @Suppress("UNCHECKED_CAST")
        val steps = tests.getValue("app.OtherTest")["path"] as List<Map<String, Any?>>
        assertTrue(steps.any { it["seed"] == "SERVICE" }, steps.toString())
        assertTrue(steps.all { it.containsKey("seed") }, steps.toString())
    }

    private fun impl(constant: Int): String =
        "package app;\n\npublic class Impl implements Svc {\n    public int id() { return $constant; }\n}\n"
}
