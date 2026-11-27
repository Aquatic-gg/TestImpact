package gg.aquatic.testimpact.graph.internal.kotlin

import gg.aquatic.testimpact.graph.ReachScope
import kotlin.metadata.KmClass
import kotlin.metadata.KmClassifier
import kotlin.metadata.KmPackage
import kotlin.metadata.KmType
import kotlin.metadata.KmTypeAlias
import kotlin.metadata.Visibility
import kotlin.metadata.jvm.JvmMetadataVersion
import kotlin.metadata.jvm.KotlinClassMetadata
import kotlin.metadata.visibility
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MetadataProtoTest {

    private val version = JvmMetadataVersion.LATEST_STABLE_SUPPORTED

    private fun alias(name: String, visibility: Visibility) = KmTypeAlias(name).apply {
        this.visibility = visibility
        underlyingType = KmType().apply { classifier = KmClassifier.Class("kotlin/String") }
        expandedType = KmType().apply { classifier = KmClassifier.Class("kotlin/String") }
    }

    private fun facade(vararg visibilities: Visibility): kotlin.Metadata {
        val pkg = KmPackage()
        visibilities.forEachIndexed { index, visibility -> pkg.typeAliases.add(alias("A$index", visibility)) }
        return KotlinClassMetadata.FileFacade(pkg, version, 0).write()
    }

    private fun scopeOf(metadata: kotlin.Metadata): ReachScope? = MetadataProto.typeAliasScope(metadata.kind, metadata.data1.toList())

    @Test
    fun `a file without typealiases declares none`() {
        assertNull(scopeOf(facade()))
    }

    @Test
    fun `a private typealias is file scoped`() {
        assertEquals(ReachScope.FILE, scopeOf(facade(Visibility.PRIVATE)))
    }

    @Test
    fun `public and internal typealiases reach every class`() {
        assertEquals(ReachScope.ALL, scopeOf(facade(Visibility.PUBLIC)))
        assertEquals(ReachScope.ALL, scopeOf(facade(Visibility.INTERNAL)))
    }

    @Test
    fun `the widest of several typealiases wins`() {
        assertEquals(ReachScope.FILE, scopeOf(facade(Visibility.PRIVATE, Visibility.PRIVATE)))
        assertEquals(ReachScope.ALL, scopeOf(facade(Visibility.PRIVATE, Visibility.PUBLIC, Visibility.PRIVATE)))
    }

    @Test
    fun `a typealias in a class message is found at field 11`() {
        for ((visibility, expected) in listOf(Visibility.PRIVATE to ReachScope.FILE, Visibility.PUBLIC to ReachScope.ALL)) {
            val klass = KmClass().apply {
                name = "p/Holder"
                typeAliases.add(alias("Inner", visibility))
            }
            val metadata = KotlinClassMetadata.Class(klass, version, 0).write()
            assertEquals(1, metadata.kind)
            assertEquals(expected, scopeOf(metadata), "visibility $visibility")
        }
        val none = KotlinClassMetadata.Class(KmClass().apply { name = "p/Plain" }, version, 0).write()
        assertNull(scopeOf(none))
    }

    @Test
    fun `a multifile part carries its aliases at the package field`() {
        val pkg = KmPackage().apply { typeAliases.add(alias("A", Visibility.PRIVATE)) }
        val metadata = KotlinClassMetadata.MultiFileClassPart(pkg, "p/Facade", version, 0).write()
        assertEquals(5, metadata.kind)
        assertEquals(ReachScope.FILE, scopeOf(metadata))
    }

    @Test
    fun `kinds that cannot declare aliases give none and unknown kinds are conservative`() {
        val data1 = facade(Visibility.PRIVATE).data1.toList()
        assertNull(MetadataProto.typeAliasScope(3, data1))
        assertNull(MetadataProto.typeAliasScope(4, emptyList()))
        assertEquals(ReachScope.ALL, MetadataProto.typeAliasScope(99, data1))
    }

    @Test
    fun `garbage and non UTF-8 mode data is conservative`() {
        assertEquals(ReachScope.ALL, MetadataProto.typeAliasScope(2, listOf("abc")))
        assertEquals(ReachScope.ALL, MetadataProto.typeAliasScope(2, emptyList()))
        assertEquals(ReachScope.ALL, MetadataProto.typeAliasScope(2, listOf("")))
        assertEquals(ReachScope.ALL, MetadataProto.typeAliasScope(1, listOf("\u0000Ā")))
    }

    @Test
    fun `protobuf that breaks its own framing is conservative`() {
        fun d1(vararg bytes: Int) = listOf("\u0000" + bytes.map { it.toChar() }.joinToString(""))
        assertEquals(ReachScope.ALL, MetadataProto.typeAliasScope(2, d1(0x00, 0x2A, 0x05, 0xFF)))
        assertEquals(ReachScope.ALL, MetadataProto.typeAliasScope(2, d1(0x00, 0x0B)))
        assertEquals(ReachScope.ALL, MetadataProto.typeAliasScope(2, d1(0x00, 0x2A, 0x7F, 0x01)))
        assertEquals(ReachScope.ALL, MetadataProto.typeAliasScope(2, d1(0x05)))
        assertEquals(ReachScope.ALL, MetadataProto.typeAliasScope(2, d1(0x00, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x01)))
    }

    @Test
    fun `every truncation of real metadata is decoded without an exception`() {
        val data1 = facade(Visibility.PRIVATE, Visibility.PUBLIC).data1.joinToString("")
        for (cut in 0..data1.length) {
            MetadataProto.typeAliasScope(2, listOf(data1.substring(0, cut)))
        }
    }
}
