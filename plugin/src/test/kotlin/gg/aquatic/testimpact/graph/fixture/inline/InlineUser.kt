package gg.aquatic.testimpact.graph.fixture.inline

class InlineUser {
    fun fromFunction() = twice { 1 }

    fun fromObject() = InlineObject.thrice { 2 }

    fun fromCompanion() = InlineHost.once { 3 }

    fun regenerated(): Runnable = makeRunnable { fromFunction() }
}
