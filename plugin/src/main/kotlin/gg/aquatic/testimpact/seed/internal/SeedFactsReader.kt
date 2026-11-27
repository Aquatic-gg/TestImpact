package gg.aquatic.testimpact.seed.internal

import gg.aquatic.testimpact.graph.internal.classfile.ByteCursor
import gg.aquatic.testimpact.graph.internal.classfile.ConstantPool
import gg.aquatic.testimpact.graph.internal.classfile.MalformedClassException
import gg.aquatic.testimpact.seed.SeedFacts
import gg.aquatic.testimpact.seed.ServiceProvision
import java.util.TreeMap
import java.util.TreeSet

/**
 * Reads the seed facts of one class file from its header and constant pool only; members are not walked,
 * except in a module descriptor, whose `Module` attribute is read for its `provides` clauses.
 * Any input that breaks the format throws [MalformedClassException].
 */
internal object SeedFactsReader {

    fun read(bytes: ByteArray): SeedFacts {
        val cursor = ByteCursor(bytes)
        if (cursor.u4() != MAGIC) throw MalformedClassException("not a class file: bad magic number", 0)
        cursor.u2()
        val major = cursor.u2()
        if (major < MIN_MAJOR) throw MalformedClassException("unsupported class file major version $major", 6)
        val pool = ConstantPool.read(cursor)
        val access = cursor.u2()
        val name = pool.className(cursor.u2()) ?: throw MalformedClassException("this_class is not a class")

        val provides = if (access and ACC_MODULE != 0) readModule(cursor, bytes, pool) else emptyList()
        val flags = callFlags(pool)
        return SeedFacts(
            name = name,
            literals = literals(pool),
            dynamic = flags and SeedApis.DYNAMIC != 0,
            loadsResources = flags and SeedApis.LOADER != 0,
            buildsStrings = flags and SeedApis.BUILDER != 0,
            provides = provides,
        )
    }

    private fun literals(pool: ConstantPool): List<String> {
        val found = TreeSet<String>()
        pool.forEachOfTag(ConstantPool.UTF8) { index ->
            if (!pool.utf8HasShape(index, '.', FORBIDDEN_IN_LITERAL)) return@forEachOfTag
            val text = pool.utf8(index)
            if (text.length in MIN_LITERAL..MAX_LITERAL) found.add(text)
        }
        return found.toList()
    }

    private fun callFlags(pool: ConstantPool): Int {
        var flags = 0
        val visit = { index: Int ->
            val owner = pool.className(pool.memberClassIndex(index))
            if (owner != null) {
                flags = flags or SeedApis.classify(owner, pool.nameAndTypeName(pool.memberNameAndTypeIndex(index)))
            }
        }
        pool.forEachOfTag(ConstantPool.METHOD_REF, visit)
        pool.forEachOfTag(ConstantPool.INTERFACE_METHOD_REF, visit)
        return flags
    }

    private fun readModule(cursor: ByteCursor, bytes: ByteArray, pool: ConstantPool): List<ServiceProvision> {
        cursor.u2()
        cursor.skip(cursor.u2() * 2)
        repeat(2) { repeat(cursor.u2()) { skipMember(cursor) } }
        var provides: List<ServiceProvision> = emptyList()
        repeat(cursor.u2()) {
            val attributeName = pool.utf8(cursor.u2())
            val length = cursor.u4()
            if (length > cursor.remaining) {
                throw MalformedClassException("attribute length $length exceeds the remaining ${cursor.remaining} bytes", cursor.position)
            }
            val start = cursor.position
            cursor.skip(length)
            if (attributeName == "Module") {
                val body = ByteCursor(bytes, start, start + length.toInt())
                provides = readProvides(body, pool)
                if (body.remaining != 0) throw MalformedClassException("${body.remaining} trailing bytes in the Module attribute", body.position)
            }
        }
        if (cursor.remaining != 0) throw MalformedClassException("${cursor.remaining} trailing bytes after the class", cursor.position)
        return provides
    }

    private fun skipMember(cursor: ByteCursor) {
        cursor.skip(6)
        repeat(cursor.u2()) {
            cursor.u2()
            cursor.skip(cursor.u4())
        }
    }

    private fun readProvides(body: ByteCursor, pool: ConstantPool): List<ServiceProvision> {
        body.skip(6)
        body.skip(body.u2() * REQUIRES_SIZE)
        repeat(2) {
            repeat(body.u2()) {
                body.skip(4)
                body.skip(body.u2() * 2)
            }
        }
        body.skip(body.u2() * 2)
        val byService = TreeMap<String, TreeSet<String>>()
        repeat(body.u2()) {
            val service = pool.className(body.u2()) ?: throw MalformedClassException("provided service is not a class")
            val providers = byService.getOrPut(service) { TreeSet() }
            repeat(body.u2()) {
                providers.add(pool.className(body.u2()) ?: throw MalformedClassException("service provider is not a class"))
            }
        }
        return byService.map { (service, providers) -> ServiceProvision(service, providers.toList()) }
    }

    private const val MAGIC = 0xCAFEBABEL
    private const val MIN_MAJOR = 45
    private const val ACC_MODULE = 0x8000
    private const val REQUIRES_SIZE = 6
    private const val MIN_LITERAL = 2
    private const val MAX_LITERAL = 1024

    private val FORBIDDEN_IN_LITERAL: String = buildString {
        append(";(<>[ ")
        for (code in 0..31) append(code.toChar())
        append(127.toChar())
    }
}
