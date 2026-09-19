package dev.wildware.udea.render.view

import com.github.quillraven.fleks.World
import com.github.quillraven.fleks.configureWorld
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.core.fixtures.testGameContext
import dev.wildware.udea.core.gameContext
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.render.FrameSurface
import dev.wildware.udea.render.OffscreenTarget
import dev.wildware.udea.render.RenderPhase
import dev.wildware.udea.render.RenderPipeline
import dev.wildware.udea.render.RenderRegistry
import dev.wildware.udea.render.RenderResources
import dev.wildware.udea.render.RenderSystem
import dev.wildware.udea.render.RenderTargets
import dev.wildware.udea.render.ScreenTarget
import dev.wildware.udea.render.camera.CameraRig
import dev.wildware.udea.render.draw.Rgba
import dev.wildware.udea.render.draw.SpriteBatch2D
import dev.wildware.udea.render.draw.SpriteRecord
import dev.wildware.udea.render.draw.SpriteTexture
import dev.wildware.udea.render.support.ManualFrameClock
import dev.wildware.udea.render.support.testTargets
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The editor views at the pipeline, with no render context (issue #234): what each view's record
 * holds after a frame, against what the capturable record holds.
 *
 * A record is exactly what a Kool pass draws - one node reads one record - so "the capturable record
 * holds no gizmo" is "the capturable pass draws no gizmo". Whether Kool then puts those records on
 * the right passes, and whether a capture reads the right one, is `GlWorldViewportTest`'s half.
 */
class WorldViewportTest {

    private val ctx: GameContext = testGameContext(seed = 23L)
    private val world: World = configureWorld { injectables { gameContext(ctx) } }
    private val capturable = SpriteBatch2D(SpriteTexture.whitePixel("test-offscreen-white"))

    /** Every size the surface was told to take, in order. */
    private val resized = ArrayList<String>()

    /** Clears the capturable batch at the top of each frame, as `KoolSurface.begin` does. */
    private val targets: RenderTargets = testTargets(
        batch = capturable,
        surface = object : FrameSurface {
            override fun begin() = capturable.clear()
            override fun endAndPresent(screen: ScreenTarget) = Unit
            override fun resize(width: Int, height: Int) {
                resized += "${width}x$height"
            }
        },
        width = WIDTH,
        height = HEIGHT,
    )
    private val registry = RenderRegistry(ManualFrameClock())
    private val rig = CameraRig(
        netIds = NetIdIndex(),
        poses = { _, _, _, _ -> false },
        frameTime = registry.frameTime,
        worldWidth = WORLD_WIDTH,
        worldHeight = WORLD_HEIGHT,
    )

    private val pipeline: RenderPipeline = registry.run {
        register(RenderPhase.PreRender, { rig })
        register(RenderPhase.World, { resources -> Marker(resources, rig) })
        register(RenderPhase.UI, { resources -> Hud(resources) })
        build(world, ctx, targets)
    }

    @Test
    fun `the Scene view draws the same world through its own camera, and leaves the HUD out`() {
        val scene = view(EditorCamera())
        pipeline.open(scene)
        pipeline.render(0.5f)

        // Opened on the game's framing: the marker is where the Game view has it.
        val game = targets.batch.home.fills(MARKER).single()
        val first = scene.record.fills(MARKER).single()
        assertNear(game.x, first.x, "the Scene view did not open on the game's framing")
        assertNear(game.y, first.y, "the Scene view did not open on the game's framing")

        checkNotNull(scene.camera).pan(PAN_X, PAN_Y)
        pipeline.render(0.5f)

        val panned = scene.record.fills(MARKER).single()
        val gameAgain = targets.batch.home.fills(MARKER).single()
        assertNear(game.x + PAN_X, panned.x, "the Scene view's marker did not follow its own camera")
        assertNear(game.y + PAN_Y, panned.y, "the Scene view's marker did not follow its own camera")
        assertNear(game.x, gameAgain.x, "the Scene view's camera moved the capturable frame")
        assertNear(game.y, gameAgain.y, "the Scene view's camera moved the capturable frame")
        assertEquals(1, targets.batch.home.fills(HUD).size, "the capturable frame lost its HUD")
        assertEquals(emptyList(), scene.record.fills(HUD), "the Scene view drew the game's HUD")
    }

    @Test
    fun `gizmos land in the views that draw them and never in the capturable record`() {
        val scene = view(EditorCamera())
        val gameView = view(null)
        val gizmo = Square(0f, 0f)
        scene.gizmos = gizmo
        gameView.gizmos = gizmo
        pipeline.open(scene)
        pipeline.open(gameView)

        pipeline.render(0.5f)
        assertEquals(1, scene.record.fills(GIZMO).size, "the Scene view draws its gizmos from the start")
        assertEquals(emptyList(), gameView.record.fills(GIZMO), "the Game view drew gizmos with its overlay off")

        gameView.showGizmos = true
        checkNotNull(scene.camera).pan(PAN_X, 0f)
        pipeline.render(0.5f)

        val marker = targets.batch.home.fills(MARKER).single()
        val inGame = gameView.record.fills(GIZMO).single()
        val inScene = scene.record.fills(GIZMO).single()
        // Each gizmo is centred on world (0, 0) - the marker's corner - as its view sees it.
        assertNear(marker.x, inGame.x + Square.SIZE / 2f, "the Game view's gizmo is not on the game's (0, 0)")
        assertNear(marker.x + PAN_X, inScene.x + Square.SIZE / 2f, "the Scene view's gizmo is not on its (0, 0)")
        assertEquals(emptyList(), targets.batch.home.fills(GIZMO), "a gizmo reached the capturable record")
    }

    @Test
    fun `a gizmo line is one strip from its first end to its second, as thick as asked, turned to face along it`() {
        val scene = view(EditorCamera())
        scene.gizmos = object : GizmoLayer {
            override fun draw(canvas: GizmoCanvas) {
                // Three across and four up from (10, 20): five long, at atan(4 / 3) from the x axis.
                canvas.line(10f, 20f, 40f, 60f, 2f, GIZMO)
            }
        }
        pipeline.open(scene)
        pipeline.render(0.5f)

        assertEquals(1, scene.record.fills(GIZMO).size, "a line is one strip")
        // The world's marker is drawn into the view first; the strip is the instance in the gizmo's colour.
        val strip = (0 until scene.record.instanceCount).single { scene.record.tints[it] == GIZMO.packed }
        val floats = scene.record.floats.copyOfRange(strip * SpriteBatch2D.FLOATS_PER_INSTANCE, (strip + 1) * SpriteBatch2D.FLOATS_PER_INSTANCE)
        // The unturned strip starts at the first end and is centred on the line across its thickness...
        assertNear(10f, floats[SpriteBatch2D.X], "the strip does not start at the line's first end")
        assertNear(19f, floats[SpriteBatch2D.Y], "the strip is not centred on the line")
        assertNear(50f, floats[SpriteBatch2D.WIDTH], "the strip is not as long as the line")
        assertNear(2f, floats[SpriteBatch2D.HEIGHT], "the strip is not as thick as asked")
        // ...and turns about the first end, so its far end lands on the second.
        assertNear(0f, floats[SpriteBatch2D.ORIGIN_X], "the strip does not turn about the line's first end")
        assertNear(1f, floats[SpriteBatch2D.ORIGIN_Y], "the strip does not turn about the line's first end")
        assertNear(Math.toDegrees(atan2(40.0, 30.0)).toFloat(), floats[SpriteBatch2D.ROTATION], "the strip does not face along the line")
        assertEquals(emptyList(), targets.batch.home.fills(GIZMO), "a gizmo line reached the capturable record")
    }

    @Test
    fun `a press reaches a gizmo in the Scene view and never in the Game view`() {
        val scene = view(EditorCamera())
        val gameView = view(null)
        val gizmo = Square(0f, 0f)
        scene.gizmos = gizmo
        gameView.gizmos = gizmo
        gameView.showGizmos = true
        pipeline.open(scene)
        pipeline.open(gameView)
        pipeline.render(0.5f)

        val onScene = scene.record.fills(GIZMO).single()
        val onGame = gameView.record.fills(GIZMO).single()
        assertTrue(scene.pressGizmo(onScene.x + 1f, onScene.y + 1f), "a press on the Scene view's gizmo missed it")
        assertEquals(1, gizmo.presses)
        assertTrue(!gameView.pressGizmo(onGame.x + 1f, onGame.y + 1f), "the Game view let a gizmo take a press")
        assertEquals(1, gizmo.presses, "the Game view's press reached the gizmo")
    }

    @Test
    fun `a view closed or disposed with the pipeline is drawn no more`() {
        val scene = view(EditorCamera())
        pipeline.open(scene)
        pipeline.render(0.5f)
        scene.close()
        scene.record.clear()
        pipeline.render(0.5f)
        assertEquals(emptyList(), scene.record.fills(MARKER), "a closed view was drawn")

        val second = view(EditorCamera())
        pipeline.open(second)
        pipeline.dispose()
        assertTrue(second.isClosed, "disposing the pipeline left a view open")
    }

    @Test
    fun `a Scene view maps a pointer on its letterboxed picture back to its own pixels`() {
        val scene = view(EditorCamera())
        val at = ViewPoint()
        // A picture twice the view's width and the same height: bars left and right, a view half
        // the picture wide in the middle, scaled 1:1 vertically.
        assertTrue(scene.toView(WIDTH / 2f + 10f, 20f, WIDTH * 2, HEIGHT, at), "a point on the view was called a bar")
        assertNear(10f, at.x, "the pointer's x was not mapped through the letterbox")
        assertNear(HEIGHT - 20f, at.y, "the pointer's y was not flipped to count from the bottom")
        assertTrue(!scene.toView(5f, 20f, WIDTH * 2, HEIGHT, at), "a point on the bar was called the view")
    }

    @Test
    fun `a Game view resizes the capturable frame to its own size before the next frame draws`() {
        val gameView = view(null)
        pipeline.open(gameView)
        pipeline.render(0.5f)

        gameView.resizeTo(TALL_WIDTH, TALL_HEIGHT)
        assertEquals(WIDTH, pipeline.offscreen.width, "the frame was resized before a frame asked for it")
        pipeline.render(0.5f)

        assertEquals(listOf("${TALL_WIDTH}x$TALL_HEIGHT"), resized, "the surface was not resized to the Game view's size")
        assertEquals(TALL_WIDTH, pipeline.offscreen.width, "the capturable frame is not the Game view's width")
        assertEquals(TALL_HEIGHT, pipeline.offscreen.height, "the capturable frame is not the Game view's height")
        assertEquals(TALL_WIDTH, gameView.width)
        assertEquals(TALL_HEIGHT, gameView.height)
        // The game's camera fitted the new shape rather than being squeezed into it: the marker, a
        // one-unit square, is still square, and still all the world the game shows across.
        val tall = targets.batch.home.fills(MARKER).single()
        assertNear(tall.width, tall.height, "the marker is not square: the game's camera was squeezed into the new shape")
        assertNear(TALL_WIDTH / WORLD_WIDTH, tall.width, "the game no longer shows its whole width across the frame")

        pipeline.render(0.5f)
        assertEquals(1, resized.size, "the frame was resized again with no new size asked for")
    }

    @Test
    fun `a Scene view resizes only itself, and its camera fits the new shape`() {
        val scene = view(EditorCamera())
        pipeline.open(scene)
        pipeline.render(0.5f)

        scene.resizeTo(TALL_WIDTH, TALL_HEIGHT)
        pipeline.render(0.5f)

        assertEquals(emptyList(), resized, "a Scene view resized the capturable frame")
        assertEquals(WIDTH, pipeline.offscreen.width)
        assertEquals(TALL_WIDTH, scene.width)
        assertEquals(TALL_HEIGHT, scene.height)
        val after = scene.record.fills(MARKER).single()
        assertNear(after.width, after.height, "the marker is not square: the Scene view's camera was squeezed into the new shape")
    }

    @Test
    fun `a size of nothing is no size`() {
        val gameView = view(null)
        pipeline.open(gameView)
        gameView.resizeTo(0, TALL_HEIGHT)
        gameView.resizeTo(TALL_WIDTH, -1)
        pipeline.render(0.5f)
        assertEquals(emptyList(), resized, "a zero or negative size resized the frame")
        assertEquals(WIDTH, gameView.width)
    }

    // --- fixture -------------------------------------------------------------------------

    private fun view(camera: EditorCamera?): WorldViewport {
        val record = SpriteRecord()
        return WorldViewport(
            camera = camera,
            target = OffscreenTarget(WIDTH, HEIGHT),
            record = record,
            batch = SpriteBatch2D(SpriteTexture.whitePixel("test-view-white"), home = record),
            frame = null,
            captures = null,
            kool = null,
        )
    }

    /** One fill as the record holds it: pixel position, size and tint. */
    private class Fill(val x: Float, val y: Float, val width: Float, val height: Float, val tint: Int)

    private fun SpriteRecord.fills(tint: Rgba): List<Fill> = (0 until instanceCount)
        .map { index ->
            val at = index * SpriteBatch2D.FLOATS_PER_INSTANCE
            Fill(floats[at + SpriteBatch2D.X], floats[at + SpriteBatch2D.Y], floats[at + SpriteBatch2D.WIDTH], floats[at + SpriteBatch2D.HEIGHT], tints[index])
        }
        .filter { it.tint == tint.packed }

    private fun assertNear(expected: Float, actual: Float, message: String) {
        assertTrue(abs(expected - actual) < 0.01f, "$message: expected $expected, was $actual")
    }

    /** A one-world-unit square at the origin, through the rig: the world. */
    private class Marker(private val resources: RenderResources, private val rig: CameraRig) : RenderSystem {
        override fun render(target: OffscreenTarget, alpha: Float) {
            val batch = resources.batch
            batch.begin(rig.projection)
            batch.fill(0f, 0f, 1f, 1f, MARKER)
            batch.end()
        }
    }

    /** A bar in screen pixels: the game's HUD. */
    private class Hud(private val resources: RenderResources) : RenderSystem {
        override fun render(target: OffscreenTarget, alpha: Float) {
            val batch = resources.batch
            batch.beginPixels()
            batch.fill(0f, 0f, 50f, 10f, HUD)
            batch.end()
        }
    }

    /** A placeholder gizmo: a square of [SIZE] view pixels centred on a world point. */
    private class Square(private val worldX: Float, private val worldY: Float) : GizmoLayer {
        private val at = ViewPoint()
        var presses = 0

        override fun draw(canvas: GizmoCanvas) {
            if (canvas.project(worldX, worldY, 0f, at)) canvas.fill(at.x - SIZE / 2f, at.y - SIZE / 2f, SIZE, SIZE, GIZMO)
        }

        override fun press(canvas: GizmoCanvas, viewX: Float, viewY: Float): Boolean {
            if (!canvas.project(worldX, worldY, 0f, at)) return false
            val hit = abs(viewX - at.x) <= SIZE / 2f && abs(viewY - at.y) <= SIZE / 2f
            if (hit) presses++
            return hit
        }

        companion object {
            const val SIZE = 16f
        }
    }

    private companion object {
        const val WIDTH = 640
        const val HEIGHT = 360
        const val WORLD_WIDTH = 32f
        const val WORLD_HEIGHT = 18f

        /** A view taller than it is wide: the shape a gap between two side panels usually has. */
        const val TALL_WIDTH = 300
        const val TALL_HEIGHT = 360

        const val PAN_X = 100f
        const val PAN_Y = -40f

        val MARKER = Rgba.of(1f, 0f, 0f)
        val HUD = Rgba.of(0f, 0f, 1f)
        val GIZMO = Rgba.of(0f, 1f, 0f)
    }
}
