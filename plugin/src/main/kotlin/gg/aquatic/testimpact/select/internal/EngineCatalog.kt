package gg.aquatic.testimpact.select.internal

/**
 * The JUnit Platform engines whose test discovery follows class references, and the extension points that act on
 * every test of a run. An engine that is not listed may discover tests by other means, so a task using it is unknown.
 */
internal object EngineCatalog {
    private const val JUPITER = "org.junit.jupiter.engine.JupiterTestEngine"
    private const val VINTAGE = "org.junit.vintage.engine.VintageTestEngine"
    private const val KOTEST_PREFIX = "io.kotest."

    /** Service interfaces whose registered implementations apply to every test class of the task. */
    val globalServices: List<String> = listOf(
        "org.junit.jupiter.api.extension.Extension",
        "org.junit.platform.launcher.LauncherSessionListener",
        "org.junit.platform.launcher.LauncherDiscoveryListener",
        "org.junit.platform.launcher.TestExecutionListener",
        "org.junit.platform.launcher.PostDiscoveryFilter",
        "org.junit.platform.launcher.LauncherInterceptor",
    )

    val kotestGlobalSupertypes: Set<String> = setOf(
        "io.kotest.core.config.AbstractProjectConfig",
        "io.kotest.core.config.AbstractPackageConfig",
    )
    private const val KOTEST_AUTO_SCAN = "io.kotest.core.annotation.AutoScan"

    fun isSupportedEngine(engine: String): Boolean =
        engine == JUPITER || engine == VINTAGE || engine.startsWith(KOTEST_PREFIX)

    fun isKotestGlobalReference(name: String): Boolean = name == KOTEST_AUTO_SCAN
}
