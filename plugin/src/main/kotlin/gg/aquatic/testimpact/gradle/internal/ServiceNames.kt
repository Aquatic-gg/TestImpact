package gg.aquatic.testimpact.gradle.internal

import java.util.UUID

/**
 * Names of the build services the plugin registers. Build services are shared by name across the whole build, and two
 * projects can load the plugin through different class loaders; a service created by one loader cannot be cast by the
 * other. The suffix is random once per loaded copy of this class, so every copy registers and reads its own services.
 */
internal object ServiceNames {
    private val suffix: String = UUID.randomUUID().toString()

    val classGraph: String = "testImpactClassGraph-$suffix"
    val selectionInputs: String = "testImpactSelectionInputs-$suffix"
}
