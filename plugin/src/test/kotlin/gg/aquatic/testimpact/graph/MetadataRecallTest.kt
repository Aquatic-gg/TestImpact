package gg.aquatic.testimpact.graph

import kotlin.metadata.KmClass
import kotlin.metadata.KmClassifier
import kotlin.metadata.KmFunction
import kotlin.metadata.KmPackage
import kotlin.metadata.KmType
import kotlin.metadata.KmTypeAlias
import kotlin.metadata.KmTypeParameter
import kotlin.metadata.KmValueParameter
import kotlin.metadata.jvm.KotlinClassMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Kotlin metadata library, as an independent reader, names every type a fixture class declares. */
class MetadataRecallTest {
    private val graph = FixtureClasses.graph

    private fun binaryName(internal: String): String {
        val slash = internal.lastIndexOf('/')
        return internal.substring(0, slash + 1).replace('/', '.') + internal.substring(slash + 1).replace('.', '$')
    }

    private fun types(function: KmFunction, into: MutableList<KmType>) {
        into.add(function.returnType)
        function.receiverParameterType?.let(into::add)
        valueParameters(function.valueParameters, into)
        typeParameters(function.typeParameters, into)
    }

    private fun valueParameters(parameters: List<KmValueParameter>, into: MutableList<KmType>) {
        for (parameter in parameters) {
            into.add(parameter.type)
            parameter.varargElementType?.let(into::add)
        }
    }

    private fun typeParameters(parameters: List<KmTypeParameter>, into: MutableList<KmType>) {
        for (parameter in parameters) into.addAll(parameter.upperBounds)
    }

    private fun declaredTypes(metadata: KotlinClassMetadata): List<KmType> {
        val found = ArrayList<KmType>()
        fun fromPackage(kmPackage: KmPackage) {
            kmPackage.functions.forEach { types(it, found) }
            kmPackage.properties.forEach { property ->
                found.add(property.returnType)
                property.receiverParameterType?.let(found::add)
            }
            kmPackage.typeAliases.forEach { aliasTypes(it, found) }
        }
        fun fromClass(kmClass: KmClass) {
            found.addAll(kmClass.supertypes)
            typeParameters(kmClass.typeParameters, found)
            kmClass.constructors.forEach { valueParameters(it.valueParameters, found) }
            kmClass.functions.forEach { types(it, found) }
            kmClass.properties.forEach { property ->
                found.add(property.returnType)
                property.receiverParameterType?.let(found::add)
            }
            kmClass.typeAliases.forEach { aliasTypes(it, found) }
        }
        when (metadata) {
            is KotlinClassMetadata.Class -> fromClass(metadata.kmClass)
            is KotlinClassMetadata.FileFacade -> fromPackage(metadata.kmPackage)
            is KotlinClassMetadata.MultiFileClassPart -> fromPackage(metadata.kmPackage)
            else -> Unit
        }
        return found
    }

    private fun aliasTypes(alias: KmTypeAlias, into: MutableList<KmType>) {
        into.add(alias.underlyingType)
        into.add(alias.expandedType)
        typeParameters(alias.typeParameters, into)
    }

    private fun classifiers(type: KmType, into: MutableSet<KmClassifier>) {
        into.add(type.classifier)
        type.abbreviatedType?.let { classifiers(it, into) }
        type.outerType?.let { classifiers(it, into) }
        type.flexibleTypeUpperBound?.type?.let { classifiers(it, into) }
        for (argument in type.arguments) argument.type?.let { classifiers(it, into) }
    }

    @Test
    fun `every fixture type a kotlin class declares is a dependency of that class`() {
        val loader = FixtureClasses::class.java.classLoader
        val missing = ArrayList<String>()
        var checked = 0
        for (name in graph.classes) {
            if (graph.facts(name)?.isKotlin != true) continue
            val annotation = Class.forName(name, false, loader).getAnnotation(Metadata::class.java) ?: continue
            val metadata = KotlinClassMetadata.readStrict(annotation)
            val found = LinkedHashSet<KmClassifier>()
            declaredTypes(metadata).forEach { classifiers(it, found) }
            val dependencies = graph.dependencies(name).map { it.to }.toSet()
            for (classifier in found) {
                when (classifier) {
                    is KmClassifier.Class -> {
                        if (classifier.name.startsWith(".")) continue
                        val target = binaryName(classifier.name)
                        if (target == name || target !in graph.classes) continue
                        checked++
                        if (target !in dependencies) missing.add("$name -> $target")
                    }
                    is KmClassifier.TypeAlias -> {
                        if (!classifier.name.startsWith(FixtureClasses.PACKAGE.replace('.', '/'))) continue
                        val aliasPackage = binaryName(classifier.name).substringBeforeLast('.')
                        val definers = graph.classes.filter {
                            it.substringBeforeLast('.') == aliasPackage && graph.facts(it)?.typeAliasScope != null
                        }
                        checked++
                        if (definers.none { it in dependencies }) missing.add("$name -> typealias ${classifier.name}")
                    }
                    is KmClassifier.TypeParameter -> Unit
                }
            }
        }
        assertEquals(emptyList(), missing, "types the Kotlin metadata declares but the graph misses")
        assertTrue(checked > 5, "the oracle checked only $checked fixture types")
    }
}
