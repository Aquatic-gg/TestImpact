package gg.aquatic.testimpact.graph

import gg.aquatic.testimpact.graph.internal.classfile.ClassBytes
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class ClassGraphScannerTest {
    @TempDir
    lateinit var temp: Path

    private fun classFile(name: String, superName: String = "java/lang/Object", vararg refs: String): ByteArray {
        val bytes = ClassBytes()
        val self = bytes.classRef(name)
        val parent = bytes.classRef(superName)
        refs.forEach { bytes.classRef(it) }
        return bytes.classFile(self, parent)
    }

    private fun writeDirectory(dir: Path, entries: Map<String, ByteArray>): Path {
        for ((name, content) in entries) {
            val file = dir.resolve(name)
            file.parent.createDirectories()
            file.writeBytes(content)
        }
        return dir
    }

    private fun writeJar(file: Path, entries: Map<String, ByteArray>): Path {
        ZipOutputStream(Files.newOutputStream(file)).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return file
    }

    private fun known(result: ClassGraphResult): DependencyGraph = assertIs<ClassGraphResult.Known>(result).graph

    private fun unknown(result: ClassGraphResult): List<ClassProblem> = assertIs<ClassGraphResult.Unknown>(result).problems

    private val sample = mapOf(
        "p/A.class" to classFile("p/A", "p/B", "p/C"),
        "p/B.class" to classFile("p/B"),
        "p/sub/C.class" to classFile("p/C"),
    )

    @Test
    fun directoryAndJarGiveTheSameGraph() {
        val dir = writeDirectory(temp.resolve("classes"), sample)
        val jar = writeJar(temp.resolve("lib.jar"), sample)

        val fromDirectory = known(ClassGraphScanner.scan(listOf(dir)))
        val fromJar = known(ClassGraphScanner.scan(listOf(jar)))

        assertEquals(listOf("p.A", "p.B", "p.C"), fromDirectory.classes)
        assertEquals(fromDirectory.classes, fromJar.classes)
        for (name in fromDirectory.classes) {
            assertEquals(fromDirectory.facts(name), fromJar.facts(name))
            assertEquals(fromDirectory.dependencies(name), fromJar.dependencies(name))
        }
        assertEquals(listOf("p.B" to EdgeKind.SUPERTYPE, "p.C" to EdgeKind.REFERENCE), fromJar.dependencies("p.A").map { it.to to it.kind })
    }

    @Test
    fun multiReleaseEntriesAndModuleInfoAreIgnored() {
        val jar = writeJar(
            temp.resolve("mr.jar"),
            sample + mapOf(
                "META-INF/versions/9/p/A.class" to byteArrayOf(1, 2, 3),
                "module-info.class" to byteArrayOf(9),
                "readme.txt" to byteArrayOf(0),
            ),
        )

        assertEquals(listOf("p.A", "p.B", "p.C"), known(ClassGraphScanner.scan(listOf(jar))).classes)
    }

    @Test
    fun directoryIgnoresTheSameEntries() {
        val dir = writeDirectory(
            temp.resolve("mr"),
            sample + mapOf("META-INF/versions/9/p/A.class" to byteArrayOf(1), "module-info.class" to byteArrayOf(9)),
        )

        assertEquals(listOf("p.A", "p.B", "p.C"), known(ClassGraphScanner.scan(listOf(dir))).classes)
    }

    @Test
    fun missingRootContributesNothing() {
        val graph = known(ClassGraphScanner.scan(listOf(temp.resolve("absent"))))

        assertEquals(emptyList(), graph.classes)
    }

    @Test
    fun truncatedClassMakesTheResultUnknownAndNamesIt() {
        val dir = writeDirectory(temp.resolve("broken"), sample + ("p/Bad.class" to classFile("p/Bad").copyOf(20)))

        val problems = unknown(ClassGraphScanner.scan(listOf(dir)))

        assertEquals(1, problems.size)
        assertTrue(problems[0].location.replace('\\', '/').endsWith("p/Bad.class"), problems[0].location)
    }

    @Test
    fun brokenJarEntryIsNamedWithJarAndEntry() {
        val jar = writeJar(temp.resolve("bad.jar"), sample + ("p/Bad.class" to byteArrayOf(0, 1, 2)))

        val problems = unknown(ClassGraphScanner.scan(listOf(jar)))

        assertEquals(listOf("$jar!/p/Bad.class"), problems.map { it.location })
    }

    @Test
    fun corruptJarMakesTheResultUnknown() {
        val jar = temp.resolve("corrupt.jar")
        jar.writeBytes(ByteArray(64) { it.toByte() })

        val problems = unknown(ClassGraphScanner.scan(listOf(jar)))

        assertEquals(listOf(jar.toString()), problems.map { it.location })
    }

    @Test
    fun classAboveTheCapMakesTheResultUnknown() {
        val dir = writeDirectory(temp.resolve("big"), sample)

        val problems = unknown(ClassGraphScanner.scan(listOf(dir), maxClassBytes = 10))

        assertEquals(3, problems.size)
        assertTrue(problems.all { "larger than 10 bytes" in it.message })
    }

    @Test
    fun capIsMeasuredOnTheBytesReadNotTheDeclaredSize() {
        val bytes = classFile("p/A")
        val jar = writeJar(temp.resolve("cap.jar"), mapOf("p/A.class" to bytes))

        known(ClassGraphScanner.scan(listOf(jar), maxClassBytes = bytes.size))
        unknown(ClassGraphScanner.scan(listOf(jar), maxClassBytes = bytes.size - 1))
    }

    @Test
    fun problemsAreSorted() {
        val dir = writeDirectory(
            temp.resolve("many"),
            mapOf("z/Z.class" to byteArrayOf(1), "a/A.class" to byteArrayOf(2), "m/M.class" to byteArrayOf(3)),
        )

        val locations = unknown(ClassGraphScanner.scan(listOf(dir))).map { it.location.replace('\\', '/') }

        assertEquals(locations.sorted(), locations)
    }

    @Test
    fun rootOrderDoesNotMatterAndScansAreRepeatable() {
        val first = writeDirectory(temp.resolve("one"), mapOf("p/A.class" to classFile("p/A", "p/B")))
        val second = writeJar(temp.resolve("two.jar"), mapOf("p/B.class" to classFile("p/B"), "p/A.class" to classFile("p/A", "p/B")))

        val forward = known(ClassGraphScanner.scan(listOf(first, second)))
        val backward = known(ClassGraphScanner.scan(listOf(second, first)))
        val again = known(ClassGraphScanner.scan(listOf(first, second)))

        for (graph in listOf(backward, again)) {
            assertEquals(forward.classes, graph.classes)
            for (name in forward.classes) {
                assertEquals(forward.facts(name), graph.facts(name))
                assertEquals(forward.dependencies(name), graph.dependencies(name))
                assertEquals(forward.dependents(name), graph.dependents(name))
            }
        }
        assertEquals(listOf("p.A", "p.B"), forward.classes)
    }
}
