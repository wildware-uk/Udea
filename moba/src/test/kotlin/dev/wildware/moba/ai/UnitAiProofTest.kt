package dev.wildware.moba.ai

import dev.wildware.moba.ability.CombatFixture
import dev.wildware.moba.ability.MobaCues
import dev.wildware.moba.ability.MobaScale
import dev.wildware.moba.ability.MobaUnits
import dev.wildware.moba.ability.Teams
import dev.wildware.udea.core.identity.NetId
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the AI decides, proved through a real running host rather than asserted about a mock.
 *
 * Every test here spawns its units far enough apart that **nothing can reach anything** -
 * `MeleeAttackExec.RANGE` is 0.8 corpus units and the pairs stand two apart - so the only thing
 * that can move a number is the decision under test. `CombatFixture` runs no
 * `UnitBattleSystem`, so nobody closes and nobody is knocked about: a position that changes
 * changed because the brain pushed it.
 */
class UnitAiProofTest {

    /**
     * The headline: a unit below ten health runs, and a Fearless one at the same health does not.
     *
     * Two isolated pairs a hundred corpus units apart, which is well outside
     * [UnitBrain.SIGHT_RADIUS], so neither pair can see the other:
     *
     * - a **soldier** on `SoldierTeam`, which the corpus gives no `aiTags` block, two units from
     *   an orc it cannot reach;
     * - an **orc** on `OrcTeam`, whose `character/orc.udea.kts` declares `AITag.Fearless`, two
     *   units from a skeleton it cannot reach.
     *
     * Both are wounded to nine, one below [UnitBrain.FLEE_HEALTH]. The soldier's separation from
     * its attacker grows; the orc's does not move by a float.
     */
    @Test
    fun `a wounded unit retreats and a fearless one at the same health holds`() {
        val game = CombatFixture()
        val runner = game.spawn("soldier", 0f, 0f)
        val runnersThreat = game.spawn("orc", 2f, 0f)
        val holder = game.spawn("orc", 100f, 0f)
        val holdersThreat = game.spawn("skeleton", 102f, 0f)
        game.step(1)

        game.wound(runner, WOUNDED)
        game.wound(holder, WOUNDED)
        val startedApart = 2f * MobaScale.WORLD
        assertEquals(startedApart, separation(game, runner, runnersThreat), TOLERANCE)
        assertEquals(startedApart, separation(game, holder, holdersThreat), TOLERANCE)

        game.step(RETREAT_TICKS)

        val ran = separation(game, runner, runnersThreat)
        val held = separation(game, holder, holdersThreat)
        assertEquals(WOUNDED, game.health(runner), "nothing is in reach of the runner, so it is still on nine")
        assertEquals(WOUNDED, game.health(holder), "nothing is in reach of the holder either")
        assertTrue(
            ran > startedApart + MobaScale.WORLD,
            "a soldier on $WOUNDED health should have run at least a character's width from the " +
                "orc beside it in $RETREAT_TICKS ticks; it is $ran world units away and started $startedApart",
        )
        assertEquals(
            startedApart,
            held,
            TOLERANCE,
            "an orc is AITag.Fearless, so on the same $WOUNDED health it holds its ground; it moved to $held",
        )
    }

    /**
     * A unit that is hurt but *not* routed keeps fighting: the flee gate is the health, not the wound.
     *
     * The same soldier, wounded to eleven instead of nine, is inside its own arrow's range of the
     * orc. It shoots rather than runs, which is what makes [UnitBrain.FLEE_HEALTH] a threshold
     * rather than a mood.
     */
    @Test
    fun `a wounded unit above the flee threshold fights instead of running`() {
        val game = CombatFixture()
        val soldier = game.spawn("soldier", 0f, 0f)
        val orc = game.spawn("orc", 2f, 0f)
        game.step(1)
        game.wound(soldier, UNROUTED)

        val stoodAt = game.positionOf(soldier).x
        game.step(RETREAT_TICKS)

        assertEquals(
            stoodAt,
            game.positionOf(soldier).x,
            TOLERANCE,
            "on $UNROUTED health, one above the threshold, the soldier does not take a step back",
        )
        assertTrue(
            game.cues.any { it.cueId == MobaCues.ARROW_FIRED && it.source == soldier },
            "and it uses the two units of separation to shoot: ${game.cues}",
        )
    }

    /**
     * Ranged past the sword's reach, melee inside it - the old `if (distance > .5F)` split.
     *
     * The soldier's arrow is slot 1 and its sword slot 0, so the system this replaced - which
     * fired the highest-numbered ready ability - loosed an arrow into an orc standing on top of
     * it. Half a corpus unit apart is inside `MeleeAttackExec.RANGE`; the first thing the soldier
     * does is swing.
     */
    @Test
    fun `a unit in melee range swings instead of shooting`() {
        val game = CombatFixture()
        val soldier = game.spawn("soldier", 0f, 0f)
        game.spawn("orc", 0.5f, 0f)
        game.step(1)

        game.step(MELEE_CHOICE_TICKS)

        val ownCues = game.cues.filter { it.source == soldier }
        assertTrue(
            ownCues.any { it.cueId == MobaCues.MELEE_SWOOSH },
            "point blank, the soldier swings: $ownCues",
        )
        assertTrue(
            ownCues.none { it.cueId == MobaCues.ARROW_FIRED },
            "and does not fire an arrow into something it is touching: $ownCues",
        )
    }

