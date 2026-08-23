package dev.wildware.udea.render.draw

import com.badlogic.gdx.graphics.g2d.Animation
import com.badlogic.gdx.graphics.g2d.ParticleEffect
import com.badlogic.gdx.graphics.g2d.TextureRegion
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
import dev.wildware.udea.render.OffscreenTarget
import dev.wildware.udea.render.RenderResources
import dev.wildware.udea.render.RenderPhase
import dev.wildware.udea.render.RenderRegistry
import dev.wildware.udea.render.camera.CameraRig
import dev.wildware.udea.render.interp.InterpSnapshotSystem
import dev.wildware.udea.render.interp.Interpolator
import dev.wildware.udea.render.support.HeadlessGl
import dev.wildware.udea.render.support.RecordingBatch
import dev.wildware.udea.render.support.testTargets
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The ported drawing systems, driven through a real [RenderRegistry] with a recording batch.
 *
 * None of this needs a GL context, which is the point of the port: every one of the originals
 * constructed a `SpriteBatch` (or called `VisUI.getSkin()`) in a field initialiser, so not one
 * of them could be instantiated in a test, and not one of them had a test.
 */
class DrawSystemPortTest {

    private var gl: HeadlessGl? = null

    private val batch = RecordingBatch()

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

    private val targets = testTargets(batch = batch.batch, width = 640, height = 360)

    @BeforeEach
    fun installGl() {
        gl = HeadlessGl.installed(width = 640, height = 360)
    }

    @AfterEach
    fun removeGl() {
        gl?.uninstall()
        gl = null
    }

    @Test
    fun `every ported renderer draws at least once over a frame`() {
        val entity = spawnSprite(x = 1f, y = 2f)
        with(world) {
            entity.configure {
                it += SpriteAnimation(animation = twoFrameAnimation())
                it += ParticleEffects(mutableListOf(ParticleEffect()))
                it += DebugLabels(mutableListOf(DebugLabel("hello", ctx.clock.tick + 100L)))
            }
        }
        sim.step()

        val pipeline = buildPipeline(background = region())
        pipeline.render(0.5f)

        assertEquals(1L, background.drawnCount, "the background did not draw")
        assertEquals(1, sprites.drawnCount, "the sprite did not draw")
        assertEquals(1, animations.advancedCount, "the animation playhead did not advance")
        assertEquals(1, particles.drawnCount, "the particle effect did not draw")
        assertEquals(1, debug.drawnCount, "the debug label did not draw")
        assertTrue(batch.draws.isNotEmpty(), "nothing reached the batch at all")
        assertFalse(batch.mismatchedBeginEnd, "a renderer left the batch begun")
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

        val widths = batch.draws.mapNotNull { it.region?.regionWidth }
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

        val draw = batch.draws.single()
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
        assertEquals(1, first.regionWidth, "the first key frame was not shown")
        assertEquals(2, second?.regionWidth, "the playhead never reached the second frame")
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
    fun `steady state rendering allocates no per-frame comparator or pose`() {
        // The claim is structural rather than measured: the comparator, the Pose and the
        // projection Matrix4 are fields, so rendering twice must not change their identity.
        // A `sortedBy` or a `Vector3(...)` inside the draw loop would fail this.
        repeat(20) { spawnSprite(x = it.toFloat(), y = 0f) }
        sim.step()
        val pipeline = buildPipeline()
        pipeline.render(0.5f)
        val drawsPerFrame = batch.draws.size

        repeat(10) { pipeline.render(0.5f) }

        assertEquals(drawsPerFrame * 11, batch.draws.size, "draw count per frame changed")
        assertFalse(batch.mismatchedBeginEnd)
    }

    // --- fixture -------------------------------------------------------------------------

    private lateinit var background: BackgroundRenderSystem
    private lateinit var sprites: SpriteRenderSystem
    private lateinit var animations: AnimationRenderSystem
    private lateinit var particles: ParticleRenderSystem
    private lateinit var debug: DebugOverlayRenderSystem

    private fun buildPipeline(background: TextureRegion? = null) = RenderRegistry().apply {
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
            RenderPhase.World,
            { resources ->
                ParticleRenderSystem(resources, rig, interpolator, frameTime)
                    .also { particles = it }
            },
        )
        register(
            RenderPhase.Debug,
            { resources ->
                DebugOverlayRenderSystem(resources, rig, interpolator, netIds, NoOpFont())
                    .also { debug = it }
            },
        )
    }.build(world, ctx, targets)

    private fun spawnSprite(
        x: Float,
        y: Float,
        order: Int = 0,
        region: TextureRegion? = region(),
    ): Entity = world.entity {
        it += PhysicsBody(x = x, y = y)
        it += SpriteRenderer(
            region = region,
            width = SPRITE_SIZE,
            height = SPRITE_SIZE,
            order = order,
        )
    }.also { netIds.allocate(it) }

    private fun region(width: Int = 8, height: Int = 8): TextureRegion = SizedRegion(width, height)

    /**
     * A [TextureRegion] with a size and no texture behind it.
     *
     * `TextureRegion.setRegion(x, y, w, h)` derives its UVs from the texture's dimensions, so
     * the ordinary way to make one needs a real `Texture` and therefore a GL context. Nothing
     * here samples the region — the batch is a recorder — so overriding the two accessors is
     * enough, and it keeps the whole port testable without a window.
     */
    private class SizedRegion(private val width: Int, private val height: Int) : TextureRegion() {
        override fun getRegionWidth(): Int = width
        override fun getRegionHeight(): Int = height
        override fun toString(): String = "SizedRegion(${width}x$height)"
    }

    private fun twoFrameAnimation(): Animation<TextureRegion> =
        Animation(0.1f, com.badlogic.gdx.utils.Array(arrayOf(region(1), region(2))))

    private class FixedFrameTime(override val frameSeconds: Float) : FrameTime

    /**
     * A `BitmapFont` that draws nothing.
     *
     * `BitmapFont()` loads its default `.fnt` and a `Texture` for it, and a `Texture` needs a
     * context. Overriding `draw` keeps the debug renderer exercised without one.
     */
    private class NoOpFont : com.badlogic.gdx.graphics.g2d.BitmapFont(
        com.badlogic.gdx.graphics.g2d.BitmapFont.BitmapFontData(),
        com.badlogic.gdx.utils.Array(arrayOf<TextureRegion>(SizedRegion(1, 1))),
        true,
    ) {
        override fun draw(
            batch: com.badlogic.gdx.graphics.g2d.Batch,
            str: CharSequence,
            x: Float,
            y: Float,
        ): com.badlogic.gdx.graphics.g2d.GlyphLayout = com.badlogic.gdx.graphics.g2d.GlyphLayout()
    }

    private companion object {
        const val SPRITE_SIZE = 2f
    }
}
