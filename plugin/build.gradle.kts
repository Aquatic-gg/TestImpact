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

testing {
    suites {
        named<JvmTestSuite>("test") {
            useJUnitJupiter(libs.versions.junit)
            dependencies {
                implementation(libs.kotlin.test)
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

tasks.named("check") {
    dependsOn(testing.suites.named("functionalTest"))
}

tasks.validatePlugins {
    enableStricterValidation = true
    failOnWarning = true
}
