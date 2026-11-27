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
                testTask.configure { shouldRunAfter(tasks.named("test")) }
            }
        }
    }
}

gradlePlugin.testSourceSets.add(sourceSets["functionalTest"])

tasks.test {
    useJUnitPlatform { excludeTags("benchmark") }
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
