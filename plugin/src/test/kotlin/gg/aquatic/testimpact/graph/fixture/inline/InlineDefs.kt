package gg.aquatic.testimpact.graph.fixture.inline

inline fun twice(block: () -> Int): Int = block() + block()

inline fun makeRunnable(crossinline body: () -> Unit): Runnable = object : Runnable {
    override fun run() {
        body()
    }
}

object InlineObject {
    inline fun thrice(block: () -> Int): Int = block() + block() + block()
}

class InlineHost {
    companion object {
        inline fun once(block: () -> Int): Int = block()
    }
}
