package gg.aquatic.testimpact.graph.internal.classfile

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConstantPoolShapeTest {

    private fun poolOf(builder: ClassBytes): ConstantPool = ConstantPool.read(ByteCursor(builder.poolBytes()))

    @Test
    fun `required character present and no forbidden character matches`() {
        val b = ClassBytes()
        val name = b.utf8("p.Target")
        val noDot = b.utf8("p/Target")
        val pool = poolOf(b)

        assertTrue(pool.utf8HasShape(name, '.', ";( "))
        assertFalse(pool.utf8HasShape(noDot, '.', ";( "))
    }

    @Test
    fun `any forbidden character rejects the entry`() {
        val b = ClassBytes()
        val descriptor = b.utf8("(Lp.A;)V")
        val spaced = b.utf8("p.A b")
        val pool = poolOf(b)

        assertFalse(pool.utf8HasShape(descriptor, '.', ";( "))
        assertFalse(pool.utf8HasShape(spaced, '.', ";( "))
    }

    @Test
    fun `an empty entry has no required character`() {
        val b = ClassBytes()
        val empty = b.utf8("")

        assertFalse(poolOf(b).utf8HasShape(empty, '.', ""))
    }

    @Test
    fun `multibyte sequences never match an ascii character`() {
        val b = ClassBytes()
        val accented = b.utf8("café.x")
        val onlyAccent = b.utf8("é€")
        val nul = b.utf8("a\u0000.b")
        val pool = poolOf(b)

        assertTrue(pool.utf8HasShape(accented, '.', "é"))
        assertFalse(pool.utf8HasShape(onlyAccent, '.', ""))
        assertTrue(pool.utf8HasShape(nul, '.', "\u0000"))
    }

    @Test
    fun `a wrong tag or an index outside of the pool throws`() {
        val b = ClassBytes()
        val text = b.utf8("p.A")
        val string = b.string("p.B")
        val pool = poolOf(b)

        assertTrue(pool.utf8HasShape(text, '.', ""))
        assertFailsWith<MalformedClassException> { pool.utf8HasShape(string, '.', "") }
        assertFailsWith<MalformedClassException> { pool.utf8HasShape(0, '.', "") }
        assertFailsWith<MalformedClassException> { pool.utf8HasShape(pool.size, '.', "") }
    }
}
