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
import dev.wildware.udea.render.camera.IsometricRig
import dev.wildware.udea.render.capture.CaptureRequest
import dev.wildware.udea.render.capture.FrameCaptureSlot
import dev.wildware.udea.render.capture.capture
import dev.wildware.udea.render.draw.Rgba
import dev.wildware.udea.render.draw.SpriteTexture
import dev.wildware.udea.render.model.ModelCamera
import dev.wildware.udea.render.model.ModelLight
import dev.wildware.udea.render.model.ModelMaterial
import dev.wildware.udea.render.model.ModelMesh
import dev.wildware.udea.render.model.ModelProjection
import dev.wildware.udea.render.model.ModelRenderSystem
import dev.wildware.udea.render.model.ModelRenderer
import dev.wildware.udea.render.model.loadModel
import dev.wildware.udea.render.support.CameraProjections
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The orthographic isometric camera (issue #257) through a real Kool context, a real
 * `ModelRenderSystem` and a real [IsometricRig]: **what is flattened is what a player sees.**
 *
 * `ModelCameraTest` and `IsometricRigTest` hold the arithmetic - the matrices, where the eye goes,
 * what a pan does. None of that says whether `ModelStage` draws through an orthographic Kool camera
 * when the `ModelCamera` asks for one, so here the pixels are read back.
 *
 * Four identical cubes stand on flat ground, one to each side of what the camera looks at, with a thin
 * tall pillar to each side below them and one cube lifted well off the ground. Each is a colour of its
 * own, so each can be found in a capture on its own. Then:
 *
 * 1. **The exposed matrices are the picture.** Every shape's colour centroid is where
 *    [ModelCamera.writeViewMatrix] and [ModelCamera.writeProjectionMatrix] say it is, to within a few
 *    pixels. That is the property issue #262's picking will rest on, checked forwards.
 * 2. **The same size anywhere on screen.** The four cubes cover the same number of pixels and have the
 *    same bounding box wherever on the picture they land.
 * 3. **Edges stay parallel.** Each pillar is a box along world +Z. Its silhouette sits the same
 *    distance across the picture at the top as at the foot: no vanishing point, no lean.
 * 4. **The control.** The same scene, the same rig, the same eye and target, with only the projection
 *    swapped to perspective and the eye brought near enough for it to tell. Now the cubes differ in
 *    size and the pillars lean - so 2 and 3 are measurements that *can* fail, rather than measurements
 *    that come out zero whatever the renderer does.
 * 5. **Four yaw steps.** The Khronos Fox on the ground with the cubes round it, photographed at each of
 *    the rig's four quarter turns, with 1 and 2 re-checked at every one: the view really turned, the
 *    cubes are still all one size, and the matrices still describe the picture.
 *
 * One `@Test`, because Kool allows one context per JVM (see `GlCaptureTest`).
 */
class GlIsoCameraTest {

    /** Where each shape was spawned, so a prediction uses the world position and not the rig's yaw. */
    private val placedAt = HashMap<String, Pair<Float, Float>>()

    @Test
    fun `an orthographic isometric camera draws a cube the same size anywhere on screen, with edges that stay parallel`() {
        GlAvailability.require()

        withIso { backend, host, rig, lens ->
            val slot = backend.pipeline!!.capture!!
            val world = host.world
            // Everything step 5 clears away before the fox walks on.
            val flatOnly = ArrayList<Entity>()

            backend.onRenderThread {
                rig.moveHalfLife = 0f
                rig.viewHeight = VIEW_HEIGHT
                world.entity {
                    it += Transform3D()
                    it += ModelRenderer(ModelMesh.plane(GROUND_SIZE, GROUND_SIZE), ModelMaterial(ground(), roughness = 0.95f))
                }
                for (shape in CUBES + PILLARS) {
                    val at = shape.right * rig.rightX + shape.forward * rig.forwardX to
                        shape.right * rig.rightY + shape.forward * rig.forwardY
                    placedAt[shape.name] = at
                    val entity = world.entity {
                        it += Transform3D(x = at.first, y = at.second, z = shape.height / 2f)
                        it += ModelRenderer(shape.mesh(), ModelMaterial(solid(shape.red, shape.green, shape.blue, shape.name), roughness = 0.95f))
                    }
                    if (shape in PILLARS) flatOnly += entity
                }
                // Z is up: a cube lifted well off the ground must draw above the one standing under it,
                // and that is what tells this test which way up the captured image is read.
                val under = placedAt.getValue(LIFTED_OVER)
                flatOnly += world.entity {
                    it += Transform3D(x = under.first, y = under.second, z = LIFT_HEIGHT, scaleX = LIFT_SCALE, scaleY = LIFT_SCALE, scaleZ = LIFT_SCALE)
                    it += ModelRenderer(ModelMesh.box(CUBE_SIDE, CUBE_SIDE, CUBE_SIDE), ModelMaterial(solid(250, 250, 250, "iso-lifted"), roughness = 0.95f))
                }
            }
            // A new mesh can come back empty on its first frame (see GlModelRenderTest).
            frame(slot)

            // 1, 2, 3: the orthographic picture.
            val flat = frame(slot)
            save(flat, "issue257-ortho-cubes.png")
            val flatCamera = backend.onRenderThread { copyOf(rig.camera) }
            assertTrue(
                flatCamera.projection == ModelProjection.Orthographic,
                "the rig did not flatten the camera: $flatCamera",
            )
            val flat3D = measure(flat, flatCamera, CUBES + PILLARS, ORTHO_PREDICTION_TOLERANCE, "orthographic")
            assertLiftedIsAbove(flat, flat3D, "orthographic")
            assertOneSize(flat3D, "orthographic")
            for (pillar in PILLARS) {
                val lean = flat3D.getValue(pillar.name).lean
                println("GlIsoCameraTest: orthographic: the ${pillar.name} leans $lean pixels")
                assertTrue(
                    abs(lean) < LEAN_TOLERANCE,
                    "an orthographic camera leaned the ${pillar.name} by $lean pixels: its edges are not parallel",
                )
            }

            // 4: the control. The same scene through a perspective lens, near enough for it to tell.
            backend.onRenderThread {
                rig.eyeDistance = CONTROL_DISTANCE
                lens.perspective = true
            }
            frame(slot)
            val cone = frame(slot)
            save(cone, "issue257-perspective-control.png")
            val coneCamera = backend.onRenderThread { copyOf(rig.camera) }
            assertTrue(
                coneCamera.projection == ModelProjection.Perspective,
                "the control never left orthographic: $coneCamera",
            )
            val cone3D = measure(cone, coneCamera, CUBES + PILLARS, CONE_PREDICTION_TOLERANCE, "perspective")

            val coneAreas = CUBES.map { cone3D.getValue(it.name).pixels }
            println("GlIsoCameraTest: perspective cube areas $coneAreas")
            assertTrue(
                coneAreas.max().toFloat() / coneAreas.min() > AREA_SPREAD,
                "a perspective camera drew four cubes at four depths all one size, so the size " +
                    "measurement above cannot fail: $coneAreas",
            )
            for (pillar in PILLARS) {
                val lean = cone3D.getValue(pillar.name).lean
                println("GlIsoCameraTest: perspective: the ${pillar.name} leans $lean pixels")
                assertTrue(
                    abs(lean) > LEAN_TOLERANCE,
                    "a perspective camera left the ${pillar.name} with no lean at all ($lean pixels), so the " +
                        "lean measurement above cannot fail",
                )
            }

            // 5: four yaw steps, with a model in the middle of them. The pillars and the lifted cube
            // go first: a quarter turn swings a pillar round to where a cube is, and the lifted cube
            // lands squarely on the fox, and neither an occluded cube nor a hidden fox can be measured.
            backend.onRenderThread {
                lens.perspective = false
                rig.eyeDistance = DEFAULT_DISTANCE
                with(world) { flatOnly.forEach { it.remove() } }
            }
            photographTheFox(backend, host, rig, slot)
        }
    }

    /**
     * The Khronos Fox standing where the camera looks, with the cubes round it, photographed at each of
     * the rig's four quarter turns and measured again at every one.
     */
    private fun photographTheFox(backend: KoolBackend, host: GameHost, rig: IsometricRig, slot: FrameCaptureSlot) {
        val world = host.world
        val foxModel = loadModel(exampleAssets(), Model(AssetId("models/fox"), ResPath("models/fox/Fox.glb")))
        backend.onRenderThread {
            world.entity {
                it += Transform3D(scaleX = FOX_SCALE, scaleY = FOX_SCALE, scaleZ = FOX_SCALE)
                it += ModelRenderer(model = foxModel)
            }
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

        for (step in 0 until QUARTER_TURNS) {
            if (step > 0) backend.onRenderThread { rig.turnRight() }
            // The half-life is zero, so one frame arrives where the turn put it; a second is taken
            // because a mesh can come back empty on the frame it first appears in.
            frame(slot)
            val shot = frame(slot)
            val yaw = backend.onRenderThread { rig.yawDegrees }
            save(shot, "issue257-yaw-$step-${yawName(yaw)}.png")
            val camera = backend.onRenderThread { copyOf(rig.camera) }
            val shapes = measure(shot, camera, CUBES, ORTHO_PREDICTION_TOLERANCE, "yaw $yaw")
            assertOneSize(shapes, "yaw $yaw")
            assertTrue(count(shot, ::isOrange) >= MIN_FOX_PIXELS, "the fox is not in the picture at yaw $yaw")
        }
    }

    // --- measuring -------------------------------------------------------------------------

    /**
     * Finds each of [shapes] in [image] by its colour and checks it against where [camera]'s own
     * matrices put it, which is the assertion the whole file rests on.
     */
    private fun measure(
        image: BufferedImage,
        camera: ModelCamera,
        shapes: List<Placed>,
        tolerance: Float,
        what: String,
    ): Map<String, Shape> {
        val found = HashMap<String, Shape>()
        for (shape in shapes) {
            val blob = blob(image, shape.matches)
            assertTrue(
                blob.pixels >= MIN_SHAPE_PIXELS,
                "$what: the ${shape.name} is not in the picture (${blob.pixels} pixels)",
            )
            val at = placedAt.getValue(shape.name)
            val predicted = CameraProjections.pixelOf(camera, at.first, at.second, shape.height / 2f, image.width, image.height)
            println(
                "GlIsoCameraTest: $what: ${shape.name} measured (${blob.x}, ${blob.y}) over ${blob.pixels} pixels, " +
                    "${blob.width}x${blob.height}; the matrices predict (${predicted.x}, ${predicted.y})",
            )
            assertTrue(
                abs(blob.x - predicted.x) < tolerance && abs(blob.y - predicted.y) < tolerance,
                "$what: the ${shape.name} is drawn at (${blob.x}, ${blob.y}) but the camera's own matrices put it " +
                    "at (${predicted.x}, ${predicted.y}): the numbers a game would un-project through are not the picture",
            )
            found[shape.name] = blob
        }
        return found
    }

    /** Up in the world is up on the picture: the lifted cube draws above the one it hangs over. */
    private fun assertLiftedIsAbove(image: BufferedImage, shapes: Map<String, Shape>, what: String) {
        val lifted = blob(image, ::isWhite)
        val under = shapes.getValue(LIFTED_OVER)
        println("GlIsoCameraTest: $what: the lifted cube is at (${lifted.x}, ${lifted.y}), the one under it at (${under.x}, ${under.y})")
        assertTrue(lifted.pixels >= MIN_SHAPE_PIXELS, "$what: the lifted cube is not in the picture (${lifted.pixels} pixels)")
        assertTrue(
            lifted.y < under.y,
            "$what: a cube $LIFT_HEIGHT units above the ground drew below the one standing under it " +
                "(${lifted.y} against ${under.y}), so up in the world is not up on the picture",
        )
    }

    /** Four identical cubes, four identical pictures of a cube: the whole point of a flat projection. */
    private fun assertOneSize(shapes: Map<String, Shape>, what: String) {
        val areas = CUBES.map { shapes.getValue(it.name).pixels }
        println("GlIsoCameraTest: $what: cube areas $areas")
        assertTrue(
            areas.max().toFloat() / areas.min() < AREA_SPREAD,
            "$what: an orthographic camera drew four identical cubes at four sizes: $areas",
        )
        val first = shapes.getValue(CUBES.first().name)
        for (cube in CUBES) {
            val here = shapes.getValue(cube.name)
            assertTrue(
                abs(here.width - first.width) <= BOX_TOLERANCE && abs(here.height - first.height) <= BOX_TOLERANCE,
                "$what: the ${cube.name} is ${here.width}x${here.height} where the ${CUBES.first().name} is " +
                    "${first.width}x${first.height}",
            )
        }
    }

    /** Where a colour sits in a picture: its centroid, its extent, and how far its top leans off its foot. */
    private class Shape(val x: Float, val y: Float, val pixels: Int, val width: Int, val height: Int, val lean: Float)

    private fun blob(image: BufferedImage, test: (Int, Int, Int) -> Boolean): Shape {
        var n = 0
        var sumX = 0L
        var sumY = 0L
        var minX = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var minY = Int.MAX_VALUE
        var maxY = Int.MIN_VALUE
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val pixel = image.getRGB(x, y)
                if (!test((pixel ushr 16) and 0xFF, (pixel ushr 8) and 0xFF, pixel and 0xFF)) continue
                n++
                sumX += x
                sumY += y
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
        if (n == 0) return Shape(-1f, -1f, 0, 0, 0, 0f)
        return Shape(sumX.toFloat() / n, sumY.toFloat() / n, n, maxX - minX + 1, maxY - minY + 1, leanOf(image, test, minY, maxY))
    }

    /**
     * How far across the picture the middle of a shape's top band sits from the middle of its foot
     * band, in pixels. A box along world +Z has no lean under a projection with no vanishing point.
     */
    private fun leanOf(image: BufferedImage, test: (Int, Int, Int) -> Boolean, minY: Int, maxY: Int): Float {
        val band = ((maxY - minY + 1) * BAND_SHARE).toInt().coerceAtLeast(1)
        return meanX(image, test, minY, minY + band) - meanX(image, test, maxY - band, maxY)
    }

    private fun meanX(image: BufferedImage, test: (Int, Int, Int) -> Boolean, fromY: Int, toY: Int): Float {
        var n = 0
        var sum = 0L
        for (y in fromY.coerceAtLeast(0)..toY.coerceAtMost(image.height - 1)) {
            for (x in 0 until image.width) {
                val pixel = image.getRGB(x, y)
                if (test((pixel ushr 16) and 0xFF, (pixel ushr 8) and 0xFF, pixel and 0xFF)) {
                    n++
                    sum += x
                }
            }
        }
        return if (n == 0) 0f else sum.toFloat() / n
    }

    private fun count(image: BufferedImage, test: (Int, Int, Int) -> Boolean): Int = blob(image, test).pixels

    // --- the scene -------------------------------------------------------------------------

    /**
     * One coloured thing standing on the ground: [right] world units across the picture from what the
     * camera looks at and [forward] into it, at the rig's starting yaw.
     */
    private class Placed(
        val name: String,
        val right: Float,
        val forward: Float,
        val height: Float,
        val side: Float,
        val red: Int,
        val green: Int,
        val blue: Int,
        val matches: (Int, Int, Int) -> Boolean,
    ) {
        fun mesh(): ModelMesh = ModelMesh.box(side, side, height)
    }

    // --- fixture -------------------------------------------------------------------------

    /** A pale blue sky, one fill under everything, in no shape's colour. */
    private class Sky(private val resources: RenderResources) : RenderSystem {
        override fun render(target: OffscreenTarget, alpha: Float) {
            val batch = resources.batch
            batch.beginPixels()
            batch.fill(0f, 0f, target.width.toFloat(), target.height.toFloat(), Rgba.of(0.62f, 0.76f, 0.93f))
            batch.end()
        }
    }

    /**
     * Swaps the projection on the rig's own camera after the rig has placed it: the control of step 4.
     * The eye, what it looks at and the scene are all still the rig's; only the lens changes.
     */
    private class Lens(private val camera: ModelCamera) : RenderSystem {
        var perspective = false

        override fun render(target: OffscreenTarget, alpha: Float) {
            if (!perspective) return
            camera.projection = ModelProjection.Perspective
            camera.fovYDegrees = CONTROL_FOV
            camera.near = CONTROL_NEAR
            camera.far = CONTROL_FAR
        }
    }

    private fun withIso(block: (KoolBackend, GameHost, IsometricRig, Lens) -> Unit) {
        val definition = UdeaGameDef(registry = CoreUdeaRegistry, modules = emptyList())
        val registry = RenderRegistry()
        val camera = ModelCamera()
        lateinit var rig: IsometricRig
        lateinit var lens: Lens
        val light = ModelLight(
            directionX = -0.4f,
            directionY = 0.5f,
            directionZ = -1f,
            intensity = 2f,
            ambient = Rgba.of(0.4f, 0.4f, 0.4f),
            shadowDistance = SHADOW_DISTANCE,
        )
        // A sky under the 3D pass, so the pictures have a horizon. Registered first: it draws first.
        registry.register(RenderPhase.PreRender, { resources -> Sky(resources) })
        registry.register(RenderPhase.PreRender, { resources ->
            IsometricRig(resources, registry.frameTime, camera).also { rig = it }
        })
        // After the rig, so the control's lens is the last word on the projection.
        registry.register(RenderPhase.PreRender, { _ -> Lens(camera).also { lens = it } })
        registry.register(RenderPhase.World, { resources -> ModelRenderSystem(resources, camera, light) })
        val backend = KoolBackend.start(
            RenderMode.Offscreen,
            WindowConfig(
                title = "udea-iso-camera",
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
            block(backend, host, rig, lens)
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

    /** Sandy tiles, light and dark, so the ground reads as ground and matches no shape's colour. */
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
        return SpriteTexture.fromRgba(size, size, rgba, "iso-ground")
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

    private fun yawName(yaw: Float): String = "yaw%03d".format(((yaw % 360f + 360f) % 360f).toInt())

    private fun isWhite(r: Int, g: Int, b: Int): Boolean =
        r > 150 && g > 150 && b > 150 && maxOf(r, g, b) - minOf(r, g, b) <= 30

    /** The fox's coat, as `GlImportedModelRenderTest` reads it. The yellow cube fails `r > g + 25`. */
    private fun isOrange(r: Int, g: Int, b: Int): Boolean = r > b + 60 && g > b + 20 && r > g + 25

    private companion object {
        const val WIDTH = 480
        const val HEIGHT = 320

        const val VIEW_HEIGHT = 14f
        const val GROUND_SIZE = 120f

        /** The rig's own default, restored after the control has borrowed the eye. */
        const val DEFAULT_DISTANCE = 200f

        /** Near enough for a perspective lens to foreshorten visibly, with a field of view that frames
         *  the same 14 world units at the distance the camera looks: `2 * atan(7 / 40)`. */
        const val CONTROL_DISTANCE = 40f
        const val CONTROL_FOV = 20f
        const val CONTROL_NEAR = 0.5f
        const val CONTROL_FAR = 400f

        const val CUBE_SIDE = 1.6f
        const val PILLAR_SIDE = 0.6f
        const val PILLAR_HEIGHT = 4.5f

        /** The cube that hangs in the air, over [LIFTED_OVER], to say which way up the picture is. */
        const val LIFT_HEIGHT = 5f
        const val LIFT_SCALE = 0.6f
        const val LIFTED_OVER = "east"

        /**
         * Far enough for the shadow slab, which `ModelStage` measures from the camera's near plane, to
         * reach the ground at all: an orthographic eye sits [DEFAULT_DISTANCE] back, so the ground is
         * around that far down the line of sight.
         */
        const val SHADOW_DISTANCE = 300f

        /**
         * The four cubes, on a cross round what the camera looks at. Every one is within seven world
         * units of the middle, so a quarter turn keeps all four inside a picture fourteen units tall.
         */
        val CUBES: List<Placed> = listOf(
            Placed("north", 0f, 7f, CUBE_SIDE, CUBE_SIDE, 225, 35, 35) { r, g, b -> r > 50 && r > g * 2 && r > b * 2 },
            Placed("south", 0f, -7f, CUBE_SIDE, CUBE_SIDE, 40, 200, 60) { r, g, b -> g > 50 && g > r * 2 && g > b * 2 },
            Placed("east", 6f, 0f, CUBE_SIDE, CUBE_SIDE, 45, 70, 225) { r, g, b -> b > 50 && b > r * 2 && b > g * 2 },
            // Yellow has to be told apart from the fox, which walks on in step 5 and is also red and
            // green over little blue. Two clauses do it: the fox's green is around six tenths of its
            // red where yellow's is nine, and the fox's tan has far more blue in it than yellow does.
            // With only the first, one pixel of the fox's back joined this cube's blob and stretched
            // its bounding box from 57 pixels tall to 100.
            Placed("west", -6f, 0f, CUBE_SIDE, CUBE_SIDE, 235, 215, 45) { r, g, b ->
                r > 50 && g > 50 && r > b * 3 && g > b * 3 && g * 5 > r * 4
            },
        )

        /** Two pillars low on the picture, where nothing else is, for the lean measurement. */
        val PILLARS: List<Placed> = listOf(
            Placed("left pillar", -6.5f, -11f, PILLAR_HEIGHT, PILLAR_SIDE, 210, 40, 205) { r, g, b -> r > 50 && r > g * 2 && b > g * 2 },
            Placed("right pillar", 6.5f, -11f, PILLAR_HEIGHT, PILLAR_SIDE, 40, 205, 200) { r, g, b -> g > 50 && g > r * 2 && b > r * 2 },
        )

        const val MIN_SHAPE_PIXELS = 60

        /**
         * Pixels a measured centroid may sit from the one the matrices predict. A cube is centrally
         * symmetric, and so is its orthographic silhouette, which is why the flat tolerance is tight;
         * under perspective the silhouette is not, so the control's is loose.
         */
        const val ORTHO_PREDICTION_TOLERANCE = 5f
        const val CONE_PREDICTION_TOLERANCE = 16f

        /** How far apart the largest and smallest cube may be, as a ratio, before it is a perspective. */
        const val AREA_SPREAD = 1.12f

        /** Pixels two cubes' bounding boxes may differ by. */
        const val BOX_TOLERANCE = 2

        /** Pixels a pillar's top may sit from its foot across the picture before it is leaning. */
        const val LEAN_TOLERANCE = 2.5f

        /** The share of a shape's height each lean band is: the top eighth against the bottom eighth. */
        const val BAND_SHARE = 0.125f

        const val QUARTER_TURNS = 4

        /** The fox's file is in centimetre-like units, about 80 tall: this makes it 3.2 tall, twice
         *  a cube, so it is plainly a model rather than another box in the pictures below. */
        const val FOX_SCALE = 0.04f

        const val MIN_FOX_PIXELS = 300
        const val FOX_FRAME_BUDGET = 240
    }
}
