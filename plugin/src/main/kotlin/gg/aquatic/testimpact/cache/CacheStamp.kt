package gg.aquatic.testimpact.cache

import java.util.Properties

/** What a cache file must have been written by to be trusted: the layout of the file and the plugin that parsed. */
internal data class CacheStamp(val formatVersion: Int, val pluginVersion: String) {
    companion object {
        /** Bump whenever `ClassFacts`, `ConstantField` or the output of the class file reader changes. */
        const val FORMAT_VERSION: Int = 1

        private const val RESOURCE = "gg/aquatic/testimpact/plugin.properties"
        private const val UNKNOWN = "unknown"

        val current: CacheStamp by lazy { CacheStamp(FORMAT_VERSION, readPluginVersion()) }

        private fun readPluginVersion(): String {
            val stream = CacheStamp::class.java.classLoader.getResourceAsStream(RESOURCE) ?: return UNKNOWN
            return stream.use { input ->
                val properties = Properties().apply { load(input) }
                properties.getProperty("version")?.takeIf { it.isNotBlank() } ?: UNKNOWN
            }
        }
    }
}
