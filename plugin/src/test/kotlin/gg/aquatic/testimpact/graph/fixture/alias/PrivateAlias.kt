package gg.aquatic.testimpact.graph.fixture.alias

private typealias Secret = Int

class PrivateAliasUser {
    fun value(): Int {
        val secret: Secret = 1
        return secret
    }
}
