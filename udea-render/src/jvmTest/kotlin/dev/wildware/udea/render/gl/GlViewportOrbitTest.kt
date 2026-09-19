package dev.wildware.udea.render.gl

import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.module.UdeaGameDef
import dev.wildware.udea.core.spatial.Transform3D
import dev.wildware.udea.generated.CoreUdeaRegistry
import dev.wildware.udea.render.RenderPhase
import dev.wildware.udea.render.RenderRegistry
import dev.wildware.udea.render.backend.KoolBackend
import dev.wildware.udea.render.backend.WindowConfig
import dev.wildware.udea.render.capture.CaptureRequest
import dev.wildware.udea.render.capture.CaptureResult
import dev.wildware.udea.render.capture.capture
import dev.wildware.udea.render.draw.SpriteTexture
import dev.wildware.udea.render.model.ModelCamera
import dev.wildware.udea.render.model.ModelLight
import dev.wildware.udea.render.model.ModelMaterial
import dev.wildware.udea.render.model.ModelMesh
import dev.wildware.udea.render.model.ModelRenderSystem
import dev.wildware.udea.render.model.ModelRenderer
import dev.wildware.udea.render.view.EditorCamera
import dev.wildware.udea.render.view.ViewDimension
import dev.wildware.udea.render.view.ViewPoint
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The Scene tab's 3D orbit (issue #234) on a real Kool context: what Kool draws through the editor
 * camera lands where `EditorCamera.project` says it does, before and after the orbit turns, while
 * the capturable frame keeps the game's camera.
 *
 * ## The world
 *
 * The game's `ModelCamera` looks at the origin from the -Y side and above, Z up. One small white cube
 * stands at ([CUBE_X], [CUBE_Y], [CUBE_Z]), off the centre on purpose: a point at the centre of an
 * orbit stays at the centre of the picture however the orbit turns, so it could not tell a turned
 * camera from a still one.
 *
 * ## What is read
 *
 * - **Opened on the game's camera.** The first Scene frame has the cube at the pixel the game camera
 *   puts it at in the capture.
 * - **Orbit.** Turned [YAW] degrees about Z and raised [PITCH] degrees, the Scene view has the cube at
 *   the pixel `project` names, not where it was; and the capture has it where it always had it. This
 *   holds `project`'s look-at - Z up, yaw from +X - to Kool's, which is what a pointer on a gizmo is
 *   hit-tested with.
 * - **Dolly.** Moved in by [DOLLY], the cube is again where `project` puts it, and further from the
 *   picture's centre than before.
 */
class GlViewportOrbitTest {

