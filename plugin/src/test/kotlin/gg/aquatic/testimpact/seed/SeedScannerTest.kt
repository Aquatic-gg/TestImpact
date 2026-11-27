package gg.aquatic.testimpact.seed

import gg.aquatic.testimpact.graph.ClassProblem
import gg.aquatic.testimpact.graph.internal.classfile.ClassBytes
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.io.TempDir

class SeedScannerTest {
    @TempDir
    lateinit var temp: Path

    private fun classFile(name: String, literal: String? = null): ByteArray {
        val bytes = ClassBytes()
        val self = bytes.classRef(name)
        val parent = bytes.classRef("java/lang/Object")
        if (literal != null) bytes.string(literal)
        return bytes.classFile(self, parent)
    }

    private fun moduleInfo(service: String, impl: String): ByteArray {
        val b = ClassBytes()
        val self = b.classRef("module-info")
        val moduleName = b.raw(1, 19, 0, b.utf8("app"))
        val api = b.classRef(service)
        val provider = b.classRef(impl)
        val body = ByteArrayOutputStream()
        val out = DataOutputStream(body)
        out.writeShort(moduleName)
        repeat(6) { out.writeShort(0) }
        out.writeShort(1)
        out.writeShort(api)
        out.writeShort(1)
        out.writeShort(provider)
        val attribute = b.attribute("Module", body.toByteArray())
        return b.classFile(self, access = 0x8000, attributes = listOf(attribute))
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

    private fun known(result: SeedResult): SeedInputs = assertIs<SeedResult.Known>(result).inputs

    private fun unknown(result: SeedResult): List<ClassProblem> = assertIs<SeedResult.Unknown>(result).problems

    private val sample = mapOf(
        "p/A.class" to classFile("p/A", "p.B"),
        "p/B.class" to classFile("p/B"),
        "META-INF/services/p.Api" to "p.B\n# note\n".toByteArray(),
        "plugin.yml" to "main: p.A\n".toByteArray(),
        "assets/logo.png" to byteArrayOf(1, 2, 3),
    )

    @Test
    fun `services file and descriptor are read and binary assets only by path`() {
        val inputs = known(SeedScanner.scan(listOf(writeDirectory(temp.resolve("out"), sample)), emptyList()))

        assertEquals(listOf("p.A", "p.B"), inputs.classes.map { it.name })
        assertEquals(listOf("p.B"), inputs.classes[0].literals)
        assertEquals(
            listOf(
                ResourceFacts("META-INF/services/p.Api", listOf("p.B"), true),
                ResourceFacts("assets/logo.png", emptyList(), false),
                ResourceFacts("plugin.yml", listOf("p.A"), true),
            ),
            inputs.resources,
        )
    }

    @Test
    fun `directory and jar give the same inputs`() {
        val fromDirectory = known(SeedScanner.scan(listOf(writeDirectory(temp.resolve("out"), sample)), emptyList()))
        val fromJar = known(SeedScanner.scan(listOf(writeJar(temp.resolve("lib.jar"), sample)), emptyList()))

        assertEquals(fromDirectory, fromJar)
    }

    @Test
    fun `resource roots add resources and ignore class files`() {
        val classes = writeDirectory(temp.resolve("classes"), mapOf("p/A.class" to classFile("p/A")))
        val resources = writeDirectory(
            temp.resolve("res"),
            mapOf("app.properties" to "k=p.A".toByteArray(), "stray/X.class" to classFile("stray/X")),
        )

        val inputs = known(SeedScanner.scan(listOf(classes), listOf(resources)))

        assertEquals(listOf("p.A"), inputs.classes.map { it.name })
        assertEquals(listOf(ResourceFacts("app.properties", listOf("p.A"), true)), inputs.resources)
    }

    @Test
    fun `missing root contributes nothing`() {
        val inputs = known(SeedScanner.scan(listOf(temp.resolve("nope")), listOf(temp.resolve("none.jar"))))

        assertEquals(SeedInputs(emptyList(), emptyList()), inputs)
    }

    @Test
    fun `versioned module-info is read and other versioned entries are skipped`() {
        val jar = writeJar(
            temp.resolve("mr.jar"),
            mapOf(
                "p/A.class" to classFile("p/A"),
                "META-INF/versions/9/module-info.class" to moduleInfo("p/Api", "p/Impl"),
                "META-INF/versions/9/p/A.class" to byteArrayOf(1, 2, 3),
                "META-INF/versions/9/data.txt" to "p.A".toByteArray(),
            ),
        )

        val inputs = known(SeedScanner.scan(listOf(jar), emptyList()))

        assertEquals(listOf("module-info", "p.A"), inputs.classes.map { it.name })
        assertEquals(listOf(ServiceProvision("p.Api", listOf("p.Impl"))), inputs.classes[0].provides)
        assertEquals(emptyList(), inputs.resources)
    }

    private fun link(link: Path, target: Path) {
        val created = try {
            Files.createSymbolicLink(link, target)
            true
        } catch (e: UnsupportedOperationException) {
            false
        } catch (e: IOException) {
            false
        }
        Assumptions.assumeTrue(created, "the platform cannot create symbolic links here")
    }

    @Test
    fun `inner links to a class, a descriptor or a directory make the result unknown`() {
        val outside = writeDirectory(
            temp.resolve("outside"),
            mapOf("Out.class" to classFile("Out"), "d.yml" to "a.b".toByteArray(), "sub/f.txt" to "x".toByteArray()),
        )
        for ((name, target) in mapOf("L.class" to "Out.class", "l.yml" to "d.yml", "dirLink" to "sub")) {
            val dir = writeDirectory(temp.resolve("with-$name"), mapOf("p/A.class" to classFile("p/A")))
            link(dir.resolve(name), outside.resolve(target))

            val problems = unknown(SeedScanner.scan(listOf(dir), emptyList()))

            assertEquals(1, problems.size, name)
            assertTrue(problems[0].location.endsWith(name), problems[0].location)
        }
    }

    @Test
    fun `a link to a binary asset is not read and not a problem`() {
        val outside = writeDirectory(temp.resolve("outside"), mapOf("logo.png" to byteArrayOf(1)))
        val dir = writeDirectory(temp.resolve("withAsset"), mapOf("p/A.class" to classFile("p/A")))
        link(dir.resolve("logo.png"), outside.resolve("logo.png"))

        assertEquals(listOf("p.A"), known(SeedScanner.scan(listOf(dir), emptyList())).classes.map { it.name })
    }

    private fun junction(link: Path, target: Path) {
        Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"), "junctions exist on Windows only")
        val created = try {
            ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                .redirectErrorStream(true).start().let { it.inputStream.readAllBytes(); it.waitFor() == 0 }
        } catch (e: IOException) {
            false
        }
        Assumptions.assumeTrue(created, "the junction could not be created")
    }

    @Test
    fun `an inner junction to a directory makes the result unknown without walking it`() {
        val outside = writeDirectory(temp.resolve("outsideJunction"), mapOf("q/Out.class" to classFile("q/Out")))
        val dir = writeDirectory(temp.resolve("withJunction"), mapOf("p/A.class" to classFile("p/A")))
        junction(dir.resolve("q"), outside.resolve("q"))

        val problems = unknown(SeedScanner.scan(listOf(dir), emptyList()))

        assertEquals(1, problems.size)
        assertTrue(problems[0].location.replace('\\', '/').endsWith("/q"), problems[0].location)
    }

    @Test
    fun `a junction as the root is resolved`() {
        val real = writeDirectory(temp.resolve("realRoot"), mapOf("p/A.class" to classFile("p/A")))
        val root = temp.resolve("rootJunction")
        junction(root, real)

        assertEquals(listOf("p.A"), known(SeedScanner.scan(listOf(root), emptyList())).classes.map { it.name })
    }

    @Test
    fun `the same resource in several roots is merged whatever the root order`() {
        val one = writeJar(temp.resolve("one.jar"), mapOf("META-INF/MANIFEST.MF" to "a.One".toByteArray(), "x.bin" to byteArrayOf(1)))
        val two = writeDirectory(temp.resolve("two"), mapOf("META-INF/MANIFEST.MF" to "b.Two a.One".toByteArray(), "x.bin" to byteArrayOf(2)))

        val forward = SeedScanner.scan(listOf(one, two), emptyList())

        assertEquals(forward, SeedScanner.scan(listOf(two, one), emptyList()))
        assertEquals(
            listOf(
                ResourceFacts("META-INF/MANIFEST.MF", listOf("a.One", "b.Two"), true),
                ResourceFacts("x.bin", emptyList(), false),
            ),
            known(forward).resources,
        )
    }

    @Test
    fun `merging the scans of single roots equals scanning them together`() {
        val one = writeJar(temp.resolve("one.jar"), mapOf("META-INF/MANIFEST.MF" to "a.One".toByteArray(), "x.bin" to byteArrayOf(1)))
        val two = writeDirectory(temp.resolve("two"), mapOf("META-INF/MANIFEST.MF" to "b.Two a.One".toByteArray(), "y.bin" to byteArrayOf(2)))
        val bad = temp.resolve("bad.jar").also { it.writeText("not a zip archive") }

        val together = SeedScanner.scan(listOf(one), listOf(two))
        val merged = SeedScanner.merge(
            listOf(SeedScanner.scan(listOf(one), emptyList()), SeedScanner.scan(emptyList(), listOf(two))),
        )
        assertEquals(together, merged)

        val failing = SeedScanner.scan(listOf(one, bad), emptyList())
        assertEquals(failing, SeedScanner.merge(listOf(SeedScanner.scan(listOf(one), emptyList()), SeedScanner.scan(listOf(bad), emptyList()))))
    }

    @Test
    fun `corrupt jar, malformed class and truncated entry make the result unknown`() {
        val corrupt = temp.resolve("bad.jar")
        corrupt.writeText("not a zip archive")
        assertEquals(1, unknown(SeedScanner.scan(listOf(corrupt), emptyList())).size)

        val bad = writeJar(temp.resolve("bad-class.jar"), mapOf("p/A.class" to classFile("p/A").copyOf(20)))
        val problems = unknown(SeedScanner.scan(listOf(bad), emptyList()))
        assertEquals(1, problems.size)
        assertTrue(problems[0].location.endsWith("bad-class.jar!/p/A.class"), problems[0].location)
        assertTrue(problems[0].message.startsWith("malformed class"), problems[0].message)
    }

    @Test
    fun `descriptor above the cap is a problem and a binary asset above it is ignored`() {
        val dir = writeDirectory(
            temp.resolve("caps"),
            mapOf("big.yml" to ByteArray(11), "big.bin" to ByteArray(11), "p/A.class" to classFile("p/A")),
        )

        val problems = unknown(SeedScanner.scan(listOf(dir), emptyList(), maxResourceBytes = 10))

        assertEquals(1, problems.size)
        assertTrue(problems[0].location.endsWith("big.yml"), problems[0].location)

        val ok = writeDirectory(temp.resolve("ok"), mapOf("big.bin" to ByteArray(11), "ten.yml" to ByteArray(10)))
        assertEquals(2, known(SeedScanner.scan(emptyList(), listOf(ok), maxResourceBytes = 10)).resources.size)
    }

    @Test
    fun `class above the cap is a problem`() {
        val dir = writeDirectory(temp.resolve("big"), mapOf("p/A.class" to classFile("p/A")))

        assertEquals(1, unknown(SeedScanner.scan(listOf(dir), emptyList(), maxClassBytes = 10)).size)
    }

    @Test
    fun `problems are sorted`() {
        val first = writeJar(temp.resolve("b.jar"), mapOf("z/Z.class" to byteArrayOf(1)))
        val second = writeJar(temp.resolve("a.jar"), mapOf("y/Y.class" to byteArrayOf(1)))

        val problems = unknown(SeedScanner.scan(listOf(first, second), emptyList()))

        assertEquals(problems.sortedBy { it.location }, problems)
        assertEquals(2, problems.size)
    }

    @Test
    fun `root order does not change the inputs and scans are repeatable`() {
        val one = writeDirectory(temp.resolve("one"), mapOf("p/A.class" to classFile("p/A"), "a.yml" to "p.A".toByteArray()))
        val two = writeJar(temp.resolve("two.jar"), mapOf("q/B.class" to classFile("q/B"), "b.txt" to "q.B".toByteArray()))
        val three = writeDirectory(temp.resolve("three"), mapOf("c.json" to "p.A".toByteArray()))

        val forward = SeedScanner.scan(listOf(one, two), listOf(three))

        assertEquals(forward, SeedScanner.scan(listOf(two, one), listOf(three)))
        assertEquals(forward, SeedScanner.scan(listOf(one, two), listOf(three)))
        assertEquals(listOf("a.yml", "b.txt", "c.json"), known(forward).resources.map { it.path })
    }
}