    /**
     * A priest heals an ally that is below half health, with nobody telling it to.
     *
     * The priest is at full health, so the old self-only rule
     * (`UnitAISystem.kt:50`, `health < maxHealth / 2`) would never have fired. There is no enemy
     * anywhere on the field, so nothing else can move the ally's health either.
     */
    @Test
    fun `a priest heals an ally below half health on its own`() {
        val game = CombatFixture()
        val priest = game.spawn("priest", 0f, 0f)
        val ally = game.spawn("soldier", 2f, 0f)
        game.step(1)
        game.wound(ally, BADLY_HURT)

        assertEquals(FULL_PRIEST_HEALTH, game.health(priest), "the priest itself is untouched")

        game.step(HEAL_TICKS)

        assertTrue(
            game.health(ally) > BADLY_HURT,
            "the ally was on $BADLY_HURT of 100 and is on ${game.health(ally)}",
        )
        assertEquals(
            FULL_PRIEST_MANA - PRIEST_HEAL_COST,
            game.mana(priest),
            TOLERANCE,
            "the priest paid for it",
        )
    }

    /**
     * And it does *not* heal an ally that is merely scratched.
     *
     * This is the half the system this replaced got wrong: its target policy asked
     * `requiresDamaged`, which is "below maximum", so one point of chip damage on a full-strength
     * soldier spent a ten-mana heal and a ten-second cooldown. The rule is half health.
     */
    @Test
    fun `a priest holds its heal for an ally that is only scratched`() {
        val game = CombatFixture()
        val priest = game.spawn("priest", 0f, 0f)
        val ally = game.spawn("soldier", 2f, 0f)
        game.step(1)
        game.wound(ally, SCRATCHED)

        game.step(HEAL_TICKS)

        assertEquals(
            SCRATCHED,
            game.health(ally),
            TOLERANCE,
            "an ally on $SCRATCHED of 100 is not worth a heal",
        )
        assertEquals(FULL_PRIEST_MANA, game.mana(priest), TOLERANCE, "so no mana was spent")
    }

    /**
     * `ability/passive_health_regen` is applied to the units whose kind declares a regen rate.
     *
     * It was declared by `MobaEffects`, carried per-character by `UnitKind.healthRegen`, and
     * applied by nothing at all - the module KDoc said so. The priest's `healthRegen = 2F` is now
     * two health a second.
     *
     * The priest is wounded to thirty of fifty, which is *above* half, so it does not heal itself
     * and the only thing that can move the number is the regen. The soldier is a hundred units
     * away - outside the heal radius, so it is a control rather than a patient - and declares no
     * regen, so it must not drift.
     */
    @Test
    fun `a unit with a regen rate regenerates and one without does not`() {
        val game = CombatFixture()
        val priest = game.spawn("priest", 0f, 0f)
        // Out of the priest's three-unit heal radius by two orders of magnitude, so the control
        // is a control: the only thing that could move its health is a regen it does not have.
        val soldier = game.spawn("soldier", 100f, 0f)
        game.step(1)
        game.wound(priest, REGEN_START)
        game.wound(soldier, REGEN_START)

        game.step(REGEN_TICKS)

        val regenerated = game.health(priest)
        assertTrue(
            regenerated > REGEN_START,
            "the priest regenerates 2 a second and started on $REGEN_START; it is on $regenerated",
        )
        assertEquals(
            REGEN_START,
            game.health(soldier),
            TOLERANCE,
            "a soldier declares healthRegen = 0, so it must not gain a hit point",
        )
    }

    /**
     * The team-keyed Fearless roster agrees with the tag the corpus actually declares.
     *
     * [AiRoster] answers by team because a live entity carries no character name (see its KDoc).
     * That is only safe while the corpus's Fearless set is exactly two whole teams, which this
     * asserts kind by kind: the day somebody adds a fearless soldier or a timid orc, this fails
     * and the per-kind field stops being optional.
     */
    @Test
    fun `the fearless roster agrees with the corpus tags`() {
        val game = CombatFixture()
        for (kind in MobaUnits.kinds(game.module.abilities)) {
            assertEquals(
                kind.name in AiRoster.FEARLESS_CHARACTERS,
                AiRoster.isFearless(kind.team),
                "${kind.name} is on team ${kind.team}",
            )
        }
        assertTrue(AiRoster.isFearless(Teams.NEUTRAL), "a unit with no enemies has nothing to run from")
    }


    private fun separation(game: CombatFixture, a: NetId, b: NetId): Float {
        val first = game.positionOf(a)
        val second = game.positionOf(b)
        val dx = first.x - second.x
        val dy = first.y - second.y
        return sqrt(dx * dx + dy * dy)
    }

    private companion object {

        /** One below [UnitBrain.FLEE_HEALTH]. */
        const val WOUNDED: Float = 9f

        /** One above it. */
        const val UNROUTED: Float = 11f

        /** Below half of a soldier's hundred. */
        const val BADLY_HURT: Float = 40f

        /** Damaged, but well above half. */
        const val SCRATCHED: Float = 90f

        /** Above half of a priest's fifty, so the priest will not heal itself. */
        const val REGEN_START: Float = 30f

        const val FULL_PRIEST_HEALTH: Float = 50f
        const val FULL_PRIEST_MANA: Float = 100f
        const val PRIEST_HEAL_COST: Float = 10f

        /** Two seconds of running. */
        const val RETREAT_TICKS: Int = 120

        /** Longer than one melee swing, so the first choice has been made and finished. */
        const val MELEE_CHOICE_TICKS: Int = 40

        /** The cast lands 24 ticks in and the heal-over-time fires every fifteen after that. */
        const val HEAL_TICKS: Int = 120

        /** Three regen periods of sixty ticks, and change. */
        const val REGEN_TICKS: Int = 200

        /** Floats compared over a couple of hundred ticks of integration. */
        const val TOLERANCE: Float = 0.01f
    }
}
