package dev.wildware.udea.render

import com.github.quillraven.fleks.World
import com.github.quillraven.fleks.configureWorld
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.core.fixtures.testGameContext
import dev.wildware.udea.core.gameContext
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.core.loop.WorldSimulation
import dev.wildware.udea.core.physics.PhysicsBody
import dev.wildware.udea.render.camera.CameraRig
import dev.wildware.udea.render.draw.AnimationRenderSystem
import dev.wildware.udea.render.draw.BackgroundRenderSystem
import dev.wildware.udea.render.draw.BitmapFont2D
import dev.wildware.udea.render.draw.DebugLabel
import dev.wildware.udea.render.draw.DebugLabels
import dev.wildware.udea.render.draw.DebugOverlayRenderSystem
import dev.wildware.udea.render.draw.SpriteRegion
import dev.wildware.udea.render.draw.SpriteRenderSystem
import dev.wildware.udea.render.draw.SpriteRenderer
import dev.wildware.udea.render.draw.SpriteTexture
import dev.wildware.udea.render.interp.InterpSnapshotSystem
import dev.wildware.udea.render.interp.Interpolator
import dev.wildware.udea.render.support.AllocationProbe
import dev.wildware.udea.render.support.testTargets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The per-frame allocation budget for the drawing path, measured rather than asserted in prose.
 *
 * ## Why this file exists
 *
 * `Interpolator`, `SpriteRenderSystem` and `DebugOverlayRenderSystem` each name a
 * `RenderAllocationTest` in their KDoc as the thing that "keeps that claim honest" — the reused
 * [dev.wildware.udea.render.interp.Pose], the comparator built once at bind, the reused label
 * `StringBuilder`. No such file existed. The stand-in, `DrawSystemPortTest`'s "steady state
 * rendering allocates no per-frame comparator or pose", asserts only that the draw count per
 * frame does not change, which cannot fail for the property it is named after.
 *
 * ## What is asserted, and what that rests on
 *
 * Two things, because either alone is weak. **Allocation does not grow with the number of
 * entities drawn** — ten times the entities, the same bytes. And **a hundred steady-state frames
 * allocate zero bytes**, which pins the constant part so it cannot quietly grow.
 *
 * The zero is a measurement, and it rests on two stated things. [AllocationProbe] needs
 * HotSpot's per-thread allocation counter and the tests return early without one, so the JVM
 * under measurement is always HotSpot; and it warms the block 200 times before measuring, so
 * the path is C2-compiled. On a JVM where the zero stops holding, this goes red and says
 * something true — it is not a number to relax.
 *
 * ## Measuring the real batch, not a fake
 *
 * Under LibGDX this used a hand-written `Batch` implementation that counted calls and allocated
 * nothing of its own, because a `java.lang.reflect.Proxy` recorder would have measured itself.
 * `SpriteBatch2D` is Udea's own now, its constructor is `internal` and reachable from this test
 * source set as a friend of `commonMain`, and its own KDoc states the property this test checks
 * - so there is nothing left to fake. [Fixture.bytesAcross] clears it at the top of every
 * measured frame, exactly as a real `FrameSurface` would, and the batch itself is measured
 * directly.
 *
 * ## What it does NOT catch, stated because the alternative is a second lie
 *
 * C2's escape analysis scalar-replaces allocations that do not escape their frame, and the
 * probe counts heap bytes, so this test is **blind to non-escaping garbage** and sensitive to
 * the escaping kind. That was checked by mutation rather than assumed on the LibGDX-era version
 * of this suite: reintroducing a `Vector3()` per entity in `DebugOverlayRenderSystem.drawLabels`
 * left it green because C2 proved it did not escape, while replacing `Family.sort` with an
 * `ArrayList` copy and a `sortWith` took it red on both assertions at once.
 *
 * So the honest scope is: *the drawing path allocates nothing the JIT cannot eliminate*. That
 * is the statement that matters operationally — a scalar-replaced object costs no GC — but it
 * is narrower than "no object is ever written in a draw loop".
 */
class RenderAllocationTest {

    @Test
    fun `a steady-state frame allocates no more for two hundred entities than for twenty`() {
        if (!AllocationProbe.isSupported) return

        val small = Fixture(entities = 20)
        val large = Fixture(entities = 200)

        val smallBytes = small.bytesPerFrame()
        val largeBytes = large.bytesPerFrame()

        // The fixtures have to be drawing, or this compares two empty frames.
        assertTrue(small.batch.instanceCount > 0, "the small fixture drew nothing")
        assertTrue(large.batch.instanceCount > small.batch.instanceCount, "the large fixture drew no more")

        assertEquals(
            smallBytes,
            largeBytes,
            "drawing ten times as many entities allocated ${largeBytes - smallBytes} extra " +
                "bytes per frame, so something on the per-entity path is allocating: a Pose, a " +
                "position, a String for a label, or a comparator per comparison",
        )
    }

