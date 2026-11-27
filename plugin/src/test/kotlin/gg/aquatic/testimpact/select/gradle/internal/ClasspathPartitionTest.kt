package gg.aquatic.testimpact.select.gradle.internal

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir

class ClasspathPartitionTest {

    @TempDir
    lateinit var dir: Path

    private fun directory(name: String): Path = Files.createDirectories(dir.resolve(name))

    private fun file(name: String): Path {
        val file = dir.resolve(name)
        Files.createDirectories(file.parent)
        return Files.write(file, byteArrayOf(0))
    }

    @Test
    fun `jars under the excluded roots are dropped`() {
        val home = directory("gradle-home")
        val m2 = directory("m2")
        val external = file("gradle-home/caches/lib.jar")
        val maven = file("m2/org/lib.jar")
        val project = file("project/build/libs/app.jar")

        val split = ClasspathPartition.split(emptyList(), listOf(external, maven, project), listOf(home, m2))

        assertEquals(listOf(project.toAbsolutePath().normalize()), split.otherRoots)
    }

    @Test
    fun `directories are kept even below an excluded root`() {
        val home = directory("gradle-home")
        val classes = directory("gradle-home/odd/classes")
        val split = ClasspathPartition.split(emptyList(), listOf(classes), listOf(home))
        assertEquals(listOf(classes.toAbsolutePath().normalize()), split.otherRoots)
    }

    @Test
    fun `test directories on the classpath are not other roots`() {
        val tests = directory("build/classes/test")
        val main = directory("build/classes/main")
        val split = ClasspathPartition.split(listOf(tests), listOf(tests, main), emptyList())
        assertEquals(listOf(tests.toAbsolutePath().normalize()), split.testRoots)
        assertEquals(listOf(main.toAbsolutePath().normalize()), split.otherRoots)
    }

    @Test
    fun `missing test directories are ignored and duplicates collapse`() {
        val main = directory("main")
        val split = ClasspathPartition.split(listOf(dir.resolve("absent")), listOf(main, main), emptyList())
        assertEquals(emptyList(), split.testRoots)
        assertEquals(1, split.otherRoots.size)
    }

    @Test
    fun `a jar beside an excluded root with a shared name prefix is kept`() {
        val home = directory("gradle")
        val sibling = file("gradle-extra/lib.jar")
        val split = ClasspathPartition.split(emptyList(), listOf(sibling), listOf(home))
        assertEquals(listOf(sibling.toAbsolutePath().normalize()), split.otherRoots)
    }

    @Test
    fun `excluded roots match regardless of case on windows`() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val home = directory("Gradle-Home")
        val jar = file("Gradle-Home/lib.jar")
        val shouted = Path.of(home.toString().uppercase())
        assertEquals(emptyList(), ClasspathPartition.split(emptyList(), listOf(jar), listOf(shouted)).otherRoots)
    }

    @Test
    fun `relative segments are normalised before matching`() {
        val home = directory("home")
        val jar = file("home/lib.jar")
        val sneaky = dir.resolve("other/../home/lib.jar")
        assertEquals(emptyList(), ClasspathPartition.split(emptyList(), listOf(sneaky), listOf(home)).otherRoots)
        assertEquals(1, ClasspathPartition.split(emptyList(), listOf(jar), emptyList()).otherRoots.size)
    }
}
