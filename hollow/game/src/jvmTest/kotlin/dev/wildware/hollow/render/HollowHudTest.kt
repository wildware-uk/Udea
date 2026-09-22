package dev.wildware.hollow.render

import dev.wildware.hollow.CombatRules
import dev.wildware.hollow.Fox
import dev.wildware.hollow.FoxWaves
import dev.wildware.hollow.PlayerScene
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.Ticks
import dev.wildware.udea.core.identity.NetId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #252's second acceptance criterion, in the half a headless test can reach: the numbers the
 * HUD shows are live and are the world's.
 *
 * [HollowHudModel] is the whole of the HUD except its drawing - the numbers a player reads off the
 * screen are exactly the ones asserted here, because `HollowHudScreen` is handed this state object
 * and prints it. That the panels reach the *capture* is a GL claim and is `HollowHudGlTest`'s;
 * that the numbers are right is this one's.
 *
 * Nothing here constructs a fake world. The model is sampled from a real `HollowGame` with a real
 * fight in it, through the same call `HollowHudSystem` makes once a frame.
 */
class HollowHudTest {

    private val scene = PlayerScene(waves = WAVES)

    private val model: HollowHudModel by lazy { HollowHudModel(scene.combat, WAVES) }

    @AfterTest
    fun close() {
        scene.close()
    }

    @Test
    fun `health on the HUD is the character's own health, as it changes`() {
        val player = scene.spawn()
        scene.run(1)
        val max = scene.attributesOf(player).base(scene.combat.maxHealth)

        sample(player)
        assertTrue(model.state.present, "the HUD says there is no player when there is one")
        assertEquals(max, model.state.health, "a fresh character's HUD does not show full health")
        assertEquals(max, model.state.maxHealth, "the HUD shows no maximum to read the health against")
        assertFalse(model.state.dead, "a fresh character's HUD says it is dead")

        scene.setHealth(player, max - 40f)
        sample(player)
        assertEquals(max - 40f, model.state.health, "the HUD did not follow the health down")

        scene.setHealth(player, 0f)
        scene.run(1)
        sample(player)
        assertEquals(0f, model.state.health, "the HUD did not follow the health to zero")
        assertTrue(model.state.dead, "a character on zero hit points is not shown as dead")
    }

    @Test
    fun `each slot counts its own cooldown down to zero`() {
        val player = scene.spawn()
        // A fox in reach, so the swing has something to hit and is not a swing at nothing.
        Fox.spawn(scene.world, scene.netIds, x = CombatRules.ATTACK_RANGE - 0.1f, y = 0f, decideAt = NEVER)
        scene.run(1)

        sample(player)
        for (slot in 0 until CombatRules.PLAYER_SLOTS) {
            assertEquals(0, model.state.remainingAt(slot), "slot $slot was cooling before anything was used")
        }

        scene.attack = true
        scene.run(1)
        scene.attack = false
        sample(player)
        val started = model.state.remainingAt(CombatRules.ATTACK_SLOT)
        assertEquals(
            CombatRules.ATTACK_COOLDOWN.count.toInt() - 1,
            started,
            "the swing's slot does not show its whole cooldown the tick after it fired",
        )
        // The other two are untouched: a HUD that showed one number for every slot would pass an
        // assertion about the slot that was used.
        assertEquals(0, model.state.remainingAt(CombatRules.DASH_SLOT), "using the swing cooled the dash")
        assertEquals(0, model.state.remainingAt(CombatRules.HEAL_SLOT), "using the swing cooled the heal")

        // And it counts down, tick by tick, and stops at zero rather than going negative.
        repeat(started) { elapsed ->
            scene.run(1)
            sample(player)
            assertEquals(
                started - elapsed - 1,
                model.state.remainingAt(CombatRules.ATTACK_SLOT),
                "the swing's slot did not count down ${elapsed + 1} ticks in",
            )
        }
        scene.run(10)
        sample(player)
        assertEquals(0, model.state.remainingAt(CombatRules.ATTACK_SLOT), "a slot that is ready shows ticks left")
    }

    @Test
    fun `the wave number is the schedule's, counted from the tick the HUD is asked about`() {
        val player = scene.spawn()
        scene.run(1)

        sample(player, at = Tick(0L))
        assertEquals(0L, model.state.wave, "a wave had arrived before the first one was due")
        sample(player, at = WAVES.first - 1L)
        assertEquals(0L, model.state.wave, "the wave arrived a tick early")
        sample(player, at = WAVES.first)
        assertEquals(1L, model.state.wave, "the first wave did not arrive on the tick it is scheduled for")
        sample(player, at = WAVES.first + WAVES.every.count - 1L)
        assertEquals(1L, model.state.wave, "the second wave arrived a tick early")
        sample(player, at = WAVES.first + WAVES.every.count)
        assertEquals(2L, model.state.wave, "the second wave did not arrive on its tick")
    }

    @Test
    fun `the score is zero, because scoring is issue 253`() {
        val player = scene.spawn()
        scene.run(1)
        sample(player)
        assertEquals(0, model.state.score, "something is already scoring in H4")
    }

    @Test
    fun `a HUD with no character yet shows the wave and nothing else`() {
        scene.spawn()
        scene.run(1)

        // What a client's HUD holds between the window opening and the server naming its character.
        sample(NetId.NONE, at = WAVES.first)

        assertFalse(model.state.present, "the HUD claims a player it was never given")
        assertEquals(1L, model.state.wave, "the wave strip went blank because the character was not known")
        for (slot in 0 until CombatRules.PLAYER_SLOTS) {
            assertEquals(0, model.state.remainingAt(slot), "slot $slot was cooling with no character to cool")
        }
    }

    private fun sample(character: NetId, at: Tick = scene.tick) {
        model.sample(scene.world, scene.netIds, character, at)
    }

    private companion object {

        /** A first wave a second in, then one every five seconds. */
        val WAVES: FoxWaves = FoxWaves(first = Tick(60L), every = Ticks(300L), size = 2, growth = 1, cap = 8)

        /** A `decideAt` no test reaches, so a placed fox keeps the goal it was given. */
        val NEVER: Tick = Tick(1_000_000L)
    }
}
