package gg.aquatic.testimpact.seed.internal

import java.util.TreeSet

/** Finds dotted identifier tokens in resource bytes without decoding the file. */
internal object ResourceTokens {
    private const val MAX_TOKEN = 512
    private const val META_INF = "META-INF/"
    private val TEXT_EXTENSIONS = setOf("yml", "yaml", "properties", "json", "xml", "conf", "toml", "txt", "mf")

    /** A resource whose content is scanned for class and package names. */
    fun isDescriptor(path: String): Boolean {
        if (path.startsWith(META_INF)) return true
        val dot = path.lastIndexOf('.')
        val slash = path.lastIndexOf('/')
        return dot > slash && path.substring(dot + 1).lowercase() in TEXT_EXTENSIONS
    }

    /**
     * Tokens are identifier segments joined by single dots, start after a non-identifier byte and hold at least one
     * dot. A token longer than 512 characters is dropped; a non-ASCII byte ends a token.
     */
    fun scan(bytes: ByteArray): List<String> {
        val tokens = TreeSet<String>()
        var i = 0
        while (i < bytes.size) {
            if (!isIdentifier(bytes[i])) {
                i++
                continue
            }
            val start = i
            var dots = 0
            while (true) {
                while (i < bytes.size && isIdentifier(bytes[i])) i++
                if (i + 1 < bytes.size && bytes[i] == DOT && isIdentifier(bytes[i + 1])) {
                    dots++
                    i++
                } else {
                    break
                }
            }
            val length = i - start
            if (dots > 0 && length <= MAX_TOKEN) tokens.add(String(bytes, start, length, Charsets.US_ASCII))
        }
        return tokens.toList()
    }

    private const val DOT: Byte = '.'.code.toByte()

    private fun isIdentifier(b: Byte): Boolean {
        val c = b.toInt()
        return c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code || c in '0'.code..'9'.code || c == '_'.code || c == '$'.code
    }
}
