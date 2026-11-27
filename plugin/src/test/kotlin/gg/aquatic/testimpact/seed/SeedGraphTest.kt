package gg.aquatic.testimpact.seed

import gg.aquatic.testimpact.graph.internal.classfile.ClassBytes
import gg.aquatic.testimpact.seed.internal.SeedFactsReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SeedGraphTest {

    private fun cls(
        name: String,
        literals: List<String> = emptyList(),
        dynamic: Boolean = false,
        loads: Boolean = false,
        builds: Boolean = false,
        provides: List<ServiceProvision> = emptyList(),
    ) = SeedFacts(name, literals.sorted(), dynamic, loads, builds, provides)

    private fun res(path: String, tokens: List<String> = emptyList(), descriptor: Boolean = tokens.isNotEmpty()) =
        ResourceFacts(path, tokens.sorted(), descriptor)

    private fun graph(
        classes: List<SeedFacts> = emptyList(),
        resources: List<ResourceFacts> = emptyList(),
        rules: SeedRules = SeedRules.NONE,
    ) = SeedGraph.of(classes, resources, rules)

    private fun SeedGraph.pairs(node: String): List<Pair<String, SeedKind>> = dependents(node).map { it.from to it.kind }

    @Test
    fun `resource nodes are normalised`() {
        assertEquals("/a/b.txt", SeedGraph.resourceNode("a\\b.txt"))
        assertEquals("/a/b.txt", SeedGraph.resourceNode("/a/b.txt"))
        assertEquals("/a/b.txt", SeedGraph.resourceNode("a/b.txt"))
    }

    @Test
    fun `service kind from a module descriptor provides`() {
        val module = cls("module-info", provides = listOf(ServiceProvision("p.Api", listOf("q.Impl"))))
        assertEquals(listOf("p.Api" to SeedKind.SERVICE), graph(listOf(module)).pairs("q.Impl"))
    }

    @Test
    fun `service kind from a services file links the interface to the file`() {
        val g = graph(resources = listOf(res("META-INF/services/p.Api", listOf("q.Impl"))))
        assertEquals(listOf("p.Api" to SeedKind.SERVICE), g.pairs("/META-INF/services/p.Api"))
        assertEquals(listOf("/META-INF/services/p.Api" to SeedKind.DESCRIPTOR), g.pairs("q.Impl"))
    }

    @Test
    fun `class name kind from a literal`() {
        val g = graph(listOf(cls("h.Holder", listOf("t.Target")), cls("t.Target")))
        assertEquals(listOf("h.Holder" to SeedKind.CLASS_NAME), g.pairs("t.Target"))
    }

    @Test
    fun `class name kind from the element of an array descriptor literal`() {
        val bytes = ClassBytes()
        val self = bytes.classRef("h/Holder")
        bytes.string("[Lt.Target;")
        val facts = SeedFactsReader.read(bytes.classFile(self))
        val g = graph(listOf(facts, cls("t.Target")))
        assertEquals(listOf("h.Holder" to SeedKind.CLASS_NAME), g.pairs("t.Target"))
    }

    @Test
    fun `literals are normalised by one leading slash and a trailing dot`() {
        val g = graph(listOf(cls("h.Holder", listOf("/t.Target.")), cls("h.Other", listOf("x/t.Target"))))
        assertEquals(listOf("h.Holder" to SeedKind.CLASS_NAME), g.pairs("t.Target"))
    }

    @Test
    fun `package name kind reaches every class below the package`() {
        val g = graph(listOf(cls("h.Holder", listOf("p.scan")), cls("p.scan.A"), cls("p.scan.deep.B")))
        assertEquals(listOf("h.Holder" to SeedKind.PACKAGE_NAME), g.pairs("p.scan.A"))
        assertEquals(listOf("h.Holder" to SeedKind.PACKAGE_NAME), g.pairs("p.scan.deep.B"))
        assertEquals(emptyList(), g.pairs("p.other.C"))
    }

    @Test
    fun `a one segment package literal is ignored`() {
        val g = graph(listOf(cls("h.Holder", listOf("p")), cls("p.A"), cls("p.x.B")))
        assertEquals(emptyList(), g.pairs("p.A"))
        assertEquals(emptyList(), g.pairs("p.x.B"))
    }

    @Test
    fun `descriptor kind from a token naming a class or a package`() {
        val g = graph(
            listOf(cls("m.Main"), cls("k.sub.Other")),
            listOf(res("plugin.yml", listOf("m.Main", "k.sub")), res("binary.dat", descriptor = false)),
        )
        assertEquals(listOf("/plugin.yml" to SeedKind.DESCRIPTOR), g.pairs("m.Main"))
        assertEquals(listOf("/plugin.yml" to SeedKind.DESCRIPTOR), g.pairs("k.sub.Other"))
    }

    @Test
    fun `resource name kind matches slash bounded suffixes`() {
        val g = graph(
            listOf(
                cls("a.Abs", listOf("/x/y/z.txt")),
                cls("a.Rel", listOf("y/z.txt")),
                cls("a.Simple", listOf("z.txt")),
                cls("a.Partial", listOf("yz.txt")),
            ),
        )
        assertEquals(
            listOf("a.Abs", "a.Rel", "a.Simple").map { it to SeedKind.RESOURCE_NAME },
            g.pairs("/x/y/z.txt"),
        )
    }

    @Test
    fun `without a naming literal every loader may load the resource`() {
        val g = graph(listOf(cls("a.L1", loads = true), cls("a.L2", loads = true, builds = true), cls("a.None")))
        assertEquals(listOf("a.L1", "a.L2").map { it to SeedKind.RESOURCE_LOADER }, g.pairs("/orphan.txt"))
    }

    @Test
    fun `a naming literal never hides the loaders`() {
        val g = graph(
            listOf(
                cls("a.Named", listOf("s.txt"), loads = true),
                cls("a.Builder", loads = true, builds = true),
                cls("a.Plain", loads = true),
                cls("a.Holder", listOf("s.txt")),
            ),
        )
        assertEquals(
            listOf(
                "a.Builder" to SeedKind.RESOURCE_LOADER,
                "a.Holder" to SeedKind.RESOURCE_NAME,
                "a.Named" to SeedKind.RESOURCE_NAME,
                "a.Plain" to SeedKind.RESOURCE_LOADER,
            ),
            g.pairs("/s.txt"),
        )
    }

    @Test
    fun `an unrelated holder of a bare file name keeps the parameter loader`() {
        val g = graph(listOf(cls("a.JsonLoader", loads = true), cls("other.Thing", listOf("en.json"))))
        assertEquals(
            listOf("a.JsonLoader" to SeedKind.RESOURCE_LOADER, "other.Thing" to SeedKind.RESOURCE_NAME),
            g.pairs("/data/en.json"),
        )
    }

    @Test
    fun `dynamic packages reach every reflecting class outside them`() {
        val g = graph(
            listOf(cls("com.other.Loader", dynamic = true), cls("com.example.plugins.Foo"), cls("com.third.Plain")),
            rules = SeedRules.of(listOf("com.example.plugins"), emptyList()),
        )
        assertEquals(listOf("com.other.Loader" to SeedKind.DYNAMIC), g.pairs("com.example.plugins.Foo"))
        assertEquals(emptyList(), g.pairs("com.third.Plain"))
    }

    @Test
    fun `without a dynamic package a reflecting class elsewhere is not reached`() {
        val g = graph(listOf(cls("com.other.Loader", dynamic = true), cls("com.example.plugins.Foo")))
        assertEquals(emptyList(), g.pairs("com.example.plugins.Foo"))
    }

    @Test
    fun `a resource nobody names or loads is not attributed`() {
        val g = graph(listOf(cls("a.Plain"), cls("a.Holder", listOf("named.txt"))))
        assertFalse(g.isAttributed("/junit-platform.properties"))
        assertFalse(g.isAttributed("/mockito-extensions/org.mockito.plugins.MockMaker"))
        assertTrue(g.isAttributed("/x/named.txt"))
        assertTrue(g.isAttributed("a.Plain"))
        assertTrue(g.isAttributed("never.Scanned"))
    }

    @Test
    fun `any resource loader attributes a resource`() {
        val g = graph(listOf(cls("a.Loader", loads = true)))
        assertTrue(g.isAttributed("/logback-test.xml"))
    }

    @Test
    fun `a services file is attributed only when its interface was scanned`() {
        val g = graph(
            listOf(cls("p.Api"), cls("a.Loader", loads = true)),
            listOf(res("META-INF/services/p.Api"), res("META-INF/services/ext.Spi")),
        )
        assertTrue(g.isAttributed("/META-INF/services/p.Api"))
        assertFalse(g.isAttributed("/META-INF/services/ext.Spi"))
    }

    @Test
    fun `dynamic kind uses the enclosing packages of the class`() {
        val g = graph(
            listOf(
                cls("a.b.Reflector", dynamic = true),
                cls("a.b.sub.Plain"),
                cls("a.other.Unrelated"),
                cls("a.b.sub.Deep", dynamic = true),
            ),
        )
        assertEquals(
            listOf("a.b.Reflector" to SeedKind.DYNAMIC, "a.b.sub.Deep" to SeedKind.DYNAMIC),
            g.pairs("a.b.sub.Plain"),
        )
        assertEquals(emptyList(), g.pairs("a.other.Unrelated"))
        assertEquals(listOf("a.b.Reflector" to SeedKind.DYNAMIC), g.pairs("a.b.Plain2"))
    }

    @Test
    fun `dynamic classes reach resources in their package tree`() {
        val g = graph(listOf(cls("a.b.Reflector", dynamic = true), cls("a.c.Far", dynamic = true)))
        assertEquals(listOf("a.b.Reflector" to SeedKind.DYNAMIC), g.pairs("/a/b/c/res.txt"))
        assertEquals(emptyList(), g.pairs("/z/res.txt"))
    }

    @Test
    fun `a dynamic class in the default package reaches everything`() {
        val g = graph(listOf(cls("Top", dynamic = true)))
        assertEquals(listOf("Top" to SeedKind.DYNAMIC), g.pairs("p.q.X"))
        assertEquals(listOf("Top" to SeedKind.DYNAMIC), g.pairs("/r.txt"))
    }

    @Test
    fun `dynamicPackages mark scanned classes below the package`() {
        val rules = SeedRules.of(listOf("u.dyn"), emptyList())
        val g = graph(listOf(cls("u.dyn.A"), cls("u.dyn.sub.B"), cls("u.other.C")), rules = rules)
        assertTrue(g.isDynamic("u.dyn.A"))
        assertTrue(g.isDynamic("u.dyn.sub.B"))
        assertFalse(g.isDynamic("u.other.C"))
        assertFalse(g.isDynamic("u.dyn.Unscanned"))
        assertEquals(listOf("u.dyn.A" to SeedKind.DYNAMIC), g.pairs("u.dyn.sub.B"))
    }

    @Test
    fun `unscanned names are valid`() {
        val g = graph(listOf(cls("h.Holder", listOf("gone.Missing"))))
        assertEquals(listOf("h.Holder" to SeedKind.CLASS_NAME), g.pairs("gone.Missing"))
        assertEquals(emptyList(), g.pairs("never.Seen"))
        assertEquals(emptyList(), g.pairs("/never/seen.txt"))
    }

    @Test
    fun `edges are sorted with one per pair the lowest kind and no self edge`() {
        val g = graph(
            listOf(
                cls("z.Z", listOf("t.pkg.T", "t.pkg"), dynamic = true),
                cls("a.A", listOf("t.pkg")),
                cls("t.pkg.T", listOf("t.pkg.T", "t.pkg"), dynamic = true),
            ),
            listOf(res("d.yml", listOf("t.pkg.T"))),
        )
        assertEquals(
            listOf("/d.yml" to SeedKind.DESCRIPTOR, "a.A" to SeedKind.PACKAGE_NAME, "z.Z" to SeedKind.CLASS_NAME),
            g.pairs("t.pkg.T"),
        )
    }

    @Test
    fun `a duplicate class is merged as a union regardless of order`() {
        val one = cls("d.Dup", listOf("t.One"), loads = true)
        val two = cls("d.Dup", listOf("t.Two"), builds = true, dynamic = true, provides = listOf(ServiceProvision("s.I", listOf("i.B"))))
        val three = cls("d.Dup", provides = listOf(ServiceProvision("s.I", listOf("i.A"))))
        val a = graph(listOf(one, two, three), listOf(res("r.yml", listOf("x.A")), res("r.yml", listOf("x.B"))))
        val b = graph(listOf(three, two, one), listOf(res("r.yml", listOf("x.B")), res("r.yml", listOf("x.A"))))
        for (node in listOf("t.One", "t.Two", "i.A", "i.B", "x.A", "x.B", "/any.txt", "d.Other")) {
            assertEquals(a.dependents(node), b.dependents(node), node)
        }
        assertEquals(listOf("d.Dup" to SeedKind.CLASS_NAME), a.pairs("t.One"))
        assertEquals(listOf("s.I" to SeedKind.SERVICE), a.pairs("i.A"))
        assertEquals(listOf("/r.yml" to SeedKind.DESCRIPTOR), a.pairs("x.B"))
        assertEquals(listOf("d.Dup"), a.classes)
        assertTrue(a.isDynamic("d.Dup"))
    }

    @Test
    fun `a graph built from scanned inputs matches one built from its parts`() {
        val classes = listOf(cls("h.Holder", listOf("t.Target")))
        val resources = listOf(res("m.yml", listOf("t.Target")))
        val rules = SeedRules.NONE
        val parts = SeedGraph.of(classes, resources, rules)
        val inputs = SeedGraph.of(SeedInputs(classes, resources), rules)
        assertEquals(parts.dependents("t.Target"), inputs.dependents("t.Target"))
    }
}
