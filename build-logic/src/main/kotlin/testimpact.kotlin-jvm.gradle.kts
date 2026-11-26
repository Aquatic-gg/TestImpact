import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("org.jetbrains.kotlin.jvm")
}

/** Oldest JDK the plugin supports. */
val oldestSupportedJdk = 17

/** Kotlin embedded in the oldest supported Gradle (9.0); its stdlib wins at runtime. */
val embeddedKotlin = KotlinVersion.KOTLIN_2_2

kotlin {
    jvmToolchain(oldestSupportedJdk)
    explicitApi()
    compilerOptions {
        apiVersion = embeddedKotlin
        languageVersion = embeddedKotlin
        allWarningsAsErrors = true
    }
}
