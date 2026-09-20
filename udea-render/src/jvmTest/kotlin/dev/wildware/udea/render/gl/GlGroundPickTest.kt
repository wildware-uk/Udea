package dev.wildware.udea.render.gl

import com.github.quillraven.fleks.Entity
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.module.CoreModule
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
import dev.wildware.udea.render.camera.IsometricRig
import dev.wildware.udea.render.capture.CaptureRequest
import dev.wildware.udea.render.capture.FrameCaptureSlot
import dev.wildware.udea.render.capture.capture
import dev.wildware.udea.render.draw.Rgba
import dev.wildware.udea.render.draw.SpriteTexture
import dev.wildware.udea.render.input.PointerPosition
import dev.wildware.udea.render.model.ModelCamera
import dev.wildware.udea.render.model.ModelLight
import dev.wildware.udea.render.model.ModelMaterial
import dev.wildware.udea.render.model.ModelMesh
import dev.wildware.udea.render.model.ModelRenderSystem
import dev.wildware.udea.render.model.ModelRenderer
import dev.wildware.udea.render.pick.WorldPointer
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **Issue #262's two criteria, measured in the pixels a real Kool context actually drew.**
 *
 * `CameraPickTest` proves the un-projection comes back to its own pixel through the camera's own
 * matrices, and `GlIsoCameraTest` proves those matrices describe the picture. That is a chain of two
 * links, and this closes it into one measurement that needs neither: a marker is **placed in the
 * world** at the point the picker says is under a chosen pixel, the frame is read back, and where
 * the marker was drawn is compared with the pixel that was chosen.
 *
 * ```
 * a chosen pixel -> WorldPointer.aim -> a world point -> an entity moved there
 *                -> Kool draws it -> a capture -> the marker's centroid -> back to the pixel?
 * ```
 *
 * Nothing in that loop is arithmetic this file wrote. If the un-projection, the projection, the
 * camera or the renderer disagreed about anything, the marker would be drawn somewhere else.
 *
 * ## Criterion one: within one render pixel, at three zoom levels
 *
 * [ZOOMS] is 8, 20 and 60 world units of view height - a unit filling a quarter of the screen, the
 * default, and a view of a whole map. Nine cursor positions each, corners included. The worst error
 * over all of them is printed and asserted against [PIXEL_TOLERANCE].
 *
 * The tolerance is one pixel *of the measurement*, not of the arithmetic: the marker is a small
 * square of ground and its centroid is found by averaging the pixels that matched its colour, so the
 * reading carries a rasterisation error the arithmetic does not. The arithmetic's own figure is in
 * `CameraPickTest`, which measures it at under a thousandth of a pixel.
 *
 * ## Criterion two: clicking a model returns that entity
 *
 * Three cubes with `NetId`s stand on the ground. Each is projected to a pixel through the camera's
 * published matrices - not through the picker - and picking that pixel must name that cube. A pixel
 * over open ground must name none, which is the half that catches a picker that answers whatever it
 * looked at last.
 *
 * One `@Test`, because Kool allows one context per JVM (see `GlCaptureTest`).
 */
class GlGroundPickTest {

