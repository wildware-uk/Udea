package dev.wildware.udea.render.gl

import com.github.quillraven.fleks.Entity
import dev.wildware.udea.assets.AssetId
import dev.wildware.udea.assets.Model
import dev.wildware.udea.assets.ResPath
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.module.UdeaGameDef
import dev.wildware.udea.core.spatial.Transform3D
import dev.wildware.udea.generated.CoreUdeaRegistry
import dev.wildware.udea.render.OffscreenTarget
import dev.wildware.udea.render.RenderPhase
import dev.wildware.udea.render.RenderRegistry
import dev.wildware.udea.render.RenderResources
import dev.wildware.udea.render.RenderSystem
import dev.wildware.udea.render.backend.KoolBackend
import dev.wildware.udea.render.backend.WindowConfig
import dev.wildware.udea.render.camera.ThirdPersonRig
import dev.wildware.udea.render.capture.CaptureRequest
import dev.wildware.udea.render.capture.FrameCaptureSlot
import dev.wildware.udea.render.capture.capture
import dev.wildware.udea.render.draw.Rgba
import dev.wildware.udea.render.draw.SpriteTexture
import dev.wildware.udea.render.kool.KoolPointer
import dev.wildware.udea.render.model.ModelCamera
import dev.wildware.udea.render.model.ModelLight
import dev.wildware.udea.render.model.ModelMaterial
import dev.wildware.udea.render.model.ModelMesh
import dev.wildware.udea.render.model.ModelRenderSystem
import dev.wildware.udea.render.model.ModelRenderer
import dev.wildware.udea.render.model.loadModel
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The third-person rig (issue #248) through a real Kool context, a real `ModelRenderSystem` and a
 * real mouse: **what follows is what a player sees.**
 *
 * `ThirdPersonRigTest` holds the rig's numbers - where the eye is, where it looks. That says nothing
 * about whether a `ModelRenderSystem` handed the rig's camera draws through it, or whether the frame
 * it draws has the target where the numbers put it, or whether Kool's mouse ever reaches the rig at
 * all. So here the pictures are read back:
 *
 * 1. **Centred, at the configured distance.** The followed entity is a red ball whose centre is the
 *    rig's focus. It is moved about the world; in every captured frame the red pixels' centroid is the
 *    middle of the picture, and their area is the size a ball of that radius has at the configured
 *    distance through the camera's field of view. Doubling the distance halves it.
 * 2. **The mouse turns the view.** Raw GLFW cursor moves go in through Kool's own callback - the entry
 *    point a real mouse uses - and travel Kool's `PointerInput`, its `InputStack` and `KoolPointer`
 *    to the rig. Moving right turns the view right: a fixed blue marker ahead and to the right swings
 *    towards the middle, and the ball stays centred. Moving down tips the view down.
 * 3. **From behind a model, at two yaw angles.** The Khronos Fox, followed from behind as it faces one
 *    way and then another, on a checkered ground, and the mouse turning the view round it. These are
 *    the pictures a person looks at; they are written to the GL report directory.
 *
 * One `@Test`, because Kool allows one context per JVM (see `GlCaptureTest`).
 */
class GlThirdPersonRigTest {

    @Test
    fun `the rig keeps its target centred at its distance, and the mouse turns the view`() {
        GlAvailability.require()

        withRig { backend, host, rig ->
            val slot = backend.pipeline!!.capture!!
            val world = host.world

            lateinit var ball: Entity
            lateinit var marker: Entity
            lateinit var ground: Entity
            backend.onRenderThread {
                // Below everything the ball does, so the ground shows the world moving under a ball that
                // stays in the middle, and never hides part of the ball.
                ground = world.entity {
                    it += Transform3D(z = -2f)
                    it += ModelRenderer(ModelMesh.plane(GROUND_SIZE, GROUND_SIZE), ModelMaterial(ground(), roughness = 0.9f))
                }
                ball = world.entity {
                    it += Transform3D()
                    it += ModelRenderer(ModelMesh.sphere(BALL_RADIUS, steps = 48), ModelMaterial(solid(220, 30, 30, "rig-ball")))
                }
                marker = world.entity {
                    it += Transform3D(x = 2f, y = 4f, z = 0.5f)
                    it += ModelRenderer(ModelMesh.box(1f, 1f, 1f), ModelMaterial(solid(30, 60, 220, "rig-marker")))
                }
                rig.target = definitionNetIds.allocate(ball)
                rig.followHalfLife = 0f
                rig.focusHeight = 0f
                rig.distance = DISTANCE
                rig.yawDegrees = 90f
                rig.pitchDegrees = 20f
                rig.degreesPerPixel = DEGREES_PER_PIXEL
            }
            // A new mesh can come back empty on its first frame (see GlModelRenderTest).
            slot.capture(CaptureRequest())

            // 1. Centred, at the configured distance, wherever the ball goes.
            val expected = ballRadiusPixels(DISTANCE)
            val places = listOf(
                Triple(0f, 0f, 0f),
                Triple(4f, -3f, 0.5f),
                Triple(-6f, 5f, 1.5f),
                Triple(12f, 9f, 0f),
            )
            for ((index, place) in places.withIndex()) {
                backend.onRenderThread {
                    with(world) {
                        val transform = ball[Transform3D]
                        transform.x = place.first
                        transform.y = place.second
                        transform.z = place.third
                    }
                }
                val image = frame(slot)
                save(image, "issue248-centred-$index.png")
                val red = blob(image, ::isRed)
                println("GlThirdPersonRigTest: ball at $place: centroid (${red.x}, ${red.y}), radius ${red.radius}, expected $expected")
                assertCentred(red, image, "the ball at $place")
                assertNear(expected, red.radius, "the ball at $place is not the size its distance gives it")
            }

            backend.onRenderThread { rig.distance = DISTANCE * 2f }
            val far = blob(frame(slot), ::isRed)
            println("GlThirdPersonRigTest: at twice the distance the radius is ${far.radius}, expected ${ballRadiusPixels(DISTANCE * 2f)}")
            assertNear(ballRadiusPixels(DISTANCE * 2f), far.radius, "the ball at twice the distance is not half the size")
            backend.onRenderThread {
                rig.distance = DISTANCE
                with(world) { ball[Transform3D].x = 0f; ball[Transform3D].y = 0f; ball[Transform3D].z = 0f }
            }

            // 2. The real mouse turns the view. The first move puts Kool's pointer somewhere: its
            // motion is from wherever Kool thought the pointer was, and is spent before measuring.
            mouse.moveTo(backend, MOUSE_X, MOUSE_Y)
            frame(slot)
            frame(slot)
            backend.onRenderThread { rig.yawDegrees = 90f; rig.pitchDegrees = 20f }
            val before = frame(slot)
            save(before, "issue248-mouse-1-before.png")
            val markerBefore = blob(before, ::isBlue)
            assertTrue(markerBefore.x > before.width / 2 + 10, "the marker should start right of centre, was at ${markerBefore.x}")

            mouse.moveBy(backend, TURN_PIXELS.toDouble(), 0.0)
            settle(slot)
            val turned = frame(slot)
            save(turned, "issue248-mouse-2-turned-right.png")
            val yaw = backend.onRenderThread { rig.yawDegrees }
            println("GlThirdPersonRigTest: $TURN_PIXELS pixels right turned the yaw from 90 to $yaw")
            assertTrue(
                abs(yaw - (90f - TURN_PIXELS.toFloat() * DEGREES_PER_PIXEL)) < 0.5f,
                "moving the mouse $TURN_PIXELS pixels right should turn the view ${TURN_PIXELS * DEGREES_PER_PIXEL} degrees right; the yaw is $yaw",
            )
            val markerAfter = blob(turned, ::isBlue)
            println("GlThirdPersonRigTest: the marker moved from x=${markerBefore.x} to x=${markerAfter.x}")
            assertTrue(
                markerAfter.x < markerBefore.x - 20,
                "turning right did not swing the marker ahead-right towards the middle: ${markerBefore.x} to ${markerAfter.x}",
            )
            assertCentred(blob(turned, ::isRed), turned, "the ball after the mouse turned the view")

            val eyeBefore = backend.onRenderThread { rig.camera.eyeZ }
            mouse.moveBy(backend, 0.0, TIP_PIXELS.toDouble())
            settle(slot)
            val tipped = frame(slot)
            save(tipped, "issue248-mouse-3-tipped-down.png")
            val pitch = backend.onRenderThread { rig.pitchDegrees }
            val eyeAfter = backend.onRenderThread { rig.camera.eyeZ }
            println("GlThirdPersonRigTest: $TIP_PIXELS pixels down tipped the pitch from 20 to $pitch, the eye from $eyeBefore to $eyeAfter")
            assertTrue(
                abs(pitch - (20f + TIP_PIXELS.toFloat() * DEGREES_PER_PIXEL)) < 0.5f,
                "moving the mouse $TIP_PIXELS pixels down should tip the view ${TIP_PIXELS * DEGREES_PER_PIXEL} degrees down; the pitch is $pitch",
            )
            assertTrue(eyeAfter > eyeBefore, "tipping the view down did not raise the eye: $eyeBefore to $eyeAfter")
            assertCentred(blob(tipped, ::isRed), tipped, "the ball after the mouse tipped the view")

            // 3. From behind the fox, at two yaw angles, and the mouse turning round it.
            backend.onRenderThread { with(world) { ground[Transform3D].z = 0f } }
            photographTheFox(backend, host, rig, slot, listOf(ball, marker))
        }
    }

    /**
     * The fox on the checkered ground with three crates for landmarks, photographed from behind as it faces
     * [FIRST_YAW] and then [SECOND_YAW], and in six steps of the mouse turning the view round it.
     */
    private fun photographTheFox(backend: KoolBackend, host: GameHost, rig: ThirdPersonRig, slot: FrameCaptureSlot, remove: List<Entity>) {
        val world = host.world
        val foxModel = loadModel(exampleAssets(), Model(AssetId("models/fox"), ResPath("models/fox/Fox.glb")))
        lateinit var fox: Entity
        backend.onRenderThread {
            with(world) { remove.forEach { it.remove() } }
            for ((x, y) in listOf(3f to 5f, -4f to -3f, -2f to 6f)) {
                world.entity {
                    it += Transform3D(x = x, y = y, z = 0.5f, rotationZ = 0.4f)
                    it += ModelRenderer(ModelMesh.box(1f, 1f, 1f), ModelMaterial(solid(120, 120, 140, "rig-crate"), roughness = 0.8f))
                }
            }
            fox = world.entity {
                it += Transform3D(rotationZ = foxHeading(FIRST_YAW), scaleX = FOX_SCALE, scaleY = FOX_SCALE, scaleZ = FOX_SCALE)
                it += ModelRenderer(model = foxModel)
            }
            rig.target = definitionNetIds.allocate(fox)
            rig.focusHeight = 0.7f
            rig.distance = 4f
            rig.pitchDegrees = 18f
            // Coarser than above, so every turn below keeps the real pointer inside the window.
            rig.degreesPerPixel = FOX_DEGREES_PER_PIXEL
        }
        // Kool draws the fox once its texture has decoded on its loader threads (see
        // GlImportedModelRenderTest): frames until one shows the fox's orange.
        var image = frame(slot)
        var frames = 1
        while (count(image, ::isOrange) < MIN_FOX_PIXELS && frames < FOX_FRAME_BUDGET) {
            image = frame(slot)
            frames++
        }
        assertTrue(count(image, ::isOrange) >= MIN_FOX_PIXELS, "the fox never appeared in $frames frames")

        // Behind the fox as it faces FIRST_YAW, the camera turned there by the mouse.
        turnTo(backend, rig, slot, FIRST_YAW)
        val first = frame(slot)
        save(first, "issue248-fox-00-behind-yaw${FIRST_YAW.toInt()}.png")
        assertFoxCentred(first, "behind the fox at yaw $FIRST_YAW")

        // The mouse turns the view round the fox, which stays in the middle.
        for (step in 1..TURN_STEPS) {
            mouse.moveBy(backend, FOX_TURN_PIXELS.toDouble(), 0.0)
            settle(slot)
            val shot = frame(slot)
            val yaw = backend.onRenderThread { rig.yawDegrees }
            save(shot, "issue248-fox-%02d-turned-yaw%d.png".format(step, yaw.toInt()))
            assertFoxCentred(shot, "the fox at yaw $yaw, turned by the mouse")
        }

        // The fox turns to face SECOND_YAW, and the mouse brings the camera round behind it again.
        backend.onRenderThread { with(world) { fox[Transform3D].rotationZ = foxHeading(SECOND_YAW) } }
        turnTo(backend, rig, slot, SECOND_YAW)
        val second = frame(slot)
        save(second, "issue248-fox-%02d-behind-yaw%d.png".format(TURN_STEPS + 1, SECOND_YAW.toInt()))
        assertFoxCentred(second, "behind the fox at yaw $SECOND_YAW")
    }

    /** Moves the real mouse sideways by the pixels it takes to turn the rig to face [yaw]. */
    private fun turnTo(backend: KoolBackend, rig: ThirdPersonRig, slot: FrameCaptureSlot, yaw: Float) {
        val now = backend.onRenderThread { rig.yawDegrees }
        var turn = now - yaw
        if (turn > 180f) turn -= 360f
        if (turn <= -180f) turn += 360f
        // Right turns the view clockwise, which lowers the yaw: `turn` degrees right is that many pixels.
        mouse.moveBy(backend, (turn / FOX_DEGREES_PER_PIXEL).toDouble(), 0.0)
        settle(slot)
        val reached = backend.onRenderThread { rig.yawDegrees }
        println("GlThirdPersonRigTest: the mouse turned the view from $now to $reached (wanted $yaw)")
        assertTrue(abs(reached - yaw) < 0.5f, "the mouse was to turn the view to $yaw; it faces $reached")
    }

    private fun assertFoxCentred(image: BufferedImage, what: String) {
        val fox = blob(image, ::isOrange)
        println("GlThirdPersonRigTest: $what: the fox is centred at (${fox.x}, ${fox.y}) over ${fox.pixels} pixels")
        assertTrue(fox.pixels >= MIN_FOX_PIXELS, "$what: the fox is not in the picture (${fox.pixels} pixels)")
        // The fox is not a ball: its body is not symmetric about its origin, so its colour's centroid is
        // only near the middle. The ball above is the precise measure; this says the fox is framed.
        assertTrue(
            abs(fox.x - image.width / 2f) < image.width / 6f && abs(fox.y - image.height / 2f) < image.height / 4f,
            "$what: the fox is not in the middle of the picture, its centroid is (${fox.x}, ${fox.y})",
        )
    }

    // --- fixture -------------------------------------------------------------------------

    /** A pale blue sky, one fill under everything. Never red, never blue enough to read as the marker. */
    private class Sky(private val resources: RenderResources) : RenderSystem {
        override fun render(target: OffscreenTarget, alpha: Float) {
            val batch = resources.batch
            batch.beginPixels()
            batch.fill(0f, 0f, target.width.toFloat(), target.height.toFloat(), Rgba.of(0.62f, 0.76f, 0.93f))
            batch.end()
        }
    }

    /** Where this test last put the real mouse, so a move can be said as a distance. */
    private val mouse = Mouse()

    private class Mouse {
        var x = 0.0
        var y = 0.0

        fun moveTo(backend: KoolBackend, x: Double, y: Double) {
            check(x in 0.0..(WIDTH - 1.0) && y in 0.0..(HEIGHT - 1.0)) { "the test moved the mouse out of the window, to ($x, $y)" }
            this.x = x
            this.y = y
            backend.moveMouseTo(x, y)
        }

        fun moveBy(backend: KoolBackend, dx: Double, dy: Double) = moveTo(backend, x + dx, y + dy)
    }

    /** The index the rig resolves its target through: the definition's own, as a game hands it over. */
    private lateinit var definitionNetIds: dev.wildware.udea.core.identity.NetIdIndex

    private fun withRig(block: (KoolBackend, GameHost, ThirdPersonRig) -> Unit) {
        val definition = UdeaGameDef(registry = CoreUdeaRegistry, modules = emptyList())
        definitionNetIds = definition.core.netIds
        val registry = RenderRegistry()
        val camera = ModelCamera()
        lateinit var rig: ThirdPersonRig
        // Soft light from above and a strong ambient, so the ball's colour holds over the whole disc.
        val light = ModelLight(directionX = -0.3f, directionY = 0.5f, directionZ = -1f, intensity = 2.5f, ambient = Rgba.of(0.55f, 0.55f, 0.6f))
        // A sky under the 3D pass, so the pictures have a horizon. Registered first: it draws first.
        registry.register(RenderPhase.PreRender, { resources -> Sky(resources) })
        registry.register(RenderPhase.PreRender, { resources ->
            ThirdPersonRig(resources, definition.core.netIds, registry.frameTime, camera).also { rig = it }
        })
        registry.register(RenderPhase.World, { resources -> ModelRenderSystem(resources, camera, light) })
        val backend = KoolBackend.start(
            RenderMode.Offscreen,
            WindowConfig(
                title = "udea-third-person-rig",
                windowWidth = WIDTH,
                windowHeight = HEIGHT,
                renderWidth = WIDTH,
                renderHeight = HEIGHT,
            ),
            registry,
        )
        try {
            val host = GameHost(RenderMode.Offscreen, definition, backend)
            backend.drive(host)
            // Built on the render thread, where it joins Kool's input stack - as a game builds it.
            val pointer = backend.onRenderThread { KoolPointer() }
            backend.onRenderThread {
                rig.motion = pointer
                rig.buttons = pointer
            }
            block(backend, host, rig)
        } finally {
            backend.close()
        }
    }

    /**
     * Lets a mouse move reach the rig: Kool reads the pointer at the top of a frame, and a move made
     * between frames can land in either of the next two, so two go by.
     */
    private fun settle(slot: FrameCaptureSlot) {
        frame(slot)
        frame(slot)
    }

    private fun frame(slot: FrameCaptureSlot): BufferedImage =
        ImageIO.read(ByteArrayInputStream(slot.capture(CaptureRequest()).bytes))
            ?: error("the captured bytes are not a decodable image")

    /** Where a colour sits in a picture: its centroid, its pixel count, and the radius of a disc that size. */
    private class Blob(val x: Float, val y: Float, val pixels: Int) {
        val radius: Float get() = sqrt(pixels / PI.toFloat())
    }

    private fun blob(image: BufferedImage, test: (Int, Int, Int) -> Boolean): Blob {
        var n = 0
        var sumX = 0L
        var sumY = 0L
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val pixel = image.getRGB(x, y)
                if (test((pixel ushr 16) and 0xFF, (pixel ushr 8) and 0xFF, pixel and 0xFF)) {
                    n++
                    sumX += x
                    sumY += y
                }
            }
        }
        return if (n == 0) Blob(-1f, -1f, 0) else Blob(sumX.toFloat() / n, sumY.toFloat() / n, n)
    }

    private fun count(image: BufferedImage, test: (Int, Int, Int) -> Boolean): Int = blob(image, test).pixels

    private fun assertCentred(blob: Blob, image: BufferedImage, what: String) {
        assertTrue(blob.pixels > 0, "$what is not in the picture at all")
        val dx = blob.x - (image.width - 1) / 2f
        val dy = blob.y - (image.height - 1) / 2f
        assertTrue(
            abs(dx) <= CENTRE_TOLERANCE && abs(dy) <= CENTRE_TOLERANCE,
            "$what is not in the middle of the picture: its centroid is (${blob.x}, ${blob.y}), " +
                "the middle is (${(image.width - 1) / 2f}, ${(image.height - 1) / 2f})",
        )
    }

    private fun assertNear(expected: Float, actual: Float, what: String) {
        assertTrue(abs(actual - expected) <= expected * SIZE_TOLERANCE, "$what: $actual pixels, expected $expected")
    }

    /**
     * The radius, in pixels, a ball of [BALL_RADIUS] draws at when its centre is [distance] from the eye
     * and in the middle of the view: the half-angle it subtends, `asin(r / d)`, through the camera's
     * vertical field of view onto half the picture's height.
     */
    private fun ballRadiusPixels(distance: Float): Float {
        val halfAngle = asin(BALL_RADIUS / distance)
        val halfFov = ModelCamera().fovYDegrees / 2f * PI.toFloat() / 180f
        return HEIGHT / 2f * tan(halfAngle) / tan(halfFov)
    }

    private fun isRed(r: Int, g: Int, b: Int): Boolean = r > 60 && r > g * 2 && r > b * 2

    private fun isBlue(r: Int, g: Int, b: Int): Boolean = b > 60 && b > r * 2 && b > g + 30

    /** The fox's coat, as `GlImportedModelRenderTest` reads it. */
    private fun isOrange(r: Int, g: Int, b: Int): Boolean = r > b + 60 && g > b + 20 && r > g + 25

    /** The fox's heading for it to face [yaw]: its file faces [FOX_FORWARD_DEGREES] at a heading of 0. */
    private fun foxHeading(yaw: Float): Float = ((yaw - FOX_FORWARD_DEGREES) * PI / 180f).toFloat()

    private fun solid(r: Int, g: Int, b: Int, name: String): SpriteTexture {
        val size = 4
        val rgba = ByteArray(size * size * 4)
        for (i in 0 until size * size) {
            rgba[i * 4] = r.toByte()
            rgba[i * 4 + 1] = g.toByte()
            rgba[i * 4 + 2] = b.toByte()
            rgba[i * 4 + 3] = -1
        }
        return SpriteTexture.fromRgba(size, size, rgba, name)
    }

    /** Grass-green tiles, light and dark, about a world unit and a half across on the 200-unit ground, which reaches past the horizon: the ground the fox stands on. */
    private fun ground(): SpriteTexture {
        val size = 512
        val cell = size / 128
        val rgba = ByteArray(size * size * 4)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val light = ((x / cell) + (y / cell)) % 2 == 0
                val at = (y * size + x) * 4
                rgba[at] = (if (light) 96 else 64).toByte()
                rgba[at + 1] = (if (light) 150 else 112).toByte()
                rgba[at + 2] = (if (light) 80 else 60).toByte()
                rgba[at + 3] = -1
            }
        }
        return SpriteTexture.fromRgba(size, size, rgba, "rig-ground")
    }

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

        const val BALL_RADIUS = 0.5f
        const val DISTANCE = 6f
        const val DEGREES_PER_PIXEL = 0.2f

        /** Pixels from the middle a centroid may sit: a tessellated, lit ball is not a perfect disc. */
        const val CENTRE_TOLERANCE = 2f

        /** How far a measured radius may be from the predicted one, as a fraction of it. */
        const val SIZE_TOLERANCE = 0.08f

        const val MOUSE_X = 100.0
        const val MOUSE_Y = 100.0
        const val TURN_PIXELS = 100
        const val TIP_PIXELS = 50

        const val FIRST_YAW = 90f
        const val SECOND_YAW = -150f
        const val TURN_STEPS = 6

        /** Degrees a pixel turns the view round the fox, and the pixels of each of its turn steps. */
        const val FOX_DEGREES_PER_PIXEL = 1f
        const val FOX_TURN_PIXELS = 30

        /** The fox's file is in centimetre-like units, about 80 tall: this makes it 1.3 tall. */
        const val FOX_SCALE = 0.016f

        /**
         * The direction the fox faces at a heading of zero, in degrees counter-clockwise from +X: -Y.
         * Read off this test's own first picture, which with 90 here showed the fox's face.
         */
        const val FOX_FORWARD_DEGREES = -90f

        const val GROUND_SIZE = 200f
        const val MIN_FOX_PIXELS = 400
        const val FOX_FRAME_BUDGET = 240
    }
}
