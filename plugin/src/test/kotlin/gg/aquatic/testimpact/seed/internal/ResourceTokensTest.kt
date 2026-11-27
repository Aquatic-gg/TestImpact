package gg.aquatic.testimpact.seed.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResourceTokensTest {

    private fun scan(text: String): List<String> = ResourceTokens.scan(text.toByteArray(Charsets.ISO_8859_1))

    @Test
    fun `finds nested names with dollars and tokens at the file start and end`() {
        assertEquals(
            listOf("a.b.Outer\$Inner", "p.q.End", "p.q.Start"),
            scan("p.q.Start: x\nclass: a.b.Outer\$Inner\nlast=p.q.End"),
        )
    }

    @Test
    fun `a name without a dot is not a token and a trailing dot is not part of it`() {
        assertEquals(emptyList(), scan("main: Plain\nname"))
        assertEquals(listOf("a.b"), scan("end of a.b."))
    }

    @Test
    fun `tokens are sorted and distinct`() {
        assertEquals(listOf("a.b", "c.d"), scan("c.d a.b c.d a.b"))
    }

    @Test
    fun `a token over 512 characters is dropped and 512 is kept`() {
        val kept = "a." + "b".repeat(510)
        val dropped = "a." + "b".repeat(511)

        assertEquals(listOf(kept), scan("$kept $dropped"))
    }

    @Test
    fun `non-ascii and binary bytes end a token and are never decoded`() {
        val bytes = byteArrayOf(0xFF.toByte(), 0, 'a'.code.toByte(), '.'.code.toByte(), 'b'.code.toByte(), 0xC3.toByte(), 0xA9.toByte(), 'c'.code.toByte(), '.'.code.toByte(), 'd'.code.toByte())

        assertEquals(listOf("a.b", "c.d"), ResourceTokens.scan(bytes))
    }

    @Test
    fun `empty input has no tokens`() {
        assertEquals(emptyList(), ResourceTokens.scan(ByteArray(0)))
    }

    @Test
    fun `descriptors are files under META-INF or with a text extension`() {
        assertTrue(ResourceTokens.isDescriptor("META-INF/services/p.Api"))
        assertTrue(ResourceTokens.isDescriptor("META-INF/anything.bin"))
        for (name in listOf("plugin.yml", "a/b/app.YAML", "x.properties", "c.json", "d.xml", "e.conf", "f.toml", "g.txt", "h.MF")) {
            assertTrue(ResourceTokens.isDescriptor(name), name)
        }
        for (name in listOf("img.png", "data.bin", "dir.txt/blob", "noextension", "a/b.class")) {
            assertFalse(ResourceTokens.isDescriptor(name), name)
        }
    }
}
