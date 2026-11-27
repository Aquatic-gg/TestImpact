package gg.aquatic.testimpact.report.internal

/**
 * Writes JSON from maps (kept in iteration order), lists, strings, integers, booleans and null. Output uses two-space
 * indent, LF line ends and a trailing newline, so equal input gives equal bytes.
 */
internal object JsonWriter {

    fun write(value: Any?): String {
        val out = StringBuilder()
        value(out, value, 0)
        return out.append('\n').toString()
    }

    private fun value(out: StringBuilder, value: Any?, depth: Int) {
        when (value) {
            null -> out.append("null")
            is String -> string(out, value)
            is Boolean -> out.append(value)
            is Int, is Long -> out.append(value)
            is Map<*, *> -> obj(out, value, depth)
            is List<*> -> array(out, value, depth)
            else -> throw IllegalArgumentException("Unsupported JSON value type ${value.javaClass.name}")
        }
    }

    private fun obj(out: StringBuilder, map: Map<*, *>, depth: Int) {
        if (map.isEmpty()) {
            out.append("{}")
            return
        }
        out.append("{\n")
        var first = true
        for ((key, member) in map) {
            if (key !is String) throw IllegalArgumentException("JSON object keys must be strings")
            if (!first) out.append(",\n")
            first = false
            indent(out, depth + 1)
            string(out, key)
            out.append(": ")
            value(out, member, depth + 1)
        }
        out.append('\n')
        indent(out, depth)
        out.append('}')
    }

    private fun array(out: StringBuilder, list: List<*>, depth: Int) {
        if (list.isEmpty()) {
            out.append("[]")
            return
        }
        out.append("[\n")
        for ((index, member) in list.withIndex()) {
            if (index > 0) out.append(",\n")
            indent(out, depth + 1)
            value(out, member, depth + 1)
        }
        out.append('\n')
        indent(out, depth)
        out.append(']')
    }

    private fun indent(out: StringBuilder, depth: Int) {
        repeat(depth) { out.append("  ") }
    }

    private fun string(out: StringBuilder, text: String) {
        out.append('"')
        var index = 0
        while (index < text.length) {
            val char = text[index]
            when {
                char == '"' -> out.append("\\\"")
                char == '\\' -> out.append("\\\\")
                char == '\b' -> out.append("\\b")
                char == '\u000C' -> out.append("\\f")
                char == '\n' -> out.append("\\n")
                char == '\r' -> out.append("\\r")
                char == '\t' -> out.append("\\t")
                char < ' ' || char == ' ' || char == ' ' -> unicode(out, char)
                char.isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate() -> {
                    out.append(char).append(text[index + 1])
                    index++
                }

                char.isSurrogate() -> unicode(out, char)
                else -> out.append(char)
            }
            index++
        }
        out.append('"')
    }

    private fun unicode(out: StringBuilder, char: Char) {
        out.append("\\u").append(char.code.toString(16).padStart(4, '0'))
    }
}
