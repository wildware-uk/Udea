package dev.wildware.moba

import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.moba.ability.Combatant
import dev.wildware.moba.ability.MeleeAttackExec
import dev.wildware.moba.ability.MobaAbilityModule
import dev.wildware.moba.ability.MobaCues
import dev.wildware.moba.ability.MobaScale
import dev.wildware.moba.ability.MobaUnits
import dev.wildware.moba.ability.Projectile
import dev.wildware.moba.entry.MobaEntry
import dev.wildware.moba.level.GameUnit
import dev.wildware.moba.level.MobaBlueprints
import dev.wildware.moba.level.Team
import dev.wildware.moba.level.UnitBattleSystem
import dev.wildware.moba.level.UnitKind
import dev.wildware.udea.core.Cue
import dev.wildware.udea.core.CueQueue
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.gas.Abilities
import dev.wildware.udea.gas.Attributes
import dev.wildware.udea.gas.GameplayEffects
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The seams between the five parallel wave agents, checked on the game they all ship into.
 *
 * ## Why these are not covered by anybody's own tests
 *
 * Every one of these claims is about **two** pieces agreeing, and each piece was green on its own
 * while the pair was broken. `CombatProofTest` spawned units two units apart and proved the priest
 * heals; `MobaLevelTest` spawned twenty-seven units a hundred units apart and proved they die.
 * Both passed while no ability in the game could reach anything, because the two halves had been
 * authored in world scales forty times apart and no test in the build ever put a level unit and an
 * ability in the same sentence. That is the failure mode an integration test exists for, and it is
 * the reason these assertions are about *relationships between constants* as much as about
 * behaviour.
 */
class MobaIntegrationTest {

    /**
     * A headless host with the level loaded and the swap tick already applied, **and** the module
     * it was built from.
     *
     * The definition is built here rather than through `MobaGame.host` for one reason: an
     * [dev.wildware.udea.gas.AttributeId] is an index into one `AttributeTable`, and this game
     * builds a fresh one per definition. A test that called `CharacterAttributes.create()` for
     * itself would be reading ids into a table nothing in the world is using - which happens to
     * agree today, and would stop the first time a name was inserted into that list.
     */
    private fun booted(): Booted {
        val definition = MobaGame.definition()
        val module = definition.modules.filterIsInstance<MobaModule>().single()
        val host = GameHost(RenderMode.Headless, definition, null)
        MobaEntry.seed(host)
        return Booted(host, module)
    }

    /** A booted game and the module that decides what is in it. */
    private class Booted(val host: GameHost, val module: MobaModule) {

        /** This game's attribute ids, off the very tables its units were built against. */
        val attributes get() = module.combat.attributes

        /**
         * Every cue the simulation has raised since the last call, by id.
         *
         * Drained off the context's own queue. Nothing else drains it in `Headless` - there is no
         * renderer and no audio - so a cue emitted between two calls is still here, and draining
         * is what keeps a 1024-entry queue from silently dropping the rest of the fight.
         */
        fun drainCues(into: MutableMap<Int, Int>) {
            val queue = host.ctx.cues as? CueQueue ?: return
            queue.drain { cue: Cue -> into[cue.id.raw] = (into[cue.id.raw] ?: 0) + 1 }
        }
    }

    /**
     * Every unit on the field carries both halves of the game on one entity.
     *
     * The claim the whole integration rests on. Before it, `dev.wildware.moba.level` spawned
     * twenty-seven entities with a position, a team and a sprite, and `dev.wildware.moba.ability`
     * registered an ability system whose every family required a `Combatant` that no entity in a
     * shipped process had - so `AbilitySystem`, `ProjectileSystem`, `DeathSystem` and the autopilot
     * all ran, every tick, over nothing.
     */
    @Test
    fun `every level unit is both a GameUnit and a fully equipped combatant`() {
        val game = booted()
        val host = game.host
        var units = 0
        with(host.world) {
            host.world.family { all(GameUnit) }.forEach { entity ->
                units++
                val unit = entity[GameUnit]
                val combatant = requireNotNull(entity.getOrNull(Combatant)) {
                    "$unit has no Combatant, so no ability in this game can see it"
                }
                assertEquals(
                    unit.team,
                    combatant.teamId,
                    "$unit and its Combatant disagree about whose side it is on, so it would " +
                        "walk toward one enemy and swing at another",
                )
                val abilities = requireNotNull(entity.getOrNull(Abilities)) { "$unit has no Abilities" }
                assertTrue(
                    abilities.instanceAt(0).isGranted,
                    "$unit has no basic attack granted, so it can never damage anything",
                )
                val attributes = requireNotNull(entity.getOrNull(Attributes)) { "$unit has no Attributes" }
                assertTrue(
                    attributes.current(game.attributes.health) > 0f,
                    "$unit spawned with no health",
                )
                requireNotNull(entity.getOrNull(GameplayEffects)) { "$unit has nowhere to hold effects" }
            }
        }
        assertEquals(27, units, "the level's whole roster")
    }

