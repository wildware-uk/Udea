package dev.wildware.udea.render.gl

import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.core.module.UdeaGameDef
import dev.wildware.udea.generated.CoreUdeaRegistry
import dev.wildware.udea.render.OffscreenTarget
import dev.wildware.udea.render.RenderPhase
import dev.wildware.udea.render.RenderRegistry
import dev.wildware.udea.render.RenderResources
import dev.wildware.udea.render.RenderSystem
import dev.wildware.udea.render.backend.KoolBackend
import dev.wildware.udea.render.backend.WindowConfig
import dev.wildware.udea.render.camera.CameraRig
import dev.wildware.udea.render.capture.CaptureRequest
import dev.wildware.udea.render.capture.CaptureResult
import dev.wildware.udea.render.capture.capture
import dev.wildware.udea.render.draw.Projection2D
import dev.wildware.udea.render.draw.Rgba
import dev.wildware.udea.render.view.EditorCamera
import dev.wildware.udea.render.view.GizmoCanvas
import dev.wildware.udea.render.view.GizmoLayer
import dev.wildware.udea.render.view.ViewPoint
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Issue #234's editor views on a real Kool context: the Game tab and the Scene tab draw the same
 * world at the same tick through different cameras, and a gizmo drawn in both reaches neither
 * `render.screenshot` nor anything else the capturable pass holds.
 *
 * ## The world
 *
 * A red square at world (5, 3) seen through a `CameraRig`, and a blue bar in the bottom-left corner of
 * the screen drawn in the `UI` phase - the game's HUD. A placeholder gizmo, a yellow square of fixed
 * size on screen, marks world (-5, -3) in both tabs: the Game tab's overlay is turned on, and the
 * Scene tab draws gizmos by default.
 *
 * ## The three pictures, from one frame
 *
 * The capturable frame (what `render.screenshot` files), the Game tab and the Scene tab are all
 * requested on the render thread between two frames, so one frame claims all three: the ticks they
 * are stamped with must agree, which is the "same tick" half of the criterion.
 *
 * - **The capture** holds the red square where the game camera puts it and the HUD, and not one
 *   yellow pixel anywhere: the exclusion.
 * - **The Game tab** is the capture's own pixels plus the gizmo: every pixel that is not yellow is
 *   the capture's pixel, and there is yellow where the game camera puts world (-5, -3).
 * - **The Scene tab** has been panned and zoomed. Its red square and its gizmo are at the pixels
 *   `EditorCamera.project` names for them - the camera's projection agreeing with what Kool drew -
 *   and not where the game camera has them; and it holds no HUD.
 */
class GlWorldViewportTest {

    @Test
    fun `both tabs draw one tick through two cameras, and a gizmo drawn in both never reaches a capture`() {
        GlAvailability.require()

        val registry = RenderRegistry()
        val rig = CameraRig(
            netIds = NetIdIndex(),
            poses = { _, _, _, _ -> false },
            frameTime = registry.frameTime,
            worldWidth = WORLD_WIDTH,
            worldHeight = WORLD_HEIGHT,
        )
        registry.register(RenderPhase.PreRender, { rig })
        registry.register(RenderPhase.World, { resources -> RedSquare(resources, rig) })
        registry.register(RenderPhase.UI, { resources -> BlueHud(resources) })

        val backend = KoolBackend.start(
            RenderMode.Offscreen,
            WindowConfig(
                title = "udea-world-viewport",
                windowWidth = WIDTH,
                windowHeight = HEIGHT,
                renderWidth = WIDTH,
                renderHeight = HEIGHT,
            ),
            registry,
        )
        try {
            val host = GameHost(RenderMode.Offscreen, UdeaGameDef(registry = CoreUdeaRegistry, modules = emptyList()), backend)
            backend.drive(host)
            val slot = backend.pipeline!!.capture!!
            val sceneCamera = EditorCamera()
            val scene = backend.openSceneView(sceneCamera)
            val game = backend.openGameView()
            backend.onRenderThread {
                scene.gizmos = Marker
                game.gizmos = Marker
                game.showGizmos = true
            }

            // A frame to adopt the game's framing, then the Scene camera moves off it.
            slot.capture(CaptureRequest())
            backend.onRenderThread {
                sceneCamera.pan(PAN_X, PAN_Y)
                sceneCamera.zoomAt(ZOOM, WIDTH / 2f, HEIGHT / 2f)
            }
            slot.capture(CaptureRequest())

            // One frame claims all three.
            val (captured, gameShot, sceneShot) = backend.onRenderThread {
                Triple(slot.submit(CaptureRequest()), game.capture(), scene.capture())
            }
            val capture = await(captured)
            val gameTab = await(gameShot)
            val sceneTab = await(sceneShot)
            save("world-viewport-capture.png", capture.bytes)
            save("world-viewport-game-tab.png", gameTab.bytes)
            save("world-viewport-scene-tab.png", sceneTab.bytes)
            assertEquals(capture.tick, gameTab.tick, "the Game tab is not the capture's tick")
            assertEquals(capture.tick, sceneTab.tick, "the Scene tab is not the capture's tick")

            // Where each camera puts the two world points, read on the render thread between frames.
            val gameProjection = backend.onRenderThread { Projection2D().apply { set(rig.projection) } }
            val sceneRed = ViewPoint()
            val sceneGizmo = ViewPoint()
            backend.onRenderThread {
                sceneCamera.project(RED_X + 1f, RED_Y + 1f, 0f, sceneRed)
                sceneCamera.project(GIZMO_X, GIZMO_Y, 0f, sceneGizmo)
            }
            val gameRed = ViewPoint(gameProjection.pixelX(RED_X + 1f), gameProjection.pixelY(RED_Y + 1f))
            val gameGizmo = ViewPoint(gameProjection.pixelX(GIZMO_X), gameProjection.pixelY(GIZMO_Y))
            assertTrue(
                distance(sceneRed, gameRed) > MIN_CAMERA_SEPARATION,
                "the two cameras put the red square in the same place, so this proves nothing: $sceneRed vs $gameRed",
            )

            val captureImage = decode(capture.bytes)
            val gameImage = decode(gameTab.bytes)
            val sceneImage = decode(sceneTab.bytes)

            // The capture: the world through the game camera, the HUD, and no gizmo anywhere.
            assertEquals(RED, pixel(captureImage, gameRed), "the capture has no red square where the game camera puts it")
            assertEquals(BLUE, pixel(captureImage, HUD_POINT), "the capture lost the game's HUD")
            assertEquals(0, count(captureImage, YELLOW), "render.screenshot holds gizmo pixels")

            // The Game tab: the capture's pixels, and the gizmo over them.
            assertEquals(YELLOW, pixel(gameImage, gameGizmo), "the Game tab's overlay drew no gizmo at world (-5, -3)")
            var differing = 0
            for (y in 0 until HEIGHT) for (x in 0 until WIDTH) {
                val shown = gameImage.getRGB(x, y) and RGB
                if (shown != YELLOW && shown != captureImage.getRGB(x, y) and RGB) differing++
            }
            assertEquals(0, differing, "the Game tab differs from the capture away from its gizmo")

            // The Scene tab: the same world through the editor camera, its gizmo, and no HUD.
            assertEquals(RED, pixel(sceneImage, sceneRed), "the Scene tab has no red square where its camera projects it")
            assertNotEquals(RED, pixel(sceneImage, gameRed), "the Scene tab drew the red square where the game camera has it")
            assertEquals(YELLOW, pixel(sceneImage, sceneGizmo), "the Scene tab drew no gizmo where its camera projects it")
            assertEquals(0, count(sceneImage, BLUE), "the Scene tab drew the game's HUD")
        } finally {
            backend.close()
        }
    }

