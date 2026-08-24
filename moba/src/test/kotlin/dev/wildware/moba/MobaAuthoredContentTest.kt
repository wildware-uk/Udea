package dev.wildware.moba

import dev.wildware.moba.ability.MobaAbilities
import dev.wildware.moba.ability.MobaEffects
import dev.wildware.udea.assets.Ability
import dev.wildware.udea.assets.AssetId
import dev.wildware.udea.assets.Character
import dev.wildware.udea.assets.Effect
import dev.wildware.udea.assets.EffectDuration
import dev.wildware.udea.assets.GameplayEffect
import dev.wildware.udea.assets.GameplayTagName
import dev.wildware.udea.assets.Level
import dev.wildware.udea.generated.GameAssets
import dev.wildware.udea.gas.ticksFromSeconds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The authored gameplay data and the Kotlin tables the simulation runs on say the same thing.
 *
 * ## The drift this closes
 *
 * `character`, `gameplayEffect` and `effect` were `AssetKind.Unpublishable`, so this game carried
 * every one of them **twice**: once in a `.udea.kts` under `moba/src/main/assets`, a root nothing
 * packed and nothing loaded, and once in Kotlin. Each copy's KDoc pointed at the other and nothing
 * compared them:
 *
 * - `MobaEffects` — *"Ported from `src/main/assets/ability/gameplay_effects.udea.kts` [...] the
 *   packer cannot publish one and nothing loads these at boot [...] `MobaAbilityContentTest` pins
 *   the names"*. There was no `MobaAbilityContentTest`.
 * - `MobaAbilities` — *"one `ability(...)` call per `AbilityDef` here"*, in a file the build did
 *   not read.
 * - `UnitKind` — *"`MobaUnitAssetParityTest` diffs the numbers here against the numbers there"*.
 *   There was no `MobaUnitAssetParityTest` either.
 * - `CharacterRoster` — resolves a unit's five animation states by **id suffix**, because
 *   `character(animationMap = ...)`, which says it directly, could not be packed.
 *
 * The three kinds are published now and the two roots are one, so the authored copy is in the
 * bundle this test opens. This is the comparison those four KDocs promised.
 *
 * ## What it does not claim
 *
 * The tables are still built in Kotlin, and that is not a stub being hidden: a `GameplayEffectDef`
 * holds an interned `AttributeId`, a `TagSet` and an `IntArray` of cue ids, which are results of a
 * running game's attribute and tag tables and cannot be decoded from a bundle without one. What
 * this closes is the *unchecked* duplication - the failure where somebody retunes a cooldown in
 * the file a designer edits and the simulation keeps the old number, green all the way.
 *
 * No GL, no physics natives, no world: it reads the packed bundle off the test runtime classpath
 * and compares it with `const val`s.
 */
class MobaAuthoredContentTest {

    private val registry get() = MobaAssets.registry

    private fun <T> everyAsset(type: Class<T>): List<T> =
        registry.ids.mapNotNull { registry.find(it) }.filterIsInstance(type)

    /** How many ticks a second the simulation runs at, for the seconds-to-ticks comparisons. */
    private val tickRate = 60

    @Test
    fun `every gameplay effect the simulation runs is declared in the asset graph`() {
        val authored = everyAsset(GameplayEffect::class.java).map { it.id.value }.sorted()

        assertEquals(
            listOf(
                MobaEffects.COOLDOWN,
                MobaEffects.COST_MANA,
                MobaEffects.DAMAGE,
                MobaEffects.HEAL,
                MobaEffects.HEAL_OVER_TIME,
                MobaEffects.PASSIVE_HEALTH_REGEN,
                MobaEffects.STUN,
            ).sorted(),
            authored,
            "`ability/gameplay_effects.udea.kts` and `MobaEffects` disagree about which effects exist",
        )
    }

    /**
     * And about the two numbers a period gets wrong silently.
     *
     * A period is authored in seconds and applied in ticks. `ticksFromSeconds` is the one
     * deterministic conversion, so the comparison runs the real function rather than multiplying
     * by sixty here - a second rounding rule in a test is a test that can agree with nothing.
     */
    @Test
    fun `the authored periods are the tick counts the effect table holds`() {
        val byId = everyAsset(GameplayEffect::class.java).associateBy { it.id.value }

        val hot = assertNotNull(byId[MobaEffects.HEAL_OVER_TIME])
        assertEquals(MobaEffects.HEAL_PERIOD_TICKS, ticksFromSeconds(hot.period, tickRate))
        assertEquals(
            EffectDuration.SetByCaller(GameplayTagName("Data.Duration")),
            hot.duration,
            "the priest's heal lasts as long as the caller says, or it is not a heal-over-time",
        )

        val regen = assertNotNull(byId[MobaEffects.PASSIVE_HEALTH_REGEN])
        assertEquals(MobaEffects.REGEN_PERIOD_TICKS, ticksFromSeconds(regen.period, tickRate))
        assertEquals(EffectDuration.Infinite, regen.duration)

        // An instant effect writes `base` and a duration effect does not, so this is the field
        // whose drift is a permanently wrong stat rather than a wrong number for a while.
        assertEquals(EffectDuration.Instant, assertNotNull(byId[MobaEffects.DAMAGE]).duration)
        assertEquals(EffectDuration.Instant, assertNotNull(byId[MobaEffects.HEAL]).duration)
        assertEquals(EffectDuration.Instant, assertNotNull(byId[MobaEffects.COST_MANA]).duration)
    }

