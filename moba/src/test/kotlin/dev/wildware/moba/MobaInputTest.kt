package dev.wildware.moba

import com.badlogic.gdx.Input
import dev.wildware.moba.entry.MobaEntry
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.render.input.DeviceIntent
import dev.wildware.udea.render.input.InjectedIntent
import dev.wildware.udea.render.input.IntentState
import dev.wildware.udea.render.input.KeyboardState
import dev.wildware.udea.render.interp.Pose
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * WASD moves the player, and an agent moves the *same* player by the same route.
 *
 * ## Why this test is over a whole `GameHost` and not over a system
 *
 * The thing that was broken was never one system: it was the absence of a path from a key to a
 * coordinate. `moba` had one blueprint, no input, and an entry point that opened a window on a
 * simulation nothing could touch. So this drives the real definition - the real module list, the
 * real phase ordering, the real level - and asserts that a key produces movement, because a test
 * over `PlayerControlSystem` alone would have passed on the day nothing registered it.
 *
 * It runs [RenderMode.Headless]: there is no window, no GL context and no `Gdx.input` in the
 * process. That is the second claim, and it is the one the old engine could not make at all.
 */
class MobaInputTest {

    /** D walks right, and only right. */
    @Test
    fun `a key press moves the player`() {
        val fixture = Fixture()
        val before = fixture.playerPosition()

        fixture.keys.hold(Input.Keys.D)
        fixture.host.run(TICKS)

        val after = fixture.playerPosition()
        assertTrue(
            after.x > before.x + 1f,
            "holding D for $TICKS ticks moved the player from ${before.x} to ${after.x}",
        )
        assertEquals(before.y, after.y, absoluteTolerance = 1e-3f, message = "D moved the player vertically")
    }

    /** W walks up: positive y, matching world space rather than screen space. */
    @Test
    fun `W walks up the screen`() {
        val fixture = Fixture()
        val before = fixture.playerPosition()

        fixture.keys.hold(Input.Keys.W)
        fixture.host.run(TICKS)

        assertTrue(fixture.playerPosition().y > before.y + 1f, "W did not walk up")
    }

    /** Both keys at once is what a player means by both: nothing. */
    @Test
    fun `opposing keys leave the player where it is`() {
        val fixture = Fixture()
        val before = fixture.playerPosition()

        fixture.keys.hold(Input.Keys.A)
        fixture.keys.hold(Input.Keys.D)
        fixture.host.run(TICKS)

        val after = fixture.playerPosition()
        assertEquals(before.x, after.x, absoluteTolerance = 1e-3f)
    }

    /** Let go and the character stops. It sounds trivial; a stuck axis is the classic input bug. */
    @Test
    fun `releasing the key stops the player`() {
        val fixture = Fixture()
        fixture.keys.hold(Input.Keys.D)
        fixture.host.run(TICKS)
        fixture.keys.release(Input.Keys.D)
        fixture.host.run(1)

        val settled = fixture.playerPosition().x
        fixture.host.run(TICKS)

        assertEquals(settled, fixture.playerPosition().x, absoluteTolerance = 1e-3f)
    }

    /**
     * The claim the agent's input tools rest on, run as an experiment.
     *
     * Two processes, one driven by a keyboard and one by an [InjectedIntent] deflecting the same
     * axis, land the player on the same coordinate. If the agent's input reached the simulation by
     * any other path - a synthetic key event posted into LibGDX, say - these would agree only by
     * coincidence, and in `Headless` the agent's would not move at all.
     */
    @Test
    fun `an agent driving the injected source moves the player exactly as a keyboard does`() {
        val byKeyboard = Fixture()
        val start = byKeyboard.playerPosition()
        byKeyboard.keys.hold(Input.Keys.D)
        byKeyboard.keys.hold(Input.Keys.W)
        byKeyboard.host.run(TICKS)

        val byAgent = Fixture()
        val injected = InjectedIntent(MobaControls.BINDINGS.catalog)
        byAgent.host.ctx[IntentState.KEY].source = injected
        // The same vector a diagonal on the keyboard produces: the keyboard's own clamp brings
        // (1,1) to length one, and an agent asking for full deflection asks for the same thing.
        val diagonal = 1f / kotlin.math.sqrt(2f)
        injected.setAxis(MobaControls.MOVE_AXIS, diagonal, diagonal)
        byAgent.host.run(TICKS)

        val fromKeys = byKeyboard.playerPosition()
        val fromAgent = byAgent.playerPosition()
        assertTrue(
            abs(fromKeys.x - fromAgent.x) < 1e-3f && abs(fromKeys.y - fromAgent.y) < 1e-3f,
            "a keyboard put the player at (${fromKeys.x}, ${fromKeys.y}) and an agent put it at " +
                "(${fromAgent.x}, ${fromAgent.y}); they are not the same path",
        )
        assertTrue(
            fromKeys.x > start.x + 1f,
            "neither input moved the player at all: it is still at ${fromKeys.x}",
        )
    }

