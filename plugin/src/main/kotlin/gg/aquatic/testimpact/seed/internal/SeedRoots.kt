package gg.aquatic.testimpact.seed.internal

import gg.aquatic.testimpact.graph.ClassProblem
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * Walks a class or resource root, a directory or a jar, in sorted order. The root itself may be a link and is
 * resolved; a link inside a directory is never followed and is a problem when it names a class, a descriptor or a
 * directory (links to other files are not read, so they cannot hide anything the scan relies on). A missing root
 * has no entries. Entries under `META-INF/versions/` are skipped except a versioned `module-info.class`.
 */
internal object SeedRoots {
    private const val VERSIONS_PREFIX = "META-INF/versions/"
    private const val MODULE_INFO = "module-info.class"

    /** [visit] runs inside the walk, so an entry's [SeedEntry.open] is only valid during that call. */
    fun walk(root: Path, problems: MutableList<ClassProblem>, visit: (SeedEntry) -> Unit) {
        when {
            Files.notExists(root) -> Unit
            Files.isDirectory(root) -> walkDirectory(root, problems, visit)
            else -> walkJar(root, problems, visit)
        }
    }

    private fun walkDirectory(root: Path, problems: MutableList<ClassProblem>, visit: (SeedEntry) -> Unit) {
        val files = ArrayList<Pair<Path, String>>()
        try {
            val real = root.toRealPath()
            val linkProblem = "link inside a root is not followed"
            Files.walkFileTree(real, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (dir == real) return FileVisitResult.CONTINUE
                    val own = Files.readAttributes(dir, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                    if (own.isSymbolicLink || own.isOther) {
                        problems.add(ClassProblem(dir.toString(), linkProblem))
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val name = real.relativize(file).joinToString("/") { it.toString() }
                    when {
                        attrs.isSymbolicLink || attrs.isOther ->
                            if (isClassEntry(name) || ResourceTokens.isDescriptor(name) || Files.isDirectory(file)) {
                                problems.add(ClassProblem(file.toString(), linkProblem))
                            }
                        attrs.isRegularFile && included(name) -> files.add(file to name)
                    }
                    return FileVisitResult.CONTINUE
                }
            })
        } catch (e: IOException) {
            problems.add(ClassProblem(root.toString(), "cannot walk directory: ${describe(e)}"))
            return
        } catch (e: UncheckedIOException) {
            problems.add(ClassProblem(root.toString(), "cannot walk directory: ${describe(e)}"))
            return
        }
        for ((path, name) in files.sortedBy { it.second }) {
            visit(SeedEntry(path.toString(), name) { Files.newInputStream(path) })
        }
    }

    private fun walkJar(root: Path, problems: MutableList<ClassProblem>, visit: (SeedEntry) -> Unit) {
        try {
            ZipFile(root.toFile()).use { zip ->
                val entries = zip.entries().asSequence()
                    .filter { !it.isDirectory && included(it.name) }
                    .sortedBy { it.name }
                    .toList()
                for (entry in entries) {
                    visit(SeedEntry("$root!/${entry.name}", entry.name) { zip.getInputStream(entry) })
                }
            }
        } catch (e: ZipException) {
            problems.add(ClassProblem(root.toString(), "corrupt archive: ${describe(e)}"))
        } catch (e: IOException) {
            problems.add(ClassProblem(root.toString(), "cannot read archive: ${describe(e)}"))
        } catch (e: RuntimeException) {
            problems.add(ClassProblem(root.toString(), "cannot read archive: ${describe(e)}"))
        }
    }

    private fun included(name: String): Boolean = !name.startsWith(VERSIONS_PREFIX) || isVersionedModuleInfo(name)

    private fun isClassEntry(name: String): Boolean = name.endsWith(".class") && included(name)

    private fun isVersionedModuleInfo(name: String): Boolean {
        val rest = name.removePrefix(VERSIONS_PREFIX)
        val slash = rest.indexOf('/')
        return slash > 0 && rest.substring(slash + 1) == MODULE_INFO
    }

    private fun describe(e: Exception): String = e.message ?: e.javaClass.simpleName
}
