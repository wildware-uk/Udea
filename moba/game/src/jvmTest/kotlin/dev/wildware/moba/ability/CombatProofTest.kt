package dev.wildware.moba.ability

import dev.wildware.udea.gas.ActivationResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The three things this game has to be able to do, driven through a real running host.
 *
 * Each one names the attribute values before and after, because "an ability fired" is not the
 * claim - the claim is that a number a player can see moved by the amount the design says.
 */
class CombatProofTest {

    /**
     * A priest heals a damaged ally: 5 health every 15 ticks for 300 ticks, and it costs mana.
     *
     * The soldier is wounded to 40 of 100 and stands two units from the priest - inside
     * [PriestHealExec.RADIUS] and outside anything else's reach, so the only thing that can move
     * either number is the heal.
     */
    @Test
    fun `a priest heals a damaged ally over time and pays mana for it`() {
        val game = CombatFixture()
        val priest = game.spawn("priest", 0f, 0f)
        val ally = game.spawn("soldier", 2f, 0f)
        game.step(1)
        game.wound(ally, 40f)

        val healthBefore = game.health(ally)
        val manaBefore = game.mana(priest)
        assertEquals(40f, healthBefore, "the ally starts wounded")
        assertEquals(100f, manaBefore, "the priest starts with the mana its character asset gives it")

        // The cast lands its heal 24 ticks in; the heal then fires every fifteen ticks. 120 ticks
        // is the cast plus six periods.
        game.step(120)

        val healthAfter = game.health(ally)
        val manaAfter = game.mana(priest)

        assertTrue(healthAfter > healthBefore, "the ally must have been healed, was $healthAfter")
        assertEquals(
            manaBefore - MobaAbilities.PRIEST_HEAL_MANA_COST,
            manaAfter,
            "one cast costs exactly one heal's mana",
        )
        assertEquals(
            healthBefore + PERIODS_IN_120_TICKS * PriestHealExec.HEAL_PER_PERIOD,
            healthAfter,
            "five health per fifteen ticks, from the tick the heal landed",
        )
        assertTrue(game.cueCount(MobaCues.HEAL) > 0, "the heal has to emit a cue for anything to draw")
    }

    /**
     * An elite orc spins and damages several units at once, in one tick.
     *
     * Three skeletons stand inside [OrcSpinExec.RADIUS]; each has 50 health and takes
     * `strength * 1.5` = 30.
     */
    @Test
    fun `an elite orc spin damages every enemy in reach on the same tick`() {
        // Activated by hand, and with the skeletons' own abilities switched off. A spin is a
        // 66-tick cast and a melee hit stuns for 30, and `blockedBy` now genuinely cancels a
        // blocked cast - so three skeletons swinging back interrupt the spin before it lands,
        // every time. That is the game working correctly and it is a different test; this one is
        // about what the spin does when it goes off.
        val game = CombatFixture(autopilot = false)
        val orc = game.spawn("orc_elite", 0f, 0f)
        // A pack on one side, not a ring. The spin opens with a lunge at the nearest enemy - the
        // old ability did the same thing with an impulse - so an orc that started in the middle
        // of a ring has left two thirds of it behind by the time the blow lands 48 ticks later.
        val victims = listOf(
            game.spawn("skeleton", 0.6f, 0f),
            game.spawn("skeleton", 0.6f, 0.3f),
            game.spawn("skeleton", 0.6f, -0.3f),
        )
        game.step(1)

        val before = victims.map { game.health(it) }
        assertEquals(listOf(50f, 50f, 50f), before, "three skeletons at full health")
        assertEquals(20f, game.current(orc, game.module.attributes.strength))

        assertEquals(ActivationResult.Activated, game.activate(orc, SPECIAL_SLOT))
        // The spin lands on tick 48 of the activation, and the cast runs to 66, so this is after
        // the blow and before anything else this unit does could be confused for it.
        game.step(OrcSpinExec.HIT_TICK.toInt() + 4)

        val after = victims.map { game.health(it) }
        val expected = 50f - 20f * OrcSpinExec.DAMAGE_SCALE
        assertEquals(listOf(expected, expected, expected), after, "all three take the same blow")

        val damageTicks = game.cues.filter { it.cueId == MobaCues.DAMAGE }.map { it.tick }.distinct()
        assertEquals(1, damageTicks.size, "one spin is one tick's worth of damage, not three swings")

        val stunned = victims.count { victim ->
            game.effectsOf(victim).let { effects ->
                (0 until effects.count).any { slot ->
                    effects.defIndexAt(slot) == game.module.effects.stun
                }
            }
        }
        assertEquals(3, stunned, "the spin stuns everything it hits")
    }