    @Test
    fun `the Scene view orbits round the game's centre, Z up, where its projection says it does`() {
        GlAvailability.require()

        val registry = RenderRegistry()
        val game = ModelCamera().apply { lookAt(0f, -8f, 5f, 0f, 0f, 0f) }
        val light = ModelLight(directionX = 0.3f, directionY = 0.5f, directionZ = -1f)
        registry.register(RenderPhase.World, { resources -> ModelRenderSystem(resources, game, light) })

        val backend = KoolBackend.start(
            RenderMode.Offscreen,
            WindowConfig(
                title = "udea-viewport-orbit",
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
            val camera = EditorCamera().apply { dimension = ViewDimension.ThreeD }
            val scene = backend.openSceneView(camera)
            backend.onRenderThread {
                host.world.entity {
                    it += Transform3D(x = CUBE_X, y = CUBE_Y, z = CUBE_Z)
                    it += ModelRenderer(ModelMesh.box(CUBE_SIZE, CUBE_SIZE, CUBE_SIZE), ModelMaterial(white()))
                }
            }
            // Frames go by first, as in GlModelRenderTest: the first frame after a model is added
            // can come back without it.
            slot.capture(CaptureRequest())
            slot.capture(CaptureRequest())

            // 1. Opened on the game's camera.
            val opened = frame(backend, scene, slot)
            val openedAt = projected(backend, camera)
            save("viewport-orbit-opened.png", opened.scene)
            assertTrue(lit(opened.scene, openedAt), "the Scene view has no cube where its camera projects it: $openedAt")
            assertTrue(lit(opened.capture, openedAt), "the Scene view did not open on the game's camera: $openedAt")

            // 2. The orbit.
            backend.onRenderThread { camera.orbit(YAW, PITCH) }
            val turned = frame(backend, scene, slot)
            val turnedAt = projected(backend, camera)
            save("viewport-orbit-turned.png", turned.scene)
            assertTrue(
                distance(openedAt, turnedAt) > MIN_MOVE,
                "the orbit did not move the cube's projected point far enough to tell: $openedAt to $turnedAt",
            )
            assertTrue(lit(turned.scene, turnedAt), "after the orbit, no cube where the projection puts it: $turnedAt")
            assertTrue(!lit(turned.scene, openedAt), "after the orbit, the cube is still where it was: $openedAt")
            assertTrue(lit(turned.capture, openedAt), "the Scene view's orbit moved the capturable frame's camera")

            // 3. The dolly.
            backend.onRenderThread { camera.dolly(DOLLY) }
            val closer = frame(backend, scene, slot)
            val closerAt = projected(backend, camera)
            save("viewport-orbit-dolly.png", closer.scene)
            assertTrue(lit(closer.scene, closerAt), "after the dolly, no cube where the projection puts it: $closerAt")
            assertTrue(
                distance(closerAt, CENTRE) > distance(turnedAt, CENTRE) + MIN_MOVE / 2f,
                "moving in did not move an off-centre cube outwards: $turnedAt to $closerAt",
            )
        } finally {
            backend.close()
        }
    }

    // --- fixture -------------------------------------------------------------------------

    private class Frame(val capture: BufferedImage, val scene: BufferedImage)

    /** The capturable frame and the Scene view, requested between two frames so one frame serves both. */
    private fun frame(
        backend: KoolBackend,
        scene: dev.wildware.udea.render.view.WorldViewport,
        slot: dev.wildware.udea.render.capture.FrameCaptureSlot,
    ): Frame {
        val (capture, view) = backend.onRenderThread { slot.submit(CaptureRequest()) to scene.capture() }
        return Frame(decode(await(capture).bytes), decode(await(view).bytes))
    }

    /** Where the camera puts the cube's centre now, read on the render thread between frames. */
    private fun projected(backend: KoolBackend, camera: EditorCamera): ViewPoint = backend.onRenderThread {
        ViewPoint().also { check(camera.project(CUBE_X, CUBE_Y, CUBE_Z, it)) { "the cube is behind the eye" } }
    }

    private fun await(result: Deferred<CaptureResult>): CaptureResult =
        runBlocking { withTimeout(CAPTURE_TIMEOUT_MILLIS) { result.await() } }

    private fun decode(png: ByteArray): BufferedImage =
        ImageIO.read(ByteArrayInputStream(png)) ?: error("the captured bytes are not a decodable image")

    /**
     * Whether most of a small window round view point [at] (bottom-left origin, as GL counts) is
     * lit - a cube face, not the black of the clear.
     */
    private fun lit(image: BufferedImage, at: ViewPoint): Boolean {
        val cx = at.x.toInt()
        val cy = image.height - 1 - at.y.toInt()
        var bright = 0
        var count = 0
        for (y in cy - HALF_WINDOW..cy + HALF_WINDOW) for (x in cx - HALF_WINDOW..cx + HALF_WINDOW) {
            if (x !in 0 until image.width || y !in 0 until image.height) continue
            val pixel = image.getRGB(x, y)
            val level = maxOf((pixel ushr 16) and 0xFF, (pixel ushr 8) and 0xFF, pixel and 0xFF)
            if (level > LIT_LEVEL) bright++
            count++
        }
        return count > 0 && bright * 2 > count
    }

    private fun distance(a: ViewPoint, b: ViewPoint): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }

    private fun white(): SpriteTexture {
        val rgba = ByteArray(4 * 4 * 4) { -1 }
        return SpriteTexture.fromRgba(4, 4, rgba, "orbit-test-white")
    }

    private fun save(name: String, image: BufferedImage) {
        val dir = System.getProperty("udea.render.glReportDir") ?: return
        File(dir).mkdirs()
        ImageIO.write(image, "png", File(dir, name))
    }

    private companion object {
        const val WIDTH = 320
        const val HEIGHT = 240
        val CENTRE = ViewPoint(WIDTH / 2f, HEIGHT / 2f)

        const val CUBE_X = 2.5f
        const val CUBE_Y = 1.5f
        const val CUBE_Z = 0.5f
        const val CUBE_SIZE = 0.8f

        /** The orbit's turn, in degrees: a quarter of the way round, and a little higher. */
        const val YAW = 90f
        const val PITCH = 15f
        const val DOLLY = 0.8f

        /** Pixels the cube's projected point must move for a turn to be told from no turn. */
        const val MIN_MOVE = 30f

        const val HALF_WINDOW = 2
        const val LIT_LEVEL = 40

        const val CAPTURE_TIMEOUT_MILLIS = 10_000L
    }
}
