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
    private val classLoadingOwners = setOf(
        "java.lang.Class", "java.lang.ClassLoader", "java.net.URLClassLoader", "java.security.SecureClassLoader",
        "java.lang.Module",
    )
    private val loaderMethods = setOf("resources")
    private val loaderPrefixes = listOf("getResource", "getSystemResource", "findResource")
    private val classLoadMethods = setOf("loadClass", "findClass")
    private val kotlinCalls = setOf("call", "callBy")
    private const val KOTLIN_REFLECT = "kotlin.reflect."
    private const val RESOURCE_BUNDLE = "java.util.ResourceBundle"
    private val stringBuilders = setOf("java.lang.StringBuilder", "java.lang.StringBuffer", "java.lang.invoke.StringConcatFactory")
    private val stringMethods = setOf("format", "formatted", "concat", "join")

    fun classify(owner: String, name: String): Int {
        var flags = 0
        if (isDynamic(owner, name)) flags = flags or DYNAMIC
        if (isLoader(owner, name)) flags = flags or LOADER
        if (isBuilder(owner, name)) flags = flags or BUILDER
        return flags
    }

    /**
     * Javac names the static receiver type as owner, so `URLClassLoader.loadClass` is not `ClassLoader.loadClass`.
     * Name-only matching is therefore limited to the known JDK class and class loader types and to any owner whose
     * simple name ends with `ClassLoader`; a common name such as `call` or `newInstance` on other owners never counts.
     */
    private fun isClassLoading(owner: String): Boolean =
        owner in classLoadingOwners || owner.substringAfterLast('.').substringAfterLast('$').endsWith("ClassLoader")

    private fun isLoader(owner: String, name: String): Boolean = when {
        owner == RESOURCE_BUNDLE -> name == "getBundle"
        isClassLoading(owner) -> name in loaderMethods || loaderPrefixes.any(name::startsWith)
        else -> false
    }

    private fun isDynamic(owner: String, name: String): Boolean = when {
        isClassLoading(owner) -> name in classReflection || name in classLoadMethods
        owner == "java.util.ServiceLoader" || owner == "java.lang.invoke.MethodHandles\$Lookup" -> true
        owner == REFLECT_ARRAY -> false
        owner.startsWith(KOTLIN_REFLECT) && name in kotlinCalls -> true
        else -> dynamicPrefixes.any(owner::startsWith)
    }

    private fun isBuilder(owner: String, name: String): Boolean = when (owner) {
        "java.lang.String" -> name in stringMethods
        "kotlin.jvm.internal.Intrinsics" -> name == "stringPlus"
        else -> owner in stringBuilders
    }
}
