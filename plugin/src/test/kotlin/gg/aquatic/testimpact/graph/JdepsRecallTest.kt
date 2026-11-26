package gg.aquatic.testimpact.graph

import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Path
import java.nio.file.Paths
import java.util.spi.ToolProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The JDK's own dependency analyzer as an independent oracle for recall. */
class JdepsRecallTest {
    private val pair = Regex("""^\s+(\S+)\s+->\s+(\S+)""")

    private fun jarOf(type: Class<*>): Path = Paths.get(type.protectionDomain.codeSource.location.toURI())

    private fun jdepsPairs(roots: List<Path>): Set<Pair<String, String>> {
        val jdeps = ToolProvider.findFirst("jdeps").orElseThrow { AssertionError("jdeps is not part of the JDK running the tests") }
        val out = StringWriter()
        val err = StringWriter()
        val arguments = listOf("-verbose:class", "-filter:none", "--multi-release", "base") + roots.map { it.toString() }
        val exit = jdeps.run(PrintWriter(out), PrintWriter(err), *arguments.toTypedArray())
        assertEquals(0, exit, "jdeps failed: $err")
        val pairs = HashSet<Pair<String, String>>()
        for (line in out.toString().lineSequence()) {
            val match = pair.find(line) ?: continue
            val (from, to) = match.destructured
            if (from != to && from != "module-info") pairs.add(from to to)
        }
        return pairs
    }

    private fun assertCovered(roots: List<Path>, graph: DependencyGraph): Int {
        val pairs = jdepsPairs(roots)
        assertTrue(pairs.size > 10, "jdeps reported only ${pairs.size} dependencies for $roots")
        val dependencies = HashMap<String, Set<String>>()
        val missing = pairs.filter { (from, to) ->
            val known = dependencies.getOrPut(from) {
                graph.dependencies(from)
                    .filter { it.kind == EdgeKind.SUPERTYPE || it.kind == EdgeKind.REFERENCE }
                    .mapTo(HashSet()) { it.to }
            }
            to !in known
        }
        assertEquals(emptyList(), missing.sortedBy { it.first + it.second }.take(50), "jdeps dependencies missing from the graph (${missing.size} of ${pairs.size})")
        return pairs.size
    }

    @Test
    fun `every dependency jdeps reports for the fixtures is an edge`() {
        assertCovered(FixtureClasses.directories, FixtureClasses.graph)
    }

    @Test
    fun `every dependency jdeps reports for the kotlin stdlib is an edge`() {
        val jar = jarOf(Unit::class.java)
        val graph = assertIs<ClassGraphResult.Known>(ClassGraphScanner.scan(listOf(jar))).graph
        assertCovered(listOf(jar), graph)
    }
}
