package dev.wildware.hollow.desktop.gl

import com.github.quillraven.fleks.World
import dev.wildware.hollow.HollowGame
import dev.wildware.hollow.Player
import dev.wildware.hollow.desktop.HollowLaunch
import dev.wildware.hollow.desktop.HudPanels
import dev.wildware.hollow.render.HollowHudSource
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.loop.BarrierAction
import dev.wildware.udea.core.loop.barrier
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.render.capture.CaptureRequest
import dev.wildware.udea.render.capture.FrameCaptureSlot
import dev.wildware.udea.render.capture.capture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The HUD lived with for a quarter of a minute of real frames, in one [RenderMode] (issue #252,
 * and the rule #275 wrote).
 *
 * ## Why this exists, and why two frames would not do
 *
 * #275: registering an `OverlaySystem` closed the render pipeline about eight seconds in - a
 * windowed game shut itself, exit 0, nothing logged - and the only test of that hook drew two
 * frames, so it passed. Hollow's HUD is a `RenderSystem` that composes a ComposeGL screen on the
 * render thread on **every** frame, for as long as a game is played, so the same failure would look
 * exactly the same here: a green suite, and a game that closes itself while somebody is playing it.
 *
 * So this runs the real launcher - [HollowLaunch.start], the same public call `:hollow:desktop:run`
 * makes, with the HUD registered the way a launcher registers it - for [SOAK_SECONDS] of wall-clock
 * frames, and asks three things that a dead pipeline cannot answer:
 *
 * - **frames kept arriving**, sampled every second, so a loop that stops half way through fails
 *   naming the second it stopped on rather than being averaged away by the seconds before it;
 * - **nothing was swallowed**: a throwable out of the frame callback is kept and re-asserted at the
 *   end, because a render loop that ends on an exception is the failure this test is named after;
 * - **the HUD is still in the captured frame at the end**, read through the same [HudPanels] the
 *   fight shot reads - so "still drawing" means the world pass and the captured interface both
 *   still ran, not merely that a counter moved.
 *
 * ## One mode per class, because one context per JVM
 *
 * Kool allows one `KoolContext` per process for the life of the process, so a mode is a class and
 * `udeaHollowGlTest` runs each in a JVM of its own (`forkEvery = 1`) - the arrangement
 * `udea-render`'s `udeaGlTest` and `udea-editor`'s `udeaEditorGlTest` use, for the same reason.
 * Everything but [mode] is here, so the two cannot drift apart.
 */
internal abstract class HollowHudSoak {

    /** Which mode this class soaks. `Headless` has no context and no HUD, so it is not one of them. */
    protected abstract val mode: RenderMode

    /** Frames the render loop has completed. Written on the render thread, read by the test. */
    @Volatile
    private var frames: Long = 0L

    /** The first throwable out of the frame callback, or null. Never swallowed: see the KDoc. */
    @Volatile
    private var crash: Throwable? = null

    protected fun soak() {
        GlAvailabilityHere.require()
        val started = HollowLaunch.start(mode)
        try {
            val host = started.host
            HollowGame.seed(host)
            started.backend.drive { delta ->
                try {
                    host.frame(delta)
                    frames++
                } catch (failure: Throwable) {
                    // Kept rather than rethrown into the loop: the point of this test is that a
                    // failure inside a per-frame hook is reported, and a rethrow here is exactly
                    // what #275 found nobody sees.
                    if (crash == null) crash = failure
                    throw failure
                }
            }
            val slot = checkNotNull(started.backend.pipeline?.capture) { "the pipeline has no capture slot" }
            val character = spawnCharacter(host, slot)
            // Whose HUD, and as of when: what a launcher sets once the character is known.
            started.scene.hudSource = object : HollowHudSource {
                override val character: NetId = character
                override val now: Tick get() = host.tick
            }
            started.backend.onRenderThread { started.follow(character) }

            var lastFrames = frames
            var lastTick = host.tick
            for (second in 1..SOAK_SECONDS) {
                Thread.sleep(MILLIS_PER_SECOND)
                val now = frames
                assertTrue(
                    now > lastFrames,
                    "$mode: the render loop stopped drawing in second $second of $SOAK_SECONDS " +
                        "(still $now frames); ${crash ?: "no exception was raised"}",
                )
                assertTrue(
                    host.tick > lastTick,
                    "$mode: the simulation stopped in second $second of $SOAK_SECONDS at $lastTick",
                )
                lastFrames = now
                lastTick = host.tick
            }

            assertNull(crash, "$mode: a frame raised ${crash?.let { "${it::class.simpleName}: ${it.message}" }}")
            assertTrue(
                frames >= MINIMUM_FRAMES,
                "$mode: only $frames frames in $SOAK_SECONDS seconds, which is not a game anybody is playing",
            )
            // Alive: `onRenderThread` throws if the render thread has gone, which is the fact a
            // frame counter alone cannot establish.
            val aliveAt = started.backend.onRenderThread { host.tick }
            assertTrue(aliveAt >= lastTick, "$mode: the render thread answered $aliveAt, behind $lastTick")

            // And still drawing the HUD into the capture, after all that.
            val png = slot.capture(CaptureRequest(afterTick = host.tick + CAPTURE_MARGIN)).bytes
            assertEquals(
                emptyList(),
                HudPanels.missingFrom("the closing capture", png),
                "$mode: after $SOAK_SECONDS seconds the HUD is no longer in the captured frame",
            )
        } finally {
            started.close()
        }
    }

    /**
     * Spawns one character through the barrier - the way everything outside a system mutates this
     * engine's world - and hands back its `NetId`, having waited for the tick that drained it.
     */
    private fun spawnCharacter(host: GameHost, slot: FrameCaptureSlot): NetId {
        val action = SpawnCharacter()
        host.ctx.barrier.submit(action)
        var waited = 0
        while (action.character == NetId.NONE && waited < FRAME_BUDGET) {
            slot.capture(CaptureRequest())
            waited++
        }
        return checkNotNull(action.character.takeIf { it != NetId.NONE }) {
            "the character was never spawned in $FRAME_BUDGET frames"
        }
    }

    /** Puts one character in the middle of the clearing, on the tick the barrier is drained. */
    private class SpawnCharacter : BarrierAction {

        override val label: String get() = "hollow.soak.spawn"

        @Volatile
        var character: NetId = NetId.NONE
            private set

        override fun apply(world: World, ctx: GameContext) {
            character = Player.spawn(world, ctx[CoreModule.NET_IDS], x = 0f, y = 0f)
        }
    }

    private companion object {

        /**
         * How long a soak is. Longer than the eight seconds #275's overlay survived, and long
         * enough that a leak per frame has thousands of frames to show itself in.
         */
        const val SOAK_SECONDS: Int = 16

        const val MILLIS_PER_SECOND: Long = 1_000L

        /**
         * The fewest frames a soak may end on. Ten a second: far under what llvmpipe manages on
         * this clearing, and far over what a pipeline drawing one frame and stopping would reach.
         */
        const val MINIMUM_FRAMES: Long = SOAK_SECONDS * 10L

        /** Ticks past now the closing capture waits for, so it is a frame drawn after the soak. */
        const val CAPTURE_MARGIN: Long = 2L

        const val FRAME_BUDGET: Int = 600

    }
}

/**
 * The HUD soaked in `Offscreen`: a real Kool context with no window, which is what an agent and
 * every capture-taking harness runs. See [HollowHudSoak].
 */
internal class HollowHudOffscreenSoakTest : HollowHudSoak() {

    override val mode: RenderMode = RenderMode.Offscreen

    @Test
    fun `the HUD draws for sixteen seconds of offscreen frames and the game is still alive`() {
        soak()
    }
}

/**
 * The HUD soaked in `Windowed`: a real Kool context with a visible window, which is what a player
 * runs and what #275's overlay closed after eight seconds. See [HollowHudSoak].
 */
internal class HollowHudWindowedSoakTest : HollowHudSoak() {

    override val mode: RenderMode = RenderMode.Windowed

    @Test
    fun `the HUD draws for sixteen seconds of windowed frames and the game is still alive`() {
        soak()
    }
}
