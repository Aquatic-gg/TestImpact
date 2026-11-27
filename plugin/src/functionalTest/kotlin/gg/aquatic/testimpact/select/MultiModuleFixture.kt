package gg.aquatic.testimpact.select

import org.gradle.testkit.runner.GradleRunner
import java.nio.file.Files
import java.nio.file.Path

/**
 * Writes a six-module Java build that exercises project dependencies, test fixtures, a custom test suite, inherited
 * tests across modules and an unknown engine. The probe plugin is applied in each module by class name.
 */
internal object MultiModuleFixture {
    private const val JUPITER = "5.14.4"
    const val PROBE_ID = "gg.aquatic.testimpact.probe"
    private const val PROBE = PROBE_ID

    val modules = listOf("a", "b", "c", "d", "e", "f")

    /** A runner with the plugin under test and the probe plugin on its classpath. */
    fun runner(projectDir: Path): GradleRunner {
        val base = GradleRunner.create().withProjectDir(projectDir.toFile()).withPluginClasspath()
        val probeClasses = Path.of(SelectionProbePlugin::class.java.protectionDomain.codeSource.location.toURI()).toFile()
        val descriptor = "META-INF/gradle-plugins/$PROBE_ID.properties"
        val descriptorUrl = checkNotNull(SelectionProbePlugin::class.java.classLoader.getResource(descriptor)) { descriptor }
        val probeResources = Path.of(descriptorUrl.toURI()).parent.parent.parent.toFile()
        return base.withPluginClasspath(base.pluginClasspath + probeClasses + probeResources)
    }

    fun write(root: Path) {
        file(root, "settings.gradle.kts", settings())
        file(
            root, "build.gradle.kts",
            "plugins {\n    id(\"gg.aquatic.testimpact\")\n    id(\"$PROBE\") apply false\n}\n",
        )
        file(root, "a/build.gradle.kts", script(""))
        file(root, "a/src/main/java/a/Core.java", "package a;\n\npublic class Core {\n    public int value() { return 1; }\n}\n")

        file(root, "b/build.gradle.kts", script("dependencies { api(project(\":a\")) }"))
        file(
            root, "b/src/main/java/b/Service.java",
            "package b;\n\nimport a.Core;\n\npublic class Service {\n    public Core core() { return new Core(); }\n}\n",
        )

        file(
            root, "c/build.gradle.kts",
            script(
                """
                dependencies { implementation(project(":b")) }
                testing {
                    suites {
                        named<JvmTestSuite>("test") { useJUnitJupiter("$JUPITER") }
                        register<JvmTestSuite>("integrationTest") {
                            useJUnitJupiter("$JUPITER")
                            dependencies {
                                implementation(project())
                                implementation(project(":b"))
                            }
                        }
                    }
                }
                """.trimIndent(),
            ),
        )
        file(root, "c/src/main/java/c/Feature.java", "package c;\n\npublic class Feature {\n    public String name() { return \"feature\"; }\n}\n")
        file(
            root, "c/src/test/java/c/ServiceTest.java",
            """
            package c;

            import a.Core;
            import b.Service;
            import org.junit.jupiter.api.Nested;
            import org.junit.jupiter.api.Test;
            import org.junit.jupiter.params.ParameterizedTest;
            import org.junit.jupiter.params.provider.ValueSource;

            class ServiceTest {
                @ParameterizedTest
                @ValueSource(ints = {1, 2})
                void parameterised(int value) {
                    new Service();
                }

                @Nested
                class Inner {
                    @Test
                    void usesCore() {
                        new Core().value();
                    }
                }
            }
            """.trimIndent() + "\n",
        )
        file(
            root, "c/src/integrationTest/java/c/ServiceIT.java",
            """
            package c;

            import b.Service;
            import org.junit.jupiter.api.Test;

            class ServiceIT {
                @Test
                void service() {
                    new Service().core();
                }
            }
            """.trimIndent() + "\n",
        )

        file(
            root, "d/build.gradle.kts",
            script(
                """
                plugins { `java-test-fixtures` }
                dependencies { testFixturesApi("org.junit.jupiter:junit-jupiter:$JUPITER") }
                testing { suites { named<JvmTestSuite>("test") { useJUnitJupiter("$JUPITER") } } }
                """.trimIndent(),
            ),
        )
        file(root, "d/src/main/java/d/Engine.java", "package d;\n\npublic class Engine {\n    public boolean running() { return true; }\n}\n")
        file(
            root, "d/src/testFixtures/java/d/AbstractContractTest.java",
            """
            package d;

            import org.junit.jupiter.api.Test;

            public abstract class AbstractContractTest {
                protected final Engine engine = new Engine();

                @Test
                void contract() {
                    engine.running();
                }
            }
            """.trimIndent() + "\n",
        )
        file(root, "d/src/test/java/d/EngineContractTest.java", "package d;\n\nclass EngineContractTest extends AbstractContractTest {\n}\n")

        file(
            root, "e/build.gradle.kts",
            script(
                """
                dependencies { testImplementation(testFixtures(project(":d"))) }
                testing { suites { named<JvmTestSuite>("test") { useJUnitJupiter("$JUPITER") } } }
                """.trimIndent(),
            ),
        )
        file(root, "e/src/test/java/e/OtherContractTest.java", "package e;\n\nclass OtherContractTest extends d.AbstractContractTest {\n}\n")
        file(
            root, "e/src/test/java/e/UnrelatedTest.java",
            "package e;\n\nimport org.junit.jupiter.api.Test;\n\nclass UnrelatedTest {\n    @Test\n    void unrelated() {}\n}\n",
        )

        file(
            root, "f/build.gradle.kts",
            script("testing { suites { named<JvmTestSuite>(\"test\") { useJUnitJupiter(\"$JUPITER\") } } }"),
        )
        file(
            root, "f/src/test/java/f/SomeTest.java",
            "package f;\n\nimport org.junit.jupiter.api.Test;\n\nclass SomeTest {\n    @Test\n    void some() {}\n}\n",
        )
        file(root, "f/src/test/resources/META-INF/services/org.junit.platform.engine.TestEngine", "f.FakeEngine\n")
    }

    private fun settings() = """
        rootProject.name = "fixture"
        dependencyResolutionManagement {
            repositories { mavenCentral() }
        }
        include(${modules.joinToString(", ") { "\"$it\"" }})
    """.trimIndent() + "\n"

    private fun script(body: String): String {
        val head = body.lineSequence().filter { it.startsWith("plugins {") }.toList()
        val rest = body.lineSequence().filterNot { it.startsWith("plugins {") }.joinToString("\n")
        val extra = head.joinToString("\n") { it.removePrefix("plugins {").removeSuffix("}").trim() }
        val plugins = listOf("`java-library`", extra).filter(String::isNotEmpty).joinToString("\n    ")
        return "plugins {\n    $plugins\n}\n\napply(plugin = \"$PROBE\")\n\n$rest\n"
    }

    private fun file(root: Path, relative: String, content: String) {
        val target = root.resolve(relative)
        Files.createDirectories(target.parent)
        Files.writeString(target, content)
    }
}
