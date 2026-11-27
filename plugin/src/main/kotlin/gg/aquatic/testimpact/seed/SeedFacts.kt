package gg.aquatic.testimpact.seed

/**
 * What one class file says that its bytecode references do not: the unit that can be cached per class.
 *
 * - [name]: binary name of the class.
 * - [literals]: sorted, distinct Utf8 constants that look like a dotted name or a resource path.
 * - [dynamic]: the class calls reflection or service loading APIs, so what it reaches is not visible in bytecode.
 * - [loadsResources]: the class calls a resource lookup such as `Class.getResource`.
 * - [buildsStrings]: the class builds strings at runtime, so a resource name it loads may be computed.
 * - [provides]: `provides ... with ...` clauses when the class is a module descriptor, sorted by service.
 */
internal data class SeedFacts(
    val name: String,
    val literals: List<String>,
    val dynamic: Boolean,
    val loadsResources: Boolean,
    val buildsStrings: Boolean,
    val provides: List<ServiceProvision>,
)