    @Test
    fun `every ability the simulation runs is declared, with the cooldown the table uses`() {
        val byId = everyAsset(Ability::class.java).associateBy { it.id.value }

        assertEquals(
            listOf(
                MobaAbilities.FIRE_ARROW,
                MobaAbilities.MELEE,
                MobaAbilities.ORC_SPIN,
                MobaAbilities.PRIEST_HEAL,
            ).sorted(),
            byId.keys.sorted(),
        )

        val cooldownTag = GameplayTagName("Data.Cooldown")
        fun cooldownTicks(id: String): Int {
            val ability = assertNotNull(byId[id], "`$id` is not in the bundle")
            val seconds = assertNotNull(
                ability.setByCaller[cooldownTag],
                "`$id` stages no `Data.Cooldown`, so nothing puts it on cooldown",
            )
            assertEquals(
                AssetId(MobaEffects.COOLDOWN),
                assertNotNull(ability.cooldown, "`$id` names no cooldown effect").id,
            )
            return ticksFromSeconds(seconds, tickRate)
        }

        assertEquals(MobaAbilities.MELEE_COOLDOWN_TICKS, cooldownTicks(MobaAbilities.MELEE))
        assertEquals(MobaAbilities.ORC_SPIN_COOLDOWN_TICKS, cooldownTicks(MobaAbilities.ORC_SPIN))
        assertEquals(MobaAbilities.PRIEST_HEAL_COOLDOWN_TICKS, cooldownTicks(MobaAbilities.PRIEST_HEAL))
        assertEquals(MobaAbilities.FIRE_ARROW_COOLDOWN_TICKS, cooldownTicks(MobaAbilities.FIRE_ARROW))

        // The one cost in the game, authored positive and spent negative.
        val heal = assertNotNull(byId[MobaAbilities.PRIEST_HEAL])
        assertEquals(
            MobaAbilities.PRIEST_HEAL_MANA_COST,
            heal.setByCaller[GameplayTagName("Cost.Mana")],
        )
        assertEquals(listOf(AssetId(MobaEffects.COST_MANA)), heal.costs.map { it.id })
    }

    /**
     * The authored role map and the id-suffix convention the renderer resolves by agree.
     *
     * Two statements of one fact, which is why this is checked rather than trusted. The suffix
     * convention is what `CharacterRoster` reads at bundle-open time and what
     * `CharacterAnimationSystem` draws from; the map is what an author writes. A `character(...)`
     * that pointed `walk` at the death animation would be invisible to every other check in this
     * repository - the unit would simply die on the spot, forever, and look deliberate.
     */
    @Test
    fun `every character's authored animation roles are the ones the renderer resolves`() {
        val characters = everyAsset(Character::class.java)
        assertEquals(6, characters.size, "six characters: ${characters.map { it.id.value }}")

        for (character in characters) {
            val name = character.id.value.removePrefix(CharacterRoster.PREFIX)
            val entry = assertNotNull(
                MobaCharacters.roster.byName(name),
                "`${character.id}` is authored and the roster has no art for it",
            )
            for (state in UnitState.entries) {
                val authored = assertNotNull(
                    character.animations[state.suffix],
                    "`${character.id}` declares no `${state.suffix}` animation",
                )
                assertEquals(
                    entry.animation(state).id,
                    authored.id,
                    "`${character.id}`'s authored `${state.suffix}` is not the animation " +
                        "`CharacterRoster` resolves for $state",
                )
            }
            // Every role resolves through the registry, so the map is bound and not just present.
            assertTrue(
                character.animations.values.all { registry.find(it.id) != null },
                "`${character.id}` has a role pointing at an id the graph does not hold",
            )
        }
    }

    /** Every unit the level places is one of those characters. */
    @Test
    fun `the level spawns only characters the bundle declares`() {
        val level: Level = registry[GameAssets.level.testLevel]
        val names = everyAsset(Character::class.java).map { it.id }.toSet()

        assertEquals(27, level.entities.size)
        for (entity in level.entities) {
            val recipe = assertNotNull(entity.blueprint, "`${entity.name}` names no recipe")
            assertTrue(
                recipe.id in names,
                "`${entity.name}` spawns from `${recipe.id}`, which is not a declared character",
            )
        }
    }

    /**
     * The three visual effects, and the lifetimes the spawner counts down in ticks.
     *
     * `EffectKind` held the duration as a Kotlin constant because `effect(...)` could not be
     * packed. The authored value is back and this is what stops the two diverging.
     */
    @Test
    fun `every visual effect's authored duration is the lifetime the spawner uses`() {
        val byId = everyAsset(Effect::class.java).associateBy { it.id.value }
        assertEquals(listOf("effects/heal", "effects/hit", "effects/spell"), byId.keys.sorted())

        for (kind in EffectKind.entries) {
            val authored = assertNotNull(
                byId.values.singleOrNull { it.animation == kind.animation.value.substringAfterLast('/') },
                "no `effect(...)` names ${kind.animation}",
            )
            assertEquals(
                kind.lifeTicks,
                ticksFromSeconds(authored.duration, tickRate).toLong(),
                "`${authored.id}` is authored ${authored.duration}s and ${kind.name} lives " +
                    "${kind.lifeTicks} ticks",
            )
            assertNotNull(
                registry.find(authored.animationSet.id),
                "`${authored.id}` names an animation set the graph does not hold",
            )
        }
    }
}