    // --- fixture -------------------------------------------------------------------------

    private fun await(result: Deferred<CaptureResult>): CaptureResult =
        runBlocking { withTimeout(CAPTURE_TIMEOUT_MILLIS) { result.await() } }

    private fun decode(png: ByteArray): BufferedImage =
        ImageIO.read(ByteArrayInputStream(png)) ?: error("the captured bytes are not a decodable image")

    /** The colour at view point [at], counted from the bottom left as GL counts, in a top-down image. */
    private fun pixel(image: BufferedImage, at: ViewPoint): Int =
        image.getRGB(at.x.toInt(), image.height - 1 - at.y.toInt()) and RGB

    private fun count(image: BufferedImage, colour: Int): Int {
        var found = 0
        for (y in 0 until image.height) for (x in 0 until image.width) if (image.getRGB(x, y) and RGB == colour) found++
        return found
    }

    private fun distance(a: ViewPoint, b: ViewPoint): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun save(name: String, png: ByteArray) {
        val dir = System.getProperty("udea.render.glReportDir") ?: return
        File(dir).mkdirs()
        File(dir, name).writeBytes(png)
    }

    /** A 2 x 2 world-unit red square with its corner at ([RED_X], [RED_Y]): the world. */
    private class RedSquare(private val resources: RenderResources, private val rig: CameraRig) : RenderSystem {
        override fun render(target: OffscreenTarget, alpha: Float) {
            val batch = resources.batch
            batch.begin(rig.projection)
            batch.fill(RED_X, RED_Y, 2f, 2f, Rgba.of(1f, 0f, 0f))
            batch.end()
        }
    }

    /** A blue bar in the bottom-left corner of the screen, in pixels: the game's HUD. */
    private class BlueHud(private val resources: RenderResources) : RenderSystem {
        override fun render(target: OffscreenTarget, alpha: Float) {
            val batch = resources.batch
            batch.beginPixels()
            batch.fill(0f, 0f, HUD_WIDTH, HUD_HEIGHT, Rgba.of(0f, 0f, 1f))
            batch.end()
        }
    }

    /** The placeholder gizmo: a yellow square [SIZE] view pixels across, on world ([GIZMO_X], [GIZMO_Y]). */
    private object Marker : GizmoLayer {
        private val at = ViewPoint()

        override fun draw(canvas: GizmoCanvas) {
            if (canvas.project(GIZMO_X, GIZMO_Y, 0f, at)) {
                canvas.fill(at.x - SIZE / 2f, at.y - SIZE / 2f, SIZE, SIZE, Rgba.of(1f, 1f, 0f))
            }
        }

        const val SIZE = 12f
    }

    private companion object {
        const val WIDTH = 320
        const val HEIGHT = 180
        const val WORLD_WIDTH = 32f
        const val WORLD_HEIGHT = 18f

        const val RED_X = 5f
        const val RED_Y = 3f
        const val GIZMO_X = -5f
        const val GIZMO_Y = -3f

        const val HUD_WIDTH = 60f
        const val HUD_HEIGHT = 10f
        val HUD_POINT = ViewPoint(HUD_WIDTH / 2f, HUD_HEIGHT / 2f)

        /** The Scene camera's move: dragged right and down, then zoomed out about the middle. */
        const val PAN_X = 40f
        const val PAN_Y = -20f
        const val ZOOM = 2f

        /** Pixels apart the two cameras must put one point for the picture to tell them apart. */
        const val MIN_CAMERA_SEPARATION = 20f

        const val RGB = 0xFFFFFF
        const val RED = 0xFF0000
        const val BLUE = 0x0000FF
        const val YELLOW = 0xFFFF00

        const val CAPTURE_TIMEOUT_MILLIS = 10_000L
    }
}
