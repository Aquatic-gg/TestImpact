package gg.aquatic.testimpact.cache

import gg.aquatic.testimpact.graph.ClassFacts
import gg.aquatic.testimpact.graph.ClassGraphResult
import gg.aquatic.testimpact.graph.FixtureClasses
import gg.aquatic.testimpact.graph.internal.classfile.ClassFileReader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.name
import kotlin.io.path.relativeTo
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Class files to scan in cache tests, and the means to compare two scans. */
internal object CacheCorpus {
    /** A writable copy of the fixture classes as one root with a subdirectory per fixture output directory. */
    fun create(base: Path): Path {
        val root = base.resolve("corpus")
        FixtureClasses.directories.forEachIndexed { index, source ->
            source.toFile().copyRecursively(root.resolve("k$index").toFile(), overwrite = true)
        }
        return root
    }

    /** The class files below [root] as a jar at [jar]. */
    fun jar(root: Path, jar: Path): Path {
        JarOutputStream(Files.newOutputStream(jar)).use { out ->
            val files = Files.walk(root).use { stream -> stream.filter { it.name.endsWith(".class") }.sorted().toList() }
            for (file in files) {
                out.putNextEntry(JarEntry(file.relativeTo(root).joinToString("/")))
                out.write(Files.readAllBytes(file))
                out.closeEntry()
            }
        }
        return jar
    }

    fun assertSameGraph(expected: ClassGraphResult, actual: ClassGraphResult) {
        val wanted = assertIs<ClassGraphResult.Known>(expected).graph
        val got = assertIs<ClassGraphResult.Known>(actual).graph
        assertEquals(wanted.classes, got.classes)
        for (name in wanted.classes) {
            assertEquals(wanted.facts(name), got.facts(name), "facts of $name")
            assertEquals(wanted.dependencies(name), got.dependencies(name), "dependencies of $name")
            assertEquals(wanted.dependents(name), got.dependents(name), "dependents of $name")
        }
    }

    /** Parses like the real reader and counts how often. */
    class CountingParser : (ByteArray) -> ClassFacts {
        private val parses = AtomicInteger()

        val count: Int get() = parses.get()

        override fun invoke(bytes: ByteArray): ClassFacts {
            parses.incrementAndGet()
            return ClassFileReader.read(bytes)
        }
    }
}
