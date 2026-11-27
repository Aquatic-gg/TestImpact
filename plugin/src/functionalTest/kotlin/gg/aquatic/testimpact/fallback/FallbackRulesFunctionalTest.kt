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

/** The rules decided from the change set: each runs every test class and names its id on the console line. */
class FallbackRulesFunctionalTest {

    @TempDir
    lateinit var projectDir: Path

    private val both = listOf("app.CalcTest", "app.OtherTest")

    private fun project(extra: String = "", files: Map<String, String> = emptyMap()) {
        ImpactFixture.singleModule(projectDir, extra = extra)
        files.forEach { (path, content) -> ImpactFixture.file(projectDir, path, content) }
        GitFixture.init(projectDir)
        FallbackFixture.primeHistory(projectDir)
    }

    private fun build(vararg args: String) = FallbackFixture.build(projectDir, *args)

    @Test
    fun `a changed build script runs everything`() {
        project()
        Files.writeString(projectDir.resolve("build.gradle.kts"), Files.readString(projectDir.resolve("build.gradle.kts")) + "\n// edited\n")

        FallbackFixture.assertFull(projectDir, build("test"), "build-logic", both)
    }

    @Test
    fun `a changed version catalog runs everything`() {
        project(files = mapOf("gradle/libs.versions.toml" to "[versions]\njunit = \"5.14.3\"\n"))
        ImpactFixture.file(projectDir, "gradle/libs.versions.toml", "[versions]\njunit = \"5.14.4\"\n")

        FallbackFixture.assertFull(projectDir, build("test"), "dependency-change", both)
    }

    @Test
    fun `a changed lock file runs everything`() {
        project(files = mapOf("gradle.lockfile" to "# one\n"))
        ImpactFixture.file(projectDir, "gradle.lockfile", "# two\n")

        FallbackFixture.assertFull(projectDir, build("test"), "dependency-change", both)
    }

    @Test
    fun `a change that reaches both test classes is a wide change`() {
        project(
            extra = "testImpact {\n    wideChangePercent.set(40)\n    wideChangeMinClasses.set(2)\n}",
            files = mapOf("src/test/java/app/OtherTest.java" to ImpactFixture.test("OtherTest", "new Other().name(); new Calc().add(1, 1);")),
        )
        ImpactFixture.file(projectDir, "src/main/java/app/Calc.java", ImpactFixture.calc(2))

        val result = build("test")

        FallbackFixture.assertFull(projectDir, result, "wide-change", both)
        assertContains(result.output, "2 of 2 test classes are affected, above 40%")
    }

    @Test
    fun `a file type the plugin cannot map runs everything and documentation does not`() {
        project(files = mapOf("src/main/proto/a.proto" to "syntax = \"proto3\";\n", "README.md" to "one\n"))
        ImpactFixture.file(projectDir, "src/main/proto/a.proto", "syntax = \"proto3\";\nmessage A {}\n")
        FallbackFixture.assertFull(projectDir, build("test"), "unknown-file-type", both)

        GitFixture.commitAll(projectDir, "proto")
        ImpactFixture.file(projectDir, "README.md", "two\n")
        val docs = build("test")

        assertFalse(docs.output.contains("full:"), docs.output)
        assertContains(docs.output, "selected 0 of 2 test classes")
    }

    @Test
    fun `a changed resource runs everything`() {
        project(files = mapOf("src/main/resources/x.txt" to "1\n"))
        ImpactFixture.file(projectDir, "src/main/resources/x.txt", "2\n")

        FallbackFixture.assertFull(projectDir, build("test"), "resource-change", both)
    }

    @Test
    fun `a changed file in a generated source root selects like any source`() {
        project(
            extra = "sourceSets.main { java.srcDir(\"src/generated/java\") }",
            files = mapOf(
                "src/generated/java/app/Gen.java" to "package app;\n\npublic class Gen {\n    public int v() { return 1; }\n}\n",
                "src/test/java/app/GenTest.java" to ImpactFixture.test("GenTest", "new Gen().v();"),
            ),
        )
        ImpactFixture.file(projectDir, "src/generated/java/app/Gen.java", "package app;\n\npublic class Gen {\n    public int v() { return 2; }\n}\n")

        val result = build("test")

        assertFalse(result.output.contains("full:"), result.output)
        assertContains(result.output, "selected 1 of 3 test classes")
        assertTrue(FallbackFixture.ran(projectDir, "app.GenTest"))
        assertFalse(FallbackFixture.ran(projectDir, "app.CalcTest"))
    }

    @Test
    fun `the report names the same rule as the build and as forced`() {
        project()
        Files.writeString(projectDir.resolve("build.gradle.kts"), Files.readString(projectDir.resolve("build.gradle.kts")) + "\n// edited\n")

        val result = build("testImpactReport", "testImpactSelectTest")

        assertContains(result.output, "full: build-logic")
        val json = report()
        assertEquals("BUILD_LOGIC", rule(json))
        assertEquals("BUILD_LOGIC", (json["forced"] as Map<*, *>)["reason"])
    }

    @Test
    fun `the report without history shows the first run`() {
        ImpactFixture.singleModule(projectDir)
        GitFixture.init(projectDir)
        ImpactFixture.file(projectDir, "src/main/java/app/Calc.java", ImpactFixture.calc(2))

        val result = build("testImpactReport", "testImpactSelectTest")

        assertContains(result.output, "full: first-run")
        assertEquals("FIRST_RUN", rule(report()))
    }

    @Suppress("UNCHECKED_CAST")
    private fun report(): Map<String, Any?> =
        JsonSlurper().parseText(Files.readString(projectDir.resolve("build/reports/testimpact/report.json"))) as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun rule(json: Map<String, Any?>): Any? =
        ((json["tasks"] as List<Map<String, Any?>>).single { it["path"] == ":test" }["rule"] as Map<String, Any?>)["reason"]
}
