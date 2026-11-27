plugins {
    id("testimpact.kotlin-jvm")
    `java-gradle-plugin`
}

gradlePlugin {
    plugins {
        register("testImpact") {
            id = "gg.aquatic.testimpact"
            implementationClass = "gg.aquatic.testimpact.gradle.TestImpactPlugin"
            displayName = "TestImpact"
            description = "Runs only the tests a change can reach, with a full-run safety net."
        }
    }
}

val pluginVersionProperties = tasks.register<WriteProperties>("pluginVersionProperties") {
    destinationFile = layout.buildDirectory.file("generated/plugin-version/gg/aquatic/testimpact/plugin.properties")
    property("version", project.version.toString())
}

sourceSets.main {
    resources.srcDir(files(layout.buildDirectory.dir("generated/plugin-version")).builtBy(pluginVersionProperties))
}

val testJavaVersion: Int? = providers.gradleProperty("testimpact.testJava").orNull?.let { raw ->
    raw.trim().toIntOrNull()?.takeIf { it >= 17 }
        ?: throw GradleException("testimpact.testJava must be an integer JDK version of 17 or newer, got '$raw'")
}

testing {
    suites {
        named<JvmTestSuite>("test") {
            useJUnitJupiter(libs.versions.junit)
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlin.metadata.jvm)
            }
        }
        register<JvmTestSuite>("functionalTest") {
            useJUnitJupiter(libs.versions.junit)
            dependencies {
                implementation(project())
                implementation(gradleTestKit())
                implementation(libs.kotlin.test.junit5)
            }
            targets.configureEach {
                testTask.configure {
                    shouldRunAfter(tasks.named("test"))
                    systemProperty("testimpact.kotlinVersion", libs.versions.kotlin.get())
                    systemProperty(
                        "testimpact.gradleVersions",
                        listOf(libs.versions.gradle.oldest, libs.versions.gradle.previous, libs.versions.gradle.newest)
                            .joinToString(",") { it.get() }
                    )
                    testJavaVersion?.let { systemProperty("testimpact.testJava", it) }
                }
            }
        }
    }
}

gradlePlugin.testSourceSets.add(sourceSets["functionalTest"])

kotlin.target.compilations.named("functionalTest") {
    associateWith(kotlin.target.compilations.getByName("main"))
}

class ReadmeArgumentProvider(@get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE) val readme: RegularFile) :
    CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> = listOf("-Dtestimpact.readme=${readme.asFile.absolutePath}")
}

testJavaVersion?.let { jdk ->
    tasks.withType<Test>().configureEach {
        javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(jdk) }
    }
}

tasks.test {
    useJUnitPlatform { excludeTags("benchmark") }
    jvmArgumentProviders.add(ReadmeArgumentProvider(layout.settingsDirectory.file("README.md")))
}

val benchmarkTest = tasks.register<Test>("benchmarkTest") {
    description = "Runs the timing benchmarks excluded from test."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("benchmark") }
    shouldRunAfter(tasks.test)
}

tasks.named("check") {
    dependsOn(testing.suites.named("functionalTest"), benchmarkTest)
}

tasks.test {
    finalizedBy(testing.suites.named("functionalTest"))
}

tasks.validatePlugins {
    enableStricterValidation = true
    failOnWarning = true
}
