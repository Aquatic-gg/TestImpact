package gg.aquatic.testimpact.seed.internal

import kotlin.test.Test
import kotlin.test.assertEquals

class SeedApisTest {

    private val classMethods = listOf(
        "forName", "getMethod", "getMethods", "getDeclaredMethod", "getDeclaredMethods",
        "getField", "getFields", "getDeclaredField", "getDeclaredFields",
        "getConstructor", "getConstructors", "getDeclaredConstructor", "getDeclaredConstructors", "newInstance",
    )

    @Test
    fun `every reflective Class method is dynamic`() {
        for (name in classMethods) {
            assertEquals(SeedApis.DYNAMIC, SeedApis.classify("java.lang.Class", name), name)
        }
    }

    @Test
    fun `other Class methods are not dynamic`() {
        assertEquals(0, SeedApis.classify("java.lang.Class", "getName"))
        assertEquals(0, SeedApis.classify("java.lang.Class", "isInstance"))
    }

    @Test
    fun `loadClass is dynamic only on ClassLoader`() {
        assertEquals(SeedApis.DYNAMIC, SeedApis.classify("java.lang.ClassLoader", "loadClass"))
        assertEquals(0, SeedApis.classify("java.lang.ClassLoader", "getParent"))
    }

    @Test
    fun `service loading and method handle lookups are dynamic for any member`() {
        assertEquals(SeedApis.DYNAMIC, SeedApis.classify("java.util.ServiceLoader", "load"))
        assertEquals(SeedApis.DYNAMIC, SeedApis.classify("java.util.ServiceLoader", "iterator"))
        assertEquals(SeedApis.DYNAMIC, SeedApis.classify("java.lang.invoke.MethodHandles\$Lookup", "findVirtual"))
    }

    @Test
    fun `reflection packages are dynamic except java lang reflect Array`() {
        assertEquals(SeedApis.DYNAMIC, SeedApis.classify("java.lang.reflect.Method", "invoke"))
        assertEquals(SeedApis.DYNAMIC, SeedApis.classify("java.lang.reflect.Proxy", "newProxyInstance"))
        assertEquals(SeedApis.DYNAMIC, SeedApis.classify("kotlin.reflect.full.KClasses", "getMemberProperties"))
        assertEquals(SeedApis.DYNAMIC, SeedApis.classify("kotlin.reflect.jvm.ReflectJvmMapping", "getJavaMethod"))
        assertEquals(0, SeedApis.classify("java.lang.reflect.Array", "newInstance"))
        assertEquals(0, SeedApis.classify("kotlin.reflect.KClass", "getSimpleName"))
    }

    @Test
    fun `bootstrap owners of lambdas, templates and records are not dynamic`() {
        assertEquals(0, SeedApis.classify("java.lang.invoke.LambdaMetafactory", "metafactory"))
        assertEquals(0, SeedApis.classify("java.lang.runtime.ObjectMethods", "bootstrap"))
        assertEquals(SeedApis.BUILDER, SeedApis.classify("java.lang.invoke.StringConcatFactory", "makeConcatWithConstants"))
    }

    @Test
    fun `resource lookups on Class, ClassLoader and Module are loaders`() {
        for (owner in listOf("java.lang.Class", "java.lang.ClassLoader", "java.lang.Module")) {
            assertEquals(SeedApis.LOADER, SeedApis.classify(owner, "getResource"), owner)
            assertEquals(SeedApis.LOADER, SeedApis.classify(owner, "getResourceAsStream"), owner)
        }
        assertEquals(SeedApis.LOADER, SeedApis.classify("java.lang.ClassLoader", "getResources"))
        assertEquals(SeedApis.LOADER, SeedApis.classify("java.lang.ClassLoader", "getSystemResource"))
        assertEquals(SeedApis.LOADER, SeedApis.classify("java.lang.ClassLoader", "getSystemResourceAsStream"))
        assertEquals(0, SeedApis.classify("java.io.File", "getResource"))
    }

    @Test
    fun `string builders are recognised`() {
        assertEquals(SeedApis.BUILDER, SeedApis.classify("java.lang.StringBuilder", "append"))
        assertEquals(SeedApis.BUILDER, SeedApis.classify("java.lang.StringBuffer", "append"))
        for (name in listOf("format", "formatted", "concat", "join")) {
            assertEquals(SeedApis.BUILDER, SeedApis.classify("java.lang.String", name), name)
        }
        assertEquals(SeedApis.BUILDER, SeedApis.classify("kotlin.jvm.internal.Intrinsics", "stringPlus"))
        assertEquals(0, SeedApis.classify("java.lang.String", "length"))
        assertEquals(0, SeedApis.classify("kotlin.jvm.internal.Intrinsics", "checkNotNull"))
    }
}
