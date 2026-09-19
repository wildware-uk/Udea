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
 * ## The shadow
 *
 * The shadow map is a pass of its own, and it must skin each model with that model's own pose
 * (#242, reopened). Kool's shadow pass shares one depth shader, and so one set of bone matrices,
 * between every mesh it has no depth shader for; so a skinned model's shadow took the pose of
 * whichever skinned model was drawn after it - a running fox beside a standing one cast a standing
 * shadow. A fox on its own was never wrong, so the last part draws two.
 *
 * It puts a blue floor under the fox, turns the camera to look down on it from one side and lets a
 * light from the other side throw the fox's side-on silhouette across the floor towards the camera,
 * then stands a second fox in its bind pose behind it, made after it so it is drawn after it. The
 * measure is the floor's shadow: a pixel is floor when it is blue, which no part of either fox is,
 * and in shadow when its blue is dark. The first fox in its bind pose and the same fox mid-stride in
 * Run must throw different shadows on the pixels that are floor in both frames - so a leg moving
 * in front of the floor is not counted as its shadow moving. The second fox is the same in both
 * frames, so nothing it draws is counted.
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

            // 4. The shadow, with a second fox in the frame: in the bind pose and mid-stride in Run.
            backend.onRenderThread {
                camera.lookAt(0f, 7f, 6f, 0f, 0.8f, 0f)
                light.directionX = 0f
                light.directionY = 1f
                light.directionZ = -SHADOW_SLOPE
                host.world.entity {
                    it += Transform3D()
                    it += ModelRenderer(ModelMesh.plane(FLOOR_SIZE, FLOOR_SIZE), ModelMaterial(floor()))
                }
                // No Animator: the bind pose, in every frame.
                host.world.entity {
                    it += Transform3D(y = SECOND_FOX_Y, rotationZ = (PI / 2).toFloat(), scaleX = SCALE, scaleY = SCALE, scaleZ = SCALE)
                    it += ModelRenderer(model = fox)
                }
                with(host.world) { entity.configure { it -= Animator } }
            }
            val bindPose = frame(backend, slot, scene).capture
            backend.onRenderThread {
                with(host.world) { entity.configure { it += Animator().apply { play(FoxClips.Run, host.tick) } } }
                host.run(RUN_MID_STRIDE)
            }
            val running = frame(backend, slot, scene).capture
            save(bindPose, "skinned-fox-shadow-bind-pose.png")
            save(running, "skinned-fox-shadow-run.png")
            val shadow = shadowed(bindPose)
            val shadowMoved = shadowMoved(bindPose, running)
            println("GlSkinnedModelRenderTest: bind-pose shadows $shadow px; $shadowMoved floor px changed shadow in Run")
            assertTrue(shadow >= MIN_SHADOW_PIXELS, "the foxes cast no shadow on the floor: $shadow pixels")
            assertTrue(
                shadowMoved >= MIN_MOVED_PIXELS,
                "the fox's shadow mid-stride in Run is its bind-pose shadow: only $shadowMoved floor pixels changed",
            )
        }
    }

    // --- fixture -------------------------------------------------------------------------

    private class Frame(val capture: BufferedImage, val scene: BufferedImage)

    // Side-on and a little above, framing a fox about 1.6 tall and 3.1 long. Both are read by the
    // system every frame, so the shadow part moves them on the render thread.
    private val camera = ModelCamera().apply { lookAt(0f, -5.5f, 1.6f, 0f, 0f, 0.8f) }
    private val light = ModelLight(directionX = -0.4f, directionY = 0.7f, directionZ = -1f)

    private fun withPausedHost(block: (KoolBackend, GameHost, ModelRenderSystem) -> Unit) {
        val registry = RenderRegistry()
        lateinit var system: ModelRenderSystem
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

    private fun silhouette(image: BufferedImage): Int = count(image, ::isFox)

    /** Pixels that are fox in one image and not in the other. */
    private fun moved(a: BufferedImage, b: BufferedImage): Int = count(a, b) { pa, pb -> isFox(pa) != isFox(pb) }

    /** The floor is blue, and no part of the fox is: a lit floor, or one in shadow. */
    private fun isFloor(pixel: Int): Boolean {
        val r = (pixel ushr 16) and 0xFF
        val g = (pixel ushr 8) and 0xFF
        val b = pixel and 0xFF
        return b > r + FLOOR_MARGIN && b > g + FLOOR_MARGIN
    }

    private fun isShadow(pixel: Int): Boolean = isFloor(pixel) && (pixel and 0xFF) < SHADOW_BLUE

    private fun shadowed(image: BufferedImage): Int = count(image, ::isShadow)

    /** Pixels that are floor in both images, in shadow in one and lit in the other. */
    private fun shadowMoved(a: BufferedImage, b: BufferedImage): Int =
        count(a, b) { pa, pb -> isFloor(pa) && isFloor(pb) && isShadow(pa) != isShadow(pb) }

    /** Pixels of [image] that pass [test]. */
    private fun count(image: BufferedImage, test: (Int) -> Boolean): Int {
        var n = 0
        for (y in 0 until image.height) for (x in 0 until image.width) if (test(image.getRGB(x, y))) n++
        return n
    }

    /** Pixels at which [a] and [b], the same size, pass [test] together. */
    private fun count(a: BufferedImage, b: BufferedImage, test: (Int, Int) -> Boolean): Int {
        var n = 0
        for (y in 0 until a.height) for (x in 0 until a.width) if (test(a.getRGB(x, y), b.getRGB(x, y))) n++
        return n
    }

    private fun pixels(image: BufferedImage): IntArray = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)

    private fun decode(png: ByteArray): BufferedImage = ImageIO.read(ByteArrayInputStream(png))
        ?: error("the captured bytes are not a decodable image")

    private fun floor(): SpriteTexture = SpriteTexture.fromRgba(
        4, 4, ByteArray(4 * 4 * 4) { i -> FLOOR_RGBA[i % 4] }, "skinned-test-floor",
    )

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

        /** The light falls this much for each unit it travels along +Y. */
        const val SHADOW_SLOPE = 1.1f

        /** Run's clip time, in ticks: legs stretched fore and aft, the tail lifted clear of the body. */
        const val RUN_MID_STRIDE = 28

        /** Where the second fox stands: behind the first, its shadow clear of the first's. */
        const val SECOND_FOX_Y = -3f

        const val FLOOR_SIZE = 30f

        /** The floor's albedo: blue, which no part of the Fox is. */
        val FLOOR_RGBA = byteArrayOf(40, 90, 230.toByte(), -1)

        /** A floor pixel's blue must beat its red and its green by this much. */
        const val FLOOR_MARGIN = 30

        /** Floor darker in blue than this is in shadow: shadowed floor is about 150, lit about 210. */
        const val SHADOW_BLUE = 180

        /** A fox's shadow smaller than this is not a fox's shadow. */
        const val MIN_SHADOW_PIXELS = 2_000
    }
}
