package gg.aquatic.testimpact.graph.internal.classfile

import gg.aquatic.testimpact.graph.ClassFacts
import gg.aquatic.testimpact.graph.ConstantField
import gg.aquatic.testimpact.graph.ReachScope
import gg.aquatic.testimpact.graph.internal.kotlin.KotlinMetadata
import gg.aquatic.testimpact.graph.internal.kotlin.SourceMap
import java.util.TreeSet

/**
 * Reads one class file into [ClassFacts]. References come from the structure of the file (every constant
 * pool class, descriptor and signature, annotations, Kotlin metadata and source map), never from guessing at
 * strings. Any input that breaks the format throws [MalformedClassException].
 */
internal object ClassFileReader {

    fun read(bytes: ByteArray): ClassFacts = Reading(bytes).read()

    private const val MAGIC = 0xCAFEBABEL
    private const val MIN_MAJOR = 45
    private const val ACC_PUBLIC = 0x0001
    private const val ACC_PRIVATE = 0x0002
    private const val ACC_PROTECTED = 0x0004
    private const val ACC_MODULE = 0x8000
    private const val INLINE_MARKER = "\$i\$f\$"

    private class RawConstant(val name: String, val descriptor: String, val value: String, val access: Int)

    private class Reading(private val bytes: ByteArray) {
        private val cursor = ByteCursor(bytes)
        private lateinit var pool: ConstantPool
        private val references = TreeSet<String>()
        private lateinit var annotations: AnnotationScanner
        private val constants = ArrayList<RawConstant>()
        private var sourceFile: String? = null
        private var innerOuter: String? = null
        private var innerFlags: Int? = null
        private var enclosing: String? = null
        private var nestHost: String? = null
        private var debugText: String? = null
        private lateinit var thisName: String

        fun read(): ClassFacts {
            if (cursor.u4() != MAGIC) throw MalformedClassException("not a class file: bad magic number", 0)
            cursor.u2()
            val major = cursor.u2()
            if (major < MIN_MAJOR) throw MalformedClassException("unsupported class file major version $major", 6)
            pool = ConstantPool.read(cursor)
            annotations = AnnotationScanner(pool, references)
            val access = cursor.u2()
            thisName = pool.className(cursor.u2()) ?: throw MalformedClassException("this_class is not a class")
            if (access and ACC_MODULE != 0) return facts(access, emptyList(), emptyList(), null, false)

            val supertypes = readSupertypes()
            collectPoolReferences()
            repeat(cursor.u2()) { readMember(isMethod = false) }
            repeat(cursor.u2()) { readMember(isMethod = true) }
            readAttributes { name, body -> classAttribute(name, body) }
            if (cursor.remaining != 0) throw MalformedClassException("${cursor.remaining} trailing bytes after the class", cursor.position)

            val inline = inlineSources()
            val metadata = annotations.metadata?.let(KotlinMetadata::of)
            if (metadata != null) references.addAll(metadata.classNames)
            return facts(access, supertypes, inline, metadata, true)
        }

        private fun facts(
            access: Int,
            supertypes: List<String>,
            inline: List<String>,
            metadata: KotlinMetadata?,
            withEdges: Boolean,
        ): ClassFacts {
            val classScope = classScope(access)
            val fields = constants.map { ConstantField(it.name, it.descriptor, it.value, minOf(memberScope(it.access), classScope)) }
                .sortedWith(compareBy({ it.name }, { it.descriptor }))
            return ClassFacts(
                name = thisName,
                access = access,
                sourceFile = sourceFile,
                supertypes = supertypes,
                outer = if (withEdges) innerOuter ?: enclosing ?: nestHost ?: metadata?.facade else null,
                references = if (withEdges) references.filter { it != thisName } else emptyList(),
                inlineSources = inline.filter { it != thisName },
                constantScope = fields.maxOfOrNull { it.scope },
                constantFields = fields,
                isKotlin = metadata != null,
                typeAliasScope = metadata?.typeAliasScope,
            )
        }

        private fun readSupertypes(): List<String> {
            val result = TreeSet<String>()
            val superIndex = cursor.u2()
            if (superIndex != 0) {
                result.add(pool.className(superIndex) ?: throw MalformedClassException("super_class is not a class"))
            }
            repeat(cursor.u2()) {
                result.add(pool.className(cursor.u2()) ?: throw MalformedClassException("interface is not a class"))
            }
            result.remove(thisName)
            return result.toList()
        }

        private fun collectPoolReferences() {
            pool.forEachOfTag(ConstantPool.CLASS) { index -> pool.className(index)?.let(references::add) }
            pool.forEachOfTag(ConstantPool.NAME_AND_TYPE) { index ->
                TypeNames.addFromDescriptor(pool.nameAndTypeDescriptor(index), references)
            }
            pool.forEachOfTag(ConstantPool.METHOD_TYPE) { index ->
                TypeNames.addFromDescriptor(pool.methodTypeDescriptor(index), references)
            }
        }

        private fun readMember(isMethod: Boolean) {
            val access = cursor.u2()
            val name = pool.utf8(cursor.u2())
            val descriptor = pool.utf8(cursor.u2())
            TypeNames.addFromDescriptor(descriptor, references)
            readAttributes { attribute, body ->
                when (attribute) {
                    "ConstantValue" -> {
                        if (isMethod) return@readAttributes false
                        constants.add(RawConstant(name, descriptor, constantText(body.u2()), access))
                        true
                    }
                    "Code" -> {
                        if (!isMethod) return@readAttributes false
                        readCode(body)
                        true
                    }
                    else -> memberAttribute(attribute, body)
                }
            }
        }

