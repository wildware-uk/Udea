package dev.wildware.udea.assets.compiler.migrate

import dev.wildware.udea.assets.Ability
import dev.wildware.udea.assets.AssetId
import dev.wildware.udea.assets.Axis2DBinding
import dev.wildware.udea.assets.Binding
import dev.wildware.udea.assets.BindingInput
import dev.wildware.udea.assets.Blueprint
import dev.wildware.udea.assets.Character
import dev.wildware.udea.assets.Effect
import dev.wildware.udea.assets.EffectDuration
import dev.wildware.udea.assets.EffectMagnitude
import dev.wildware.udea.assets.GameConfig
import dev.wildware.udea.assets.GameplayEffect
import dev.wildware.udea.assets.Level
import dev.wildware.udea.assets.ModifierKind
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The game's one asset root packs into a bundle the **runtime reader** can open, with nothing lost.
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
 * ### What this test asserted before, and what changed
 *
 * It asserted **twenty-seven `UDEA0013`s**. Every entity in `level/test_level` named a
 * `character(...)`; `character` was `AssetKind.Unpublishable` and `EntityDefinition.blueprint` was
 * a `Ref<Blueprint>`, so the packer reported each one and dropped the field. A level whose
 * entities name nothing cannot spawn anything, which is why this game shipped a *second*, reduced
 * asset root and left this corpus unpacked. Its own KDoc said the bill "goes to zero the day
 * `character` has a type".
 *
 * It is zero. `Character`, `GameplayEffect` and `Effect` are real `AssetData` types,
 * `EntityDefinition.blueprint` is a `Ref<SpawnRecipe>` that both `Blueprint` and `Character`
 * satisfy, the two roots are one, and every assertion below reads the tree the shipped game boots
 * from.
 */
class MigratedCorpusBundleTest {

    private val root = TestPaths.repoRoot.resolve("moba/assets")

    private var packerDiagnostics: List<String> = emptyList()

    /**
     * How many assets the corpus declared, recorded while it was compiled.
     *
     * The bundle's own size is asserted against this rather than against a literal. "Every asset
     * in the corpus binds to a typed model value" is a statement about the corpus *and* the
     * bundle agreeing, and a hard-coded number states neither side: it was 127 when this was
     * written and 147 once the shop's twenty items landed, so it had to be edited by a change
     * that broke nothing. Derived, it fails only when the bundle really has dropped something.
     */
    private var declaredAssets: Int = 0

    private fun bundle(): dev.wildware.udea.assets.pack.Bundle {
        val compiler = AssetCompiler(
            repoRoot = TestPaths.repoRoot,
            assetRoot = root,
            scriptClasspath = TestPaths.compilerClasspath.filter { it.exists() },
            cacheDirectory = TestPaths.scratch("migrated-bundle-cache"),
        )
        val result = compiler.compile(AssetCompiler.scriptsUnder(root))
        check(!result.hasErrors) {
            "the game's asset root must compile:\n" +
                result.diagnostics.joinToString("\n") { "${it.ruleId} ${it.message}" }
        }
        val packed = GraphPacker.pack(result.graph)
        declaredAssets = result.graph.assets.size
        packerDiagnostics = packed.diagnostics.map { "${it.ruleId} ${it.message}" }
        return BundleReader.open(
            BundleWriter.write(BundleContent(assets = packed.assets, atlas = PackedAtlas.EMPTY)),
        )
    }

    @Test
    fun `every asset in the corpus binds to a typed model value`() {
        bundle().use { bundle ->
            assertEquals(
                declaredAssets,
                bundle.registry.ids.size,
                "the bundle must hold every asset the corpus declared",
            )
            assertTrue(declaredAssets > 0, "an empty corpus would satisfy the line above")

            // A binding: the nested `key(62)` record became the flat pair the codec reads.
            val binding = bundle.registry[reference<Binding>("control/attack_binding")]
            assertEquals(AssetId("control/attack"), binding.control.id)
            assertEquals(BindingInput.Key(62), binding.input)

            val move = bundle.registry[reference<Axis2DBinding>("control/move_left")]
            assertEquals(AssetId("control/move"), move.axis.id)
            assertEquals(Vec2(-1F, 0F), move.direction)

            // An ability: `exec` is a class name and the tag lists survive as names.
            val melee = bundle.registry[reference<Ability>("ability/npc_melee")]
            assertEquals("dev.wildware.moba.ability.MeleeAttackExec", melee.exec.type.value)
            assertEquals(32F, melee.range)
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

            // A blueprint's component list.
            val arrow = bundle.registry[reference<Blueprint>("blueprint/arrow")]
            assertEquals(
                listOf(
                    "dev.wildware.moba.Position",
                    "dev.wildware.moba.ability.Motion",
                    "dev.wildware.moba.ability.Projectile",
                    "dev.wildware.moba.SpriteView",
                ),
                arrow.components.map { it.type.value },
            )

            // The config, including the gravity the DSL nests inside `physics { }`.
            val config = bundle.registry[reference<GameConfig>("config")]
            assertEquals(AssetId("character/soldier"), config.defaultCharacter?.id)
            assertEquals(AssetId("level/test_level"), config.defaultLevel?.id)
        }
    }