    /**
     * A soldier fires an arrow, the arrow travels, and the arrow hits.
     *
     * The orc is three units away - inside [FireArrowExec.RANGE] and well outside melee - so the
     * only thing that can take health off it is something that crossed the gap.
     */
    @Test
    fun `a soldier fires an arrow that travels and hits`() {
        val game = CombatFixture()
        val soldier = game.spawn("soldier", 0f, 0f)
        val orc = game.spawn("orc", 3f, 0f)
        game.step(1)

        val before = game.health(orc)
        assertEquals(150f, before)
        assertEquals(0, game.projectileCount(), "no arrow before the shot")

        // Fired on tick 48 of the cast, spawned on the barrier the tick after.
        game.step(51)
        assertEquals(1, game.projectileCount(), "the arrow exists")
        val launchX = game.cues.first { it.cueId == MobaCues.ARROW_FIRED }.let { it.payload0 }
        assertEquals(1f, launchX, "it was aimed straight at the orc")

        // Three units at five units a second is thirty-six ticks of flight.
        game.step(40)

        val after = game.health(orc)
        assertEquals(0, game.projectileCount(), "the arrow is consumed by the hit")
        assertEquals(before - EXPECTED_ARROW_DAMAGE, after, "the arrow took ten health off the orc")
        assertEquals(1, game.cueCount(MobaCues.ARROW_HIT), "one arrow, one hit cue")
    }

    /**
     * A melee attack ends itself, comes off cooldown, and swings again.
     *
     * Driven by hand rather than by the autopilot, and with nothing that can hit back, because
     * this is a test about one ability's lifecycle: a brawl would cancel the swing with a stun
     * and the failure would look identical to the one this test exists to catch.
     *
     * It is the test that fails if `AbilityContext.endAbility` stops working: without it an exec
     * outside `udea-gas` cannot leave `AbilityPhase.Active`, so the slot is `AlreadyActive` for
     * the rest of the entity's life and the unit swings exactly once, ever.
     */
    @Test
    fun `a melee attack ends itself and comes round again`() {
        val game = CombatFixture(autopilot = false)
        val soldier = game.spawn("soldier", 0f, 0f)
        val victim = game.spawn("skeleton", 0.5f, 0f)
        game.step(1)

        assertEquals(ActivationResult.Activated, game.activate(soldier, MELEE_SLOT))
        game.step(MELEE_SWING_TICKS + 1)

        val victimHealth = game.health(victim)
        assertEquals(40f, victimHealth, "the first swing landed: 50 health less the soldier's 10")
        assertTrue(!game.isActive(soldier, MELEE_SLOT), "the swing has to end on its own")
        assertTrue(
            game.canActivate(soldier, MELEE_SLOT) is ActivationResult.OnCooldown,
            "and then it is on cooldown, not stuck active",
        )

        game.step(MobaAbilities.MELEE_COOLDOWN_TICKS - MELEE_SWING_TICKS)


        assertEquals(
            ActivationResult.Activated,
            game.canActivate(soldier, MELEE_SLOT),
            "48 ticks after the activation it is ready again",
        )
        assertEquals(ActivationResult.Activated, game.activate(soldier, MELEE_SLOT))
        game.step(MELEE_SWING_TICKS + 1)
        assertEquals(30f, game.health(victim), "the second swing landed too")
        assertEquals(2, game.cueCount(MobaCues.MELEE_SWOOSH), "two swings, two swooshes")
    }

    private companion object {

        /** 120 ticks after the cast starts, with the heal landing on tick 24 and firing per 15. */
        const val PERIODS_IN_120_TICKS: Float = 6f

        /** The soldier's strength of 10, at [FireArrowExec.DAMAGE_SCALE]. */
        const val EXPECTED_ARROW_DAMAGE: Float = 10f

        /** Slot 0 is every unit's basic attack. */
        const val MELEE_SLOT: Int = 0

        /** Slot 1 is the character's special, which is `Slot.B` in the character scripts. */
        const val SPECIAL_SLOT: Int = 1

        /**
         * [MeleeAttackExec.DURATION_TICKS], as an `Int` for [CombatFixture.step].
         *
         * Stepped as `+ 1` at each call site: activating reads `host.tick`, which is the tick the
         * loop will simulate *next*, so N steps after an activation cover elapsed `0 until N` and
         * the tick the swing ends on is the (N+1)th.
         */
        const val MELEE_SWING_TICKS: Int = 36
    }
}
