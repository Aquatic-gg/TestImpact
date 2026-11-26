package gg.aquatic.testimpact.change.internal

/**
 * Finds the top-level declarations of a Kotlin or Java source with a small lexer instead of a parser: comments,
 * strings, character literals and nested blocks are skipped, so only declarations at brace depth 0 are seen.
 * Malformed input never loops; input nested deeper than [MAX_NESTING] is rejected with [IllegalArgumentException].
 */
internal object SourceScanner {

    const val MAX_NESTING = 100

    fun scan(fileName: String, text: String): SourceDeclarations {
        val java = fileName.endsWith(".java")
        val tokens = Lexer(normalize(text), java).tokens()
        return Declarations(tokens, java, fileName).read()
    }

    private fun normalize(text: String): String =
        text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')

    private enum class Kind { IDENT, QUOTED, STRING, PUNCT }

    private class Token(val kind: Kind, val text: String) {
        fun isPunct(value: String) = kind == Kind.PUNCT && text == value
        fun isKeyword(value: String) = kind == Kind.IDENT && text == value
        val isName get() = kind == Kind.IDENT || kind == Kind.QUOTED
    }

    private class Lexer(private val s: String, private val java: Boolean) {
        private val out = ArrayList<Token>()
        private var i = 0

        fun tokens(): List<Token> {
            while (i < s.length) {
                val c = s[i]
                when {
                    c.isWhitespace() -> i++
                    c == '/' && peek(1) == '/' -> skipLine()
                    c == '/' && peek(1) == '*' -> i = skipComment(i, 0)
                    c == '"' -> string()
                    c == '\'' -> i = skipChar(i)
                    c == '`' && !java -> quoted()
                    Character.isJavaIdentifierStart(c) -> identifier()
                    c.isDigit() -> number()
                    c == ':' && peek(1) == ':' -> {
                        out += Token(Kind.PUNCT, "::")
                        i += 2
                    }
                    else -> {
                        out += Token(Kind.PUNCT, c.toString())
                        i++
                    }
                }
            }
            return out
        }

        private fun peek(offset: Int): Char = if (i + offset < s.length) s[i + offset] else '\u0000'

        private fun skipLine() {
            while (i < s.length && s[i] != '\n') i++
        }

        private fun skipComment(start: Int, level: Int): Int {
            require(level < MAX_NESTING) { "comments nested too deeply" }
            var pos = start + 2
            while (pos < s.length) {
                if (s[pos] == '*' && pos + 1 < s.length && s[pos + 1] == '/') return pos + 2
                if (!java && s[pos] == '/' && pos + 1 < s.length && s[pos + 1] == '*') {
                    pos = skipComment(pos, level + 1)
                } else {
                    pos++
                }
            }
            return pos
        }

        private fun skipChar(start: Int): Int {
            var pos = start + 1
            if (pos < s.length && s[pos] == '\\') pos += 2
            while (pos < s.length && s[pos] != '\'' && s[pos] != '\n') pos++
            return if (pos < s.length && s[pos] == '\'') pos + 1 else pos
        }

        private fun string() {
            val start = i
            i = minOf(s.length, if (s.startsWith("\"\"\"", i)) skipRaw(i + 3, 0) else skipString(i + 1, 0))
            val inner = s.substring(start + 1, (i - 1).coerceAtLeast(start + 1))
            out += Token(Kind.STRING, inner)
        }

        private fun skipString(start: Int, level: Int): Int {
            var pos = start
            while (pos < s.length) {
                when (s[pos]) {
                    '\\' -> pos += 2
                    '"' -> return pos + 1
                    '\n' -> return pos
                    '$' -> pos = if (!java && pos + 1 < s.length && s[pos + 1] == '{') skipTemplate(pos + 2, level) else pos + 1
                    else -> pos++
                }
            }
            return pos
        }

        private fun skipRaw(start: Int, level: Int): Int {
            var pos = start
            while (pos < s.length) {
                when {
                    s[pos] == '\\' && java -> pos += 2
                    s[pos] == '"' && s.startsWith("\"\"\"", pos) -> {
                        pos += 3
                        while (pos < s.length && s[pos] == '"') pos++
                        return pos
                    }
                    s[pos] == '$' && !java && pos + 1 < s.length && s[pos + 1] == '{' -> pos = skipTemplate(pos + 2, level)
                    else -> pos++
                }
            }
            return pos
        }

