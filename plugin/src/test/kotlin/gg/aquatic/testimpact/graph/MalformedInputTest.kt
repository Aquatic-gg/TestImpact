package gg.aquatic.testimpact.graph

import gg.aquatic.testimpact.graph.internal.classfile.ClassBytes
import gg.aquatic.testimpact.graph.internal.classfile.ClassFileReader
import gg.aquatic.testimpact.graph.internal.classfile.MalformedClassException
import gg.aquatic.testimpact.graph.internal.classfile.bytes
import java.time.Duration
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.function.Executable

class MalformedInputTest {
    private val samples = listOf("inline.InlineUser", "refs.RuntimeAnnotated", "owner.OwnersKt").map(FixtureClasses::bytes)

    private fun readOrReject(bytes: ByteArray) {
        try {
            ClassFileReader.read(bytes)
        } catch (expected: MalformedClassException) {
            // the only failure a hostile class file may cause
        }
    }

    @Test
    fun `every truncation of a class file is facts or a malformed class`() {
        assertTimeoutPreemptively(
            Duration.ofSeconds(30),
            Executable {
                for (sample in samples) {
                    for (length in sample.indices) readOrReject(sample.copyOf(length))
                }
            },
        )
    }

    @Test
    fun `seeded byte flips are facts or a malformed class`() {
        assertTimeoutPreemptively(
            Duration.ofSeconds(30),
            Executable {
                val random = Random(20_261_127)
                repeat(2000) {
                    val copy = samples[random.nextInt(samples.size)].copyOf()
                    copy[random.nextInt(copy.size)] = random.nextInt(256).toByte()
                    readOrReject(copy)
                }
            },
        )
    }

    @Test
    fun `an InnerClasses cycle between two classes resolves to the same owner in any order`() {
        fun cyclic(name: String, outer: String): ClassFacts {
            val b = ClassBytes()
            val self = b.classRef(name)
            val outerIndex = b.classRef(outer)
            val inner = b.attribute("InnerClasses", bytes { writeShort(1); writeShort(self); writeShort(outerIndex); writeShort(0); writeShort(1) })
            return ClassFileReader.read(b.classFile(self, attributes = listOf(inner)))
        }

        val a = cyclic("p/A", "p/B")
        val b = cyclic("p/B", "p/A")
        val forward = DependencyGraph.of(listOf(a, b))
        val backward = DependencyGraph.of(listOf(b, a))

        assertEquals(listOf("p.A", "p.A", "p.A", "p.A"), listOf(forward.owner("p.A"), forward.owner("p.B"), backward.owner("p.A"), backward.owner("p.B")))
        assertEquals(listOf("p.A", "p.B"), forward.ownedBy("p.A"))
    }
}
