package dev.wildware.hollow

import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.gas.AbilityPhase
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Issue #252's first acceptance criterion, headless: a swing damages a fox in range and not one out
 * of range, and the cooldown blocks the next swing until it is up.
 *
 * ## Both sides of every threshold
 *
 * [CombatRules.ATTACK_RANGE] and [CombatRules.BITE_REACH] are thresholds, and a threshold proved
 * from one side is a threshold anywhere - a swing that hit *everything* would pass a test that only
 * placed a fox inside the reach. So each pair here is two foxes set up identically but for the one
 * distance the rule reads: one [MARGIN] inside, one [MARGIN] outside. The same for the cooldown: the
 * swing that fires and the swing on the very next tick that does not.
 *
 * ## The whole game, and the real seam
 *
 * `PlayerScene` builds the real `HollowGame` definition with the real Box2D solver and the real
 * systems, and every control this test presses goes through the same `IntentSource` a keyboard and a
 * datagram go through. Nothing here reaches into a system: the test holds a key down, runs ticks and
 * reads the world.
 *
 * ## Damage is a roll, so every figure here is a band
 *
 * A swing does `ATTACK_DAMAGE + nextInt(Combat, ATTACK_SPREAD)`, so an assertion on a single number
 * would be an assertion about one draw of the `Combat` stream, and would move the day a system draws
 * from it in a different order. What is asserted is the band the rule defines, from both ends.
 */
class CombatTest {

    private val scene = PlayerScene(waves = null)

    @AfterTest
    fun close() {
        scene.close()
    }

    @Test
    fun `a swing damages a fox inside its reach`() {
        val player = scene.spawn()
        val fox = fox(CombatRules.ATTACK_RANGE - MARGIN)
        // The foxes must not walk out of the picture: they are placed on their own goal and never
        // decide again, so the only thing that can move one is the rule under test.
        scene.run(1)
        val before = scene.healthOf(fox)

        swingOnce(player)

        val taken = before - scene.healthOf(fox)
        assertTrue(
            taken >= CombatRules.ATTACK_DAMAGE && taken <= CombatRules.ATTACK_DAMAGE + CombatRules.ATTACK_SPREAD - 1,
            "a fox ${CombatRules.ATTACK_RANGE - MARGIN}m away took $taken, not ${CombatRules.ATTACK_DAMAGE}" +
                "..${CombatRules.ATTACK_DAMAGE + CombatRules.ATTACK_SPREAD - 1}",
        )
    }

    @Test
    fun `a swing does not touch a fox outside its reach`() {
        val player = scene.spawn()
        val fox = fox(CombatRules.ATTACK_RANGE + MARGIN)
        scene.run(1)
        val before = scene.healthOf(fox)

        swingOnce(player)

        assertEquals(
            before,
            scene.healthOf(fox),
            "a fox ${CombatRules.ATTACK_RANGE + MARGIN}m away - a tenth of a metre out of reach - was hit",
        )
    }

    @Test
    fun `holding the attack key swings once and then waits out the cooldown`() {
        scene.spawn()
        val fox = fox(CombatRules.ATTACK_RANGE - MARGIN)
        scene.run(1)
        val full = scene.healthOf(fox)

        // Held for the whole cooldown and one tick more. A swing every tick would be 36 hits.
        scene.attack = true
        scene.run(1)
        val afterFirst = scene.healthOf(fox)
        assertNotEquals(full, afterFirst, "holding attack did not swing at all")

        // Every tick from here to the last one before the cooldown is up: nothing more lands.
        repeat(CombatRules.ATTACK_COOLDOWN.count.toInt() - 1) { elapsed ->
            scene.run(1)
            assertEquals(
                afterFirst,
                scene.healthOf(fox),
                "a second swing landed ${elapsed + 1} ticks in, inside a ${CombatRules.ATTACK_COOLDOWN.count}-tick cooldown",
            )
        }

        // And the moment it is up, the held key swings again.
        scene.run(1)
        assertTrue(
            scene.healthOf(fox) < afterFirst,
            "the held key did not swing again on the tick the cooldown ended",
        )
    }