        private fun skipTemplate(start: Int, level: Int): Int {
            require(level < MAX_NESTING) { "string templates nested too deeply" }
            var pos = start
            var depth = 1
            while (pos < s.length && depth > 0) {
                val c = s[pos]
                when {
                    c == '{' -> {
                        depth++
                        pos++
                    }
                    c == '}' -> {
                        depth--
                        pos++
                    }
                    c == '"' -> pos = if (s.startsWith("\"\"\"", pos)) skipRaw(pos + 3, level + 1) else skipString(pos + 1, level + 1)
                    c == '\'' -> pos = skipChar(pos)
                    c == '/' && pos + 1 < s.length && s[pos + 1] == '*' -> pos = skipComment(pos, level + 1)
                    else -> pos++
                }
            }
            return pos
        }

        private fun quoted() {
            val end = s.indexOf('`', i + 1)
            val stop = if (end < 0) s.length else end
            out += Token(Kind.QUOTED, s.substring(i + 1, stop))
            i = if (end < 0) s.length else end + 1
        }

        private fun identifier() {
            val start = i
            while (i < s.length && Character.isJavaIdentifierPart(s[i])) i++
            out += Token(Kind.IDENT, s.substring(start, i))
        }

        private fun number() {
            while (i < s.length && Character.isJavaIdentifierPart(s[i])) i++
        }
    }

    private class Declarations(
        private val tokens: List<Token>,
        private val java: Boolean,
        private val fileName: String,
    ) {
        private var packageName: String? = null
        private val types = LinkedHashSet<String>()
        private var topLevelCallable = false
        private var jvmName: String? = null

        fun read(): SourceDeclarations {
            var depth = 0
            for (i in tokens.indices) {
                val token = tokens[i]
                if (token.kind == Kind.PUNCT) {
                    when (token.text) {
                        "{", "(", "[" -> depth++
                        "}", ")", "]" -> if (depth > 0) depth--
                        "@" -> if (depth == 0 && !java) readFileAnnotation(i)
                    }
                } else if (depth == 0 && token.kind == Kind.IDENT && !isMember(i)) {
                    readKeyword(i, token.text)
                }
            }
            val facade = if (java || !topLevelCallable) null else jvmName ?: facadeName()
            return SourceDeclarations(packageName.orEmpty(), types.toList(), facade)
        }

        private fun isMember(i: Int): Boolean {
            val previous = tokens.getOrNull(i - 1) ?: return false
            return previous.isPunct(".") || previous.isPunct("::")
        }

        private fun readKeyword(i: Int, keyword: String) {
            when (keyword) {
                "package" -> if (packageName == null) packageName = dotted(i + 1)
                "class", "interface" -> addType(i + 1)
                "object" -> if (!java) addType(i + 1)
                "enum" -> if (java) addType(i + 1)
                "record" -> if (java && isRecordHeader(i)) addType(i + 1)
                "fun" -> if (!java && !(tokens.getOrNull(i + 1)?.isKeyword("interface") ?: false)) topLevelCallable = true
                "val", "var" -> if (!java) topLevelCallable = true
            }
        }

        private fun isRecordHeader(i: Int): Boolean {
            val name = tokens.getOrNull(i + 1) ?: return false
            val after = tokens.getOrNull(i + 2) ?: return false
            return name.kind == Kind.IDENT && (after.isPunct("(") || after.isPunct("<"))
        }

        private fun addType(index: Int) {
            val name = tokens.getOrNull(index) ?: return
            if (name.isName) types += name.text
        }

        private fun dotted(start: Int): String {
            val parts = ArrayList<String>()
            var i = start
            while (true) {
                val name = tokens.getOrNull(i) ?: break
                if (!name.isName) break
                parts += name.text
                if (tokens.getOrNull(i + 1)?.isPunct(".") == true) i += 2 else break
            }
            return parts.joinToString(".")
        }

        private fun readFileAnnotation(at: Int) {
            if (tokens.getOrNull(at + 1)?.isKeyword("file") != true || tokens.getOrNull(at + 2)?.isPunct(":") != true) return
            val target = dotted(at + 3)
            if (target.substringAfterLast('.') != "JvmName") return
            var i = at + 3 + target.count { it == '.' } * 2 + 1
            if (tokens.getOrNull(i)?.isPunct("(") != true) return
            i++
            while (i < tokens.size && !tokens[i].isPunct(")")) {
                if (tokens[i].kind == Kind.STRING) {
                    jvmName = tokens[i].text
                    return
                }
                i++
            }
        }

        private fun facadeName(): String {
            val stem = fileName.substringAfterLast('/').removeSuffix(".kt")
            val sanitized = stem.map { if (Character.isJavaIdentifierPart(it)) it else '_' }.joinToString("")
            return sanitized.replaceFirstChar { it.uppercaseChar() } + "Kt"
        }
    }
}
