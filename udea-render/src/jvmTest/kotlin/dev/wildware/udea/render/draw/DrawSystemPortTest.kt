package dev.wildware.udea.render.draw

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import com.github.quillraven.fleks.configureWorld
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.core.fixtures.testGameContext
import dev.wildware.udea.core.gameContext
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.core.loop.WorldSimulation
import dev.wildware.udea.core.physics.PhysicsBody
import dev.wildware.udea.render.FrameTime
import dev.wildware.udea.render.RenderPhase
import dev.wildware.udea.render.RenderRegistry
import dev.wildware.udea.render.camera.CameraRig
import dev.wildware.udea.render.interp.InterpSnapshotSystem
import dev.wildware.udea.render.interp.Interpolator
import dev.wildware.udea.render.support.testTargets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The ported drawing systems, driven through a real [RenderRegistry] over a real
 * [SpriteBatch2D].
 *
 * None of this needs a render context, which is the point of the port: every one of the
 * LibGDX-era originals constructed a `SpriteBatch` (or called `VisUI.getSkin()`) in a field
 * initialiser, so not one of them could be instantiated in a test, and not one of them had one.
 *
 * `ParticleRenderSystem` is gone from this suite along with the LibGDX `ParticleEffect` it drew:
 * particles were not ported to Kool in issue #211 (spec section 4 names the systems that were),
 * and nothing in the new `draw` package replaces it.
 */
class DrawSystemPortTest {

    private val ctx: GameContext = testGameContext(seed = 9L)

    private val world: World = configureWorld {
        injectables { gameContext(ctx) }
        systems { add(InterpSnapshotSystem()) }
    }

    private val sim = WorldSimulation(ctx, world)

    private val netIds = NetIdIndex()

    private val frameTime = FixedFrameTime(1f / 60f)

    private val interpolator = Interpolator(ctx.clock, world.system<InterpSnapshotSystem>())

    private val rig = CameraRig(netIds, interpolator, frameTime)

    private val targets = testTargets(width = 640, height = 360)

    private val batch = targets.batch

    @Test
    fun `every ported renderer draws at least once over a frame`() {
        val entity = spawnSprite(x = 1f, y = 2f)
        with(world) {
            entity.configure {
                it += SpriteAnimation(animation = twoFrameAnimation())
                it += DebugLabels(mutableListOf(DebugLabel("hello", ctx.clock.tick + 100L)))
            }
        }
        sim.step()

        val pipeline = buildPipeline(background = region())
        pipeline.render(0.5f)

        assertEquals(1L, background.drawnCount, "the background did not draw")
        assertEquals(1, sprites.drawnCount, "the sprite did not draw")
        assertEquals(1, animations.advancedCount, "the animation playhead did not advance")
        assertEquals(1, debug.drawnCount, "the debug label did not draw")
        assertTrue(batch.instanceCount > 0, "nothing reached the batch at all")
        assertFalse(batch.isDrawing, "a renderer left the batch begun")
    }

    @Test
    fun `sprites are drawn in ascending order`() {
        // The old system passed a comparator to Fleks and relied on it; here the family is
        // sorted explicitly, so the ordering has to be asserted rather than inherited.
        val far = spawnSprite(x = 0f, y = 0f, order = -5, region = region(width = 1))
        val near = spawnSprite(x = 0f, y = 0f, order = 10, region = region(width = 2))
        val middle = spawnSprite(x = 0f, y = 0f, order = 0, region = region(width = 3))
        sim.step()

        buildPipeline().render(1f)

        val widths = batch.snapshot().map { it.textureWidth }
        assertEquals(listOf(1, 3, 2), widths, "$far $middle $near drew out of order")
    }

    @Test
    fun `a sprite with no region is skipped rather than drawn blank`() {
        spawnSprite(x = 0f, y = 0f, region = null)
        sim.step()

        buildPipeline().render(1f)

        assertEquals(0, sprites.drawnCount)
    }

    @Test
    fun `a sprite is drawn at its interpolated position, not its simulated one`() {
        val entity = spawnSprite(x = 0f, y = 0f)
        sim.step()
        with(world) { entity[PhysicsBody].x = 10f }

        buildPipeline().render(0.5f)

        val draw = batch.snapshot().single()
        // Half way between the tick's starting pose (0) and the current one (10), less half the
        // sprite's width, because the batch draws from a corner.
        assertEquals(5f - SPRITE_SIZE / 2f, draw.x, "drew at ${draw.x}")
    }

