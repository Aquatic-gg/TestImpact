package gg.aquatic.testimpact.graph

import java.net.URL
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The compiled fixture classes of the test source sets (Kotlin and Java output directories), scanned once. */
internal object FixtureClasses {
    const val ROOT = "gg/aquatic/testimpact/graph/fixture"

    /** Every directory of the classpath that holds the fixture package. */
    val directories: List<Path> by lazy {
        val urls: List<URL> = FixtureClasses::class.java.classLoader.getResources(ROOT).toList()
        val found = urls.filter { it.protocol == "file" }.map { Paths.get(it.toURI()) }.sorted()
        assertTrue(found.size >= 2, "expected the Kotlin and the Java fixture output directories, found $found")
        found
    }

    val graph: DependencyGraph by lazy {
        val result = ClassGraphScanner.scan(directories)
        assertIs<ClassGraphResult.Known>(result, "fixtures must scan cleanly: $result").graph
    }

    const val PACKAGE = "gg.aquatic.testimpact.graph.fixture"

    /** Binary name of a fixture class from its name below the fixture package, e.g. `refs.FieldUser`. */
    fun name(path: String): String = "$PACKAGE.$path"

    /** The class file bytes of a fixture class, named like [name]. */
    fun bytes(path: String): ByteArray {
        val resource = "${name(path).replace('.', '/')}.class"
        val stream = checkNotNull(FixtureClasses::class.java.classLoader.getResourceAsStream(resource)) { "missing $resource" }
        return stream.use { it.readBytes() }
    }
}