    @Test
    fun `the ready tick a client is sent is the cooldown effect itself`() {
        val player = scene.spawn()
        fox(CombatRules.ATTACK_RANGE - MARGIN)
        scene.run(1)

        scene.attack = true
        // Every tick of a whole cooldown and the swing after it, so a copy that was written once and
        // then drifted, or was never rewritten on the second swing, is caught rather than sampled past.
        repeat(CombatRules.ATTACK_COOLDOWN.count.toInt() + 2) {
            scene.run(1)
            val abilities = scene.abilitiesOf(player)
            val effects = scene.effectsOf(player)
            val remaining = scene.gas.activation
                .cooldownRemaining(abilities, effects, CombatRules.ATTACK_SLOT, scene.tick)
            val fromCopy = scene.playerOf(player).attackReady.ticksSince(scene.tick).coerceAtLeast(0L)
            assertEquals(
                remaining.toLong(),
                fromCopy,
                "at ${scene.tick}, Player.attackReady says $fromCopy ticks left and the cooldown effect says $remaining",
            )
        }
    }

    @Test
    fun `a dash carries the character along its facing and then stops`() {
        val player = scene.spawn()
        // East, held for a tick so the pose system writes the facing the dash will keep.
        scene.moveX = 1f
        scene.run(2)
        val start = scene.transformOf(player).x
        assertTrue(start > 0f, "the setup never walked the character east, so the dash has no facing to keep")

        scene.dash = true
        scene.run(1)
        scene.dash = false
        // Let go of the axis too: what moves the character from here is the dash alone.
        scene.moveX = 0f
        scene.run(CombatRules.DASH_TICKS.count.toInt())
        val dashed = scene.transformOf(player).x - start

        // A dash is `DASH_SPEED * DASH_TICKS / 60` metres, less whatever the solver's first tick has
        // already spent; a walk over the same ticks would be a quarter of that.
        val expected = CombatRules.DASH_SPEED * CombatRules.DASH_TICKS.count / TICK_RATE
        assertTrue(dashed > expected * 0.6f, "a dash covered $dashed, nothing like the $expected it asks for")
        assertTrue(dashed < expected * 1.4f, "a dash covered $dashed, more than the $expected it asks for")

        // And it ends: the tick after it, standing still with no axis held, the character stops.
        val stopped = scene.transformOf(player).x
        scene.run(TICK_RATE)
        assertEquals(stopped, scene.transformOf(player).x, DRIFT, "the character kept dashing after the dash ended")
    }

    @Test
    fun `a heal restores hit points and never past the maximum`() {
        val player = scene.spawn()
        scene.run(1)
        val max = scene.attributesOf(player).base(scene.combat.maxHealth)
        scene.setHealth(player, max - CombatRules.HEAL_AMOUNT - 5f)

        scene.heal = true
        scene.run(2)
        scene.heal = false

        assertEquals(max - 5f, scene.healthOf(player), EPSILON, "one heal did not restore ${CombatRules.HEAL_AMOUNT}")

        // Now from five below the ceiling: the same heal may not push a character over it.
        scene.run(CombatRules.HEAL_COOLDOWN.count.toInt())
        scene.heal = true
        scene.run(2)
        scene.heal = false
        assertEquals(max, scene.healthOf(player), EPSILON, "a heal took the character past its maximum health")
    }

    @Test
    fun `a fox bites only once it is within reach, and not on the way in`() {
        val player = scene.spawn(x = 0f, y = 0f)
        // Well outside the reach and chasing: it runs in, and the tick it first bites is the thing
        // under test. A fox parked out of reach would not do - a chasing fox closes to
        // `FoxBrain.BITE_RANGE`, which is inside `BITE_REACH`, so "out of reach for ever" is not a
        // state this game has. What it does have is a fox on its way in, and every tick of that
        // approach is a tick the reach has to refuse.
        val fox = fox(RUN_IN, x = 0f)
        scene.run(1)
        var health = scene.healthOf(player)

        var bitesInReach = 0
        var ticksRefusedOutOfReach = 0
        repeat(BITE_OBSERVE) {
            // The distance the bite system will read this tick: last tick's solved positions.
            val at = scene.transformOf(fox)
            val there = scene.transformOf(player)
            val away = kotlin.math.sqrt((at.x - there.x) * (at.x - there.x) + (at.y - there.y) * (at.y - there.y))
            scene.run(1)
            val now = scene.healthOf(player)
            if (now < health) {
                assertTrue(
                    away <= CombatRules.BITE_REACH,
                    "a fox bit from ${away}m, outside its ${CombatRules.BITE_REACH}m reach",
                )
                assertTrue(
                    health - now >= CombatRules.BITE_DAMAGE,
                    "a bite did ${health - now}, less than ${CombatRules.BITE_DAMAGE}",
                )
                bitesInReach++
            } else if (away > CombatRules.BITE_REACH) {
                ticksRefusedOutOfReach++
            }
            health = now
        }

        assertTrue(bitesInReach >= 2, "a fox at a player's heels bit $bitesInReach times in $BITE_OBSERVE ticks")
        assertTrue(
            ticksRefusedOutOfReach >= RUN_IN_TICKS,
            "only $ticksRefusedOutOfReach ticks were spent out of reach: the fox started inside it, so nothing was refused",
        )
        assertEquals(FoxMode.Chase, scene.foxOf(fox).mode, "the biting fox stopped chasing")
    }

