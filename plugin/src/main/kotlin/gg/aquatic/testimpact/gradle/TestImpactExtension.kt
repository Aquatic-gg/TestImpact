package gg.aquatic.testimpact.gradle

import org.gradle.api.provider.SetProperty

/**
 * The `testImpact { }` block of a project.
 *
 * Both properties are validated when test selection reads them, not when they are set.
 * An invalid entry fails the build with a message naming the property and the entry.
 */
public abstract class TestImpactExtension {

    /**
     * Packages whose classes may be reached through reflection that bytecode cannot show.
     * Each entry is a dotted package name such as `com.example.plugins`. A class that
     * reflects is assumed to reach every class in its own package and in the packages
     * below it, and a changed class in a listed package is assumed to be reachable
     * from every class that reflects. At most 1000 entries of at most 512 characters.
     */
    public abstract val dynamicPackages: SetProperty<String>

    /**
     * Test classes that always run, whatever changed. Each entry is a class name pattern
     * made of letters, digits, `_`, `$`, `.` and the wildcard `*`, which matches any
     * sequence of characters including dots. An entry without a dot matches the simple
     * class name, so `*IntegrationTest` matches every class with that suffix. At most
     * 1000 entries of at most 512 characters.
     */
    public abstract val alwaysRun: SetProperty<String>
}
