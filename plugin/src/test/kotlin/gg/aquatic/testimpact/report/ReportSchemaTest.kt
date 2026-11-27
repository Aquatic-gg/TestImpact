package gg.aquatic.testimpact.report

import gg.aquatic.testimpact.change.ChangeDetection
import gg.aquatic.testimpact.change.UnknownReason
import gg.aquatic.testimpact.fallback.FallbackRules
import gg.aquatic.testimpact.fallback.FullRunRule
import gg.aquatic.testimpact.select.Selection
import gg.aquatic.testimpact.select.SelectionUnknownReason
import groovy.json.JsonSlurper
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReportSchemaTest {

    private data class Observed(val types: MutableMap<String, String> = sortedMapOf(), val nullable: MutableSet<String> = sortedSetOf())

    private fun populated(): ImpactReport {
        val selections = listOf(
            ReportFixtures.known(":c:test", listOf(ReportFixtures.reachedTest(), ReportFixtures.globalTest())),
            Selection.Unknown(":f:test", SelectionUnknownReason.UNKNOWN_ENGINE, "f.FakeEngine"),
        )
        return ReportFixtures.report(selections, detectionMs = 3, selectionMs = mapOf(":c:test" to 4L))
    }

    private fun forced(): ImpactReport = ReportFixtures.report(
        listOf(Selection.Unknown(":c:test", SelectionUnknownReason.ANALYSIS_ERROR, "x")),
        changeRule = FallbackRules.Verdict(FullRunRule.RESOURCE_CHANGE, "resource change: x.properties", true),
    )

    private fun unknown(): ImpactReport = ReportFixtures.report(
        listOf(Selection.Unknown(":test", SelectionUnknownReason.ANALYSIS_ERROR, "no repository")),
        ChangeDetection.Unknown(UnknownReason.NOT_A_REPOSITORY, "no repository"),
        detectionMs = 1,
        project = ":",
    )

    private fun observe(value: Any?, path: String, into: Observed) {
        when (value) {
            null -> into.nullable.add(path)
            is Map<*, *> -> {
                if (path.isNotEmpty() && !path.endsWith("[]")) into.types[path] = "object"
                for ((key, child) in value) observe(child, if (path.isEmpty()) "$key" else "$path.$key", into)
            }

            is List<*> -> {
                into.types[path] = "array"
                for (child in value) observe(child, "$path[]", into)
            }

            is String -> into.types[path] = "string"
            is Number -> into.types[path] = "number"
            is Boolean -> into.types[path] = "boolean"
            else -> error("unexpected JSON value ${value.javaClass} at $path")
        }
    }

    private fun documented(): Map<String, Pair<String, Boolean>> {
        val readme = Files.readAllLines(readme())
        val start = readme.indexOfFirst { it.trim() == "```text json-schema-v1" }
        assertTrue(start >= 0, "README has no json-schema-v1 block")
        val fields = LinkedHashMap<String, Pair<String, Boolean>>()
        for (line in readme.drop(start + 1).takeWhile { it.trim() != "```" }) {
            val parts = line.split(" | ")
            assertTrue(parts.size == 3, "malformed schema line: $line")
            val type = parts[1].trim()
            assertTrue(fields.put(parts[0].trim(), type.removeSuffix("?") to type.endsWith("?")) == null, "duplicate: $line")
        }
        return fields
    }

    private fun readme(): Path = Path.of(requireNotNull(System.getProperty("testimpact.readme")) { "testimpact.readme is not set" })

    @Test
    fun `the readme field table equals the fields of the json`() {
        val observed = Observed()
        for (report in listOf(populated(), forced(), unknown())) {
            observe(JsonSlurper().parseText(ReportJson.render(report)), "", observed)
        }
        val documented = documented()

        assertEquals(documented.keys.toSet(), observed.types.keys + observed.nullable)
        for ((path, type) in observed.types) {
            assertEquals(type, documented.getValue(path).first, "type of $path")
        }
        for ((path, entry) in documented) {
            assertEquals(path in observed.nullable, entry.second, "nullable marker of $path")
        }
    }

    @Test
    fun `the schema version is one in the code and in the json`() {
        val json = JsonSlurper().parseText(ReportJson.render(populated())) as Map<*, *>

        assertEquals(1, REPORT_SCHEMA_VERSION)
        assertEquals(1, json["schemaVersion"])
        assertEquals("schemaVersion", json.keys.first())
    }
}
