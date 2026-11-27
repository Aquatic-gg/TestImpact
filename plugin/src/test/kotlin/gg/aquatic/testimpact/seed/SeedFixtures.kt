package gg.aquatic.testimpact.seed

import gg.aquatic.testimpact.graph.ClassGraphResult
import gg.aquatic.testimpact.graph.ClassGraphScanner
import gg.aquatic.testimpact.graph.DependencyGraph
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The compiled fixture classes and resources of the test source sets, scanned once. */
internal object SeedFixtures {
    const val PACKAGE = "gg.aquatic.testimpact.seed.fixture"
    private const val ROOT = "gg/aquatic/testimpact/seed/fixture"
    private const val SERVICES_FILE = "META-INF/services/$PACKAGE.service.api.Greeter"

    /** The directory holding `META-INF` and the fixture resources, the root above the services file. */
    val resourceRoot: Path by lazy {
        val url = assertNotNull(SeedFixtures::class.java.classLoader.getResource(SERVICES_FILE), "missing $SERVICES_FILE")
        val file = Paths.get(url.toURI())
        file.parent.parent.parent
    }

    /** The output directories of the compiled fixture classes (Kotlin and Java), without the resources directory. */
    val classDirectories: List<Path> by lazy {
        val urls = SeedFixtures::class.java.classLoader.getResources(ROOT).toList()
        val found = urls.filter { it.protocol == "file" }.map { Paths.get(it.toURI()) }
            .filter { !it.startsWith(resourceRoot) }.sorted()
        assertTrue(found.size >= 2, "expected the Kotlin and the Java fixture output directories, found $found")
        found
    }

    val inputs: SeedInputs by lazy {
        val result = SeedScanner.scan(classDirectories, listOf(resourceRoot))
        assertIs<SeedResult.Known>(result, "fixtures must scan cleanly: $result").inputs
    }

    val classGraph: DependencyGraph by lazy {
        val result = ClassGraphScanner.scan(classDirectories)
        assertIs<ClassGraphResult.Known>(result, "fixtures must scan cleanly: $result").graph
    }

    val seedGraph: SeedGraph by lazy { seedGraph(SeedRules.NONE) }

    fun seedGraph(rules: SeedRules): SeedGraph = SeedGraph.of(inputs, rules)

    /** Binary name of a fixture class from its name below the fixture package, e.g. `service.api.Greeter`. */
    fun name(path: String): String = "$PACKAGE.$path"

    /** Resource node of a file below the fixture resource directory, e.g. `static.txt`. */
    fun resource(file: String): String = SeedGraph.resourceNode("$ROOT/res/$file")

    /** Resource node of the services file that lists the providers of the fixture greeter. */
    val servicesFile: String get() = SeedGraph.resourceNode(SERVICES_FILE)

    /**
     * Everything that depends, directly or transitively, on [changed]: the class graph alone, or with the edges of
     * [seeds] merged in. Each node maps to the first path that reaches it, found breadth first.
     */
    fun reach(changed: Collection<String>, withSeeds: Boolean, seeds: SeedGraph = seedGraph): Map<String, Reached> {
        val reached = LinkedHashMap<String, Reached>()
        val queue = ArrayDeque<String>()
        for (node in changed.sorted()) {
            reached[node] = Reached(listOf(node), emptyList())
            queue.add(node)
        }
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val here = reached.getValue(node)
            val next = LinkedHashMap<String, SeedKind?>()
            classGraph.dependents(node).forEach { next.putIfAbsent(it.from, null) }
            if (withSeeds) seeds.dependents(node).forEach { next.putIfAbsent(it.from, it.kind) }
            for ((from, kind) in next) {
                if (from in reached) continue
                reached[from] = Reached(here.path + from, if (kind == null) here.kinds else here.kinds + kind)
                queue.add(from)
            }
        }
        return reached
    }
}
