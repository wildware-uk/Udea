package dev.wildware.udea.render

import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.TextureRegion
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
import dev.wildware.udea.render.draw.DebugLabel
import dev.wildware.udea.render.draw.DebugLabels
import dev.wildware.udea.render.draw.DebugOverlayRenderSystem
import dev.wildware.udea.render.draw.SpriteRenderSystem
import dev.wildware.udea.render.draw.SpriteRenderer
import dev.wildware.udea.render.interp.InterpSnapshotSystem
import dev.wildware.udea.render.interp.Interpolator
import dev.wildware.udea.render.support.AllocationProbe
import dev.wildware.udea.render.support.CountingBatch
import dev.wildware.udea.render.support.HeadlessGl
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
 * [dev.wildware.udea.render.interp.Pose], the comparator built once at bind, the reused
 * `Vector3`. No such file existed. The stand-in, `DrawSystemPortTest`'s "steady state rendering
 * allocates no per-frame comparator or pose", asserts only that the draw count per frame does
 * not change, which cannot fail for the property it is named after.
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
 * ## What it does NOT catch, stated because the alternative is a second lie
 *
 * C2's escape analysis scalar-replaces allocations that do not escape their frame, and the
 * probe counts heap bytes, so this test is **blind to non-escaping garbage** and sensitive to
 * the escaping kind. That was checked by mutation rather than assumed. Reintroducing a
 * `Vector3()` per entity in `DebugOverlayRenderSystem.drawLabels`, or the shipped
 * `labels.messages.removeAll { ... }` with its capturing lambda, both leave this **green**:
 * C2 proves neither escapes. Replacing `Family.sort` with an `ArrayList` copy and a `sortWith`
 * takes it red on both assertions at once — 44 000 bytes per hundred frames, and 1 296 extra
 * bytes per frame at 200 entities against 20.
 *
 * So the honest scope is: *the drawing path allocates nothing the JIT cannot eliminate*. That
 * is the statement that matters operationally — a scalar-replaced object costs no GC — but it
 * is narrower than "no object is ever written in a draw loop", and the three KDocs that cite
 * this file should be read against it. The per-entity `String` and the capturing lambda that
 * `DebugOverlayRenderSystem` shipped are gone anyway, and are gone because they were found by
 * reading the code while writing this, not because this test failed on them.
 */
class RenderAllocationTest {

    @Test
    fun `a steady-state frame allocates no more for two hundred entities than for twenty`() {
        if (!AllocationProbe.isSupported) return

        val small = build(entities = 20)
        val large = build(entities = 200)

        val smallBytes = small.bytesPerFrame()
        val largeBytes = large.bytesPerFrame()

        // The fixtures have to be drawing, or this compares two empty frames.
        assertTrue(small.batch.drawCalls > 0, "the small fixture drew nothing")
        assertTrue(large.batch.drawCalls > small.batch.drawCalls, "the large fixture drew no more")

        assertEquals(
            smallBytes,
            largeBytes,
            "drawing ten times as many entities allocated ${largeBytes - smallBytes} extra " +
                "bytes per frame, so something on the per-entity path is allocating: a Pose, a " +
                "Vector3, a String for a label, or a comparator per comparison",
        )
    }

    @Test
    fun `a hundred steady-state frames allocate nothing at all`() {
        if (!AllocationProbe.isSupported) return

        val fixture = build(entities = 50)

        // A hundred frames per measurement rather than one, so that a residual of a single
        // sixteen-byte object per frame comes back as 1600 bytes and not as something a
        // rounding argument could explain away.
        val bytes = fixture.bytesAcross(frames = 100)

        assertEquals(
            0L,
            bytes,
            "a hundred frames allocated $bytes bytes. The drawing path is meant to allocate " +
                "nothing in steady state: the Pose, the comparator, the projection Matrix4 and " +
                "the label StringBuilder are all fields",
        )
    }

