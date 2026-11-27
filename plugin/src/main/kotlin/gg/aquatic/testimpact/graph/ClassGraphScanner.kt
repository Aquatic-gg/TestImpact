package gg.aquatic.testimpact.graph

import gg.aquatic.testimpact.graph.internal.classfile.ClassFileReader
import gg.aquatic.testimpact.graph.internal.classfile.MalformedClassException
import java.io.IOException
import java.io.InputStream
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
 * Reads class directories and jars into a [DependencyGraph]. Class files and archives are untrusted: every
 * failure of a single class or archive becomes a [ClassProblem] and the result is [ClassGraphResult.Unknown],
 * never a partial graph. A root that is a link is resolved, a link inside a walked directory is not followed and
 * becomes a problem when it names a class or a directory; this covers symbolic links and other reparse points such
 * as Windows junctions, whose target is never walked. Entries are processed in sorted order, multi-release
 * entries and `module-info.class` are ignored, and the result does not depend on the order of the roots.
 */
internal object ClassGraphScanner {
    const val DEFAULT_MAX_CLASS_BYTES: Int = 64 * 1024 * 1024

    private const val CLASS_SUFFIX = ".class"
    private const val MODULE_INFO = "module-info.class"
    private const val VERSIONS_PREFIX = "META-INF/versions/"

    /** A root that does not exist contributes nothing; a class larger than [maxClassBytes] is a problem. */
    fun scan(roots: List<Path>, maxClassBytes: Int = DEFAULT_MAX_CLASS_BYTES): ClassGraphResult {
        val facts = ArrayList<ClassFacts>()
        val problems = ArrayList<ClassProblem>()
        for (root in roots) {
            when {
                Files.notExists(root) -> Unit
                Files.isDirectory(root) -> scanDirectory(root, maxClassBytes, facts, problems)
                else -> scanJar(root, maxClassBytes, facts, problems)
            }
        }
        if (problems.isNotEmpty()) {
            return ClassGraphResult.Unknown(problems.sortedWith(compareBy({ it.location }, { it.message })))
        }
        return ClassGraphResult.Known(DependencyGraph.of(facts))
    }

    private fun scanDirectory(root: Path, cap: Int, facts: MutableList<ClassFacts>, problems: MutableList<ClassProblem>) {
        val files = ArrayList<Pair<Path, String>>()
        try {
            val real = root.toRealPath()
            val linkProblem = "link inside a class directory is not followed"
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
                    val name = relative(real, file)
                    when {
                        attrs.isSymbolicLink || attrs.isOther ->
                            if (isClassEntry(name) || Files.isDirectory(file)) problems.add(ClassProblem(file.toString(), linkProblem))
                        attrs.isRegularFile && isClassEntry(name) -> files.add(file to name)
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
        for ((path, _) in files.sortedBy { it.second }) {
            readClass(path.toString(), cap, facts, problems) { Files.newInputStream(path) }
        }
    }

    private fun scanJar(root: Path, cap: Int, facts: MutableList<ClassFacts>, problems: MutableList<ClassProblem>) {
        try {
            ZipFile(root.toFile()).use { zip ->
                val entries = zip.entries().asSequence()
                    .filter { !it.isDirectory && isClassEntry(it.name) }
                    .sortedBy { it.name }
                    .toList()
                for (entry in entries) {
                    readClass("$root!/${entry.name}", cap, facts, problems) { zip.getInputStream(entry) }
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

    private fun readClass(
        location: String,
        cap: Int,
        facts: MutableList<ClassFacts>,
        problems: MutableList<ClassProblem>,
        open: () -> InputStream,
    ) {
        try {
            val bytes = open().use { it.readNBytes(cap + 1) }
            if (bytes.size > cap) {
                problems.add(ClassProblem(location, "class file larger than $cap bytes"))
                return
            }
            facts.add(ClassFileReader.read(bytes))
        } catch (e: MalformedClassException) {
            problems.add(ClassProblem(location, "malformed class: ${describe(e)}"))
        } catch (e: IOException) {
            problems.add(ClassProblem(location, "cannot read: ${describe(e)}"))
        } catch (e: RuntimeException) {
            problems.add(ClassProblem(location, "unexpected failure: ${describe(e)}"))
        }
    }

    private fun isClassEntry(path: String): Boolean =
        path.endsWith(CLASS_SUFFIX) && path != MODULE_INFO && !path.startsWith(VERSIONS_PREFIX)

    private fun relative(root: Path, file: Path): String = root.relativize(file).joinToString("/") { it.toString() }

    private fun describe(e: Exception): String = e.message ?: e.javaClass.simpleName
}
