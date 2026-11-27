package gg.aquatic.testimpact.escalation.internal

import java.util.UUID

/**
 * Name of the escalation build service. As with the other services of the plugin, projects can load the plugin through
 * different class loaders and a service of one loader cannot be cast by another, so the suffix is random once per
 * loaded copy of this class.
 */
internal object EscalationServiceName {
    val value: String = "testImpactEscalation-${UUID.randomUUID()}"
}
