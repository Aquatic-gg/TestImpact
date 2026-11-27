package gg.aquatic.testimpact.seed.internal

/**
 * Classifies a called method by owner and name into the seed flags. Owners are binary names
 * (`java.lang.invoke.MethodHandles$Lookup`). Lambdas, string templates and records call through bootstrap
 * methods that are deliberately absent, so they never count as dynamic.
 */
internal object SeedApis {
    const val DYNAMIC = 1
    const val LOADER = 2
    const val BUILDER = 4

    private val classReflection = setOf(
        "forName", "getMethod", "getMethods", "getDeclaredMethod", "getDeclaredMethods",
        "getField", "getFields", "getDeclaredField", "getDeclaredFields",
        "getConstructor", "getConstructors", "getDeclaredConstructor", "getDeclaredConstructors",
        "newInstance",
    )
    private val dynamicPrefixes = listOf("java.lang.reflect.", "kotlin.reflect.full.", "kotlin.reflect.jvm.")
    private const val REFLECT_ARRAY = "java.lang.reflect.Array"
    private val resourceOwners = setOf("java.lang.Class", "java.lang.ClassLoader", "java.lang.Module")
    private val stringBuilders = setOf("java.lang.StringBuilder", "java.lang.StringBuffer", "java.lang.invoke.StringConcatFactory")
    private val stringMethods = setOf("format", "formatted", "concat", "join")

    fun classify(owner: String, name: String): Int {
        var flags = 0
        if (isDynamic(owner, name)) flags = flags or DYNAMIC
        if (owner in resourceOwners && (name.startsWith("getResource") || name.startsWith("getSystemResource"))) {
            flags = flags or LOADER
        }
        if (isBuilder(owner, name)) flags = flags or BUILDER
        return flags
    }

    private fun isDynamic(owner: String, name: String): Boolean = when (owner) {
        "java.lang.Class" -> name in classReflection
        "java.lang.ClassLoader" -> name == "loadClass"
        "java.util.ServiceLoader", "java.lang.invoke.MethodHandles\$Lookup" -> true
        REFLECT_ARRAY -> false
        else -> dynamicPrefixes.any(owner::startsWith)
    }

    private fun isBuilder(owner: String, name: String): Boolean = when (owner) {
        "java.lang.String" -> name in stringMethods
        "kotlin.jvm.internal.Intrinsics" -> name == "stringPlus"
        else -> owner in stringBuilders
    }
}
