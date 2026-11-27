package gg.aquatic.testimpact.seed

import gg.aquatic.testimpact.seed.SeedFixtures.name
import gg.aquatic.testimpact.seed.SeedFixtures.resource
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Per seed type: the class graph alone does not carry a change to the test-like class, the class graph with the
 * seed edges does, and the path shows which kind of seed made the difference.
 */
class SeedReachTest {
    private fun assertSeedOnly(changed: Collection<String>, target: String, vararg kinds: SeedKind, seeds: SeedGraph = SeedFixtures.seedGraph) {
        assertFalse(target in SeedFixtures.reach(changed, withSeeds = false, seeds), "$target must not be reached by the graph alone")
        val reached = assertNotNull(SeedFixtures.reach(changed, withSeeds = true, seeds)[target], "$target not reached with seeds")
        for (kind in kinds) assertContains(reached.kinds, kind, "path ${reached.path}")
    }

    @Test
    fun `a changed service implementation reaches the consumer check`() =
        assertSeedOnly(listOf(name("service.impl.LoudGreeter")), name("service.consumer.GreeterCheck"), SeedKind.SERVICE)

    @Test
    fun `a changed class named by a constant reaches the check of the holder`() =
        assertSeedOnly(listOf(name("reflect.target.ForNameTarget")), name("reflect.user.ForNameCheck"), SeedKind.CLASS_NAME)

    @Test
    fun `a changed class below a named package reaches the check of the holder`() =
        assertSeedOnly(listOf(name("pkg.scan.Scanned")), name("pkg.PackageScannerCheck"), SeedKind.PACKAGE_NAME)

    @Test
    fun `a changed static resource reaches its named loaders and every other loader`() {
        val changed = listOf(resource("static.txt"))
        assertSeedOnly(changed, name("res.StaticLoaderCheck"), SeedKind.RESOURCE_NAME)
        assertSeedOnly(changed, name("res.RelativeLoaderCheck"), SeedKind.RESOURCE_NAME)
        assertSeedOnly(changed, name("res.DynamicLoaderCheck"), SeedKind.RESOURCE_LOADER)
        assertSeedOnly(changed, name("res.DescriptorLoaderCheck"), SeedKind.RESOURCE_LOADER)
        assertFalse(name("res.NoLoaderCheck") in SeedFixtures.reach(changed, withSeeds = true))
    }

    @Test
    fun `a changed resource nobody names reaches every loader check but not the other`() {
        val changed = listOf(resource("orphan.txt"))
        for (loader in listOf("Static", "Relative", "Dynamic", "Descriptor")) {
            assertSeedOnly(changed, name("res.${loader}LoaderCheck"), SeedKind.RESOURCE_LOADER)
        }
        assertFalse(name("res.NoLoaderCheck") in SeedFixtures.reach(changed, withSeeds = true))
    }

    @Test
    fun `a changed class named in a descriptor reaches the check of the loader`() =
        assertSeedOnly(listOf(name("res.DescribedMain")), name("res.DescriptorLoaderCheck"), SeedKind.DESCRIPTOR, SeedKind.RESOURCE_NAME)

    @Test
    fun `a changed class below a reflecting package reaches the check of the reflector`() =
        assertSeedOnly(listOf(name("dyn.sub.Plain")), name("dyn.ReflectorCheck"), SeedKind.DYNAMIC)

    @Test
    fun `a class outside the reflecting package reaches nothing`() {
        val reached = SeedFixtures.reach(listOf(name("dynother.Unrelated")), withSeeds = true)
        assertEquals(setOf(name("dynother.Unrelated")), reached.keys)
    }

    @Test
    fun `a listed dynamic package makes its classes reach each other`() {
        val rules = SeedRules.of(listOf(name("userdyn")), emptyList())
        val changed = listOf(name("userdyn.A"))
        val target = name("userdyn.BCheck")
        assertSeedOnly(changed, target, SeedKind.DYNAMIC, seeds = SeedFixtures.seedGraph(rules))
        assertFalse(target in SeedFixtures.reach(changed, withSeeds = true), "without the rule nothing links A to B")
    }

    @Test
    fun `a listed dynamic package reaches the check of a reflector outside it`() {
        val rules = SeedRules.of(listOf(name("userdyn")), emptyList())
        val changed = listOf(name("userdyn.A"))
        val target = name("dyn.ReflectorCheck")
        assertSeedOnly(changed, target, SeedKind.DYNAMIC, seeds = SeedFixtures.seedGraph(rules))
        assertFalse(target in SeedFixtures.reach(changed, withSeeds = true), "without the rule nothing links A to the reflector")
    }

    @Test
    fun `always run patterns select every check class with nothing changed`() {
        val rules = SeedRules.of(emptyList(), listOf("*Check"))
        val selected = rules.alwaysRun(SeedFixtures.seedGraph.classes)
        assertEquals(SeedFixtures.seedGraph.classes.filter { it.endsWith("Check") }, selected)
        assertEquals(10, selected.size)
        assertTrue(SeedFixtures.reach(emptyList(), withSeeds = true).isEmpty())
    }
}
