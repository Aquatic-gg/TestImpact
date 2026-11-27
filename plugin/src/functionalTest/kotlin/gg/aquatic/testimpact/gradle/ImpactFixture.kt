package gg.aquatic.testimpact.gradle

import java.nio.file.Files
import java.nio.file.Path

/** Writes small Gradle builds with real JUnit tests for the selection scenarios. */
internal object ImpactFixture {
    private const val JUPITER = "5.14.4"
    private const val IGNORED = "build/\n.gradle/\n.kotlin/\n"

    private val kotlinVersion: String = checkNotNull(System.getProperty("testimpact.kotlinVersion")) {
        "the functionalTest task must pass testimpact.kotlinVersion"
    }

    /**
     * One Java module with `Calc` tested by `CalcTest` and `Other` tested by `OtherTest`.
     * [pluginLine] is the plugins entry, empty for a build without the plugin; [extra] is appended to the build script.
     */
    fun singleModule(
        root: Path,
        pluginLine: String = "id(\"gg.aquatic.testimpact\")",
        extra: String = "",
        settingsExtra: String = "",
    ) {
        file(root, "settings.gradle.kts", "rootProject.name = \"fixture\"\n$settingsExtra")
        file(root, ".gitignore", IGNORED)
        file(
            root, "build.gradle.kts",
            """
            plugins {
                `java-library`
                $pluginLine
            }

            repositories { mavenCentral() }

            testing { suites { named<JvmTestSuite>("test") { useJUnitJupiter("$JUPITER") } } }

            $extra
            """.trimIndent() + "\n",
        )
        file(root, "src/main/java/app/Calc.java", calc(1))
        file(root, "src/main/java/app/Other.java", "package app;\n\npublic class Other {\n    public String name() { return \"other\"; }\n}\n")
        file(root, "src/test/java/app/CalcTest.java", test("CalcTest", "new Calc().add(1, 2);"))
        file(root, "src/test/java/app/OtherTest.java", test("OtherTest", "new Other().name();"))
    }

    fun calc(constant: Int): String =
        "package app;\n\npublic class Calc {\n    public int add(int a, int b) { return a + b + $constant; }\n}\n"

    fun test(name: String, body: String): String =
        """
        package app;

        import org.junit.jupiter.api.Test;

        class $name {
            @Test
            void runs() {
                $body
            }
        }
        """.trimIndent() + "\n"

    /**
     * `:a` and `:b` Java, `:c` Java with a `@Nested` test, `:k` Kotlin with a Kotlin test, `:e` Java. `:c` and `:k`
     * depend on `:a`, `:e` on `:b`. The plugin is applied in every module.
     */
    fun multiModule(root: Path) {
        file(
            root, "settings.gradle.kts",
            """
            rootProject.name = "fixture"
            dependencyResolutionManagement { repositories { mavenCentral() } }
            include("a", "b", "c", "k", "e")
            """.trimIndent() + "\n",
        )
        file(root, ".gitignore", IGNORED)
        file(
            root, "build.gradle.kts",
            """
            plugins {
                id("gg.aquatic.testimpact") apply false
                id("org.jetbrains.kotlin.jvm") version "$kotlinVersion" apply false
            }
            """.trimIndent() + "\n",
        )
        file(root, "a/build.gradle.kts", javaModule(""))
        file(root, "a/src/main/java/a/Core.java", core(1))
        file(root, "a/src/test/java/a/CoreTest.java", "package a;\n\nimport org.junit.jupiter.api.Test;\n\nclass CoreTest {\n    @Test\n    void runs() { new Core().value(); }\n}\n")

        file(root, "b/build.gradle.kts", javaModule(""))
        file(root, "b/src/main/java/b/Util.java", "package b;\n\npublic class Util {\n    public int twice(int v) { return v * 2; }\n}\n")
        file(root, "b/src/test/java/b/UtilTest.java", "package b;\n\nimport org.junit.jupiter.api.Test;\n\nclass UtilTest {\n    @Test\n    void runs() { new Util().twice(1); }\n}\n")

        file(root, "c/build.gradle.kts", javaModule("dependencies { implementation(project(\":a\")) }"))
        file(root, "c/src/main/java/c/CoreUser.java", "package c;\n\nimport a.Core;\n\npublic class CoreUser {\n    public int use() { return new Core().value(); }\n}\n")
        file(
            root, "c/src/test/java/c/CoreUserTest.java",
            """
            package c;

            import org.junit.jupiter.api.Nested;
            import org.junit.jupiter.api.Test;

            class CoreUserTest {
                @Nested
                class Inner {
                    @Test
                    void runs() { new CoreUser().use(); }
                }
            }
            """.trimIndent() + "\n",
        )

        file(
            root, "k/build.gradle.kts",
            """
            plugins {
                id("org.jetbrains.kotlin.jvm")
                id("gg.aquatic.testimpact")
            }

            dependencies {
                implementation(project(":a"))
                testImplementation(kotlin("test"))
            }

            testing { suites { named<JvmTestSuite>("test") { useJUnitJupiter("$JUPITER") } } }
            """.trimIndent() + "\n",
        )
        file(root, "k/src/main/kotlin/k/Caller.kt", "package k\n\nimport a.Core\n\nclass Caller {\n    fun call(): Int = Core().value()\n}\n")
        file(
            root, "k/src/test/kotlin/k/CallerTest.kt",
            "package k\n\nimport kotlin.test.Test\n\nclass CallerTest {\n    @Test\n    fun runs() {\n        Caller().call()\n    }\n}\n",
        )

        file(root, "e/build.gradle.kts", javaModule("dependencies { implementation(project(\":b\")) }"))
        file(root, "e/src/main/java/e/UtilUser.java", "package e;\n\nimport b.Util;\n\npublic class UtilUser {\n    public int use() { return new Util().twice(2); }\n}\n")
        file(root, "e/src/test/java/e/UtilUserTest.java", "package e;\n\nimport org.junit.jupiter.api.Test;\n\nclass UtilUserTest {\n    @Test\n    void runs() { new UtilUser().use(); }\n}\n")
    }

    fun core(constant: Int): String =
        "package a;\n\npublic class Core {\n    public int value() { return $constant; }\n}\n"

    private fun javaModule(body: String): String =
        """
        plugins {
            `java-library`
            id("gg.aquatic.testimpact")
        }

        testing { suites { named<JvmTestSuite>("test") { useJUnitJupiter("$JUPITER") } } }

        $body
        """.trimIndent() + "\n"

    fun file(root: Path, relative: String, content: String) {
        val target = root.resolve(relative)
        Files.createDirectories(target.parent)
        Files.writeString(target, content)
    }
}
