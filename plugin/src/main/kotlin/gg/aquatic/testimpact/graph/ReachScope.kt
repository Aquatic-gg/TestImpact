package gg.aquatic.testimpact.graph

/**
 * How far a declaration can be used from, widest last. Class files cannot tell which classes really use a
 * constant or a typealias (the compiler folds or erases it), so every class inside the scope counts as a user.
 * A constant's scope is its field visibility, narrowed by the class visibility only for a final class that is
 * not an interface (a wider subclass would otherwise expose it).
 */
internal enum class ReachScope {
    /** Same package and same source file, which includes every class generated from that file. */
    FILE,

    /** Same package. */
    PACKAGE,

    /** Any scanned class. */
    ALL,
}
