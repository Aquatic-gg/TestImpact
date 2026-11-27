package gg.aquatic.testimpact.gradle.internal

import gg.aquatic.testimpact.gradle.SelectedEntry
import gg.aquatic.testimpact.gradle.TaskOutcome
import gg.aquatic.testimpact.gradle.TaskSelection
import gg.aquatic.testimpact.select.SelectReason
import java.nio.file.Files
import java.nio.file.Path

/**
 * Stores a [TaskSelection] as text: a version header, one `key<TAB>value` line per field, list items on lines of
 * their own and a closing `end` line that shows the file was written completely. Tabs, line breaks and backslashes
 * inside values are escaped, so a hostile class or file name cannot forge a field or a line.
 */
internal object TaskSelectionCodec {
    private const val HEADER = "testimpact-selection"
    private const val END = "end"
    private const val ABSENT = "-"
    private const val PRESENT = "+"

    fun write(selection: TaskSelection): String {
        val out = StringBuilder()
        line(out, HEADER, selection.formatVersion.toString())
        line(out, "taskPath", selection.taskPath)
        line(out, "mode", selection.mode.name)
        line(out, "outcome", selection.outcome.name)
        line(out, "analysisMillis", selection.analysisMillis.toString())
        optional(out, "candidateCount", selection.candidateCount?.toString())
        optional(out, "fullRule", selection.fullRule)
        optional(out, "fullDetail", selection.fullDetail)
        val changed = selection.changedFiles
        if (changed == null) {
            line(out, "changedFiles", ABSENT)
        } else {
            line(out, "changedFiles", PRESENT + changed.size)
            changed.forEach { line(out, "file", it) }
        }
        line(out, "selected", selection.selected.size.toString())
        for (entry in selection.selected) {
            val fields = ArrayList<String>()
            fields.add(entry.className)
            fields.add(entry.reason.name)
            fields.add(entry.recognised.toString())
            fields.add(entry.origin)
            fields.add(entry.start)
            fields.add(entry.edges.size.toString())
            fields.addAll(entry.edges)
            fields.add(entry.seedKinds.size.toString())
            fields.addAll(entry.seedKinds)
            line(out, "entry", *fields.toTypedArray())
        }
        line(out, END)
        return out.toString()
    }

    /** @throws IllegalArgumentException when the text is malformed, truncated or of an unknown version */
    fun read(text: String): TaskSelection {
        val reader = Reader(text)
        val header = reader.next(HEADER, 1)
        val version = header[0].toIntOrNull()
        require(version == TaskSelection.FORMAT_VERSION) { "unsupported selection format version '${header[0]}'" }
        val taskPath = reader.next("taskPath", 1)[0]
        val mode = enumOf<TestImpactMode>(reader.next("mode", 1)[0])
        val outcome = enumOf<TaskOutcome>(reader.next("outcome", 1)[0])
        val millis = reader.next("analysisMillis", 1)[0].toLongOrNull() ?: reader.fail("analysisMillis is not a number")
        val candidates = reader.optional("candidateCount")?.let {
            it.toIntOrNull() ?: reader.fail("candidateCount is not a number")
        }
        val rule = reader.optional("fullRule")
        val detail = reader.optional("fullDetail")
        val changedCount = reader.next("changedFiles", 1)[0]
        val changed = when {
            changedCount == ABSENT -> null
            changedCount.startsWith(PRESENT) -> List(reader.count(changedCount.drop(1))) { reader.next("file", 1)[0] }
            else -> reader.fail("changedFiles is neither '-' nor a count")
        }
        val entries = List(reader.count(reader.next("selected", 1)[0])) { readEntry(reader) }
        reader.next(END, 0)
        reader.finish()
        return TaskSelection(taskPath, mode, outcome, changed, candidates, entries, rule, detail, millis)
    }

    /** @throws java.io.IOException when the file cannot be read or is not valid UTF-8 */
    fun read(file: Path): TaskSelection = read(Files.readString(file))

