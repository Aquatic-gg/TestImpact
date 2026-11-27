package gg.aquatic.testimpact.gradle

import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty

/**
 * The `testImpact { }` block of a project.
 *
 * Every property is validated when test selection reads them, not when they are set.
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

    /**
     * How the plugin treats the Test tasks: `auto` selects the tests a change can reach, `full` runs every test
     * and `off` leaves the tasks exactly as they are without the plugin. The Gradle property `-PtestImpact` and
     * the environment variable `TEST_IMPACT` take precedence over this value. Case-insensitive; defaults to
     * `auto`. An invalid value fails the build with a message naming where it came from.
     */
    public abstract val mode: Property<String>

    /**
     * Git ref the changes are measured against, for example `origin/main`. When absent the plugin derives it
     * from the repository. 1 to 255 characters, no whitespace or control characters, no leading `-` and no `..`
     * at either end.
     */
    public abstract val baseRef: Property<String>

    /**
     * Largest share of the test classes, in percent, that a partial selection may hold. A selection above it
     * runs the full task because a change that wide is not worth analysing. 1 to 100; defaults to 60.
     */
    public abstract val wideChangePercent: Property<Int>

    /**
     * Fewest test classes a task needs before [wideChangePercent] applies, so small modules are never run in
     * full for that reason. 0 to 1,000,000; defaults to 12.
     */
    public abstract val wideChangeMinClasses: Property<Int>
}
