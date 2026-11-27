package gg.aquatic.testimpact.graph.internal.kotlin

import gg.aquatic.testimpact.graph.internal.classfile.MalformedClassException
import gg.aquatic.testimpact.graph.internal.classfile.TypeNames
import java.util.TreeSet

/**
 * The JSR-45 source map Kotlin writes into classes that contain inlined code. Each file entry of the
 * `*F` section names, on the line after `+ id name`, the internal name of the class the inlined body came from.
 */
internal object SourceMap {

    fun isSourceMap(text: String): Boolean = text.startsWith("SMAP")

    /**
     * Binary names of every class listed in a `*F` section of any stratum, sorted; empty for text that is
     * not a source map. An entry without a path or a map without the `*E` end marker throws.
     */
    fun inlinedClasses(text: String): Set<String> {
        if (!isSourceMap(text)) return emptySet()
        val classes = TreeSet<String>()
        val lines = text.lines()
        var inFiles = false
        var ended = false
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.startsWith("*E") -> {
                    ended = true
                    break
                }
                line.startsWith("*F") -> inFiles = true
                line.startsWith("*") -> inFiles = false
                inFiles && line.startsWith("+") -> {
                    val path = lines.getOrNull(i + 1)?.trim()
                    if (path.isNullOrEmpty() || path.startsWith("*") || path.startsWith("+")) {
                        throw MalformedClassException("source map file entry \"${line.take(80)}\" has no path")
                    }
                    classes.add(TypeNames.toBinary(path))
                    i++
                }
            }
            i++
        }
        if (!ended) throw MalformedClassException("source map without an end marker")
        return classes
    }
}
