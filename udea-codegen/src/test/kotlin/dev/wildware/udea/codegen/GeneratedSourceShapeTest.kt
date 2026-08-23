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
                "dev/wildware/udea/codegen/fixtures/HealthAgentState.kt",
                "dev/wildware/udea/codegen/fixtures/HealthReplicator.kt",
                "dev/wildware/udea/codegen/fixtures/MatchClockAgentState.kt",
                "dev/wildware/udea/codegen/fixtures/MovementReplicator.kt",
                "dev/wildware/udea/codegen/fixtures/PlacementReplicator.kt",
                // The agent surface. One object per @AgentTool and one per class publishing
                // @AgentState, all isolating outputs like a Replicator.
                "dev/wildware/udea/codegen/fixtures/PlaygroundSetStanceTool.kt",
                "dev/wildware/udea/codegen/fixtures/PlaygroundSpawnBlueprintTool.kt",
                "dev/wildware/udea/codegen/fixtures/PlaygroundTagEntityTool.kt",
                "dev/wildware/udea/codegen/fixtures/QuantisedProbeReplicator.kt",
                // The module-level outputs. There is exactly one aggregating group per module:
                // the `ServiceLoader` index this module contributes, and the protocol constant
                // a packet header carries.
                "dev/wildware/udea/generated/CodegenFixturesNetModule.kt",
                "dev/wildware/udea/generated/CodegenFixturesNetProtocol.kt",
                "dev/wildware/udea/generated/CodegenFixturesStateModule.kt",
                "dev/wildware/udea/generated/CodegenFixturesToolModule.kt",
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
                if (EXEMPT.any { it.matches(code.trimEnd()) }) return@forEachIndexedLine
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
    fun `every tool argument is coerced and passed by name, with no reflection anywhere`() {
        // The same ban above already covers the dispatchers, since it scans every generated
        // file. This is its positive counterpart: it is not enough that a dispatcher avoids
        // reflection, it has to name the function and pass the coerced locals to it. The whole
        // reason `@AgentTool` goes through KSP rather than a runtime registry is that the agent
        // surface must survive R8, and a surface reached reflectively does not.
        val tool = GeneratedSources.files
            .single { it.name == "PlaygroundSpawnBlueprintTool.kt" }
            .readText()
        assertTrue(
            "receiver.spawnBlueprint(blueprint = blueprint, count = count, scale = scale)" in tool,
            "the dispatcher must call the annotated function directly: " + tool,
        )
        // Read through AgentCommand's typed accessors, which convert and report; a generated
        // `command.args["blueprint"]` would be both a map lookup and a silent null.
        assertTrue("command.str(\"blueprint\")" in tool, "blueprint is never read from the call")
        assertTrue("command.int(\"count\", 1)" in tool, "count's folded default is not passed")
        assertTrue("\"scale\" in command" in tool, "scale's absence is not distinguished from its value")
    }

    @Test
    fun `a digest source reads every published scalar by direct property access`() {
        val state = GeneratedSources.files.single { it.name == "MatchClockAgentState.kt" }.readText()
        for (property in listOf("elapsedTicks", "elapsedMillis", "timeScale", "paused", "phase")) {
            assertTrue("source.$property" in state, "MatchClockAgentState never reads source.$property")
        }
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

    @Test
    fun `the reflection exemption is one property on the agent surface and nothing else`() {
        // The exemption below is the only hole in the ban, so its size is asserted rather than
        // trusted. A `Replicator` must never take it - the whole rationale for banning `::class`
        // (R8, and a per-tick field access that is a direct property read) is exact there - and
        // the agent surface may take it only for `owner`, whose value is consumed once when
        // `ToolIndex`/`AgentStateIndex` is built and never on a call.
        val exempted = GeneratedSources.files.flatMap { file ->
            file.readLines()
                .filter { line -> EXEMPT.any { it.matches(line.trimEnd()) } }
                .map { file.name to it.trim() }
        }

        assertEquals(
            listOf(
                "HealthAgentState.kt" to "import kotlin.reflect.KClass",
                "HealthAgentState.kt" to "override val owner: KClass<*> = Health::class",
                "MatchClockAgentState.kt" to "import kotlin.reflect.KClass",
                "MatchClockAgentState.kt" to "override val owner: KClass<*> = MatchClock::class",
                "PlaygroundSetStanceTool.kt" to "import kotlin.reflect.KClass",
                "PlaygroundSetStanceTool.kt" to "override val owner: KClass<*> = Playground::class",
                "PlaygroundSpawnBlueprintTool.kt" to "import kotlin.reflect.KClass",
                "PlaygroundSpawnBlueprintTool.kt" to "override val owner: KClass<*> = Playground::class",
                "PlaygroundTagEntityTool.kt" to "import kotlin.reflect.KClass",
                "PlaygroundTagEntityTool.kt" to "override val owner: KClass<*> = Playground::class",
            ),
            exempted.sortedBy { "${it.first}${it.second}" },
            "the reflection exemption has widened; every line it now covers is listed here",
        )
        assertTrue(
            exempted.none { it.first.endsWith("Replicator.kt") },
            "a Replicator took the agent surface's exemption: ${exempted.filter { it.first.endsWith("Replicator.kt") }}",
        )
    }

    private fun File.forEachIndexedLine(action: (Int, String) -> Unit) {
        readLines().forEachIndexed { index, line -> action(index + 1, line) }
    }

    private companion object {
        /**
         * The one narrowing of [BANNED], and why it is not the thing the ban is aimed at.
         *
         * `AgentToolDef.owner` and `AgentStateSource.owner` carry the declaring class as a
         * **class literal**. That is a constant class reference, not a lookup: R8 follows it and
         * keeps the class - which is the ban's stated rationale - it costs no allocation, and it
         * is read exactly once, when `ToolIndex`/`AgentStateIndex` resolves a tool to the toolset
         * instance a host registered. `T` is erased, so an index holding `AgentToolDef<*>` has no
         * other way to make that pairing, and the alternatives are worse in the ban's own terms:
         * a class *name* is a string R8 cannot follow, and discovering the receiver reflectively
         * is the thing being banned.
         *
         * The patterns are anchored to whole lines so nothing else slips through, and
         * `the reflection exemption is one property on the agent surface and nothing else` pins
         * the exact set of lines they cover.
         */
        val EXEMPT: List<Regex> = listOf(
            Regex("""import kotlin\.reflect\.KClass"""),
            Regex("""\s*override val owner: KClass<\*> = [A-Za-z_][A-Za-z0-9_.]*::class"""),
        )

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
