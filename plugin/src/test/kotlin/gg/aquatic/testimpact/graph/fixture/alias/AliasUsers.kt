package gg.aquatic.testimpact.graph.fixture.alias

class AliasTarget

class AliasSignatureUser {
    fun names(): Names = emptyList()
}

class AliasBodyUser {
    fun count(): Int {
        val local: Names = listOf("a")
        return local.size
    }
}