    /**
     * The health the bar draws, the health `Position.hp` mirrors and the attribute agree.
     *
     * Three surfaces read a unit's health and only one of them is the truth. A unit whose
     * `Position.hp` said 100 while its `health` attribute said 50 would draw a full bar over a
     * unit one swing from death, and an agent reading `world.describe_entity` would be told the
     * same lie.
     */
    @Test
    fun `Position hp mirrors the health attribute for every unit`() {
        val game = booted()
        val host = game.host
        host.run(SETTLE_TICKS)
        val health = game.attributes.health
        with(host.world) {
            host.world.family { all(GameUnit, Attributes) }.forEach { entity ->
                assertEquals(
                    entity[Attributes].current(health),
                    entity[Position].hp,
                    "${entity[GameUnit]} shows a different health through Position.hp than it has",
                )
            }
        }
    }

    /**
     * A unit closes to a distance at which its own swing can find what it closed on.
     *
     * The relationship that decides whether this game is a fight or a stand-off, and the one the
     * scale seam got wrong: [UnitBattleSystem][dev.wildware.moba.level.UnitBattleSystem] stops
     * walking at [UnitKind.reach] and [MeleeAttackExec] then looks for an enemy within its own
     * [MeleeAttackExec.RANGE]. With `reach` outside `RANGE` every unit on the field walks up to its
     * target, stops, swings, and misses - for ever, silently, with full health bars.
     */
    @Test
    fun `every unit kind closes to inside its own melee range`() {
        for (kind in UnitKind.entries) {
            assertTrue(
                UnitBattleSystem.PERSONAL_SPACE < kind.reach,
                "${kind.name} insists on ${UnitBattleSystem.PERSONAL_SPACE} of room and only " +
                    "reaches ${kind.reach}, so the crowd would shove it out of its own melee and " +
                    "the fight would deadlock with everything at full health",
            )
            assertTrue(
                kind.reach < MeleeAttackExec.RANGE,
                "${kind.name} closes to ${kind.reach} and swings ${MeleeAttackExec.RANGE}, so it " +
                    "would stop outside its own reach and never land a blow",
            )
        }
    }

    /**
     * The level's four kinds, the art roster and the ability table spell the same six names.
     *
     * The one key three independently authored trees share. A blueprint naming a character the
     * roster does not hold throws at spawn; a blueprint naming a combat kind `MobaUnits` does not
     * hold throws when the blueprints are built. Both are loud, and both are boot failures rather
     * than test failures without this.
     */
    @Test
    fun `every level blueprint names a character and a combat kind that exist`() {
        val combat = MobaAbilityModule()
        val blueprints = MobaBlueprints(combat)
        val kinds = MobaUnits.kinds(combat.abilities).associateBy { it.name }
        for (blueprint in blueprints.all) {
            val name = blueprint.kind.character
            assertEquals(name, blueprint.id.value, "the blueprint id and the character name differ")
            assertTrue(MobaCharacters.roster.indexOf(name) >= 0, "the roster has no '$name'")
            val kind = requireNotNull(kinds[name]) { "MobaUnits declares no '$name'" }
            assertEquals(
                blueprint.team,
                kind.team,
                "'$name' fights for ${blueprint.team} in the level and ${kind.team} in combat",
            )
        }
    }

