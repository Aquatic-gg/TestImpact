package gg.aquatic.testimpact.select

import org.gradle.testkit.runner.BuildResult
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SelectionFunctionalTest {

    private val projectDir: Path = Files.createTempDirectory("selection-fixture")

    private data class Picked(val className: String, val reason: String, val recognised: Boolean, val origin: String, val edges: List<String>)

    private data class Probed(val known: Boolean, val picked: List<Picked>, val unknown: String?)

    @BeforeAll
    fun writeFixture() = MultiModuleFixture.write(projectDir)

    @AfterAll
    fun cleanUp() {
        projectDir.toFile().deleteRecursively()
    }

    private fun run(seeds: String, vararg extra: String): BuildResult {
        return MultiModuleFixture.runner(projectDir)
            .withArguments(
                "selectionProbe", "-PimpactSeeds=$seeds", "--configuration-cache", "--warning-mode=fail",
                "--no-watch-fs", "--stacktrace", *extra,
            )
            .forwardOutput()
            .build()
    }

    private fun probed(taskPath: String): Probed {
        val module = taskPath.removePrefix(":").substringBefore(':')
        val file = projectDir.resolve(module).resolve("build/impact-probe").resolve(SelectionProbeTask.fileName(taskPath))
        val lines = Files.readAllLines(file).filter(String::isNotBlank)
        val head = lines.first()
        if (head.startsWith("unknown ")) return Probed(false, emptyList(), head.removePrefix("unknown "))
        val picked = lines.drop(1).map { line ->
            val parts = line.split(' ')
            Picked(parts[1], parts[2], parts[3].toBoolean(), parts[4], parts[5].split(',').filter { it != "-" })
        }
        return Probed(true, picked, null)
    }

    private fun classes(taskPath: String) = probed(taskPath).picked.map { it.className }

    @Test
    fun `a changed class reaches tests through project dependencies and custom suites`() {
        run("a.Core")

        assertEquals(listOf("c.ServiceTest"), classes(":c:test"))
        assertEquals(listOf("c.ServiceIT"), classes(":c:integrationTest"))
        for (task in listOf(":a:test", ":b:test", ":d:test", ":e:test")) {
            val result = probed(task)
            assertTrue(result.known, "$task: ${result.unknown}")
            assertEquals(emptyList(), result.picked, task)
        }

        val picked = probed(":c:test").picked.single()
        assertEquals("REACHED", picked.reason)
        assertTrue(picked.recognised)
        assertEquals("seed:a.Core", picked.origin)
        assertTrue(picked.edges.first().endsWith(">a.Core:REFERENCE"), picked.edges.toString())
        assertTrue(picked.edges.last().startsWith("c.ServiceTest\$Inner>"), picked.edges.toString())
        assertEquals(listOf("c.ServiceIT"), probed(":c:integrationTest").picked.map { it.className })
    }

    @Test
    fun `a test task with an unknown engine is unknown`() {
        run("a.Core")

        val result = probed(":f:test")
        assertFalse(result.known)
        assertTrue(result.unknown.orEmpty().startsWith("UNKNOWN_ENGINE f.FakeEngine"), result.unknown)
    }

    @Test
    fun `an abstract test in a fixture selects its subclasses in other modules`() {
        run("d.AbstractContractTest")

        assertEquals(listOf("d.EngineContractTest"), classes(":d:test"))
        assertEquals(listOf("e.OtherContractTest"), classes(":e:test"))
        val edge = probed(":e:test").picked.single().edges.single()
        assertTrue(edge.startsWith("e.OtherContractTest>d.AbstractContractTest:"), edge)
        assertTrue(edge.endsWith("SUPERTYPE"), edge)
    }

    @Test
    fun `a changed main class reaches the contract tests but not unrelated ones`() {
        run("d.Engine")

        assertEquals(listOf("d.EngineContractTest"), classes(":d:test"))
        assertEquals(listOf("e.OtherContractTest"), classes(":e:test"))
    }

    @Test
    fun `the capture survives the configuration cache and isolated projects`() {
        run("a.Core")
        val second = run("a.Core")
        assertTrue("Reusing configuration cache" in second.output, second.output)
        assertEquals(listOf("c.ServiceTest"), classes(":c:test"))

        val isolated = run("a.Core", "-Dorg.gradle.unsafe.isolated-projects=true")
        assertFalse("gg.aquatic.testimpact" in isolated.output, isolated.output)
        assertEquals(listOf("c.ServiceTest"), classes(":c:test"))
    }
}