    @Test
    fun `a fox killed by swings falls and is gone once its corpse has lain`() {
        val player = scene.spawn()
        val fox = fox(CombatRules.ATTACK_RANGE - MARGIN)
        scene.run(1)
        // Three swings short of dead, so the kill is done by a real swing rather than by this line.
        scene.setHealth(fox, CombatRules.ATTACK_DAMAGE + 1f)

        swingOnce(player)

        assertTrue(scene.healthOf(fox) <= 0f, "a fox on ${CombatRules.ATTACK_DAMAGE + 1} hit points survived a swing")
        scene.run(1)
        assertEquals(FoxMode.Dead, scene.foxOf(fox).mode, "a fox at zero health is not dead")
        assertTrue(scene.isLive(fox), "the corpse was taken away on the tick it died")

        scene.run(CombatRules.CORPSE.count.toInt())
        assertFalse(scene.isLive(fox), "a corpse was still in the world ${CombatRules.CORPSE.count} ticks after it died")
    }

    @Test
    fun `a dead character does not swing, does not move and does not draw a fox`() {
        val player = scene.spawn()
        val fox = fox(CombatRules.ATTACK_RANGE - MARGIN)
        scene.run(1)
        val foxHealth = scene.healthOf(fox)
        scene.setHealth(player, 0f)
        scene.run(1)
        val fell = scene.transformOf(player).x

        scene.attack = true
        scene.moveX = 1f
        scene.run(TICK_RATE)

        assertEquals(foxHealth, scene.healthOf(fox), "a dead character swung at a fox")
        assertEquals(fell, scene.transformOf(player).x, DRIFT, "a dead character walked")
        assertEquals(
            AbilityPhase.Inactive,
            scene.abilitiesOf(player).instanceAt(CombatRules.ATTACK_SLOT).phase,
            "a dead character has a swing in flight",
        )
        assertNotEquals(FoxMode.Chase, scene.foxOf(fox).mode, "a fox is still chasing a dead player")
    }

    /**
     * Holds the attack control for exactly one tick, which is what one swing is: the ability fires
     * on the tick the key is down and `AttackExec` lands its damage in the same tick's
     * `AttributeSystem`. Two ticks are run so the attribute write has landed before the caller reads it.
     */
    private fun swingOnce(player: NetId) {
        check(scene.playerOf(player).attack.not()) { "the attack control was already held" }
        scene.attack = true
        scene.run(1)
        scene.attack = false
    }

    /**
     * A fox [away] metres east of ([x], 0), standing on its own goal and never deciding again, so
     * nothing in the brain moves it but the rule under test.
     */
    private fun fox(away: Float, x: Float = 0f) =
        Fox.spawn(scene.world, scene.netIds, x = x + away, y = 0f, decideAt = NEVER)

    private companion object {

        /** A tenth of a metre: the same margin `FoxBehaviourTest` shows a threshold from. */
        const val MARGIN: Float = 0.1f

        /** Where the biting fox starts: inside `CHASE_RANGE`, well outside `BITE_REACH`. */
        const val RUN_IN: Float = 6f

        /**
         * The least number of ticks the reach must refuse before the first bite. The run-in is
         * `RUN_IN - BITE_REACH` = 4.6 metres at the fox's run, about 63 ticks; this is a floor well
         * under it, and its job is to fail a fox that started inside the reach and so refused nothing.
         */
        const val RUN_IN_TICKS: Int = 40

        /** The rate `HollowMovement` runs at, as a float, for the metres a dash covers. */
        const val TICK_RATE: Int = HollowMovement.TICK_RATE

        /** What the solver leaves behind: a body at rest drifts by less than this in a second. */
        const val DRIFT: Float = 0.02f

        /** Float comparison for hit points, which are whole numbers arrived at by addition. */
        const val EPSILON: Float = 0.001f

        /** Long enough to run in from [RUN_IN] and then bite twice: two cooldowns on top. */
        const val BITE_OBSERVE: Int = 320

        /** A `decideAt` no test reaches, so a placed fox keeps the goal it was given. */
        val NEVER: Tick = Tick(1_000_000L)
    }
}
