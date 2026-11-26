package gg.aquatic.testimpact.graph.fixture.owner

object OwnerObject {
    fun id() = 1
}

class WithJvmStatic {
    companion object {
        @JvmStatic
        fun make(): WithJvmStatic = WithJvmStatic()
    }
}

class AnonHost {
    fun make(): Runnable = object : Runnable {
        override fun run() {}
    }
}

class SuspendHost {
    fun go(): suspend () -> Int = { 1 }
}

fun lambdaHost(): suspend () -> Int = { 2 }

enum class Color { RED, GREEN }
