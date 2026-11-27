package gg.aquatic.testimpact.escalation

import gg.aquatic.testimpact.gradle.GitFixture
import gg.aquatic.testimpact.gradle.ImpactFixture
import java.nio.file.Files
import java.nio.file.Path

/**
 * Builds for the red-run scenarios: one module whose `Calc` has a switchable bug that `CalcTest` catches. `halt`, enabled
 * by `-Phalt`, kills the build JVM right after the quick task, so a build stopped between the phases can be observed.
 * It is a finalizer of the quick task, not a dependency of the user's task: the quick task copies that task's
 * dependencies, and a task ordered after the quick task would end up in a cycle.
 */
internal object EscalationFixture {
    private const val HALT = """
        val haltRequested = providers.gradleProperty("halt").isPresent
        val halt = tasks.register("halt") {
            enabled = haltRequested
            doLast { Runtime.getRuntime().halt(1) }
        }
        tasks.matching { it.name == "testImpactQuickTest" }.configureEach { finalizedBy(halt) }
    """

    /** A green, committed module: `CalcTest` and `OtherTest` pass. */
    fun singleModule(
        root: Path,
        extra: String = "",
        settingsExtra: String = "",
        pluginLine: String = "id(\"gg.aquatic.testimpact\")",
    ) {
        ImpactFixture.singleModule(
            root,
            pluginLine = pluginLine,
            extra = HALT.trimIndent() + "\n" + extra,
            settingsExtra = settingsExtra,
        )
        ImpactFixture.file(root, "src/test/java/app/CalcTest.java", calcTest())
        GitFixture.init(root)
    }

    /** Settings lines for a local build cache in [dir], which lies outside the project. */
    fun localCache(dir: Path): String =
        "buildCache { local { directory = file(\"${dir.toString().replace('\\', '/')}\") } }\n"

    /**
     * Two independent green modules `:a` and `:b`, each with one test asserting that its class returns 0, after the layout
     * of [ImpactFixture.multiModule]. [bug] turns one of them red.
     */
    fun twoModules(root: Path) {
        ImpactFixture.file(
            root, "settings.gradle.kts",
            "rootProject.name = \"fixture\"\n" +
                "dependencyResolutionManagement { repositories { mavenCentral() } }\n" +
                "include(\"a\", \"b\")\n",
        )
        ImpactFixture.file(root, ".gitignore", "build/\n.gradle/\n.kotlin/\n")
        ImpactFixture.file(root, "build.gradle.kts", "plugins { id(\"gg.aquatic.testimpact\") apply false }\n")
        for ((module, type) in listOf("a" to "Core", "b" to "Util")) {
            ImpactFixture.file(
                root, "$module/build.gradle.kts",
                """
                plugins {
                    `java-library`
                    id("gg.aquatic.testimpact")
                }

                testing { suites { named<JvmTestSuite>("test") { useJUnitJupiter("${ImpactFixture.JUPITER}") } } }
                """.trimIndent() + "\n",
            )
            bug(root, module, 0)
            ImpactFixture.file(
                root, "$module/src/test/java/$module/${type}Test.java",
                "package $module;\n\nimport org.junit.jupiter.api.Test;\n\nclass ${type}Test {\n" +
                    "    @Test\n    void runs() {\n" +
                    "        org.junit.jupiter.api.Assertions.assertEquals(0, new $type().value());\n    }\n}\n",
            )
        }
        GitFixture.init(root)
    }

    /** The class of [module] returns [constant]; anything but 0 fails its test. */
    fun bug(root: Path, module: String, constant: Int) {
        val type = if (module == "a") "Core" else "Util"
        ImpactFixture.file(
            root, "$module/src/main/java/$module/$type.java",
            "package $module;\n\npublic class $type {\n    public int value() { return $constant; }\n}\n",
        )
    }

    /** `Calc` with the bug of [constant]; 0 is the fixed version. */
    fun calc(root: Path, constant: Int) {
        ImpactFixture.file(root, "src/main/java/app/Calc.java", ImpactFixture.calc(constant))
    }

    /** A test that kills its JVM, so the executor dies without a class to blame. */
    fun crashTest(root: Path) {
        ImpactFixture.file(root, "src/test/java/app/CrashTest.java", ImpactFixture.test("CrashTest", "System.exit(1);"))
    }

    fun state(root: Path, name: String = "test"): String =
        Files.readString(root.resolve("build/testimpact/escalation/$name.state"))

    fun results(root: Path, task: String = "test"): Path = root.resolve("build/test-results/$task")

    fun hasReport(root: Path, className: String, task: String = "test"): Boolean =
        Files.exists(results(root, task).resolve("TEST-$className.xml"))

    fun clearResults(root: Path) {
        val dir = root.resolve("build/test-results")
        if (!Files.exists(dir)) return
        Files.walk(dir).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
    }

    private fun calcTest(): String =
        ImpactFixture.test("CalcTest", "org.junit.jupiter.api.Assertions.assertEquals(3, new Calc().add(1, 2));")
}
