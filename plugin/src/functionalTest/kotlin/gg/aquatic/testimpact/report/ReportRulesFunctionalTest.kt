package gg.aquatic.testimpact.report

import gg.aquatic.testimpact.fallback.FallbackFixture
import gg.aquatic.testimpact.gradle.GitFixture
import gg.aquatic.testimpact.gradle.ImpactFixture
import groovy.json.JsonSlurper
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The report decides like the selection of a build: `alwaysRun`, links through resources, `dynamicPackages`, the
 * extension's `baseRef` and `--base` over it, the wide-change rule, and changes judged against the task's own source
 * roots.
 */
class ReportRulesFunctionalTest {

    @TempDir
    lateinit var projectDir: Path

    private fun report(vararg tasks: String, extra: List<String> = emptyList()) = runner(tasks.toList() + extra).build()

    private fun runner(arguments: List<String>): GradleRunner = GradleRunner.create()
        .withProjectDir(projectDir.toFile())
        .withPluginClasspath()
        .withArguments(arguments + listOf("--configuration-cache", "--warning-mode=fail", "--no-watch-fs", "--stacktrace"))
        .forwardOutput()

    @Suppress("UNCHECKED_CAST")
    private fun json(module: String = ""): Map<String, Any?> = JsonSlurper()
        .parseText(Files.readString(projectDir.resolve("${module}build/reports/testimpact/report.json"))) as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun task(json: Map<String, Any?>, path: String): Map<String, Any?> =
        (json["tasks"] as List<Map<String, Any?>>).single { it["path"] == path }

    @Suppress("UNCHECKED_CAST")
    private fun changes(json: Map<String, Any?>): Map<String, Any?> = json["changes"] as Map<String, Any?>

    @Test
    fun `a new test class in another module does not force the dependant to a full run`() {
        ImpactFixture.multiModule(projectDir)
        GitFixture.init(projectDir)
        listOf("a", "b", "c", "k", "e").forEach { FallbackFixture.primeHistory(projectDir, it) }
        ImpactFixture.file(projectDir, "a/src/test/java/a/NewTest.java", ImpactFixture.test("NewTest", "new Core().value();"))

        val result = report(":c:testImpactReport", ":c:testImpactSelectTest")

        val c = json("c/")
        val test = task(c, ":c:test")
        assertEquals("selected", test["result"], test.toString())
        assertEquals(emptyList<Any?>(), test["tests"])
        assertNull(c["forced"])
        @Suppress("UNCHECKED_CAST")
        val files = changes(c)["files"] as List<Map<String, Any?>>
        assertEquals(listOf("a/src/test/java/a/NewTest.java"), files.map { it["path"] })
        assertTrue("TestImpact :c:test: changed 1, selected 0 of 1 test classes" in result.output, result.output)
    }

    @Test
    fun `a wide change is a full run in the report and in the selection`() {
        ImpactFixture.singleModule(projectDir, extra = "testImpact {\n    wideChangePercent.set(1)\n    wideChangeMinClasses.set(0)\n}")
        GitFixture.init(projectDir)
        FallbackFixture.primeHistory(projectDir)
        ImpactFixture.file(projectDir, "src/main/java/app/Calc.java", ImpactFixture.calc(2))

        val result = report("testImpactReport", "testImpactSelectTest")

        val task = task(json(), ":test")
        assertEquals("full", task["result"])
        @Suppress("UNCHECKED_CAST")
        val rule = task["rule"] as Map<String, Any?>
        assertEquals("WIDE_CHANGE", rule["reason"])
        assertEquals("1 of 2 test classes are affected, above 1%", rule["detail"])
        assertTrue("full: wide-change: 1 of 2 test classes are affected, above 1%" in result.output, result.output)
    }

    @Test
    fun `the extension's base ref is used, --base overrides it and an invalid one fails like in the build`() {
        ImpactFixture.singleModule(projectDir, extra = "testImpact { baseRef.set(\"main\") }")
        GitFixture.init(projectDir)
        FallbackFixture.primeHistory(projectDir)
        GitFixture.git(projectDir, "branch", "other")
        ImpactFixture.file(projectDir, "src/main/java/app/Calc.java", ImpactFixture.calc(2))

        report("testImpactReport")
        assertEquals("main", changes(json())["baseRef"])

        report("testImpactReport", extra = listOf("--base", "other"))
        assertEquals("other", changes(json())["baseRef"])
        assertEquals(listOf("app.CalcTest"), (task(json(), ":test")["tests"] as List<*>).map { (it as Map<*, *>)["className"] })

        val refused = runner(listOf("testImpactReport", "--base=main..")).buildAndFail()
        assertTrue("testImpact: baseRef 'main..' must not start or end with '..'" in refused.output, refused.output)
    }

    @Test
    fun `a change in a dynamic package reaches the test of a reflecting class`() {
        ImpactFixture.singleModule(projectDir, extra = "testImpact { dynamicPackages.add(\"com.example.plugins\") }")
        ImpactFixture.file(
            projectDir, "src/main/java/app/Loader.java",
            "package app;\n\npublic class Loader {\n    public Object load(String name) throws Exception {\n" +
                "        return Class.forName(name).getDeclaredConstructor().newInstance();\n    }\n}\n",
        )
        ImpactFixture.file(projectDir, "src/main/java/com/example/plugins/Plugin.java", plugin(1))
        ImpactFixture.file(
            projectDir, "src/test/java/app/LoaderTest.java",
            ImpactFixture.test("LoaderTest", "new Loader().hashCode();"),
        )
        GitFixture.init(projectDir)
        FallbackFixture.primeHistory(projectDir)
        ImpactFixture.file(projectDir, "src/main/java/com/example/plugins/Plugin.java", plugin(2))

        report("testImpactReport")

        val task = task(json(), ":test")
        assertEquals("selected", task["result"], task.toString())
        @Suppress("UNCHECKED_CAST")
        val tests = (task["tests"] as List<Map<String, Any?>>).associateBy { it["className"] }
        assertEquals(setOf("app.LoaderTest"), tests.keys)
        @Suppress("UNCHECKED_CAST")
        val steps = tests.getValue("app.LoaderTest")["path"] as List<Map<String, Any?>>
        assertTrue(steps.any { it["seed"] == "DYNAMIC" }, steps.toString())
    }

    private fun plugin(constant: Int): String =
        "package com.example.plugins;\n\npublic class Plugin {\n    public int id() { return $constant; }\n}\n"

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
        FallbackFixture.primeHistory(projectDir)
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
