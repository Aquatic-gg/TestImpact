package gg.aquatic.testimpact.graph.internal.classfile

/**
 * Class names referenced by descriptors and generic signatures (JVMS 4.3, 4.7.9.1), as binary names
 * (`p.Outer$Inner`). Scanning is iterative, so nesting depth cannot overflow the stack, and anything that
 * does not follow the grammar throws [MalformedClassException].
 */
internal object TypeNames {

    private const val PRIMITIVES = "BCDFIJSZV"

    fun toBinary(internalName: String): String = internalName.replace('/', '.')

    /**
     * Binary name of the value of a CONSTANT_Class entry: arrays resolve to the element class,
     * arrays of primitives to null.
     */
    fun fromClassConstant(internalName: String): String? {
        if (internalName.isEmpty()) throw MalformedClassException("empty class name")
        if (internalName[0] != '[') return toBinary(internalName)
        var dimensions = 0
        while (dimensions < internalName.length && internalName[dimensions] == '[') dimensions++
        val element = internalName.substring(dimensions)
        return when {
            element.length == 1 && element[0] in PRIMITIVES && element[0] != 'V' -> null
            element.length > 2 && element[0] == 'L' && element.last() == ';' ->
                toBinary(element.substring(1, element.length - 1))
            else -> throw MalformedClassException("invalid array class name \"$internalName\"")
        }
    }

    /** Adds the classes of a field descriptor or a method descriptor to [into]. */
    fun addFromDescriptor(descriptor: String, into: MutableCollection<String>) {
        scan(descriptor, generic = false, into)
    }

    /** Adds the classes of a class, field, method or record component signature to [into]. */
    fun addFromSignature(signature: String, into: MutableCollection<String>) {
        scan(signature, generic = true, into)
    }

    private class Frame(var name: String) {
        var inArguments = false
    }

    private fun scan(text: String, generic: Boolean, into: MutableCollection<String>) {
        val frames = ArrayList<Frame>()
        var inFormals = false
        var atBoundary = false
        var i = 0
        if (generic && text.startsWith("<")) {
            inFormals = true
            atBoundary = true
            i = 1
        }

        fun fail(reason: String): Nothing =
            throw MalformedClassException("$reason in ${if (generic) "signature" else "descriptor"} \"${text.take(80)}\" at index $i")

        fun typeCompleted() {
            if (frames.isEmpty() && inFormals) atBoundary = true
        }

        while (i < text.length) {
            val top = frames.lastOrNull()
            val c = text[i]
            if (top != null && !top.inArguments) {
                when (c) {
                    ';' -> {
                        frames.removeAt(frames.size - 1)
                        typeCompleted()
                        i++
                    }
                    '<' -> {
                        if (!generic) fail("type arguments in a descriptor")
                        top.inArguments = true
                        i++
                    }
                    '.' -> {
                        if (!generic) fail("inner class suffix in a descriptor")
                        val end = nameEnd(text, i + 1)
                        if (end == i + 1) fail("empty inner class name")
                        top.name = top.name + "$" + text.substring(i + 1, end)
                        into.add(toBinary(top.name))
                        i = end
                    }
                    else -> fail("unexpected '$c' after a class name")
                }
                continue
            }
            if (atBoundary) {
                when (c) {
                    '>' -> {
                        if (text[i - 1] == '<') fail("empty type parameter list")
                        inFormals = false
                        atBoundary = false
                        i++
                    }
                    ':' -> {
                        i++
                        if (i < text.length && text[i] in "L[T") atBoundary = false
                    }
                    else -> {
                        val colon = text.indexOf(':', i)
                        if (colon <= i) fail("type parameter without a bound")
                        i = colon
                    }
                }
                continue
            }
            when (c) {
                'L' -> {
                    val end = nameEnd(text, i + 1)
                    if (end == i + 1) fail("empty class name")
                    val name = text.substring(i + 1, end)
                    into.add(toBinary(name))
                    frames.add(Frame(name))
                    i = end
                }
                'T' -> {
                    if (!generic) fail("type variable in a descriptor")
                    val end = text.indexOf(';', i)
                    if (end <= i + 1) fail("malformed type variable")
                    i = end + 1
                    typeCompleted()
                }
                '[' -> i++
                '(', ')' -> i++
                '^' -> {
                    if (!generic) fail("throws clause in a descriptor")
                    i++
                }
                '*' -> {
                    if (!generic || top == null) fail("unexpected wildcard")
                    i++
                }
                '+', '-' -> {
                    if (!generic || top == null) fail("unexpected wildcard bound")
                    i++
                }
                '>' -> {
                    if (top == null || !top.inArguments) fail("unbalanced '>'")
                    top.inArguments = false
                    i++
                }
                else -> {
                    if (c !in PRIMITIVES) fail("unexpected '$c'")
                    i++
                    typeCompleted()
                }
            }
        }
        if (frames.isNotEmpty() || inFormals) fail("unterminated type")
    }

    private fun nameEnd(text: String, from: Int): Int {
        var end = from
        while (end < text.length) {
            val c = text[end]
            if (c == ';' || c == '<' || c == '.') break
            if (c in ">:[()") throw MalformedClassException("invalid character '$c' in class name \"${text.take(80)}\"")
            end++
        }
        return end
    }
}
