package gg.aquatic.testimpact.cache

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.measureTime

@Tag("benchmark")
class ClassGraphCacheBenchmarkTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `cold and warm scans of 3000 classes stay within budget`() {
        val corpus = extractCorpus(temp.resolve("corpus"), SIZE)
        val roots = Files.list(corpus).use { stream -> stream.sorted().toList() }

        scanOnce(roots, temp.resolve("jit"))

        val cold = (0 until RUNS).map { run -> scanOnce(roots, temp.resolve("cold$run")).first }.min()
        val warmDir = temp.resolve("cold${RUNS - 1}")
        val warmRuns = (0 until RUNS).map { scanOnce(roots, warmDir, expectedParses = 0) }
        val warm = warmRuns.minOf { it.first }

        println("TestImpact cache benchmark: cold ${perThousand(cold)} ms per 1000 classes, warm ${perThousand(warm)} ms per 1000 classes ($SIZE classes)")
        assertTrue(warm <= WARM_BUDGET, "warm $warm exceeds $WARM_BUDGET")
        assertTrue(cold <= COLD_BUDGET, "cold $cold exceeds $COLD_BUDGET")
    }

    private fun scanOnce(roots: List<Path>, cacheDir: Path, expectedParses: Int? = null): Pair<Duration, Int> {
        val parser = CacheCorpus.CountingParser()
        val cache = ClassGraphCache(cacheDir, parse = parser)
        val time = measureTime { cache.scan(roots) }
        if (expectedParses != null) assertEquals(expectedParses, parser.count, "parses of a warm scan")
        return time to parser.count
    }

    private fun perThousand(time: Duration): Long = time.inWholeMilliseconds * 1000 / SIZE

    private fun extractCorpus(target: Path, count: Int): Path {
        val jars = System.getProperty("java.class.path").split(java.io.File.pathSeparator)
            .map { Path.of(it) }
            .filter { Files.isRegularFile(it) && it.toString().endsWith(".jar") }
            .sorted()
        var left = count
        for ((index, jar) in jars.withIndex()) {
            if (left == 0) break
            JarFile(jar.toFile()).use { file ->
                val entries = file.entries().asSequence()
                    .filter { it.name.endsWith(".class") && !it.name.startsWith("META-INF/versions/") && !it.name.endsWith("module-info.class") }
                    .sortedBy { it.name }
                    .take(left)
                    .toList()
                for (entry in entries) {
                    val out = target.resolve("j$index").resolve(entry.name)
                    Files.createDirectories(out.parent)
                    file.getInputStream(entry).use { Files.copy(it, out) }
                }
                left -= entries.size
            }
        }
        assertEquals(0, left, "test classpath has fewer than $count class files")
        return target
    }

    private companion object {
        const val SIZE = 3000
        const val RUNS = 3
        val WARM_BUDGET = 2000.milliseconds
        val COLD_BUDGET = 4000.milliseconds
    }
}
