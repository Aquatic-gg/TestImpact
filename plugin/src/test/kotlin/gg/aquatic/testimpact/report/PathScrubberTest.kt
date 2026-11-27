package gg.aquatic.testimpact.report

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertEquals

class PathScrubberTest {

    private val scrubber = PathScrubber(listOf("/r/proj" to PathScrubber.ROOT))

    @Test
    fun `replaces the prefix with either separator style`() {
        assertEquals("at <root>/a/B.kt and <root>\\a\\B.kt", scrubber.scrub("at /r/proj/a/B.kt and \\r\\proj\\a\\B.kt"))
    }

    @Test
    fun `a prefix only matches at a path boundary`() {
        assertEquals("/r/projx/a and x/r/proj/a", scrubber.scrub("/r/projx/a and x/r/proj/a"))
        assertEquals("<root> <root>/ <root>.", scrubber.scrub("/r/proj /r/proj/ /r/proj."))
    }

    @Test
    fun `the longest prefix wins`() {
        val nested = PathScrubber(listOf("/r/proj" to PathScrubber.ROOT, "/r/proj/home" to PathScrubber.HOME))

        assertEquals("<home>/x and <root>/y", nested.scrub("/r/proj/home/x and /r/proj/y"))
    }

    @Test
    fun `case is ignored only when asked`() {
        assertEquals("/R/PROJ/a", scrubber.scrub("/R/PROJ/a"))
        assertEquals("<root>/a", PathScrubber(listOf("/r/proj" to PathScrubber.ROOT), ignoreCase = true).scrub("/R/PROJ/a"))
    }

    @Test
    fun `text without a prefix is unchanged and short prefixes are ignored`() {
        assertEquals("nothing here", scrubber.scrub("nothing here"))
        assertEquals("/ and /x", PathScrubber(listOf("/" to PathScrubber.HOME)).scrub("/ and /x"))
    }

    @Test
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun `real and given path are both replaced`() {
        val base = createTempDirectory("scrub")
        try {
            val real = base.resolve("real").createDirectories()
            val link = base.resolve("link")
            val linked = runCatching { Files.createSymbolicLink(link, real) }.isSuccess
            val scrub = PathScrubber.of(if (linked) link else real, real.resolve("gradle"), null, ignoreCase = false)

            assertEquals("<root>/x <gradle-user-home>/y", scrub.scrub("${real.toRealPath()}/x ${real.resolve("gradle")}/y"))
            if (linked) assertEquals("<root>/x", scrub.scrub("$link/x"))
        } finally {
            base.deleteRecursively()
        }
    }
}
