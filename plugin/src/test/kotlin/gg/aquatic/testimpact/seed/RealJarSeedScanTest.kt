package gg.aquatic.testimpact.seed

import java.nio.file.Path
import java.nio.file.Paths
import org.junit.jupiter.api.Assertions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.measureTimedValue

/** Libraries nobody wrote for this scanner must scan without a single problem; the time is only reported. */
class RealJarSeedScanTest {
    private fun jarOf(type: Class<*>): Path = Paths.get(type.protectionDomain.codeSource.location.toURI())

    private fun scanClean(type: Class<*>) {
        val jar = jarOf(type)
        val (result, duration) = measureTimedValue { SeedScanner.scan(listOf(jar), emptyList()) }
        val problems = (result as? SeedResult.Unknown)?.problems.orEmpty()
        assertEquals(emptyList(), problems.take(20), "problems scanning ${jar.fileName}")
        val inputs = assertIs<SeedResult.Known>(result).inputs
        assertTrue(inputs.classes.size > 100, "${jar.fileName} has only ${inputs.classes.size} classes")
        println("scanned ${jar.fileName}: ${inputs.classes.size} classes, ${inputs.resources.size} resources in $duration")
    }

    @Test
    fun `the kotlin stdlib scans without problems`() = scanClean(Unit::class.java)

    @Test
    fun `junit jupiter api scans without problems`() = scanClean(Assertions::class.java)
}
