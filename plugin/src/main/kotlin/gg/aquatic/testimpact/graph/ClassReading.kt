package gg.aquatic.testimpact.graph

import gg.aquatic.testimpact.graph.internal.classfile.ClassFileReader
import java.nio.file.Path

/** Turns the bytes of one class file found under [root] into its facts; lets a cache sit in front of the parser. */
internal fun interface ClassReading {
    fun read(root: Path, bytes: ByteArray): ClassFacts

    companion object {
        val Direct: ClassReading = ClassReading { _, bytes -> ClassFileReader.read(bytes) }
    }
}
