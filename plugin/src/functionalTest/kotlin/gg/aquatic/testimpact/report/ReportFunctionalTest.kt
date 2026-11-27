package gg.aquatic.testimpact.report

import gg.aquatic.testimpact.fallback.FallbackFixture
import gg.aquatic.testimpact.select.MultiModuleFixture
import groovy.json.JsonSlurper
import org.gradle.testkit.runner.BuildResult
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Runs `testImpactReport` on the multi-module fixture inside a real git repository. The tests share one working tree and
 * run in order: the resource change of the last one would force every task in the others.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ReportFunctionalTest {

    private val projectDir: Path = Files.createTempDirectory("report-fixture")

    @BeforeAll
    fun writeFixture() {
        MultiModuleFixture.write(projectDir)
        for (module in MultiModuleFixture.modules) {
            val script = projectDir.resolve("$module/build.gradle.kts")
            Files.writeString(script, Files.readString(script) + "\napply(plugin = \"gg.aquatic.testimpact\")\n")
        }
        Files.writeString(projectDir.resolve(".gitignore"), "build/\n.gradle/\n")
        git("init", "-b", "main")
        git("add", "-A")
        git("-c", "user.name=t", "-c", "user.email=t@t", "-c", "commit.gpgsign=false", "commit", "-m", "initial")
        git("checkout", "-b", "feature")
        for (module in MultiModuleFixture.modules) {
            for (task in listOf("test", "integrationTest")) FallbackFixture.primeHistory(projectDir, module, task)
        }
        val core = projectDir.resolve("a/src/main/java/a/Core.java")
        Files.writeString(core, Files.readString(core).replace("return 1;", "return 2;"))
    }

    @AfterAll
    fun cleanUp() {
        projectDir.toFile().deleteRecursively()
    }

    private fun git(vararg args: String) {
        val process = ProcessBuilder(listOf("git") + args)
            .directory(projectDir.toFile())
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()
        check(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0) { "git ${args.joinToString(" ")} failed" }
    }

    private fun runner(vararg extra: String) = MultiModuleFixture.runner(projectDir).withArguments(
        "testImpactReport", *extra, "--configuration-cache", "--warning-mode=fail", "--no-watch-fs", "--stacktrace",
    ).forwardOutput()

    private fun report(vararg extra: String): BuildResult = runner(*extra).build()

    private fun json(module: String): Map<String, Any?> {
        val file = projectDir.resolve("$module/build/reports/testimpact/report.json")
        @Suppress("UNCHECKED_CAST")
        return JsonSlurper().parseText(Files.readString(file)) as Map<String, Any?>
    }

    private fun text(module: String): String =
        Files.readString(projectDir.resolve("$module/build/reports/testimpact/report.txt"))

    @Suppress("UNCHECKED_CAST")
    private fun task(module: String, path: String): Map<String, Any?> =
        (json(module)["tasks"] as List<Map<String, Any?>>).single { it["path"] == path }

    @Suppress("UNCHECKED_CAST")
    private fun tests(task: Map<String, Any?>): List<Map<String, Any?>> = task["tests"] as List<Map<String, Any?>>

    private val durations = Regex(""""durationsMs":\s*\{[^}]*\}""")

    private fun maskedJson(module: String): String =
        durations.replace(Files.readString(projectDir.resolve("$module/build/reports/testimpact/report.json")), "\"durationsMs\":{}")

    @Test
    @Order(1)
    fun `a changed class is reported with its file and path in the dependant module`() {
        report("--base", "main")

        val c = json("c")
        assertEquals(1, c["schemaVersion"])
        assertEquals(":c", c["project"])
        @Suppress("UNCHECKED_CAST")
        val files = (c["changes"] as Map<String, Any?>)["files"] as List<Map<String, Any?>>
        assertEquals(listOf("a/src/main/java/a/Core.java"), files.map { it["path"] })
        assertEquals(listOf("a.Core"), files.single()["classes"])
        assertNull(c["forced"])

        val service = tests(task("c", ":c:test")).single()
        assertEquals("c.ServiceTest", service["className"])
        assertEquals("REACHED", service["reason"])
        assertEquals("a/src/main/java/a/Core.java", service["changedFile"])
        assertEquals("a.Core", service["seedClass"])
        assertEquals("c.ServiceTest\$Inner", service["reachedClass"])
        @Suppress("UNCHECKED_CAST")
        val path = service["path"] as List<Map<String, Any?>>
        assertEquals("a.Core", path.first()["to"])
        assertEquals("c.ServiceTest\$Inner", path.last()["from"])

        assertEquals(listOf("c.ServiceIT"), tests(task("c", ":c:integrationTest")).map { it["className"] })
        for (module in listOf("d", "e")) {
            val known = task(module, ":$module:test")
            assertEquals("selected", known["result"])
            assertEquals(emptyList<Any?>(), tests(known))
        }

        val full = task("f", ":f:test")
        assertEquals("full", full["result"])
        @Suppress("UNCHECKED_CAST")
        val rule = full["rule"] as Map<String, Any?>
        assertEquals("UNKNOWN_ENGINE", rule["reason"])
        assertEquals("f.FakeEngine", rule["detail"])

        val summary = text("c")
        assertTrue("c.ServiceTest <- a/src/main/java/a/Core.java: a.Core <-" in summary, summary)
        assertTrue(":c:integrationTest selected 1 of" in summary, summary)
    }

    @Test
    @Order(2)
    fun `explain prints the chain, the absence of one and refuses an invalid name`() {
        val chain = report("--explain", "c.ServiceTest").output
        assertTrue(":c:test: selected: c.ServiceTest <- a/src/main/java/a/Core.java" in chain, chain)
        assertTrue(":f:test: full run, so c.ServiceTest runs" in chain, chain)

        val none = report("--explain", "c.Nope").output
        assertTrue(":c:test: not selected" in none, none)

        val refused = runner("--explain=-x").buildAndFail()
        assertTrue("--explain" in refused.output, refused.output)
    }

    @Test
    @Order(3)
    fun `the report is stable, leaks no absolute path and works with the configuration cache and isolated projects`() {
        report("--base", "main")
        val first = MultiModuleFixture.modules.associateWith(::maskedJson)
        val firstTexts = MultiModuleFixture.modules.associateWith { text(it).lines().first() }

        val second = report("--base", "main")
        assertTrue("Reusing configuration cache" in second.output, second.output)
        assertEquals(first, MultiModuleFixture.modules.associateWith(::maskedJson))
        assertEquals(firstTexts, MultiModuleFixture.modules.associateWith { text(it).lines().first() })

        val isolated = report("--base", "main", "-Dorg.gradle.unsafe.isolated-projects=true")
        assertFalse("gg.aquatic.testimpact" in isolated.output, isolated.output)
        assertEquals(first, MultiModuleFixture.modules.associateWith(::maskedJson))

        val forbidden = listOf(projectDir.toString(), projectDir.toRealPath().toString(), System.getProperty("user.home"))
            .flatMap { listOf(it, it.replace('\\', '/'), it.replace("\\", "\\\\")) }
            .filter { it.length > 3 }
        for (module in MultiModuleFixture.modules) {
            for (name in listOf("report.json", "report.txt")) {
                val content = Files.readString(projectDir.resolve("$module/build/reports/testimpact/$name"))
                for (path in forbidden) assertFalse(path in content, "$module/$name contains $path")
            }
        }
    }

    @Test
    @Order(4)
    fun `a changed resource forces every task and the rule is named`() {
        val resources = projectDir.resolve("a/src/main/resources")
        Files.createDirectories(resources)
        Files.writeString(resources.resolve("x.properties"), "k=v\n")

        report("--base", "main")

        val detail = "a/src/main/resources/x.properties"
        for ((module, path) in listOf("c" to ":c:test", "c" to ":c:integrationTest", "d" to ":d:test", "f" to ":f:test")) {
            val task = task(module, path)
            assertEquals("full", task["result"], path)
            @Suppress("UNCHECKED_CAST")
            val rule = task["rule"] as Map<String, Any?>
            assertEquals("RESOURCE_CHANGE", rule["reason"], path)
            assertEquals(detail, rule["detail"], path)
        }
        @Suppress("UNCHECKED_CAST")
        val forced = assertNotNull(json("c")["forced"]) as Map<String, Any?>
        assertEquals("RESOURCE_CHANGE", forced["reason"])
        assertTrue(detail in text("c"), text("c"))
    }
}
