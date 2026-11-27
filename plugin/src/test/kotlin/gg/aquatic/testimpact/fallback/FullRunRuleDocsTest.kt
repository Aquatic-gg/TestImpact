package gg.aquatic.testimpact.fallback

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FullRunRuleDocsTest {

    @Test
    fun `the readme table lists exactly the full run rules in order`() {
        val lines = Files.readAllLines(readme())
        val start = lines.indexOfFirst { it.trim() == "## Full-run rules" }
        assertTrue(start >= 0, "README has no Full-run rules section")
        val section = lines.drop(start + 1).takeWhile { !it.startsWith("## ") }
        val ids = section.mapNotNull { Regex("^\\| `([a-z-]+)` \\|").find(it)?.groupValues?.get(1) }

        assertEquals(FullRunRule.entries.map { it.id }, ids)
    }

    private fun readme(): Path = Path.of(requireNotNull(System.getProperty("testimpact.readme")) { "testimpact.readme is not set" })
}