    /**
     * Builds a fixture with a fake `Gdx` installed, and takes it away again before anything is
     * measured.
     *
     * Construction genuinely needs one: `CameraRig.fitTo` calls `Viewport.update`, which reaches
     * `Gdx.gl` through `HdpiUtils`. The **frame** must not, and the `uninstall` is what proves
     * it — [HeadlessGl] is a reflective proxy, so every call through it allocates an argument
     * array and boxes each float, and a measured frame that touched one would be measuring the
     * double. With the statics nulled, a frame that reaches for `Gdx` gets a
     * `NullPointerException` instead of a quietly inflated number.
     */
    private fun build(entities: Int): Fixture {
        val gl = HeadlessGl.installed(width = 640, height = 360)
        try {
            return Fixture(entities)
        } finally {
            gl.uninstall()
        }
    }

    /**
     * A populated pipeline over a batch that allocates nothing of its own.
     *
     * `CameraRig` is constructed and bound, and `advance` is called once before the measurement
     * so the camera matrices are current — but it is **not** registered as a `RenderSystem`,
     * because `CameraRig.render` ends in `viewport.apply()`, which reaches `Gdx.gl` and can
     * only be answered here by a reflective proxy that allocates on every call. Measuring
     * through one would measure the double. `ParticleRenderSystem` is left out for the same
     * kind of reason: a real `ParticleEffect` allocates inside LibGDX on every update.
     */
    private class Fixture(entities: Int) {

        val batch = CountingBatch()

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

        private val targets = testTargets(batch = batch, width = 640, height = 360)

        private val pipeline: RenderPipeline

        init {
            repeat(entities) { index ->
                val entity = world.entity {
                    it += PhysicsBody(x = index.toFloat(), y = 0f)
                    it += SpriteRenderer(region = SizedRegion(8, 8), width = 2f, height = 2f, order = index)
                    it += DebugLabels(mutableListOf(DebugLabel("live", ctx.clock.tick + 1_000_000L)))
                }
                netIds.allocate(entity)
            }
            sim.step()

            pipeline = RenderRegistry().apply {
                val time = frameTime
                register(RenderPhase.PreRender, { resources ->
                    BackgroundRenderSystem(resources, SizedRegion(4, 4))
                })
                register(RenderPhase.World, { AnimationRenderSystem(time) })
                register(RenderPhase.World, { resources ->
                    SpriteRenderSystem(resources, rig, interpolator)
                })
                register(RenderPhase.Debug, { resources ->
                    DebugOverlayRenderSystem(resources, rig, interpolator, netIds, ReusedLayoutFont())
                })
            }.build(world, ctx, targets)

            rig.advance(targets.offscreen, alpha = 1f)
        }

        /** Smallest measured allocation of one `render`, after the path has been JIT-compiled. */
        fun bytesPerFrame(): Long = bytesAcross(frames = 1)

        /** Smallest measured allocation of [frames] consecutive frames. */
        fun bytesAcross(frames: Int): Long =
            AllocationProbe.bytesAllocated(warmups = 200, attempts = 20) {
                repeat(frames) { pipeline.render(0.5f) }
            }
    }

    private class FixedFrameTime(override val frameSeconds: Float) : FrameTime

    /** A [TextureRegion] with a size and no `Texture` behind it. See `DrawSystemPortTest`. */
    private class SizedRegion(private val width: Int, private val height: Int) : TextureRegion() {
        override fun getRegionWidth(): Int = width
        override fun getRegionHeight(): Int = height
    }

    /**
     * A font that draws nothing and returns the **same** [GlyphLayout] every time.
     *
     * `DrawSystemPortTest`'s stand-in returns `GlyphLayout()`, which is fine when the assertion
     * is about what was drawn and fatal when it is about allocation: it would put one object per
     * label per frame into the measurement and attribute the test's own garbage to the renderer.
     */
    private class ReusedLayoutFont : BitmapFont(
        BitmapFontData(),
        com.badlogic.gdx.utils.Array(arrayOf<TextureRegion>(SizedRegion(1, 1))),
        true,
    ) {
        private val layout = GlyphLayout()

        override fun draw(batch: Batch, str: CharSequence, x: Float, y: Float): GlyphLayout = layout
    }
}
