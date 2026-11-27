package gg.aquatic.testimpact.seed

/** Why a seed edge exists, in priority order: when two kinds link the same pair, the earlier one is kept. */
internal enum class SeedKind {
    /** A service interface and what provides it: a services file or a module descriptor `provides`. */
    SERVICE,

    /** A string literal that equals a class name. */
    CLASS_NAME,

    /** A string literal that equals a package, reaching every class under it. */
    PACKAGE_NAME,

    /** A descriptor resource that names a class or a package. */
    DESCRIPTOR,

    /** A string literal that names a resource. */
    RESOURCE_NAME,

    /** A class that looks resources up by an unknown or computed name. */
    RESOURCE_LOADER,

    /** A class using reflection reaches what lives in its package or below. */
    DYNAMIC,
}