    /**
     * The three kinds that had no runtime type come back typed, not opaque.
     *
     * Each of the three is asserted through the field that made publishing it worth doing rather
     * than through its mere presence: the character's **role map** (which the game carried as an
     * id-suffix convention no validator could check), the gameplay effect's **duration and
     * magnitude** (which decide whether an applied effect writes `base` or `current`), and the
     * effect's **animation set** (which the game carried as a Kotlin constant).
     */
    @Test
    fun `character, gameplayEffect and effect come back as typed model values`() {
        bundle().use { bundle ->
            val orc = bundle.registry[reference<Character>("character/orc")]
            assertEquals(150F, orc.health)
            assertEquals(
                mapOf(
                    "attack" to "character/orc_attack",
                    "death" to "character/orc_death",
                    "hit" to "character/orc_hit",
                    "idle" to "character/orc_idle",
                    "walk" to "character/orc_walk",
                ),
                orc.animations.mapValues { it.value.id.value },
            )
            assertEquals(
                mapOf(
                    "attack" to "sounds/melee_swoosh",
                    "death" to "sounds/death",
                    "hit" to "sounds/hurt",
                ),
                orc.sounds.mapValues { it.value.id.value },
            )
            assertEquals(mapOf("health" to 150F, "magicResist" to 20F, "strength" to 10F), orc.attributes)
            assertEquals(
                listOf(AssetId("ability/npc_melee")),
                orc.abilities.map { it.ability.id },
            )
            assertEquals(listOf("Slot.A"), orc.abilities.single().tags.map { it.value })
            // A role resolves through the registry, with no string lookup - the property the
            // id-suffix convention could not have.
            assertEquals(
                AssetId("character/orc_walk_sheet"),
                bundle.registry[bundle.registry[orc.animations.getValue("walk")].sheet].id,
            )

            val regen = bundle.registry[reference<GameplayEffect>("ability/passive_health_regen")]
            assertEquals(EffectDuration.Infinite, regen.duration)
            assertEquals("health", regen.target)
            assertEquals(ModifierKind.Additive, regen.modifierType)
            assertEquals(EffectMagnitude.Attribute("healthRegen"), regen.magnitude)
            assertEquals(1.0F, regen.period)

            val hot = bundle.registry[reference<GameplayEffect>("ability/heal_over_time")]
            assertEquals(EffectDuration.SetByCaller(dev.wildware.udea.assets.GameplayTagName("Data.Duration")), hot.duration)
            assertEquals(EffectMagnitude.SetByCaller(dev.wildware.udea.assets.GameplayTagName("Data.Heal")), hot.magnitude)
            assertEquals(0.25F, hot.period)

            val heal = bundle.registry[reference<Effect>("effects/heal")]
            assertEquals(AssetId("effects/heal_effect_set"), heal.animationSet.id)
            assertEquals("heal_effect", heal.animation)
            assertEquals(0.4F, heal.duration)
        }
    }

    /**
     * The twenty-seven dropped references, counted at zero.
     *
     * This is the same shape of assertion the old `the one thing the corpus cannot pack is
     * reported by id` made, inverted: it asserted `entities.all { it.blueprint == null }` and
     * `packerDiagnostics.size == 27`. Inverted rather than deleted, because a regression here is
     * invisible from the outside - a bundle whose level has no entity recipes still *opens*, and
     * the game fails at scene-swap time with a message about a blueprint id nobody named.
     */
    @Test
    fun `every entity in the level carries the recipe it spawns from`() {
        bundle().use { bundle ->
            val level = bundle.registry[reference<Level>("level/test_level")]
            assertEquals(27, level.entities.size)
            assertTrue(
                level.entities.all { it.blueprint != null },
                "an entity lost its recipe: " + level.entities.filter { it.blueprint == null }.map { it.name },
            )
            assertTrue(
                level.entities.all { it.blueprint!!.id.value.startsWith("character/") },
                "the roster is declared in `character/`, beside the art and stats each unit wears",
            )
            // And each one resolves to a real `Character` through the registry.
            val player = assertNotNull(level.entities.first { it.name == "player" }.blueprint)
            assertEquals(AssetId("character/orc_elite"), player.id)
            assertEquals(500F, bundle.registry[player].let { it as Character }.health)

            assertEquals(emptyList(), packerDiagnostics, "packing the game's asset root is clean")
        }
    }
}