        private fun constantText(index: Int): String = when (pool.tag(index)) {
            ConstantPool.INTEGER -> pool.intValue(index).toString()
            ConstantPool.FLOAT -> Float.fromBits(pool.floatBits(index)).toString()
            ConstantPool.LONG -> pool.longValue(index).toString()
            ConstantPool.DOUBLE -> Double.fromBits(pool.doubleBits(index)).toString()
            ConstantPool.STRING -> pool.stringValue(index)
            else -> throw MalformedClassException("ConstantValue points at constant pool index $index of tag ${pool.tag(index)}")
        }

        private fun readCode(body: ByteCursor) {
            body.skip(4)
            body.skip(body.u4())
            body.skip(body.u2() * 8)
            readAttributes(body) { name, attribute ->
                when (name) {
                    "RuntimeVisibleTypeAnnotations", "RuntimeInvisibleTypeAnnotations" -> {
                        annotations.scanTypeAnnotations(attribute)
                        true
                    }
                    else -> false
                }
            }
        }

        private fun memberAttribute(name: String, body: ByteCursor): Boolean {
            when (name) {
                "Signature" -> TypeNames.addFromSignature(pool.utf8(body.u2()), references)
                "RuntimeVisibleAnnotations", "RuntimeInvisibleAnnotations" -> annotations.scanAnnotations(body)
                "RuntimeVisibleParameterAnnotations", "RuntimeInvisibleParameterAnnotations" ->
                    annotations.scanParameterAnnotations(body)
                "RuntimeVisibleTypeAnnotations", "RuntimeInvisibleTypeAnnotations" -> annotations.scanTypeAnnotations(body)
                "AnnotationDefault" -> annotations.scanElementValue(body)
                else -> return false
            }
            return true
        }

        private fun classAttribute(name: String, body: ByteCursor): Boolean {
            when (name) {
                "SourceFile" -> sourceFile = pool.utf8(body.u2())
                "Signature" -> TypeNames.addFromSignature(pool.utf8(body.u2()), references)
                "InnerClasses" -> readInnerClasses(body)
                "EnclosingMethod" -> {
                    enclosing = pool.className(body.u2())
                    body.u2()
                }
                "NestHost" -> nestHost = pool.className(body.u2())
                "RuntimeVisibleAnnotations", "RuntimeInvisibleAnnotations" -> annotations.scanAnnotations(body, capture = true)
                "RuntimeVisibleTypeAnnotations", "RuntimeInvisibleTypeAnnotations" -> annotations.scanTypeAnnotations(body)
                "Record" -> readRecord(body)
                "SourceDebugExtension" -> {
                    debugText = body.modifiedUtf8At(body.position, body.remaining)
                    body.skip(body.remaining)
                }
                else -> return false
            }
            return true
        }

        private fun readInnerClasses(body: ByteCursor) {
            repeat(body.u2()) {
                val inner = pool.className(body.u2())
                val outerIndex = body.u2()
                body.u2()
                val flags = body.u2()
                if (inner == thisName) {
                    innerOuter = if (outerIndex != 0) pool.className(outerIndex) else null
                    innerFlags = flags
                }
            }
        }

        private fun readRecord(body: ByteCursor) {
            repeat(body.u2()) {
                body.u2()
                TypeNames.addFromDescriptor(pool.utf8(body.u2()), references)
                readAttributes(body) { name, attribute -> memberAttribute(name, attribute) }
            }
        }

        private fun readAttributes(handler: (String, ByteCursor) -> Boolean) = readAttributes(cursor, handler)

        private fun readAttributes(from: ByteCursor, handler: (String, ByteCursor) -> Boolean) {
            repeat(from.u2()) {
                val name = pool.utf8(from.u2())
                val length = from.u4()
                if (length > from.remaining) {
                    throw MalformedClassException("attribute $name of $length bytes exceeds the remaining ${from.remaining}", from.position)
                }
                val end = from.position + length.toInt()
                val body = ByteCursor(bytes, from.position, end)
                if (handler(name, body) && body.remaining != 0) {
                    throw MalformedClassException("attribute $name leaves ${body.remaining} bytes unread", body.position)
                }
                from.seek(end)
            }
        }

        private fun inlineSources(): List<String> {
            val maps = listOfNotNull(debugText, annotations.debugExtension).filter(SourceMap::isSourceMap)
            if (maps.isEmpty() && pool.anyUtf8StartsWith(INLINE_MARKER)) {
                throw MalformedClassException("inlined code without a source map")
            }
            val classes = TreeSet<String>()
            for (map in maps) classes.addAll(SourceMap.inlinedClasses(map))
            return classes.toList()
        }

        private fun classScope(access: Int): ReachScope = scopeOf(innerFlags ?: access)

        private fun memberScope(access: Int): ReachScope = scopeOf(access)

        private fun scopeOf(flags: Int): ReachScope = when {
            flags and ACC_PRIVATE != 0 -> ReachScope.FILE
            flags and (ACC_PUBLIC or ACC_PROTECTED) != 0 -> ReachScope.ALL
            else -> ReachScope.PACKAGE
        }
    }
}
