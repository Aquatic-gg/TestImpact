package gg.aquatic.testimpact.escalation.state

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RedStateFileTest {
    @TempDir
    lateinit var temp: Path

    private fun read(text: String): RedState {
        val file = temp.resolve("x.state")
        Files.writeString(file, text)
        return RedStateFile.read(file)
    }

    private val valid = "testimpact-escalation 1\nfull-run-required=true\nunknown-failure=false\n"

    @Test
    fun `round trip`() {
        val state = RedState(true, setOf("app.B", "app.A\$Inner"), true, RunPhase.PARTIAL, RunResult.RED)
        assertEquals(state, read(RedStateFile.render(state)))
        val green = RedState(false, emptySet(), false, RunPhase.FULL, RunResult.GREEN)
        assertEquals(green, read(RedStateFile.render(green)))
        assertEquals(RedState.NONE, read(RedStateFile.render(RedState.NONE)))
    }

    @Test
    fun `render is sorted and stable`() {
        val state = RedState(true, setOf("b.B", "a.A"), false, RunPhase.QUICK, RunResult.GREEN)
        val text = RedStateFile.render(state)
        assertEquals(
            "testimpact-escalation 1\nfull-run-required=true\nunknown-failure=false\nlast-phase=quick\nlast-result=green\nred=a.A\nred=b.B\n",
            text,
        )
        assertEquals(text, RedStateFile.render(read(text)))
    }

    @Test
    fun `a missing file is none`() {
        assertSame(RedState.NONE, RedStateFile.read(temp.resolve("absent.state")))
    }

    @Test
    fun `a crlf file reads`() {
        val state = RedState(true, setOf("a.A"), false, RunPhase.FULL, RunResult.RED)
        assertEquals(state, read(RedStateFile.render(state).replace("\n", "\r\n")))
    }

    @Test
    fun `damaged files read as corrupt`() {
        val cases = mapOf(
            "empty" to "",
            "other header" to "something\nfull-run-required=true\nunknown-failure=false\n",
            "version 2" to "testimpact-escalation 2\nfull-run-required=true\nunknown-failure=false\n",
            "unknown key" to valid + "color=blue\n",
            "bad boolean" to "testimpact-escalation 1\nfull-run-required=yes\nunknown-failure=false\n",
            "bad phase" to valid + "last-phase=sideways\n",
            "bad result" to valid + "last-result=blue\n",
            "missing required key" to "testimpact-escalation 1\nunknown-failure=false\n",
            "duplicate key" to valid + "full-run-required=false\n",
            "no equals" to valid + "red\n",
            "blank line" to valid + "\nred=a.A\n",
            "glob" to valid + "red=a*b\n",
            "empty name" to valid + "red=\n",
            "space" to valid + "red=a b\n",
            "control char" to valid + "red=a\u0001b\n",
            "long name" to valid + "red=" + "a".repeat(1_025) + "\n",
        )
        for ((label, text) in cases) assertTrue(read(text).isCorrupt(), label)
    }

    @Test
    fun `name limits`() {
        assertEquals(setOf("a".repeat(1_024)), read(valid + "red=" + "a".repeat(1_024) + "\n").red)
        val many = (1..10_000).joinToString("") { "red=c.C$it\n" }
        assertEquals(10_000, read(valid + many).red.size)
        assertTrue(read(valid + many + "red=c.Extra\n").isCorrupt())
    }

    @Test
    fun `an oversized file is corrupt`() {
        val file = temp.resolve("big.state")
        Files.write(file, ByteArray(2 shl 20) { 'a'.code.toByte() })
        assertTrue(RedStateFile.read(file).isCorrupt())
    }

    @Test
    fun `invalid utf-8 is corrupt`() {
        val file = temp.resolve("bad.state")
        Files.write(file, byteArrayOf(0xC3.toByte(), 0x28))
        assertTrue(RedStateFile.read(file).isCorrupt())
    }

    @Test
    fun `a directory in place of the file is corrupt`() {
        val dir = Files.createDirectories(temp.resolve("dir.state"))
        assertTrue(RedStateFile.read(dir).isCorrupt())
    }
}
