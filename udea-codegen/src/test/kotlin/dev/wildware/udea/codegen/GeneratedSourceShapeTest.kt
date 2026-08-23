package dev.wildware.udea.codegen

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Scans the generated sources for the mechanisms a generated `Replicator` must never use.
 *
 * `Replicator<T>` exists so that snapshot capture, delta write and the agent's field access are
 * all *direct property access* (spec 3.1): no reflection, so the surface survives R8; no map
 * lookup and no `when` on a runtime type, so capture stays inside its per-tick budget; and no
 * `putSerializable`, which is how the old generator handled every type it did not recognise —
 * a silent fallback that turned an unsupported field into a runtime failure instead of a build
 * failure.
 *
 * A round-trip test cannot catch any of these: reflective code round-trips perfectly and is
 * still wrong. So the ban is asserted on the text.
 */
class GeneratedSourceShapeTest {

    @Test
    fun `there is a Replicator for every fixture component`() {
        assertEquals(
            listOf(
                "dev/wildware/udea/codegen/fixtures/AiBlackboardReplicator.kt",
                "dev/wildware/udea/codegen/fixtures/CombatReplicator.kt",
                "dev/wildware/udea/codegen/fixtures/HealthReplicator.kt",
                "dev/wildware/udea/codegen/fixtures/MovementReplicator.kt",
                "dev/wildware/udea/codegen/fixtures/PlacementReplicator.kt",
                "dev/wildware/udea/codegen/fixtures/QuantisedProbeReplicator.kt",
                // The module-level outputs. There is exactly one aggregating group per module:
                // the `ServiceLoader` index this module contributes, and the protocol constant
                // a packet header carries.
                "dev/wildware/udea/generated/CodegenFixturesNetModule.kt",
                "dev/wildware/udea/generated/CodegenFixturesNetProtocol.kt",
            ),
            GeneratedSources.relativePaths(),
        )
    }

    @Test
    fun `no generated source reflects, looks up a map, dispatches on a runtime type or serialises blind`() {
        val offenders = mutableListOf<String>()
        for (file in GeneratedSources.files) {
            file.forEachIndexedLine { number, line ->
                val code = line.substringBefore("//")
                for ((pattern, why) in BANNED) {
                    if (pattern.containsMatchIn(code)) {
                        offenders += "${file.name}:$number: $why -> ${line.trim()}"
                    }
                }
            }
        }
        assertEquals(emptyList(), offenders, "banned constructs in generated code:\n$offenders")
    }

    @Test
    fun `every field is read and written through a direct property access`() {
        // The positive counterpart to the ban: it is not enough that the generated code avoids
        // reflection, it has to actually name the property.
        val health = GeneratedSources.files.single { it.name == "HealthReplicator.kt" }.readText()
        for (property in listOf("current", "invulnerable", "lastDamageTick", "maximum")) {
            assertTrue(
                "component.$property" in health,
                "HealthReplicator never touches component.$property directly",
            )
        }
    }

    @Test
    fun `the ban would actually fire`() {
        // A scanner that matches nothing passes vacuously forever. This pins the patterns to
        // text that must trip them.
        val samples = listOf(
            "    val kClass = component::class",
            "    val v = fields[\"health\"]",
            "    when (value) { is Float -> 1 }",
            "    data.putSerializable(component.position)",
            "    val m: Map<String, Int> = mapOf()",
            "    val c = Class.forName(name)",
            "    Cbor.encodeToByteArray(component.position)",
        )
        for (sample in samples) {
            assertTrue(
                BANNED.any { (pattern, _) -> pattern.containsMatchIn(sample) },
                "no banned pattern matches $sample",
            )
        }
    }

    private fun File.forEachIndexedLine(action: (Int, String) -> Unit) {
        readLines().forEachIndexed { index, line -> action(index + 1, line) }
    }

    private companion object {
        val BANNED: List<Pair<Regex, String>> = listOf(
            Regex("""::class""") to "reflection",
            Regex("""\bjavaClass\b""") to "reflection",
            Regex("""\bkotlin\.reflect\b|\bjava\.lang\.reflect\b""") to "reflection",
            Regex("""\bClass\.forName\b""") to "reflection",
            Regex("""\bmapOf\s*\(|\bHashMap\b|\bMap<""") to "map lookup",
            Regex("\\w\\s*\\[\\s*\"") to "map lookup",
            Regex("""when\s*\(\s*(?!fieldIndex\b)""") to
                "when on something other than the field index",
            Regex("""^\s*is\s+\w""") to "when on a runtime type",
            Regex("""putSerializable|getSerializable""") to "blind serialisation fallback",
            // The other half of the same fallback: the old generator's answer to any type it
            // did not recognise was a CBOR blob, which cost an allocation and a full encode
            // per field per tick and turned an unsupported field into a runtime failure.
            // Every type udea-codegen accepts now has a folded, allocation-free encoding, so
            // a CBOR reference in generated output means the fallback came back.
            Regex("""(?i)\bcbor\b""") to "a CBOR fallback encoding",
        )
    }
}