    @Test
    fun `the ground under the cursor is drawn under the cursor, at three zoom levels, and a model under it is named`() {
        GlAvailability.require()

        withStage { backend, host, rig, pointer ->
            val slot = backend.pipeline!!.capture!!
            lateinit var marker: Entity
            val cubes = LinkedHashMap<String, NetId>()

            backend.onRenderThread {
                rig.moveHalfLife = 0f
                val world = host.world
                world.entity {
                    it += Transform3D()
                    it += ModelRenderer(ModelMesh.plane(GROUND_SIZE, GROUND_SIZE), ModelMaterial(ground(), roughness = 0.95f))
                }
                // The marker: a bright square lying flat on the ground, moved to whatever the picker
                // says is under the cursor. Deliberately **without** a `NetId`, so it is not itself
                // pickable and cannot become the answer to the second criterion.
                //
                // A **plane** rather than a thin box, and lifted by a hair rather than by a
                // millimetre, because both of those are measurement error and the first round of
                // this test measured them: a box 0.04 units thick shows its top face and a sliver of
                // its sides to a camera tilted 30 degrees above the ground, and its silhouette's
                // centroid sat a flat 1.265 pixels up the screen from its centre at the closest
                // zoom. A plane has no sides, and what is left is the lift alone - at [MARKER_LIFT]
                // and a view height of 8, 0.01 * cos(30) / 4 * 160 = 0.35 pixels, which is inside
                // the criterion and shrinks as the view widens. Nothing here corrects for it: it is
                // a real upward bias in the reading, stated rather than subtracted.
                marker = world.entity {
                    it += Transform3D(z = MARKER_LIFT)
                    it += ModelRenderer(
                        ModelMesh.plane(MARKER_SIDE, MARKER_SIDE),
                        ModelMaterial(solid(MARKER_RED, MARKER_GREEN, MARKER_BLUE, "pick-marker"), roughness = 0.9f),
                    )
                }
            }
            // A new mesh can come back empty on its first frame (see GlModelRenderTest).
            frame(slot)
            frame(slot)

            // --- criterion one -------------------------------------------------------------
            var worst = 0f
            var worstAt = ""
            for (zoom in ZOOMS) {
                backend.onRenderThread {
                    rig.viewHeight = zoom
                    // The marker keeps its size **on the screen**, not in the world. A square of a
                    // fixed number of world units is 20 pixels across at the closest zoom and four
                    // at the widest, and four pixels is not a centroid - the first run of this test
                    // failed at a view height of 60 for exactly that, with the marker present and
                    // simply too small to measure.
                    val scale = zoom * MARKER_SCREEN_FRACTION
                    with(host.world) {
                        marker[Transform3D].scaleX = scale
                        marker[Transform3D].scaleY = scale
                    }
                }
                frame(slot)
                var worstHere = 0f
                for (cursor in CURSORS) {
                    val pixelX = WIDTH * cursor.first
                    val pixelY = HEIGHT * cursor.second
                    val aimed = backend.onRenderThread {
                        pointer.aim(pixelX, pixelY, WIDTH, HEIGHT)
                        if (!pointer.isOnWorld) null else pointer.worldX to pointer.worldY
                    } ?: error("no ground under ($pixelX, $pixelY) at a view height of $zoom")
                    backend.onRenderThread {
                        with(host.world) { marker[Transform3D].x = aimed.first; marker[Transform3D].y = aimed.second }
                    }
                    frame(slot)
                    val shot = frame(slot)
                    // One picture per cursor position at the middle zoom, so a person can see the
                    // marker walk the same grid the cursor did rather than take the numbers for it.
                    if (zoom == ZOOMS[1]) {
                        val row = (cursor.second * 10).toInt()
                        val column = (cursor.first * 10).toInt()
                        save(shot, "issue262-cursor-r${row}c$column.png")
                    }
                    val blob = blob(shot, ::isMarker)
                    assertTrue(
                        blob.pixels >= MIN_MARKER_PIXELS,
                        "at a view height of $zoom the marker placed at $aimed is not in the picture " +
                            "(${blob.pixels} pixels); it was aimed at ($pixelX, $pixelY)",
                    )
                    val error = hypot(blob.x - pixelX, blob.y - pixelY)
                    println(
                        "GlGroundPickTest: view height $zoom: cursor ($pixelX, $pixelY) -> world $aimed, " +
                            "drawn at (${blob.x}, ${blob.y}) over ${blob.pixels} pixels: $error px",
                    )
                    if (error > worstHere) worstHere = error
                    if (error > worst) {
                        worst = error
                        worstAt = "view height $zoom, cursor ($pixelX, $pixelY)"
                    }
                }
                println("GlGroundPickTest: view height $zoom: worst $worstHere px")
                // The middle of the screen, kept as the picture for that zoom.
                backend.onRenderThread {
                    pointer.aim(WIDTH / 2f, HEIGHT / 2f, WIDTH, HEIGHT)
                    with(host.world) { marker[Transform3D].x = pointer.worldX; marker[Transform3D].y = pointer.worldY }
                }
                frame(slot)
                save(frame(slot), "issue262-ground-zoom${zoom.toInt()}.png")
            }
            assertTrue(
                worst < PIXEL_TOLERANCE,
                "the ground point under the cursor was drawn $worst pixels away from the cursor at $worstAt, " +
                    "past the one-pixel criterion",
            )

            // --- criterion two -------------------------------------------------------------
            //
            // The cubes go in **now**, and not with the ground above. On the first run of this test
            // they stood there throughout, and at the closest zoom one of them threw a shadow over
            // the marker: the shadowed magenta stopped matching, thirty of the marker's pixels went
            // missing and its centroid moved two pixels. Criterion one is about an empty ground
            // plane, so it gets one.
            backend.onRenderThread {
                val world = host.world
                val netIds = host.ctx[CoreModule.NET_IDS]
                for (cube in CUBES) {
                    val entity = world.entity {
                        it += Transform3D(x = cube.x, y = cube.y, z = CUBE_SIDE / 2f)
                        it += ModelRenderer(
                            ModelMesh.box(CUBE_SIDE, CUBE_SIDE, CUBE_SIDE),
                            ModelMaterial(solid(cube.red, cube.green, cube.blue, cube.name), roughness = 0.95f),
                        )
                    }
                    cubes[cube.name] = netIds.allocate(entity)
                }
                rig.viewHeight = ZOOMS[1]
            }
            frame(slot)
            frame(slot)
            val camera = backend.onRenderThread { copyOf(rig.camera) }
            // Park the marker well off the cubes so it is in none of the pictures below.
            backend.onRenderThread {
                with(host.world) { marker[Transform3D].x = PARKED; marker[Transform3D].y = PARKED }
            }
            frame(slot)
            save(frame(slot), "issue262-cubes.png")

            for (cube in CUBES) {
                // Where this cube draws, from the camera's own matrices and not from the picker:
                // the two have to agree, and asking the picker where to click would be asking it
                // to mark its own homework.
                val at = dev.wildware.udea.render.support.CameraProjections
                    .pixelOf(camera, cube.x, cube.y, CUBE_SIDE / 2f, WIDTH, HEIGHT)
                val picked = backend.onRenderThread {
                    pointer.aim(at.x, at.y, WIDTH, HEIGHT)
                    pointer.entity
                }
                println("GlGroundPickTest: the ${cube.name} cube draws at (${at.x}, ${at.y}) and picking there gave $picked")
                assertEquals(
                    cubes.getValue(cube.name),
                    picked,
                    "clicking the middle of the ${cube.name} cube did not name it",
                )
            }

            val bare = backend.onRenderThread {
                pointer.aim(EMPTY_PIXEL_X, EMPTY_PIXEL_Y, WIDTH, HEIGHT)
                pointer.isOnWorld to pointer.entity
            }
            println("GlGroundPickTest: picking open ground at ($EMPTY_PIXEL_X, $EMPTY_PIXEL_Y) gave ${bare.second}")
            assertTrue(bare.first, "the corner of the picture is still ground")
            assertEquals(
                NetId.NONE,
                bare.second,
                "a pixel with no model on it named one, so the picker answers whatever it saw last",
            )
        }
    }

