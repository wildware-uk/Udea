package dev.wildware.udea.assets.compiler.migrate

import dev.wildware.udea.assets.Ability
import dev.wildware.udea.assets.AssetId
import dev.wildware.udea.assets.Axis2DBinding
import dev.wildware.udea.assets.Binding
import dev.wildware.udea.assets.BindingInput
import dev.wildware.udea.assets.Blueprint
import dev.wildware.udea.assets.GameConfig
import dev.wildware.udea.assets.Level
import dev.wildware.udea.assets.SpriteAnimation
import dev.wildware.udea.assets.SpriteAnimationSet
import dev.wildware.udea.assets.Vec2
import dev.wildware.udea.assets.compiler.AssetCompiler
import dev.wildware.udea.assets.compiler.TestPaths
import dev.wildware.udea.assets.compiler.pack.BundleContent
import dev.wildware.udea.assets.compiler.pack.BundleWriter
import dev.wildware.udea.assets.compiler.pack.GraphPacker
import dev.wildware.udea.assets.compiler.pack.PackedAtlas
import dev.wildware.udea.assets.pack.BundleReader
import dev.wildware.udea.assets.reference
import org.junit.jupiter.api.Test
import kotlin.io.path.exists
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The migrated corpus packs into a bundle the **runtime reader** can open.
 *
 * ### Why this is a separate claim from "it validates"
 *
 * `MigratedCorpusCompilesTest` proves the tree compiles and passes every validator. That says
 * nothing about whether it can be *shipped*: the validators check the graph, and the bundle is
 * produced by `GraphPacker`, which maps each DSL word onto the field names `AssetCodecs` reads.
 * A kind with no schema there is packed verbatim under its runtime type name, and the reader
 * then binds it with a codec that is looking for different fields — so a corpus that validates
 * perfectly can still fail to open, at launch, with `AssetDecodeException`.
 *
 * That was the live state after the eight new declaration kinds landed on `AssetScope`: six of
 * them have a runtime type and none of them had a packer schema. `binding` is the sharpest
 * case — the DSL holds `input = key(62)` as a nested record and `AssetCodecs` reads a flat
 * `inputKind`/`inputCode` pair — so this test opens the bytes and asserts the typed value.
 */
class MigratedCorpusBundleTest {

    private val root = TestPaths.repoRoot.resolve("moba/src/main/assets")

    private var packerDiagnostics: List<String> = emptyList()

    private fun bundle(): dev.wildware.udea.assets.pack.Bundle {
        val compiler = AssetCompiler(
            repoRoot = TestPaths.repoRoot,
            assetRoot = root,
            scriptClasspath = TestPaths.compilerClasspath.filter { it.exists() },
            cacheDirectory = TestPaths.scratch("migrated-bundle-cache"),
        )
        val result = compiler.compile(AssetCompiler.scriptsUnder(root))
        check(!result.hasErrors) {
            "the migrated corpus must compile:\n" +
                result.diagnostics.joinToString("\n") { "${it.ruleId} ${it.message}" }
        }
        val packed = GraphPacker.pack(result.graph)
        // Every diagnostic packing reports, kept rather than asserted away. See
        // `the one thing the corpus cannot pack is reported by id` for what they are and why
        // closing them is not this migration's work.
        packerDiagnostics = packed.diagnostics.map { "${it.ruleId} ${it.message}" }
        return BundleReader.open(
            BundleWriter.write(BundleContent(assets = packed.assets, atlas = PackedAtlas.EMPTY)),
        )
    }

