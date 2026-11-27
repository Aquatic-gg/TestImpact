package gg.aquatic.testimpact.select

import java.nio.file.Path

/**
 * What selection needs to know about one test task, resolved at execution time.
 *
 * @property testRoots class directories of the task's own tests; only classes found here can be selected.
 * @property otherRoots every other scanned classpath entry (main outputs, fixtures, project jars, file dependencies).
 * @property engines class names of the JUnit Platform engines found on the classpath.
 * @property globalClasses classes of the task's own roots registered for a global JUnit Platform or Jupiter extension point.
 */
internal data class TestTaskInput(
    val taskPath: String,
    val testRoots: List<Path>,
    val otherRoots: List<Path>,
    val framework: TestFrameworkKind,
    val engines: List<String> = emptyList(),
    val globalClasses: Set<String> = emptySet(),
)