    @Test
    fun `a hundred steady-state frames allocate nothing at all`() {
        if (!AllocationProbe.isSupported) return

        val fixture = Fixture(entities = 50)

        // A hundred frames per measurement rather than one, so that a residual of a single
        // sixteen-byte object per frame comes back as 1600 bytes and not as something a
        // rounding argument could explain away.
        val bytes = fixture.bytesAcross(frames = 100)

        assertEquals(
            0L,
            bytes,
            "a hundred frames allocated $bytes bytes. The drawing path is meant to allocate " +
                "nothing in steady state: the Pose, the comparator, the projection and the " +
                "label StringBuilder are all fields",
        )
    }

    /**
     * A populated pipeline over the real [dev.wildware.udea.render.draw.SpriteBatch2D].
     *
     * `CameraRig` is constructed and bound, and `advance` is called once before the measurement
     * so the projection is current — but it is **not** registered as a `RenderSystem`: nothing
     * about advancing it touches a render context on Kool (it is plain arithmetic, unlike the
     * LibGDX `Viewport.apply()` this fixture used to have to fake around), so there is nothing
     * left to keep out of the measured frame either way, and registering it would measure a
     * second thing this file is not about.
     */
    private class Fixture(entities: Int) {

        private val ctx: GameContext = testGameContext(seed = 3L)

        private val world: World = configureWorld {
            injectables { gameContext(ctx) }
            systems { add(InterpSnapshotSystem()) }
        }

        private val sim = WorldSimulation(ctx, world)

        private val netIds = NetIdIndex()

        private val frameTime = FixedFrameTime(1f / 60f)

        private val interpolator = Interpolator(ctx.clock, world.system<InterpSnapshotSystem>())

        private val rig = CameraRig(netIds, interpolator, frameTime).also { it.onBind(world, ctx) }

        private val targets = testTargets(width = 640, height = 360)

        /** The real batch every registered `RenderSystem` draws with. What this file measures. */
        val batch = targets.batch

        private val pipeline: RenderPipeline

        init {
            val sprite = SpriteRegion(fakeTexture(8, 8, "sprite"))
            repeat(entities) { index ->
                val entity = world.entity {
                    it += PhysicsBody(x = index.toFloat(), y = 0f)
                    it += SpriteRenderer(region = sprite, width = 2f, height = 2f, order = index)
                    it += DebugLabels(mutableListOf(DebugLabel("live", ctx.clock.tick + 1_000_000L)))
                }
                netIds.allocate(entity)
            }
            sim.step()

            val background = SpriteRegion(fakeTexture(4, 4, "background"))
            pipeline = RenderRegistry().apply {
                val time = frameTime
                register(RenderPhase.PreRender, { resources ->
                    BackgroundRenderSystem(resources, background)
                })
                register(RenderPhase.World, { AnimationRenderSystem(time) })
                register(RenderPhase.World, { resources ->
                    SpriteRenderSystem(resources, rig, interpolator)
                })
                register(RenderPhase.Debug, { resources ->
                    DebugOverlayRenderSystem(resources, rig, interpolator, netIds, BitmapFont2D.builtIn())
                })
            }.build(world, ctx, targets)

            rig.advance(targets.offscreen, alpha = 1f)
        }

        /** Smallest measured allocation of one `render`, after the path has been JIT-compiled. */
        fun bytesPerFrame(): Long = bytesAcross(frames = 1)

        /**
         * Smallest measured allocation of [frames] consecutive frames.
         *
         * `batch.clear()` opens every frame, exactly as `KoolSurface.begin()` does in
         * production. Skipping it would let `SpriteBatch2D`'s growable arrays run out of room
         * partway through the measured block and resize, which is a real allocation this test
         * would then wrongly blame on the renderers.
         */
        fun bytesAcross(frames: Int): Long =
            AllocationProbe.bytesAllocated(warmups = 200, attempts = 20) {
                repeat(frames) {
                    batch.clear()
                    pipeline.render(0.5f)
                }
            }

        /** A texture backed by real pixels and no GPU object, exactly as big as asked. */
        private fun fakeTexture(width: Int, height: Int, name: String): SpriteTexture =
            SpriteTexture.fromRgba(width, height, ByteArray(width * height * 4), name)
    }

    private class FixedFrameTime(override val frameSeconds: Float) : FrameTime
}