    @Test
    fun `an animation writes its current frame into the sprite`() {
        val entity = spawnSprite(x = 0f, y = 0f, region = null)
        val animation = twoFrameAnimation()
        with(world) { entity.configure { it += SpriteAnimation(animation = animation) } }
        sim.step()
        val pipeline = buildPipeline()

        pipeline.render(1f)
        val first = with(world) { entity[SpriteRenderer].region }

        // Two frames of a tenth of a second each; six frames of 1/60s crosses the boundary.
        repeat(6) { pipeline.render(1f) }
        val second = with(world) { entity[SpriteRenderer].region }

        assertNotNull(first)
        assertEquals(1, first.width, "the first key frame was not shown")
        assertEquals(2, second?.width, "the playhead never reached the second frame")
    }

    @Test
    fun `a paused animation keeps showing its frame instead of going blank`() {
        val entity = spawnSprite(x = 0f, y = 0f, region = null)
        with(world) {
            entity.configure { it += SpriteAnimation(animation = twoFrameAnimation(), playing = false) }
        }
        sim.step()

        buildPipeline().render(1f)

        assertNotNull(with(world) { entity[SpriteRenderer].region })
        assertEquals(0, animations.advancedCount, "a paused playhead must not advance")
    }

    @Test
    fun `debug labels expire by tick rather than by wall clock`() {
        val entity = spawnSprite(x = 0f, y = 0f)
        with(world) {
            entity.configure {
                it += DebugLabels(
                    mutableListOf(
                        DebugLabel("still here", expiresAt = ctx.clock.tick + 10L),
                        DebugLabel("already gone", expiresAt = ctx.clock.tick),
                    ),
                )
            }
        }
        sim.step()

        buildPipeline().render(1f)

        val remaining = with(world) { entity[DebugLabels].messages.map { it.text } }
        assertEquals(listOf("still here"), remaining)
    }

    @Test
    fun `the draw count per frame does not drift over a run`() {
        // Named for what it measures. It used to be called "steady state rendering allocates no
        // per-frame comparator or pose", which it could not tell you: it counts draw calls, and
        // a `sortedBy` or a position object in the draw loop leaves the draw count alone. The
        // allocation claim is measured in `RenderAllocationTest`; what is worth checking here is
        // that a system does not start dropping or duplicating draws as a run goes on. Frames are
        // deliberately not cleared between renders, so `instanceCount` accumulates and the
        // per-frame contribution is exactly its growth.
        repeat(20) { spawnSprite(x = it.toFloat(), y = 0f) }
        sim.step()
        val pipeline = buildPipeline()
        pipeline.render(0.5f)
        val drawsPerFrame = batch.instanceCount

        repeat(10) { pipeline.render(0.5f) }

        assertEquals(drawsPerFrame * 11, batch.instanceCount, "draw count per frame changed")
        assertFalse(batch.isDrawing)
    }

    @Test
    fun `a debug label is placed against the target it draws on`() {
        // The regression this guarded on LibGDX: `camera.project(v)`'s one-argument overload
        // read `Gdx.graphics.getWidth()/getHeight()` - the *window* - rather than the target
        // being drawn on, which differ by construction on an Offscreen host (`GlCaptureTest`
        // boots a window bigger than its framebuffer). On Kool there is no such overload to
        // reach for: `CameraRig` sizes itself from the `OffscreenTarget` alone and is never
        // handed a window at all (see its KDoc), so what is left to check is that the label
        // lands where `camera.projection` and the renderer's own offsets say it should.
        val entity = spawnSprite(x = 0f, y = 0f)
        with(world) {
            entity.configure {
                it += DebugLabels(mutableListOf(DebugLabel("hello", ctx.clock.tick + 100L)))
            }
        }
        sim.step()

        buildPipeline().render(1f)

        // The camera is centred on the origin over a 640x360 target, so world (0, 0) projects to
        // the middle of the target: (320, 180). The offsets are `DebugOverlayRenderSystem`'s
        // own (`LABEL_OFFSET_X` = 40, `LABEL_OFFSET_Y` = -20), and the glyph's own baseline
        // correction is `BitmapFont2D`'s (`bottom = baselineY - scale`, `scale` = 2 by default).
        //
        // Instance 0 is the sprite `SpriteRenderSystem` draws in `RenderPhase.World`, which
        // always runs before `RenderPhase.Debug`; instance 1 is therefore the debug label's own
        // leading `#` glyph, the first character `BitmapFont2D.draw` records.
        val snapshot = batch.snapshot()
        assertTrue(snapshot.size >= 2, "expected at least one sprite and one glyph: $snapshot")
        val glyph = snapshot[1]
        assertEquals(320f + 40f, glyph.x, absoluteTolerance = 0.01f, "glyph x: $snapshot")
        assertEquals(180f - 20f - 2f, glyph.y, absoluteTolerance = 0.01f, "glyph y: $snapshot")
    }

