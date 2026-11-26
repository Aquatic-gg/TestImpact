package gg.aquatic.testimpact.graph.internal.classfile

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/** Big-endian bytes written by [block], for attribute bodies and annotation structures. */
internal fun bytes(block: DataOutputStream.() -> Unit): ByteArray {
    val out = ByteArrayOutputStream()
    DataOutputStream(out).apply(block).flush()
    return out.toByteArray()
}