    /**
     * Ten seconds of the real level: units fight, animate, shoot and die.
     *
     * Deliberately one test and not four. Each half of this was already covered on its own and the
     * pair was what was broken, so splitting it would let three of the four go green over a game in
     * which nothing happened. The numbers are floors rather than exact counts: pinning "sixteen
     * units survive" turns every balance edit into a test failure, while a floor of zero is what a
     * completely dead simulation scores.
     */
    @Test
    fun `six hundred ticks of the level fight, animate, shoot and kill`() {
        val game = booted()
        val host = game.host
        val cues = HashMap<Int, Int>()
        val before = census(host)
        assertEquals(listOf(5, 12, 10), before, "the old roster: 5 orcs, 11 soldiers + a priest, 10 skeletons")

        var arrowsSeen = 0
        var walked = 0
        var attacked = 0
        var flinched = 0
        var flipped = 0
        repeat(PROBES) {
            host.run(TICKS / PROBES)
            game.drainCues(cues)
            arrowsSeen += host.world.family { all(Projectile) }.entities.size
            with(host.world) {
                host.world.family { all(CharacterView) }.forEach { entity ->
                    when (entity[CharacterView].state) {
                        UnitState.Walk -> walked++
                        UnitState.Attack -> attacked++
                        UnitState.Hit -> flinched++
                        else -> Unit
                    }
                    if (entity[CharacterView].flipX) flipped++
                }
            }
        }
        val after = census(host)

        assertTrue(after.sum() < before.sum(), "nothing died in $TICKS ticks: $before -> $after")
        for (team in before.indices) {
            assertTrue(after[team] <= before[team], "a team gained units: $before -> $after")
        }
        assertTrue(walked > 0, "no unit was ever in the walk animation, so nothing closed on anything")
        assertTrue(attacked > 0, "no unit was ever in the attack animation, so no ability ever fired")
        assertTrue(flinched > 0, "no unit was ever in the hit animation, so nothing was ever stunned")
        assertTrue(flipped > 0, "no unit ever faced left, so the whole field is drawn facing right")
        assertTrue(arrowsSeen > 0, "no arrow was ever in flight, so `ability/soldier_fire_arrow` is dead")
        assertTrue(
            (cues[MobaCues.MELEE_HIT] ?: 0) > 0,
            "no melee hit cue: abilities activated and none of them connected",
        )
    }

    /**
     * The priest heals, in the shipping level, driven by nothing but the game running.
     *
     * `CombatProofTest` proves the ability works when a fixture puts a wounded ally two units from
     * a priest. This proves the *level* produces that situation: the priest spawned by
     * `level/test_level` finds a soldier the orcs have hurt and casts on it.
     */
    @Test
    fun `the level's priest heals a wounded ally without anyone asking it to`() {
        val game = booted()
        val cues = HashMap<Int, Int>()
        repeat(PROBES) {
            game.host.run(TICKS / PROBES)
            game.drainCues(cues)
        }
        assertTrue(
            (cues[MobaCues.HEAL] ?: 0) > 0,
            "the priest never healed in $TICKS ticks of the real level, so `ability/priest_heal` " +
                "is granted and unreachable",
        )
    }

    /** Units per team id, counted from the world rather than from a spawn-side counter. */
    private fun census(host: GameHost): List<Int> {
        val counts = IntArray(TEAMS)
        with(host.world) {
            host.world.family { all(GameUnit) }.forEach { entity ->
                // Lane creeps are not the level's roster. `LaneModule` sends a wave of them
                // every ten seconds and they carry a real `GameUnit` team, exactly as every other
                // unit does, so a census that counted them would report the world growing and
                // would say nothing about the twenty-seven units this test is about. Skipped here
                // rather than spawned teamless, because `GameUnit.team` disagreeing with
                // `Combatant.teamId` is the invariant this very file pins.
                if (dev.wildware.moba.lane.LaneCreep in entity) return@forEach
                val team = entity[GameUnit].team
                if (team in counts.indices) counts[team]++
            }
        }
        return counts.toList()
    }

    private companion object {

        /** `Team.ORC`, `Team.SOLDIER`, `Team.UNDEAD`. */
        const val TEAMS: Int = 3

        /** Ten seconds at the default tick rate: the step the HTTP demo takes. */
        const val TICKS: Int = 600

        /** How many times the fight is sampled across [TICKS], so a state seen once is seen. */
        const val PROBES: Int = 12

        /** Long enough for the first `AttributeSystem` pass and the first mirror to have run. */
        const val SETTLE_TICKS: Int = 5

        /**
         * The corpus-to-world conversion this whole test exists because of.
         *
         * Named here so a reader who wonders why `reach` is 24 and `RANGE` is 32 has the factor
         * that produced the second in front of them.
         */
        val SCALE: Float = MobaScale.WORLD
    }
}
