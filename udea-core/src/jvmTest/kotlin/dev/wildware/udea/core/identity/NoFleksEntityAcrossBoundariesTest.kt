package dev.wildware.udea.core.identity

import dev.wildware.udea.core.Cue
import dev.wildware.udea.core.ModuleFiles
import dev.wildware.udea.core.replication.BitReader
import dev.wildware.udea.core.replication.BitWriter
import dev.wildware.udea.core.replication.FieldStore
import dev.wildware.udea.core.replication.Replicator
import kotlin.reflect.KClass
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.full.declaredMemberProperties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NoFleksEntityAcrossBoundariesTest {

    @Test
    fun `no replicated, snapshotted or agent-exposed declaration in udea-core is typed Entity`() {
        val sources = ModuleFiles.mainSources + ModuleFiles.testFixtureSources
        assertTrue(sources.size >= 10, "expected to find udea-core's sources, found ${sources.size}")

        val violations = sources.flatMap { file ->
            FleksEntityBoundaryRule.violations(ModuleFiles.relativePath(file), file.readText())
        }

        assertEquals(
            emptyList(),
            violations,
            "a Fleks Entity is one world's slot index; across these boundaries it must be a NetId",
        )
    }

    @Test
    fun `the rule catches an Entity-typed replicated field`() {
        val violations = FleksEntityBoundaryRule.violations(
            "Fake.kt",
            """
            package fake

            @Replicated
            data class Targeting(
                @Net var target: Entity = Entity.NONE,
                @Sim var lastAttacker: Entity = Entity.NONE,
            )
            """.trimIndent(),
        )

        assertEquals(listOf("Net", "Sim"), violations.map { it.annotation })
        assertEquals(listOf(5, 6), violations.map { it.line })
    }

    @Test
    fun `the rule catches an Entity-typed agent tool parameter across lines`() {
        val violations = FleksEntityBoundaryRule.violations(
            "Fake.kt",
            """
            package fake

            @AgentTool("damage")
            fun damage(
                target: Entity,
                amount: Float,
            ) {
                target.toString()
            }
            """.trimIndent(),
        )

        assertEquals(1, violations.size, violations.toString())
        assertEquals("AgentTool", violations.single().annotation)
    }

    @Test
    fun `the rule passes NetId-typed and Entity-adjacent declarations`() {
        val violations = FleksEntityBoundaryRule.violations(
            "Fake.kt",
            """
            package fake

            @Replicated
            data class Targeting(
                @Net var target: NetId = NetId.NONE,
                @Sim var seen: EntityBag? = null,
                @Sim var entityCount: Int = 0,
            )

            @AgentTool("describe")
            fun describe(target: NetId): String = target.toString()
            """.trimIndent(),
        )

        assertEquals(emptyList(), violations)
    }

    @Test
    fun `the rule ignores prose about Entity in KDoc and strings`() {
        // Without this, every file that documents the rule would violate it.
        val violations = FleksEntityBoundaryRule.violations(
            "Fake.kt",
            """
            package fake

            /**
             * Never put @Net on an Entity.
             */
            const val WARNING: String = "@Sim on an Entity is a defect"

            @Replicated
            data class Ok(@Net var target: NetId = NetId.NONE)
            """.trimIndent(),
        )

        assertEquals(emptyList(), violations)
    }

    @Test
    fun `no frozen contract type names Entity in its signature`() {
        // The source rule covers annotated declarations; this covers the interfaces
        // themselves, which are what the four coupled modules compile against.
        val frozen = listOf(
            Replicator::class,
            FieldStore::class,
            BitWriter::class,
            BitReader::class,
            Cue::class,
        )

        val offenders = frozen.flatMap { type -> entityMentions(type) }

        assertEquals(
            emptyList(),
            offenders,
            "the serialisation and cue contracts must speak NetId, never a Fleks Entity",
        )
    }

    private fun entityMentions(type: KClass<*>): List<String> {
        val fromProperties = type.declaredMemberProperties
            .filter { "com.github.quillraven.fleks.Entity" in it.returnType.toString() }
            .map { "${type.simpleName}.${it.name}" }

        val fromFunctions = type.declaredMemberFunctions
            .filter { function ->
                function.parameters.any { "com.github.quillraven.fleks.Entity" in it.type.toString() } ||
                    "com.github.quillraven.fleks.Entity" in function.returnType.toString()
            }
            .map { "${type.simpleName}.${it.name}()" }

        return (fromProperties + fromFunctions).sorted()
    }
}
