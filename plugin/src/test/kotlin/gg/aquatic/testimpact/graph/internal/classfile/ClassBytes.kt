package gg.aquatic.testimpact.graph.internal.classfile

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/** Builds minimal class files and raw constant pools byte by byte, including deliberately broken ones. */
internal class ClassBytes {
    private val pool = ByteArrayOutputStream()
    private val poolOut = DataOutputStream(pool)
    private var nextIndex = 1

    fun utf8(value: String): Int {
        poolOut.writeByte(1)
        poolOut.writeUTF(value)
        return nextIndex++
    }

    fun integer(value: Int): Int {
        poolOut.writeByte(3)
        poolOut.writeInt(value)
        return nextIndex++
    }

    fun float(value: Float): Int {
        poolOut.writeByte(4)
        poolOut.writeFloat(value)
        return nextIndex++
    }

    fun long(value: Long): Int {
        poolOut.writeByte(5)
        poolOut.writeLong(value)
        val index = nextIndex
        nextIndex += 2
        return index
    }

    fun double(value: Double): Int {
        poolOut.writeByte(6)
        poolOut.writeDouble(value)
        val index = nextIndex
        nextIndex += 2
        return index
    }

    fun classRef(internalName: String): Int = classRef(utf8(internalName))

    fun classRef(nameIndex: Int): Int {
        poolOut.writeByte(7)
        poolOut.writeShort(nameIndex)
        return nextIndex++
    }

    fun string(value: String): Int {
        val utf = utf8(value)
        poolOut.writeByte(8)
        poolOut.writeShort(utf)
        return nextIndex++
    }

    fun nameAndType(name: String, descriptor: String): Int {
        val nameIndex = utf8(name)
        val descriptorIndex = utf8(descriptor)
        poolOut.writeByte(12)
        poolOut.writeShort(nameIndex)
        poolOut.writeShort(descriptorIndex)
        return nextIndex++
    }

    fun member(tag: Int, classIndex: Int, nameAndTypeIndex: Int): Int {
        poolOut.writeByte(tag)
        poolOut.writeShort(classIndex)
        poolOut.writeShort(nameAndTypeIndex)
        return nextIndex++
    }

    fun methodHandle(kind: Int, referenceIndex: Int): Int {
        poolOut.writeByte(15)
        poolOut.writeByte(kind)
        poolOut.writeShort(referenceIndex)
        return nextIndex++
    }

    fun methodType(descriptor: String): Int {
        val utf = utf8(descriptor)
        poolOut.writeByte(16)
        poolOut.writeShort(utf)
        return nextIndex++
    }

    fun invokeDynamic(bootstrapIndex: Int, nameAndTypeIndex: Int): Int {
        poolOut.writeByte(18)
        poolOut.writeShort(bootstrapIndex)
        poolOut.writeShort(nameAndTypeIndex)
        return nextIndex++
    }

    /** Appends bytes verbatim and advances the slot counter by [slots]. */
    fun raw(slots: Int, vararg bytes: Int): Int {
        for (b in bytes) poolOut.writeByte(b)
        val index = nextIndex
        nextIndex += slots
        return index
    }

    /** Only `constant_pool_count` and the entries. */
    fun poolBytes(count: Int = nextIndex): ByteArray {
        poolOut.flush()
        val out = ByteArrayOutputStream()
        val data = DataOutputStream(out)
        data.writeShort(count)
        data.write(pool.toByteArray())
        return out.toByteArray()
    }

    /** A class with the pool above followed by an empty body: no interfaces, fields, methods or attributes. */
    fun classFile(
        thisIndex: Int,
        superIndex: Int = 0,
        access: Int = 0x21,
        interfaces: List<Int> = emptyList(),
        fields: List<ByteArray> = emptyList(),
        methods: List<ByteArray> = emptyList(),
        attributes: List<ByteArray> = emptyList(),
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val data = DataOutputStream(out)
        data.writeInt(0xCAFEBABE.toInt())
        data.writeShort(0)
        data.writeShort(61)
        data.write(poolBytes())
        data.writeShort(access)
        data.writeShort(thisIndex)
        data.writeShort(superIndex)
        data.writeShort(interfaces.size)
        interfaces.forEach { data.writeShort(it) }
        data.writeShort(fields.size)
        fields.forEach { data.write(it) }
        data.writeShort(methods.size)
        methods.forEach { data.write(it) }
        data.writeShort(attributes.size)
        attributes.forEach { data.write(it) }
        return out.toByteArray()
    }

    /** An attribute: name index, 4-byte length, [body]. */
    fun attribute(name: String, body: ByteArray): ByteArray = attributeAt(utf8(name), body)

    fun attributeAt(nameIndex: Int, body: ByteArray, declaredLength: Int = body.size): ByteArray {
        val out = ByteArrayOutputStream()
        val data = DataOutputStream(out)
        data.writeShort(nameIndex)
        data.writeInt(declaredLength)
        data.write(body)
        return out.toByteArray()
    }

    /** A field or method entry with the given attributes. */
    fun declaration(access: Int, name: String, descriptor: String, attributes: List<ByteArray> = emptyList()): ByteArray {
        val out = ByteArrayOutputStream()
        val data = DataOutputStream(out)
        data.writeShort(access)
        data.writeShort(utf8(name))
        data.writeShort(utf8(descriptor))
        data.writeShort(attributes.size)
        attributes.forEach { data.write(it) }
        return out.toByteArray()
    }

    internal companion object {
        const val FIELD_REF = 9
        const val METHOD_REF = 10
        const val INTERFACE_METHOD_REF = 11
    }
}
