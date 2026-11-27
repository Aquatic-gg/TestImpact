package gg.aquatic.testimpact.select.gradle.internal

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.io.TempDir

class ServiceFilesTest {

    @TempDir
    lateinit var dir: Path

    private val service = "org.example.Spi"

    private fun serviceDir(name: String, content: ByteArray): Path {
        val root = Files.createDirectories(dir.resolve(name))
        Files.createDirectories(root.resolve("META-INF/services"))
        Files.write(root.resolve("META-INF/services/$service"), content)
        return root
    }

    private fun jar(name: String, content: ByteArray): Path {
        val file = dir.resolve(name)
        ZipOutputStream(Files.newOutputStream(file)).use {
            it.putNextEntry(ZipEntry("META-INF/services/$service"))
            it.write(content)
            it.closeEntry()
        }
        return file
    }

    @Test
    fun `reads names from a directory`() {
        val root = serviceDir("a", "b.Two\na.One\n".toByteArray())
        assertEquals(listOf("a.One", "b.Two"), ServiceFiles.read(listOf(root), service))
    }

    @Test
    fun `reads names from a jar`() {
        assertEquals(listOf("a.One"), ServiceFiles.read(listOf(jar("a.jar", "a.One".toByteArray())), service))
    }

    @Test
    fun `comments blank lines crlf and a byte order mark are handled`() {
        val text = "﻿# header\r\n\r\na.One # trailing\r\n  b.Two  \r\n#c.Three\r\n"
        val root = serviceDir("a", text.toByteArray())
        assertEquals(listOf("a.One", "b.Two"), ServiceFiles.read(listOf(root), service))
    }

    @Test
    fun `names of several roots merge without duplicates`() {
        val first = serviceDir("a", "x.Same\nx.A".toByteArray())
        val second = jar("b.jar", "x.Same\nx.B".toByteArray())
        assertEquals(listOf("x.A", "x.B", "x.Same"), ServiceFiles.read(listOf(first, second), service))
    }

    @Test
    fun `missing roots and missing files contribute nothing`() {
        val empty = Files.createDirectories(dir.resolve("empty"))
        assertEquals(emptyList(), ServiceFiles.read(listOf(dir.resolve("absent"), empty), service))
    }

    @Test
    fun `a file over the size cap throws`() {
        val root = serviceDir("a", ByteArray(ServiceFiles.MAX_FILE_BYTES + 1) { 'a'.code.toByte() })
        assertFailsWith<IOException> { ServiceFiles.read(listOf(root), service) }
    }

    @Test
    fun `a file exactly at the size cap is read`() {
        val root = serviceDir("a", ByteArray(ServiceFiles.MAX_FILE_BYTES) { 'a'.code.toByte() })
        assertEquals(1, ServiceFiles.read(listOf(root), service).size)
    }

    @Test
    fun `more names than the cap throws`() {
        val text = (0..ServiceFiles.MAX_NAMES).joinToString("\n") { "p.C$it" }
        val root = serviceDir("a", text.toByteArray())
        assertFailsWith<IOException> { ServiceFiles.read(listOf(root), service) }
    }

    @Test
    fun `a corrupt jar throws`() {
        val broken = dir.resolve("broken.jar")
        Files.write(broken, ByteArray(64) { 7 })
        assertFailsWith<IOException> { ServiceFiles.read(listOf(broken), service) }
    }
}