    private fun readEntry(reader: Reader): SelectedEntry {
        val fields = reader.next("entry", -1)
        val cursor = FieldCursor(fields, reader)
        val className = cursor.take()
        val reason = enumOf<SelectReason>(cursor.take())
        val recognised = when (val flag = cursor.take()) {
            "true" -> true
            "false" -> false
            else -> reader.fail("recognised is '$flag'")
        }
        val origin = cursor.take()
        val start = cursor.take()
        val edges = List(reader.count(cursor.take(), cursor.left())) { cursor.take() }
        val kinds = List(reader.count(cursor.take(), cursor.left())) { cursor.take() }
        cursor.done()
        return SelectedEntry(className, reason, recognised, origin, start, edges, kinds)
    }

    private inline fun <reified E : Enum<E>> enumOf(name: String): E =
        enumValues<E>().firstOrNull { it.name == name }
            ?: throw IllegalArgumentException("unknown ${E::class.java.simpleName} '$name'")

    private fun line(out: StringBuilder, key: String, vararg values: String) {
        out.append(key)
        for (value in values) out.append('\t').append(escape(value))
        out.append('\n')
    }

    private fun optional(out: StringBuilder, key: String, value: String?) {
        if (value == null) line(out, key, ABSENT) else line(out, key, PRESENT + value)
    }

    private fun escape(value: String): String {
        if (value.none { it == '\\' || it == '\t' || it == '\n' || it == '\r' }) return value
        val out = StringBuilder(value.length + 8)
        for (c in value) {
            when (c) {
                '\\' -> out.append("\\\\")
                '\t' -> out.append("\\t")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                else -> out.append(c)
            }
        }
        return out.toString()
    }

    private fun unescape(value: String, reader: Reader): String {
        if ('\\' !in value) return value
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c != '\\') {
                out.append(c)
                i++
                continue
            }
            if (i + 1 >= value.length) reader.fail("dangling escape")
            when (val next = value[i + 1]) {
                '\\' -> out.append('\\')
                't' -> out.append('\t')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                else -> reader.fail("unknown escape '\\$next'")
            }
            i += 2
        }
        return out.toString()
    }

    private class FieldCursor(private val fields: List<String>, private val reader: Reader) {
        private var index = 0

        fun take(): String {
            if (index >= fields.size) reader.fail("entry line is too short")
            return fields[index++]
        }

        fun left(): Int = fields.size - index

        fun done() {
            if (index != fields.size) reader.fail("entry line has trailing fields")
        }
    }

    private class Reader(text: String) {
        private val lines: List<String> = text.split('\n').let { if (it.lastOrNull() == "") it.dropLast(1) else it }
        private var position = 0

        fun fail(message: String): Nothing =
            throw IllegalArgumentException("malformed selection file at line ${position.coerceAtLeast(1)}: $message")

        /** The unescaped values after [key]; [expected] of them, or any number when negative. */
        fun next(key: String, expected: Int): List<String> {
            if (position >= lines.size) fail("expected '$key' but the file ends")
            val parts = lines[position++].split('\t')
            if (parts[0] != key) fail("expected '$key' but found '${parts[0].take(40)}'")
            val values = parts.drop(1).map { unescape(it, this) }
            if (expected >= 0 && values.size != expected) fail("'$key' has ${values.size} values, expected $expected")
            return values
        }

        fun optional(key: String): String? {
            val value = next(key, 1)[0]
            return when {
                value == ABSENT -> null
                value.startsWith(PRESENT) -> value.drop(1)
                else -> fail("'$key' is neither '-' nor a '+' value")
            }
        }

        /** A list length no larger than [limit], the number of items that can still follow. */
        fun count(raw: String, limit: Int = lines.size - position): Int {
            val n = raw.toIntOrNull()
            if (n == null || n < 0) fail("'${raw.take(20)}' is not a valid count")
            if (n > limit) fail("count $n exceeds the $limit items left")
            return n
        }

        fun finish() {
            if (position != lines.size) fail("data after the end line")
        }
    }
}
