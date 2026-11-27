package gg.aquatic.testimpact.graph

/**
 * Why a class depends on another. When two classes are linked for several reasons the edge keeps the first
 * kind in declaration order.
 *
 * Soundness model: a class file only shows a dependency when the compiler left a trace of it. Constants are
 * folded into their users and typealiases are erased, so [CONSTANT] and [TYPEALIAS] edges are not read from
 * the user but derived from the definer: every scanned class that may see the declaration (see [ReachScope])
 * is treated as a user. That never misses a user. A constant of a class that can be subclassed (not final, or an
 * interface) is visible wherever a wider subclass is, so only its field visibility counts; a final class also
 * narrows it to its own visibility. The residual is over-selection: a change to a class that owns a public
 * constant or declares a public typealias reaches every scanned class (every Kotlin class for aliases),
 * however little of it is really used.
 */
internal enum class EdgeKind {
    /** Superclass or interface. */
    SUPERTYPE,

    /** Any other class mentioned by the class file: code, signatures, annotations, descriptors. */
    REFERENCE,

    /** An inline function body of the target was copied into the source class (Kotlin source map). */
    INLINE,

    /** The target owns a constant that is visible to the source class and may have been folded into it. */
    CONSTANT,

    /** The target declares a Kotlin typealias that is visible to the source class and may have been erased. */
    TYPEALIAS,
}