    /**
     * The player's unit is not also walked by the AI.
     *
     * `UnitBattleSystem` closes every unit on its nearest enemy. Applied to the unit a human is
     * steering, that adds a second force to whatever they asked for - a character that slides
     * toward an orc while you hold nothing, which reads as broken controls rather than as an AI
     * decision. With no key held the player must be exactly where it was.
     */
    @Test
    fun `the AI does not walk the player`() {
        val fixture = Fixture()
        val before = fixture.playerPosition()

        fixture.host.run(TICKS * 2)

        val after = fixture.playerPosition()
        assertEquals(before.x, after.x, absoluteTolerance = 1e-3f, message = "the AI walked the player")
        assertEquals(before.y, after.y, absoluteTolerance = 1e-3f, message = "the AI walked the player")
    }

    /**
     * The camera can find the player, which is what `render.follow_entity` asks before accepting.
     *
     * `CameraRig.followability` resolves the id and asks its [dev.wildware.udea.render.interp.PoseSource]
     * for a pose; a `false` there is the difference between `entity_not_followable` and a follow
     * that works. `moba` used to have no pose for any unit - the only reader was the physics one -
     * so the answer was always "no" and the tool reported `ok` regardless. This is that predicate,
     * over the real player entity.
     */
    @Test
    fun `the player has a pose the camera can follow`() {
        val fixture = Fixture()
        val entity = fixture.host.ctx[CoreModule.NET_IDS].resolveOrNull(fixture.player)
        val pose = Pose()

        assertTrue(entity != null, "the seeded player id resolves to nothing")
        assertTrue(
            PositionPoses.poseOf(fixture.host.world, entity, alpha = 1f, into = pose),
            "the player has no pose, so following it would move the camera nowhere",
        )
        assertEquals(fixture.playerPosition().x, pose.x)
    }

    /** A real host, seeded with the real level, driven by a keyboard nobody is at. */
    private class Fixture {

        val host: GameHost = MobaGame.host(RenderMode.Headless)

        val player: NetId

        val keys = FakeKeys()

        init {
            player = MobaEntry.seed(host)
            host.ctx[IntentState.KEY].source = DeviceIntent(MobaControls.BINDINGS, keys)
            // One tick so the first sample has happened and the player is settled before a test
            // records a "before" position.
            host.run(1)
        }

        /**
         * A **copy** of where the player is, and the `copy` is load-bearing.
         *
         * `entity[Position]` hands back the live component, so a `before` and an `after` taken
         * either side of a `run` are the same object and compare equal whatever moved. This test
         * passed for a broken game once already for exactly that reason.
         */
        fun playerPosition(): Pose {
            val entity = host.ctx[CoreModule.NET_IDS].resolveOrNull(player)
                ?: error("the player entity is gone")
            val position = with(host.world) { entity[Position] }
            return Pose(position.x, position.y)
        }
    }

    /**
     * A keyboard a test can hold keys on.
     *
     * The whole device surface is four methods, which is the point of [KeyboardState] being an
     * interface: none of this needs a window, and in the old engine none of it could be written.
     */
    private class FakeKeys : KeyboardState {

        private val down = HashSet<Int>()
        private val presses = HashMap<Int, Int>()

        fun hold(keycode: Int) {
            down += keycode
            presses[keycode] = (presses[keycode] ?: 0) + 1
        }

        fun release(keycode: Int) {
            down -= keycode
        }

        override fun isKeyDown(keycode: Int): Boolean = keycode in down

        override fun pressesSince(keycode: Int): Int = presses[keycode] ?: 0

        override fun endSample() {
            presses.clear()
        }
    }

    private companion object {

        /** Long enough for movement to be unambiguous, short enough that the fight has not moved on. */
        const val TICKS: Int = 10
    }
}
