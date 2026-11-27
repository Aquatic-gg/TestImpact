package gg.aquatic.testimpact.seed

import gg.aquatic.testimpact.seed.SeedFixtures.name
import gg.aquatic.testimpact.seed.SeedFixtures.resource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The seed edges the compiled fixtures must yield, as (dependent, kind) per node. */
class SeedRecallTest {
    private val graph = SeedFixtures.seedGraph

    private fun dependents(node: String): List<Pair<String, SeedKind>> = graph.dependents(node).map { it.from to it.kind }

    @Test
    fun `an implementation is listed by the services file and the file provides the interface`() {
        assertEquals(listOf(SeedFixtures.servicesFile to SeedKind.DESCRIPTOR), dependents(name("service.impl.LoudGreeter")))
        assertEquals(
            listOf(name("service.api.Greeter") to SeedKind.SERVICE),
            dependents(SeedFixtures.servicesFile).filter { it.second == SeedKind.SERVICE },
        )
    }

    @Test
    fun `a class name constant reaches the java and the kotlin holder`() {
        assertEquals(
            listOf(
                name("reflect.user.ForNameUser") to SeedKind.CLASS_NAME,
                name("reflect.user.KForNameUser") to SeedKind.CLASS_NAME,
            ),
            dependents(name("reflect.target.ForNameTarget")),
        )
    }

    @Test
    fun `a package constant reaches every class below the package`() {
        assertEquals(listOf(name("pkg.PackageScanner") to SeedKind.PACKAGE_NAME), dependents(name("pkg.scan.Scanned")))
    }

    @Test
    fun `a descriptor naming a class reaches the descriptor`() {
        assertEquals(listOf(resource("plugin.yml") to SeedKind.DESCRIPTOR), dependents(name("res.DescribedMain")))
    }

    @Test
    fun `a named resource reaches its holders and the loaders that build names`() {
        assertEquals(
            listOf(
                name("res.DynamicLoader") to SeedKind.RESOURCE_LOADER,
                name("res.RelativeLoader") to SeedKind.RESOURCE_NAME,
                name("res.StaticLoader") to SeedKind.RESOURCE_NAME,
            ),
            dependents(resource("static.txt")),
        )
    }

    @Test
    fun `a resource nobody names reaches every loader`() {
        val loaders = listOf("DescriptorLoader", "DynamicLoader", "RelativeLoader", "StaticLoader")
        assertEquals(loaders.map { name("res.$it") to SeedKind.RESOURCE_LOADER }, dependents(resource("orphan.txt")))
    }

    @Test
    fun `a reflecting class reaches what lives below its package`() {
        assertEquals(listOf(name("dyn.Reflector") to SeedKind.DYNAMIC), dependents(name("dyn.sub.Plain")))
    }

    @Test
    fun `nothing reaches a class outside every reflecting package`() {
        assertEquals(emptyList(), dependents(name("dynother.Unrelated")))
    }

    @Test
    fun `reflection and service lookups are dynamic`() {
        for (path in listOf("dyn.Reflector", "reflect.user.ForNameUser", "reflect.user.KForNameUser", "service.consumer.GreeterConsumer")) {
            assertTrue(graph.isDynamic(name(path)), "$path should be dynamic")
        }
    }

    @Test
    fun `lambdas, string templates, records and enums are not dynamic`() {
        for (path in listOf("dyn2.LambdaOnly", "dyn2.JavaRecord", "dyn2.JavaEnum", "res.StaticLoader", "res.NoLoader")) {
            assertFalse(graph.isDynamic(name(path)), "$path must not be dynamic")
        }
    }

    @Test
    fun `every edge ends on the queried class and none points at itself`() {
        for (clazz in graph.classes) {
            for (edge in graph.dependents(clazz)) {
                assertEquals(clazz, edge.to)
                assertTrue(edge.from != clazz, "self edge on $clazz")
            }
        }
    }
}
