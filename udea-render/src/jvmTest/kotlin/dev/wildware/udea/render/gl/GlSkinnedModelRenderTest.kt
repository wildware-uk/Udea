package dev.wildware.udea.render.gl

import com.github.quillraven.fleks.Entity
import dev.wildware.udea.assets.AssetId
import dev.wildware.udea.assets.Model
import dev.wildware.udea.assets.ResPath
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.module.UdeaGameDef
import dev.wildware.udea.core.spatial.Animator
import dev.wildware.udea.core.spatial.Transform3D
import dev.wildware.udea.generated.CoreUdeaRegistry
import dev.wildware.udea.render.RenderPhase
import dev.wildware.udea.render.RenderRegistry
import dev.wildware.udea.render.backend.KoolBackend
import dev.wildware.udea.render.backend.WindowConfig
import dev.wildware.udea.render.capture.CaptureRequest
import dev.wildware.udea.render.capture.CaptureResult
import dev.wildware.udea.render.capture.FrameCaptureSlot
import dev.wildware.udea.render.capture.capture
import dev.wildware.udea.render.draw.SpriteTexture
import dev.wildware.udea.render.model.FoxClips
import dev.wildware.udea.render.model.ModelCamera
import dev.wildware.udea.render.model.ModelLight
import dev.wildware.udea.render.model.ModelMaterial
import dev.wildware.udea.render.model.ModelMesh
import dev.wildware.udea.render.model.ModelRenderSystem
import dev.wildware.udea.render.model.ModelRenderer
import dev.wildware.udea.render.model.loadModel
import dev.wildware.udea.render.view.EditorCamera
import dev.wildware.udea.render.view.ViewDimension
import dev.wildware.udea.render.view.WorldViewport
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Khronos Fox posed by its `Animator` and skinned on the GPU, through a real Kool context
 * (issue #242).
 *
 * ## What it reads
 *
 * The fox stands still: its `Transform3D` never changes, and the camera never moves. So every
 * pixel of the fox that is somewhere else at one tick than at another was put there by a bone.
 * The measure is the fox's silhouette - every pixel that is not the black the pass clears to - and
 * how many pixels are fox in one frame and not in the other.
 *
 * - With an `Animator` playing Walk, two ticks a quarter of a stride apart draw two different
 *   silhouettes: the legs have swung.
 * - The same ticks with no `Animator` - nothing to pose it, so skinning moves nothing - draw the
 *   same frame, to the byte.
 * - Put back at the same clip time from a later tick, the fox draws the frame it drew before, to
 *   the byte: the pose is the tick's, not the history's.
 * - An editor's Scene view, which draws the same nodes through a pass of its own (issue #234),
 *   sees the pose change too.
 *
 * The host is paused before the first frame, so the interpolation alpha is zero throughout and a
 * tick is moved on only by `host.run` on the render thread: every frame here is a known tick.
 *
 * One `@Test` because Kool allows one context per JVM (see `GlCaptureTest`).
 */
class GlSkinnedModelRenderTest {

    private val walk = FoxClips.Walk

    @Test
    fun `the fox's legs move with its clip, and only with its clip`() {
        GlAvailability.require()
        val fox = loadModel(exampleAssets(), Model(AssetId("models/fox"), ResPath("models/fox/Fox.glb")))

        withPausedHost { backend, host, system ->
            val slot = backend.pipeline!!.capture!!
            val scene = backend.openSceneView(EditorCamera().apply { dimension = ViewDimension.ThreeD })
            lateinit var entity: Entity
            backend.onRenderThread {
                entity = host.world.entity {
                    it += Transform3D(rotationZ = (PI / 2).toFloat(), scaleX = SCALE, scaleY = SCALE, scaleZ = SCALE)
                    it += ModelRenderer(model = fox)
                }
                // A built-in shape has no joints: its Animator is ignored, with no error. Off to
                // the side, out of the camera's view, so it is drawn but not measured.
                host.world.entity {
                    it += Transform3D(x = -40f)
                    it += ModelRenderer(ModelMesh.box(1f, 1f, 1f), ModelMaterial(white()))
                    it += Animator().apply { play(walk, Tick.ZERO) }
                }
            }
            awaitFox(slot)
            assertEquals(2, backend.onRenderThread { system.drawnCount }, "the fox and the crate are both drawn")

            // 1. No Animator: two ticks apart, the same frame.
            val stillBefore = frame(backend, slot, scene)
            backend.onRenderThread { host.run(QUARTER_STRIDE) }
            val stillAfter = frame(backend, slot, scene)
            save(stillBefore.capture, "skinned-fox-no-animator-a.png")
            save(stillAfter.capture, "skinned-fox-no-animator-b.png")
            assertContentEquals(
                pixels(stillBefore.capture), pixels(stillAfter.capture),
                "with no Animator the fox moved between two ticks",
            )

            // 2. Walk: a quarter of a stride apart, the legs are elsewhere.
            val start = backend.onRenderThread { host.tick }
            backend.onRenderThread {
                with(host.world) { entity.configure { it += Animator().apply { play(walk, start) } } }
            }
            val stepA = frame(backend, slot, scene)
            val tickA = backend.onRenderThread { host.tick }
            backend.onRenderThread { host.run(QUARTER_STRIDE) }
            val stepB = frame(backend, slot, scene)
            save(stepA.capture, "skinned-fox-walk-a.png")
            save(stepB.capture, "skinned-fox-walk-b.png")
            save(stepB.scene, "skinned-fox-walk-b-scene-view.png")
            val moved = moved(stepA.capture, stepB.capture)
            val movedInView = moved(stepA.scene, stepB.scene)
            val foxPixels = silhouette(stepA.capture)
            println("GlSkinnedModelRenderTest: fox $foxPixels px; $moved px moved in the capture, $movedInView in the Scene view")
            assertTrue(foxPixels >= MIN_FOX_PIXELS, "the fox is not drawn: $foxPixels pixels")
            assertTrue(moved >= MIN_MOVED_PIXELS, "Walk ticks ${tickA.value} and ${tickA.value + QUARTER_STRIDE}: only $moved pixels of the fox moved")
            assertTrue(movedInView >= MIN_MOVED_PIXELS, "the Scene view did not see the pose change: $movedInView pixels moved")

            // 3. The same clip time again, from a later tick: Walk started over, a quarter of a
            // stride ago. The same frame.
            backend.onRenderThread {
                with(host.world) { entity[Animator].play(walk, host.tick) }
                host.run(QUARTER_STRIDE)
            }
            val again = frame(backend, slot, scene)
            assertContentEquals(
                pixels(stepB.capture), pixels(again.capture),
                "the fox at the same clip time, reached from another tick, drew a different frame",
            )
        }
    }

    // --- fixture -------------------------------------------------------------------------

    private class Frame(val capture: BufferedImage, val scene: BufferedImage)

    private fun withPausedHost(block: (KoolBackend, GameHost, ModelRenderSystem) -> Unit) {
        val registry = RenderRegistry()
        lateinit var system: ModelRenderSystem
        // Side-on and a little above, framing a fox about 1.6 tall and 3.1 long.
        val camera = ModelCamera().apply { lookAt(0f, -5.5f, 1.6f, 0f, 0f, 0.8f) }
        val light = ModelLight(directionX = -0.4f, directionY = 0.7f, directionZ = -1f)
        registry.register(RenderPhase.World, { resources ->
            ModelRenderSystem(resources, camera, light).also { system = it }
        })
        val backend = KoolBackend.start(
            RenderMode.Offscreen,
            WindowConfig(
                title = "udea-skinned-model-test",
                windowWidth = WIDTH,
                windowHeight = HEIGHT,
                renderWidth = WIDTH,
                renderHeight = HEIGHT,
            ),
            registry,
        )
        try {
            val host = GameHost(RenderMode.Offscreen, UdeaGameDef(registry = CoreUdeaRegistry, modules = emptyList()), backend)
            // Paused before any frame: alpha stays zero, and only `host.run` moves the tick.
            host.loop.paused = true
            backend.drive(host)
            block(backend, host, system)
        } finally {
            backend.close()
        }
    }

    /** Frames until the fox's texture has decoded and Kool draws it, within a budget. */
    private fun awaitFox(slot: FrameCaptureSlot) {
        var frames = 0
        var pixels: Int
        do {
            pixels = silhouette(decode(slot.capture(CaptureRequest()).bytes))
            frames++
        } while (pixels < MIN_FOX_PIXELS && frames < FRAME_BUDGET)
        println("GlSkinnedModelRenderTest: the fox appeared after $frames captured frame(s)")
    }

    /** The capturable frame and the Scene view, requested between two frames so one frame serves both. */
    private fun frame(backend: KoolBackend, slot: FrameCaptureSlot, scene: WorldViewport): Frame {
        val (capture, view) = backend.onRenderThread { slot.submit(CaptureRequest()) to scene.capture() }
        return Frame(decode(await(capture).bytes), decode(await(view).bytes))
    }

    private fun await(result: Deferred<CaptureResult>): CaptureResult =
        runBlocking { withTimeout(CAPTURE_TIMEOUT_MILLIS) { result.await() } }

    private fun isFox(pixel: Int): Boolean =
        ((pixel ushr 16) and 0xFF) > BACKGROUND || ((pixel ushr 8) and 0xFF) > BACKGROUND || (pixel and 0xFF) > BACKGROUND

    private fun silhouette(image: BufferedImage): Int {
        var n = 0
        for (y in 0 until image.height) for (x in 0 until image.width) if (isFox(image.getRGB(x, y))) n++
        return n
    }

    /** Pixels that are fox in one image and not in the other. */
    private fun moved(a: BufferedImage, b: BufferedImage): Int {
        var n = 0
        for (y in 0 until a.height) for (x in 0 until a.width) if (isFox(a.getRGB(x, y)) != isFox(b.getRGB(x, y))) n++
        return n
    }

    private fun pixels(image: BufferedImage): IntArray = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)

    private fun decode(png: ByteArray): BufferedImage = ImageIO.read(ByteArrayInputStream(png))
        ?: error("the captured bytes are not a decodable image")

    private fun white(): SpriteTexture = SpriteTexture.fromRgba(4, 4, ByteArray(4 * 4 * 4) { -1 }, "skinned-test-white")

    private fun exampleAssets(): Path = Path.of(
        System.getProperty("udea.render.exampleAssets") ?: error("-Dudea.render.exampleAssets is not set"),
    )

    /** Writes [image] where a person can look at it: the frame the assertions read. */
    private fun save(image: BufferedImage, name: String) {
        val dir = System.getProperty("udea.render.glReportDir") ?: return
        File(dir).mkdirs()
        ImageIO.write(image, "png", File(dir, name))
    }

    private companion object {
        const val WIDTH = 480
        const val HEIGHT = 320

        /** World units per unit of the file: a fox about 1.6 tall. */
        const val SCALE = 0.02f

        /** A channel above this is not the black the capture clears to. */
        const val BACKGROUND = 12

        /** Side-on at this size the fox covers about 8900 pixels; fewer than this is not a fox. */
        const val MIN_FOX_PIXELS = 5_000

        /** A quarter of Walk's 43-tick stride, in ticks. */
        const val QUARTER_STRIDE = 11

        /** Fewer silhouette pixels than this changing is not a leg swinging. */
        const val MIN_MOVED_PIXELS = 150

        /** Frames allowed for the texture to decode and the fox to appear. */
        const val FRAME_BUDGET = 240

        const val CAPTURE_TIMEOUT_MILLIS = 10_000L
    }
}