    @Test
    fun `every asset in the migrated corpus binds to a typed model value`() {
        bundle().use { bundle ->
            assertEquals(116, bundle.registry.ids.size)

            // A binding: the nested `key(62)` record became the flat pair the codec reads.
            val binding = bundle.registry[reference<Binding>("control/attack_binding")]
            assertEquals(AssetId("control/attack"), binding.control.id)
            assertEquals(BindingInput.Key(62), binding.input)

            val move = bundle.registry[reference<Axis2DBinding>("control/move_left")]
            assertEquals(AssetId("control/move"), move.axis.id)
            assertEquals(Vec2(-1F, 0F), move.direction)

            // An ability: `exec` is a class name and the tag lists survive as names.
            val melee = bundle.registry[reference<Ability>("ability/npc_melee")]
            assertEquals("dev.wildware.udea.example.ability.UnitMeleeAttack", melee.exec.type.value)
            assertEquals(0.5F, melee.range)
            assertTrue(melee.blockAnimations)
            assertEquals(listOf("Debuffs.Stunned"), melee.blockedBy.map { it.value })
            assertEquals(
                listOf("AIHint.Damage", "AIHint.TargetEnemy", "AIHint.Melee"),
                melee.tags.map { it.value },
            )

            // An animation set, and one of its members' notifies.
            val set = bundle.registry[reference<SpriteAnimationSet>("character/orc_animation_set")]
            assertEquals(
                listOf(
                    "character/orc_idle",
                    "character/orc_walk",
                    "character/orc_attack",
                    "character/orc_hit",
                    "character/orc_death",
                ),
                set.animations.map { it.id.value },
            )
            val attack = bundle.registry[reference<SpriteAnimation>("character/orc_attack")]
            assertEquals(AssetId("character/orc_attack_sheet"), attack.sheet.id)
            assertEquals(false, attack.loop)
            assertEquals(
                listOf("attack_hit" to 4, "swoosh" to 3),
                attack.notifies.map { it.name to it.frame },
            )

            // A blueprint whose components carry fields, and its inheritance edge.
            val player = bundle.registry[reference<Blueprint>("blueprint/player")]
            assertEquals(listOf("character/orc_elite"), player.inheritedFrom.map { it.value })
            assertEquals(
                listOf(
                    "dev.wildware.udea.example.component.Player",
                    "dev.wildware.udea.ecs.component.base.Networkable",
                ),
                player.components.map { it.type.value },
            )
            val arrow = bundle.registry[reference<Blueprint>("blueprint/arrow")]
            val box = arrow.components.single { it.type.simpleName == "Box" }
            assertEquals(setOf("width", "height", "isSensor"), box.fields.keys)

            // A level: named entities, their blueprint, position and added components.
            val level = bundle.registry[reference<Level>("level/test_level")]
            assertEquals(6, level.systems.size)
            assertEquals(27, level.entities.size)
            val first = level.entities.first()
            assertEquals("player", first.name)
            assertEquals(Vec2(-7F, -2F), first.position)
            assertEquals(
                listOf(
                    "dev.wildware.udea.ecs.component.base.Networkable",
                    "dev.wildware.udea.example.component.Player",
                ),
                first.components.map { it.type.value },
            )

            // The config, including the gravity the DSL nests inside `physics { }`.
            val config = bundle.registry[reference<GameConfig>("config")]
            assertEquals(AssetId("blueprint/player"), config.defaultCharacter?.id)
            assertEquals(AssetId("level/test_level"), config.defaultLevel?.id)
            assertEquals(Vec2(0F, 0F), config.physics.gravity)

            // `character`, `gameplayEffect` and `effect` have no runtime type, so they come back
            // opaque with their fields intact. That is the honest outcome, not a gap this test
            // papers over: see `AssetKind.Unpublishable`.
            val orc = assertIs<dev.wildware.udea.assets.pack.OpaqueAsset>(
                bundle.registry.find(AssetId("character/orc")),
            )
            assertEquals("character", orc.kind)
        }
    }

    /**
     * The one gap left, named rather than described.
     *
     * `EntityDefinition.blueprint` is a `Ref<Blueprint>`, and every entity in `level/test_level`
     * names a `character(...)`, which `AssetKind.Unpublishable` says has no runtime type. So the
     * packer reports `UDEA0013` and drops the field: the level comes back with its entities,
     * their names, positions and components, and without the blueprint each was spawned from.
     *
     * Before this migration packed the corpus, the packer wrote that reference *unchecked* —
     * `resolve(ref)` and not `resolve(ref, Blueprint::class)` — so the defect was invisible at
     * build time and surfaced as `AssetTypeMismatchException: asset 'character/soldier' is a
     * OpaqueAsset, but the reference expects a Blueprint` when the game opened the bundle. That
     * is fixed; what remains is the gap itself.
     *
     * Closing it is issue #84's remaining half: it means deciding which of a character's
     * animation, attribute and ability data becomes a `ComponentSpec`, which is a model decision
     * and not a rewrite of scripts. This test stops it being invisible — twenty-seven entities,
     * with a bill that goes to zero the day `character` has a type.
     */
    @Test
    fun `the one thing the corpus cannot pack is reported by id`() {
        bundle().use { bundle ->
            val level = bundle.registry[reference<Level>("level/test_level")]
            assertEquals(27, level.entities.size)
            assertTrue(
                level.entities.all { it.blueprint == null },
                "an entity kept a blueprint reference the reader would refuse at load",
            )
            assertEquals(27, packerDiagnostics.size, packerDiagnostics.joinToString("\n"))
            assertTrue(
                packerDiagnostics.all {
                    it.startsWith("UDEA0013") && "is a kind with no runtime type" in it
                },
                "packing reported something other than the known `character` gap:\n" +
                    packerDiagnostics.joinToString("\n"),
            )
        }
    }
}