    // --- measuring -------------------------------------------------------------------------

    private class Blob(val x: Float, val y: Float, val pixels: Int)

    private fun blob(image: BufferedImage, test: (Int, Int, Int) -> Boolean): Blob {
        var n = 0
        var sumX = 0L
        var sumY = 0L
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val pixel = image.getRGB(x, y)
                if (!test((pixel ushr 16) and 0xFF, (pixel ushr 8) and 0xFF, pixel and 0xFF)) continue
                n++
                sumX += x
                sumY += y
            }
        }
        if (n == 0) return Blob(-1f, -1f, 0)
        return Blob(sumX.toFloat() / n + HALF_PIXEL, sumY.toFloat() / n + HALF_PIXEL, n)
    }

    /** The marker's magenta, which nothing else in the scene comes near. */
    private fun isMarker(r: Int, g: Int, b: Int): Boolean = r > 120 && b > 120 && g < 90 && abs(r - b) < 90

    // --- the scene -------------------------------------------------------------------------

    private class Cube(val name: String, val x: Float, val y: Float, val red: Int, val green: Int, val blue: Int)

    /** A pale blue sky under everything, in no shape's colour. */
    private class Sky(private val resources: RenderResources) : RenderSystem {
        override fun render(target: OffscreenTarget, alpha: Float) {
            val batch = resources.batch
            batch.beginPixels()
            batch.fill(0f, 0f, target.width.toFloat(), target.height.toFloat(), Rgba.of(0.62f, 0.76f, 0.93f))
            batch.end()
        }
    }

    private fun withStage(block: (KoolBackend, GameHost, IsometricRig, WorldPointer) -> Unit) {
        val definition = UdeaGameDef(registry = CoreUdeaRegistry, modules = emptyList())
        val registry = RenderRegistry()
        val camera = ModelCamera()
        lateinit var rig: IsometricRig
        lateinit var pointer: WorldPointer
        lateinit var models: ModelRenderSystem
        val light = ModelLight(
            directionX = -0.4f,
            directionY = 0.5f,
            directionZ = -1f,
            intensity = 2f,
            ambient = Rgba.of(0.45f, 0.45f, 0.45f),
            shadowDistance = SHADOW_DISTANCE,
        )
        registry.register(RenderPhase.PreRender, { resources -> Sky(resources) })
        registry.register(RenderPhase.PreRender, { resources ->
            IsometricRig(resources, registry.frameTime, camera).also { rig = it }
        })
        registry.register(RenderPhase.World, { resources ->
            ModelRenderSystem(resources, camera, light).also { models = it }
        })
        // Last, so it is built after the system it picks from. The cursor is supplied by `aim`
        // rather than by a device, which is why `PointerPosition.NONE` is enough here.
        registry.register(RenderPhase.PreRender, { resources ->
            WorldPointer(resources, camera, PointerPosition.NONE, pickable = { listOf(models) })
                .also { pointer = it }
        })
        val backend = KoolBackend.start(
            RenderMode.Offscreen,
            WindowConfig(
                title = "udea-ground-pick",
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
            block(backend, host, rig, pointer)
        } finally {
            backend.close()
        }
    }

    private fun frame(slot: FrameCaptureSlot): BufferedImage =
        ImageIO.read(ByteArrayInputStream(slot.capture(CaptureRequest()).bytes))
            ?: error("the captured bytes are not a decodable image")

    /** The rig's camera as it stands, taken off the render thread so this thread can read it. */
    private fun copyOf(camera: ModelCamera): ModelCamera = ModelCamera(
        eyeX = camera.eyeX,
        eyeY = camera.eyeY,
        eyeZ = camera.eyeZ,
        targetX = camera.targetX,
        targetY = camera.targetY,
        targetZ = camera.targetZ,
        fovYDegrees = camera.fovYDegrees,
        near = camera.near,
        far = camera.far,
        projection = camera.projection,
        viewHeight = camera.viewHeight,
    )

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

    /** Sandy tiles, light and dark, so the ground reads as ground and matches nothing else. */
    private fun ground(): SpriteTexture {
        val size = 512
        val cell = size / 64
        val rgba = ByteArray(size * size * 4)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val light = ((x / cell) + (y / cell)) % 2 == 0
                val at = (y * size + x) * 4
                rgba[at] = (if (light) 138 else 104).toByte()
                rgba[at + 1] = (if (light) 128 else 96).toByte()
                rgba[at + 2] = (if (light) 112 else 84).toByte()
                rgba[at + 3] = -1
            }
        }
        return SpriteTexture.fromRgba(size, size, rgba, "pick-ground")
    }

    /** Writes [image] where a person can look at it: the frame the assertions read. */
    private fun save(image: BufferedImage, name: String) {
        val dir = System.getProperty("udea.render.glReportDir") ?: return
        File(dir).mkdirs()
        ImageIO.write(image, "png", File(dir, name))
    }

    private companion object {
        const val WIDTH = 480
        const val HEIGHT = 320

        /** Three zoom levels, as the issue asks: close in, the default, and a whole map. */
        val ZOOMS = floatArrayOf(8f, 20f, 60f)

        /**
         * Nine cursor positions as fractions of the picture, corners and edges included.
         *
         * Not the middle only: the middle of the screen is where the camera looks, so it comes back
         * right under almost any mistake - an un-projection that ignored the field of view entirely
         * would still pass there.
         */
        val CURSORS = listOf(
            0.1f to 0.1f, 0.5f to 0.1f, 0.9f to 0.1f,
            0.1f to 0.5f, 0.5f to 0.5f, 0.9f to 0.5f,
            0.1f to 0.9f, 0.5f to 0.9f, 0.9f to 0.9f,
        )

        /** One render pixel, as the issue asks, on a measurement taken from a rasterised square. */
        const val PIXEL_TOLERANCE = 1f

        /** A pixel's centre: `blob` sums pixel indices, and index 0 covers 0..1. */
        const val HALF_PIXEL = 0.5f

        const val GROUND_SIZE = 200f
        const val CUBE_SIDE = 1.6f

        /** The marker's mesh: one world unit square, scaled per zoom so it is a constant size on screen. */
        const val MARKER_SIDE = 1f

        /** The marker's side as a fraction of the view's height: about 13 pixels at any zoom. */
        const val MARKER_SCREEN_FRACTION = 0.04f

        /** How far the marker floats over the ground: enough to beat depth fighting, and no more. */
        const val MARKER_LIFT = 0.01f

        const val MARKER_RED = 235
        const val MARKER_GREEN = 30
        const val MARKER_BLUE = 210

        /** Fewer than this and the centroid is noise rather than a measurement. */
        const val MIN_MARKER_PIXELS = 12

        /** Far outside the ground plane's own extent, so the marker is in no picture below. */
        const val PARKED = 400f

        const val SHADOW_DISTANCE = 60f

        /** Open ground, well away from every cube at the default zoom. */
        const val EMPTY_PIXEL_X = 40f
        const val EMPTY_PIXEL_Y = 40f

        val CUBES = listOf(
            Cube("red", -4f, -4f, 230, 40, 40),
            Cube("green", 4.5f, -1f, 40, 210, 60),
            Cube("blue", -1f, 5f, 50, 70, 235),
        )
    }
}
