package gg.aquatic.testimpact.graph.internal.kotlin

import gg.aquatic.testimpact.graph.ReachScope
import gg.aquatic.testimpact.graph.internal.classfile.TypeNames
import java.util.TreeSet

/**
 * What the dependency graph needs from a `kotlin.Metadata` annotation: the kind, the multifile facade of a
 * part (kind 5), class names mentioned in the string table (d2), and the typealias scope.
 * d2 holds classes erased from declarations, such as the type of a `@JvmInline` parameter, as `Lp/X;`.
 */
internal class KotlinMetadata private constructor(
    val kind: Int,
    val facade: String?,
    val classNames: Set<String>,
    val typeAliasScope: ReachScope?,
) {
    internal companion object {
        private const val MULTIFILE_PART = 5
        private const val FORBIDDEN = " .;[<>():,*\\\t\r\n"

        fun of(raw: RawKotlinMetadata): KotlinMetadata {
            val facade = raw.extraString?.takeIf { raw.kind == MULTIFILE_PART && it.isNotBlank() }?.let(TypeNames::toBinary)
            val names = TreeSet<String>()
            for (text in raw.data2) addClassNames(text, names)
            return KotlinMetadata(raw.kind, facade, names, MetadataProto.typeAliasScope(raw.kind, raw.data1))
        }

        private fun addClassNames(text: String, into: MutableSet<String>) {
            if (text.length > 2 && text[0] == 'L' && text.last() == ';') {
                val internal = text.substring(1, text.length - 1)
                if (isInternalName(internal, allowDots = false)) into.add(TypeNames.toBinary(internal))
            } else if ('/' in text && isInternalName(text, allowDots = true)) {
                into.add(TypeNames.toBinary(text))
                val slash = text.lastIndexOf('/')
                if ('.' in text.substring(slash)) into.add(TypeNames.toBinary(text.substring(0, slash + 1) + text.substring(slash + 1).replace('.', '$')))
            }
        }

        private fun isInternalName(text: String, allowDots: Boolean): Boolean {
            var segmentStart = 0
            for (i in 0..text.length) {
                if (i == text.length || text[i] == '/') {
                    if (i == segmentStart) return false
                    segmentStart = i + 1
                } else {
                    val c = text[i]
                    if (c == '.' && allowDots) continue
                    if (c in FORBIDDEN || c.isWhitespace() || c.isISOControl()) return false
                }
            }
            return true
        }
    }
}
