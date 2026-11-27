package gg.aquatic.testimpact.seed

import gg.aquatic.testimpact.seed.internal.PackageNames
import gg.aquatic.testimpact.seed.internal.TestPattern

/**
 * The user's seed configuration, validated: packages whose classes count as dynamic and test class patterns that
 * always run. Entries come from a build script, so they are checked before use and every bad entry is reported.
 */
internal class SeedRules private constructor(
    val dynamicPackages: List<String>,
    private val patterns: List<TestPattern>,
) {
    private val dynamicSet: Set<String> = dynamicPackages.toSet()

    /** True when [packageName] equals or lies below a listed dynamic package. */
    fun marksDynamic(packageName: String): Boolean =
        dynamicSet.isNotEmpty() && PackageNames.withAncestors(packageName).any { it in dynamicSet }

    /** The test classes matching any `alwaysRun` pattern, sorted and distinct. */
    fun alwaysRun(testClasses: Collection<String>): List<String> =
        testClasses.filter { name -> patterns.any { it.matches(name) } }.distinct().sorted()

    companion object {
        const val MAX_ENTRIES: Int = 1000
        const val MAX_LENGTH: Int = 512
        private const val SHOWN_LENGTH = 60

        val NONE: SeedRules = SeedRules(emptyList(), emptyList())

        /** @throws IllegalArgumentException naming the property and every bad entry */
        fun of(dynamicPackages: Collection<String>, alwaysRun: Collection<String>): SeedRules {
            val problems = ArrayList<String>()
            val packages = check("dynamicPackages", dynamicPackages, ::isPackage, "not a package name", problems)
            val globs = check("alwaysRun", alwaysRun, ::isPattern, "not a class name pattern", problems)
            require(problems.isEmpty()) { problems.joinToString("; ") }
            return SeedRules(packages, globs.map { TestPattern(it) })
        }

        private fun check(
            property: String,
            entries: Collection<String>,
            valid: (String) -> Boolean,
            expectation: String,
            problems: MutableList<String>,
        ): List<String> {
            if (entries.size > MAX_ENTRIES) {
                problems.add("$property: ${entries.size} entries, at most $MAX_ENTRIES allowed")
                return emptyList()
            }
            val accepted = ArrayList<String>()
            for (entry in entries.distinct().sorted()) {
                when {
                    entry.length > MAX_LENGTH ->
                        problems.add("$property: entry '${shown(entry)}' is longer than $MAX_LENGTH characters")
                    entry.isBlank() -> problems.add("$property: entry '${shown(entry)}' is blank")
                    !valid(entry) -> problems.add("$property: entry '${shown(entry)}' is $expectation")
                    else -> accepted.add(entry)
                }
            }
            return accepted
        }

        private fun isPackage(entry: String): Boolean = entry.split('.').all { segment ->
            segment.isNotEmpty() && Character.isJavaIdentifierStart(segment[0]) &&
                segment.all { Character.isJavaIdentifierPart(it) && it != '\u0000' }
        }

        private fun isPattern(entry: String): Boolean =
            entry.all { it == '_' || it == '$' || it == '.' || it == '*' || it.isLetterOrDigit() }

        private fun shown(entry: String): String {
            val clean = entry.map { if (it.isISOControl()) '?' else it }.joinToString("")
            return if (clean.length > SHOWN_LENGTH) clean.take(SHOWN_LENGTH) + "..." else clean
        }
    }
}