    // --- fixture -------------------------------------------------------------------------

    private lateinit var background: BackgroundRenderSystem
    private lateinit var sprites: SpriteRenderSystem
    private lateinit var animations: AnimationRenderSystem
    private lateinit var debug: DebugOverlayRenderSystem

    private val font = BitmapFont2D.builtIn()

    private fun buildPipeline(background: SpriteRegion? = null) = RenderRegistry().apply {
        // `frameTime` unqualified here would resolve to RenderRegistry's own property: inside
        // `apply` the receiver's member wins over this class's field, and the systems would be
        // handed the registry's real wall clock instead of the fixed one this test drives.
        val frameTime = this@DrawSystemPortTest.frameTime
        register(
            RenderPhase.PreRender,
            { resources ->
                BackgroundRenderSystem(resources, background)
                    .also { this@DrawSystemPortTest.background = it }
            },
        )
        register(RenderPhase.World, { rig })
        register(RenderPhase.World, { AnimationRenderSystem(frameTime).also { animations = it } })
        register(
            RenderPhase.World,
            { resources -> SpriteRenderSystem(resources, rig, interpolator).also { sprites = it } },
        )
        register(
            RenderPhase.Debug,
            { resources ->
                DebugOverlayRenderSystem(resources, rig, interpolator, netIds, font)
                    .also { debug = it }
            },
        )
    }.build(world, ctx, targets)

    private fun spawnSprite(
        x: Float,
        y: Float,
        order: Int = 0,
        region: SpriteRegion? = region(),
    ): Entity = world.entity {
        it += PhysicsBody(x = x, y = y)
        it += SpriteRenderer(
            region = region,
            width = SPRITE_SIZE,
            height = SPRITE_SIZE,
            order = order,
        )
    }.also { netIds.allocate(it) }

    /**
     * A region backed by a real texture exactly [width] x [height], and no atlas packing.
     *
     * A distinct texture per call (rather than one shared page) is what makes
     * `sprites are drawn in ascending order` able to tell the three sprites apart by their
     * texture's own width: each draw starts its own run, and the run's texture is this one.
     */
    private fun region(width: Int = 8, height: Int = 8): SpriteRegion =
        SpriteRegion(SpriteTexture.fromRgba(width, height, ByteArray(width * height * 4), "region-$width"))

    private fun twoFrameAnimation(): SpriteClip = SpriteClip(listOf(region(1), region(2)), frameSeconds = 0.1f)

    private class FixedFrameTime(override val frameSeconds: Float) : FrameTime

    /** One decoded instance: where it landed, and the width of the texture it sampled. */
    private class Draw(val x: Float, val y: Float, val textureWidth: Int) {
        override fun toString(): String = "Draw(($x, $y), texture width $textureWidth)"
    }

    /** Every instance currently recorded, decoded from the batch's own arrays. */
    private fun SpriteBatch2D.snapshot(): List<Draw> {
        val textureOfInstance = arrayOfNulls<SpriteTexture>(instanceCount)
        for (run in 0 until runCount) {
            val texture = runTexture(run)
            for (index in runStart(run) until runEnd(run)) textureOfInstance[index] = texture
        }
        return (0 until instanceCount).map { index ->
            val at = index * SpriteBatch2D.FLOATS_PER_INSTANCE
            Draw(
                x = floats[at + SpriteBatch2D.X],
                y = floats[at + SpriteBatch2D.Y],
                textureWidth = checkNotNull(textureOfInstance[index]) { "instance $index has no run" }.width,
            )
        }
    }

    private companion object {
        const val SPRITE_SIZE = 2f
    }
}
