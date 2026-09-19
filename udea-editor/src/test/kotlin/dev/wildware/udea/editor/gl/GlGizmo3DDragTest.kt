package dev.wildware.udea.editor.gl

import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.udea.assets.AssetId
import dev.wildware.udea.assets.Model
import dev.wildware.udea.assets.ResPath
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.blueprint.BlueprintId
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.core.module.UdeaGameDef
import dev.wildware.udea.core.spatial.Transform3D
import dev.wildware.udea.editor.EditorGizmos
import dev.wildware.udea.editor.EditorSession
import dev.wildware.udea.editor.EditorSpawn
import dev.wildware.udea.editor.EditorToolLoop
import dev.wildware.udea.editor.EditorTools
import dev.wildware.udea.editor.EditorViews
import dev.wildware.udea.editor.GizmoAxes
import dev.wildware.udea.editor.GizmoAim
import dev.wildware.udea.editor.GizmoPreferences
import dev.wildware.udea.editor.TransformGizmos
import dev.wildware.udea.editor.TransformPlacement
import dev.wildware.udea.editor.editorFonts
import dev.wildware.udea.editor.dot
import dev.wildware.udea.editor.fixtureComponents
import dev.wildware.udea.editor.length
import dev.wildware.udea.editor.minus
import dev.wildware.udea.editor.plus
import dev.wildware.udea.editor.times
import dev.wildware.udea.editor.gizmo.AxisFrame
import dev.wildware.udea.editor.gizmo.HandlePainter
import dev.wildware.udea.editor.gizmo.WorldPoint
import dev.wildware.udea.generated.CoreUdeaRegistry
import dev.wildware.udea.render.RenderPhase
import dev.wildware.udea.render.RenderRegistry
import dev.wildware.udea.render.backend.KoolBackend
import dev.wildware.udea.render.backend.WindowConfig
import dev.wildware.udea.render.kool.KoolKeyboard
import dev.wildware.udea.render.kool.KoolPointer
import dev.wildware.udea.render.model.ModelCamera
import dev.wildware.udea.render.model.ModelLight
import dev.wildware.udea.render.model.ModelRenderSystem
import dev.wildware.udea.render.model.ModelRenderer
import dev.wildware.udea.render.model.loadModel
import dev.wildware.udea.render.ui.UiLayer
import dev.wildware.udea.render.view.EditorCamera
import dev.wildware.udea.render.view.ViewDimension
import dev.wildware.udea.render.view.ViewPoint
import dev.wildware.udea.render.view.ViewRay
import org.lwjgl.glfw.GLFW
import java.awt.image.BufferedImage
import java.nio.file.Path
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.round
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Issue #237 through a real Kool backend, the real mouse and keyboard, and a textured model - the
 * Khronos Fox, drawn by `ModelRenderSystem` - with the Scene tab's camera in 3D: **each built-in 3D
 * handle changes the Fox's `Transform3D` by the expected amount along or around its axis, in one undo
 * entry, and Escape puts it back**; a plane square moves it within its plane alone and a ring turns
 * one angle alone; grid and angle snapping round a 3D drag, Ctrl bypasses them, and local axes turn
 * the arrows with the Fox.
 *
 * Every drag is aimed by world points - it starts where a point on the handle is drawn and ends where
 * a point the handle can reach is drawn - so what each one should do is worked out in the world. The
 * pointer lands on whole pixels, off by up to a few of them down the screen where the Scene tab's
 * rectangle is read from a headless twin ([sceneViewOn]), so distances are held to that many pixels'
 * worth of world. The gizmos are the fixture's, calling the built-ins through the public API alone
 * ([TransformGizmos]), exactly as the gizmos generated from `Transform3D`'s own annotations do.
 *
 * The Scene tab is read back while each handle is held and saved beside the other GL reports as
 * `issue237-*.png`.
 */
class GlGizmo3DDragTest {

    @Test
    fun `each built-in 3D handle dragged with the real mouse changes the Fox's Transform3D by the drag in one undo entry, and Escape puts it back`() {
        GlAvailabilityHere.require()
        val fox = loadModel(exampleAssets(), Model(AssetId("models/fox"), ResPath("models/fox/Fox.glb")))

        val registry = RenderRegistry()
        registry.register(RenderPhase.World, { resources -> ModelRenderSystem(resources, gameCamera, light) })
        val frames = FrameProbe()
        registry.overlay({ frames })
        val window = WindowProbe()
        registry.overlay({ window })

        val backend = KoolBackend.start(
            RenderMode.Offscreen,
            WindowConfig(title = "udea-editor-3d-gizmos", windowWidth = WIDTH, windowHeight = HEIGHT, renderWidth = WIDTH, renderHeight = HEIGHT),
            registry,
        )
        val fonts = editorFonts()
        try {
            val host = GameHost(RenderMode.Offscreen, UdeaGameDef(registry = CoreUdeaRegistry, modules = emptyList()), backend)
            val loop = EditorToolLoop(host, fixtureComponents())
            val camera = EditorCamera().apply { dimension = ViewDimension.ThreeD }
            val views = EditorViews(backend.openSceneView(camera), backend.openGameView())
            val preferences = GizmoPreferences()
            val session = EditorSession(
                tools = EditorTools(loop.bridge, loop.author),
                tick = { Tick(0) },
                paused = { true },
                spawn = EditorSpawn("Spawn", BlueprintId("skeleton"), 0f, 0f),
                views = views,
                gizmos = EditorGizmos(TransformGizmos, host.world, host.ctx[CoreModule.NET_IDS], loop.components, TransformPlacement, preferences),
            )
            backend.drive { delta ->
                host.frame(delta)
                loop.pump()
                session.frame()
            }
            val netIds = host.ctx[CoreModule.NET_IDS]
            val id: NetId = backend.onRenderThread {
                netIds.allocate(
                    host.world.entity {
                        it += Transform3D(rotationZ = FOX_HEADING, scaleX = FOX_SCALE, scaleY = FOX_SCALE, scaleZ = FOX_SCALE)
                        it += ModelRenderer(model = fox)
                    },
                )
            }

            val ui = UiLayer(fonts, Size(WIDTH.toFloat(), HEIGHT.toFloat()))
            backend.show(ui)
            ui.show(session.window)
            backend.onRenderThread { KoolPointer(ui) }
            backend.onRenderThread { KoolKeyboard(ui) }
            awaitFrames(frames, frames.count.get() + SETTLE_FRAMES)
            // The orbit, once the view has adopted the game camera's: round the Fox from ahead and to
            // its left, a little above, so no axis lies along the line of sight.
            backend.onRenderThread {
                camera.targetX = 0f
                camera.targetY = 0f
                camera.targetZ = LOOK_AT_Z
                camera.yawDegrees = YAW
                camera.pitchDegrees = PITCH
                camera.distance = DISTANCE
            }
            backend.onRenderThread { loop.select(listOf(id)) }
            awaitFrames(frames, frames.count.get() + SETTLE_FRAMES)

            val view = sceneViewOn(WIDTH, HEIGHT)
            fun transform(): Transform3D = backend.onRenderThread {
                with(host.world) { checkNotNull(netIds.resolveOrNull(id))[Transform3D].let { copyOf(it) } }
            }
            fun history(): List<String> = backend.onRenderThread { loop.history() }
            fun screen(point: WorldPoint): Offset = backend.onRenderThread {
                val at = ViewPoint()
                check(camera.project(point.x, point.y, point.z, at)) { "$point is behind the camera" }
                screenOfView(views, view, at)
            }
            fun drawnAt(point: WorldPoint): ViewPoint = backend.onRenderThread {
                ViewPoint().also { check(camera.project(point.x, point.y, point.z, it)) { "$point is behind the camera" } }
            }
            val aim = GizmoAim(::drawnAt)
            val slack = backend.onRenderThread { camera.unitsPerPixelAt(0f, 0f, 0f) } * SCREEN_TOLERANCE
            // Puts the Fox's angles back to [x], [y] and [heading], at its own size, and the orbit back over
            // wherever the drags so far have moved it to: the world and the view are this test's to set.
            fun tilt(x: Float, y: Float, heading: Float = FOX_HEADING) {
                backend.onRenderThread {
                    with(host.world) {
                        checkNotNull(netIds.resolveOrNull(id))[Transform3D].apply {
                            rotationX = x
                            rotationY = y
                            rotationZ = heading
                            scaleX = FOX_SCALE
                            scaleY = FOX_SCALE
                            scaleZ = FOX_SCALE
                            camera.targetX = this.x
                            camera.targetY = this.y
                            camera.targetZ = this.z + LOOK_AT_Z
                        }
                    }
                }
                awaitFrames(frames, frames.count.get() + SETTLE_FRAMES)
            }
            fun shot(name: String) = saveReport("issue237-$name.png", crop(window.read(frames), view))
            var edits = 0

            shot("fox-selected-all-handles")

            // --- translate: each arrow, along its own axis alone ------------------------------------------
            for ((name, axis) in listOf("x" to X, "y" to Y, "z" to Z)) {
                val before = transform()
                val at = before.position()
                val grip = aim.pixelsOut(at, axis, ARROW_GRIP)
                backend.drag(frames, screen(at.plus(axis, grip)), screen(at.plus(axis, grip + MOVE))) { shot("translate-$name-arrow") }
                val moved = transform().position().minus(at)
                assertNear(MOVE, dot(moved, axis), "the $name arrow did not move the Fox by the drag", slack)
                assertNear(0f, moved.minus(axis.times(dot(moved, axis))).length(), "the $name arrow moved the Fox off its axis", TOLERANCE)
                assertEquals(List(++edits) { COMMIT }, history(), "a $name arrow drag is not one undo entry")
            }

            // --- translate: each plane square, within its plane alone ---------------------------------------
            for ((name, plane) in listOf("xy" to Triple(X, Y, Z), "yz" to Triple(Y, Z, X), "xz" to Triple(X, Z, Y))) {
                val (u, v, normal) = plane
                val at = transform().position()
                val from = aim.planeTab(at, u, v)
                backend.drag(frames, screen(from), screen(from.plus(u, PLANE_U).plus(v, PLANE_V))) { shot("translate-$name-plane") }
                val moved = transform().position().minus(at)
                assertNear(PLANE_U, dot(moved, u), "the $name square did not move the Fox along its first axis", slack)
                assertNear(PLANE_V, dot(moved, v), "the $name square did not move the Fox along its second axis", slack)
                assertNear(0f, dot(moved, normal), "the $name square moved the Fox off its plane", TOLERANCE)
                assertEquals(List(++edits) { COMMIT }, history(), "a $name square drag is not one undo entry")
            }

            // --- rotate: each ring, its own angle alone ----------------------------------------------------
            for ((turned, field) in listOf("x", "y", "z").withIndex()) {
                // Each ring from the same tilt: two quarter turns in a row would stand the X ring on the
                // Z ring's axis, the gimbal's one blind spot, and leave no part of either to take.
                tilt(TILT_X, TILT_Y)
                val t = transform()
                val before = t.angles()
                val rings = aim.rings(t)
                val from = aim.ringGrip(rings, turned)
                // A quarter of a turn round the ring from where it is taken, right-handed.
                backend.drag(frames, screen(rings[turned].at(from)), screen(rings[turned].at(from + 0.25f))) { shot("rotate-$field-ring") }
                val after = transform().angles()
                for (index in after.indices) {
                    val expected = before[index] + if (index == turned) (PI / 2).toFloat() else 0f
                    assertNear(expected, after[index], "the $field ring left angle $index at ${after[index]}", ANGLE_TOLERANCE)
                }
                assertEquals(List(++edits) { COMMIT }, history(), "a $field ring drag is not one undo entry")
            }
            shot("rotated")

            // --- scale: an axis box, then the middle one ---------------------------------------------------
            // The rings turned the Fox; stand it the way it started, so its scale boxes point where they did.
            tilt(0f, 0f)
            val scaleFrom = transform()
            val centre = scaleFrom.position()
            val reach = aim.pixelsOut(centre, Z, HandlePainter.SCALE_REACH)
            backend.drag(frames, screen(centre.plus(Z, reach)), screen(centre.plus(Z, reach * STRETCH))) { shot("scale-z-box") }
            val stretched = transform()
            assertNear(FOX_SCALE * STRETCH, stretched.scaleZ, "the Z box did not scale Z by how much further out it was pulled", FOX_SCALE * STRETCH_TOLERANCE)
            assertEquals(listOf(FOX_SCALE, FOX_SCALE), listOf(stretched.scaleX, stretched.scaleY), "the Z box scaled another axis")
            assertEquals(List(++edits) { COMMIT }, history(), "a scale box drag is not one undo entry")

            // The middle box: up and to the right on screen, as far as the axis boxes stand off. The box is
            // held in the plane facing the press, and grows the Fox by how far the pointer goes there along
            // world X and Z together, a reach's worth doubling it; the view's tilt makes that a little less
            // than a straight hundred pixels, so the answer comes from the camera's own rays.
            val middle = drawnAt(centre)
            val upRight = HandlePainter.SCALE_REACH / sqrt(2f)
            val released = ViewPoint(middle.x + upRight, middle.y + upRight)
            val expected = backend.onRenderThread {
                val pressed = ViewRay().also { camera.ray(middle.x, middle.y, it) }
                val to = ViewRay().also { camera.ray(released.x, released.y, it) }
                val facing = WorldPoint(pressed.directionX, pressed.directionY, pressed.directionZ)
                val along = WorldPoint(to.directionX, to.directionY, to.directionZ)
                val from = WorldPoint(to.originX, to.originY, to.originZ)
                val hit = from.plus(along, dot(centre.minus(from), facing) / dot(along, facing))
                val upAndRight = WorldPoint(1f / sqrt(2f), 0f, 1f / sqrt(2f))
                1f + dot(hit.minus(centre), upAndRight) /
                    (HandlePainter.SCALE_REACH * camera.unitsPerPixelAt(centre.x, centre.y, centre.z))
            }
            val uniformFrom = transform()
            backend.drag(frames, screenOfView(views, view, middle), screenOfView(views, view, released)) {
                shot("scale-uniform-box")
            }
            val grown = transform()
            val factor = grown.scaleX / uniformFrom.scaleX
            println("GlGizmo3DDragTest: the middle box scaled by $factor, the camera's rays say $expected")
            assertTrue(expected > UNIFORM_AT_LEAST, "the drag up and right should grow the Fox well past its size: $expected")
            assertNear(expected, factor, "the middle box did not grow the Fox by the drag", expected * STRETCH_TOLERANCE)
            assertNear(factor, grown.scaleY / uniformFrom.scaleY, "the middle box scaled Y by another factor", TOLERANCE)
            assertNear(factor, grown.scaleZ / uniformFrom.scaleZ, "the middle box scaled Z by another factor", TOLERANCE)
            assertEquals(List(++edits) { COMMIT }, history(), "a middle box drag is not one undo entry")

            // --- Escape mid-drag, on each kind of handle ---------------------------------------------------
            for ((name, grab) in listOf(
                "arrow" to { t: Transform3D -> t.position().plus(X, aim.pixelsOut(t.position(), X, ARROW_GRIP)) to X.times(MOVE) },
                "ring" to { t: Transform3D ->
                    val ring = aim.rings(t)[Z_RING]
                    val from = aim.ringGrip(aim.rings(t), Z_RING)
                    ring.at(from) to ring.at(from + 0.25f).minus(ring.at(from))
                },
                "scale box" to { t: Transform3D ->
                    val r = aim.pixelsOut(t.position(), Z, HandlePainter.SCALE_REACH)
                    t.position().plus(Z, r) to Z.times(r * (STRETCH - 1f))
                },
            )) {
                val still = transform()
                val (from, by) = grab(still)
                backend.drag(frames, screen(from), screen(from.plus(by, 1f)), release = false)
                assertTrue(still.differsFrom(transform()), "the $name drag was not live before Escape")
                backend.key(GLFW.GLFW_KEY_ESCAPE, GLFW.GLFW_PRESS)
                backend.key(GLFW.GLFW_KEY_ESCAPE, GLFW.GLFW_RELEASE)
                awaitFrames(frames, frames.count.get() + SETTLE_FRAMES)
                backend.button(GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_RELEASE)
                awaitFrames(frames, frames.count.get() + SETTLE_FRAMES)
                assertEquals(still.fields(), transform().fields(), "Escape did not put the Fox back after a $name drag")
                assertEquals(List(edits) { COMMIT }, history(), "a cancelled $name drag filed an undo entry")
            }

            // --- snapping, and Ctrl to bypass it ----------------------------------------------------------
            tilt(TILT_X, TILT_Y)
            backend.onRenderThread {
                preferences.gridSnap = true
                preferences.gridStep = GRID
                preferences.angleSnap = true
                preferences.angleStepDegrees = ANGLE_STEP
            }
            val unsnapped = transform().position()
            val zGrip = aim.pixelsOut(unsnapped, Z, ARROW_GRIP)
            backend.drag(frames, screen(unsnapped.plus(Z, zGrip)), screen(unsnapped.plus(Z, zGrip + MOVE))) { shot("snap-grid-z-arrow") }
            val snappedZ = transform().z
            assertNear(round((unsnapped.z + MOVE) / GRID) * GRID, snappedZ, "the Z move was not rounded to the grid", TOLERANCE)
            backend.holding(frames, GLFW.GLFW_KEY_LEFT_CONTROL) {
                val from = transform().position()
                val grip = aim.pixelsOut(from, Z, ARROW_GRIP)
                backend.drag(frames, screen(from.plus(Z, grip)), screen(from.plus(Z, grip + MOVE)))
            }
            assertNear(snappedZ + MOVE, transform().z, "Ctrl did not write the move exactly", slack)
            val turnFrom = transform()
            val zRing = aim.rings(turnFrom)
            val zFrom = aim.ringGrip(zRing, Z_RING)
            // Fifty degrees round the Z ring: the nearest step of fifteen is forty-five.
            backend.drag(frames, screen(zRing[Z_RING].at(zFrom)), screen(zRing[Z_RING].at(zFrom + 50f / 360f))) { shot("snap-angle-z-ring") }
            val snappedTurn = transform().rotationZ
            val step = (ANGLE_STEP * PI / 180.0).toFloat()
            assertNear(round(snappedTurn / step) * step, snappedTurn, "the turn did not land on a step of $ANGLE_STEP degrees", TOLERANCE)
            assertNear((turnFrom.rotationZ + PI / 4).toFloat().let { round(it / step) * step }, snappedTurn, "the turn was not rounded to the nearest step", TOLERANCE)
            edits += 3
            assertEquals(List(edits) { COMMIT }, history())

            // --- local axes: the arrows turn with the Fox ---------------------------------------------------
            backend.onRenderThread {
                preferences.gridSnap = false
                preferences.angleSnap = false
                preferences.axes = GizmoAxes.Local
            }
            // Turned so its own X is well off the world's, and not end-on to the view.
            tilt(TILT_X, TILT_Y, LOCAL_HEADING)
            val turned = transform()
            val local = AxisFrame.euler(turned.rotationX, turned.rotationY, turned.rotationZ)
            val localGrip = aim.pixelsOut(turned.position(), local.x, ARROW_GRIP)
            backend.drag(frames, screen(turned.position().plus(local.x, localGrip)), screen(turned.position().plus(local.x, localGrip + MOVE))) {
                shot("local-axes-x-arrow")
            }
            val movedLocal = transform().position().minus(turned.position())
            assertNear(MOVE, dot(movedLocal, local.x), "the local X arrow did not move the Fox along its own X", slack)
            assertNear(0f, movedLocal.minus(local.x.times(dot(movedLocal, local.x))).length(), "the local X arrow moved the Fox off its own X", slack)
            assertTrue(abs(dot(movedLocal, X)) < MOVE * 0.9f, "the local X arrow moved the Fox along the world's X: $movedLocal")
            assertEquals(List(++edits) { COMMIT }, history())
        } finally {
            backend.close()
            fonts.close()
        }
    }

    // --- fixture -------------------------------------------------------------------------

    /** Where the game's own camera looks from; the Scene tab's orbit starts here, then is set by the test. */
    private val gameCamera = ModelCamera().apply { lookAt(0f, -5f, 2f, 0f, 0f, LOOK_AT_Z) }

    private val light = ModelLight(directionX = -0.4f, directionY = 0.7f, directionZ = -1f)

    private fun exampleAssets(): Path = Path.of(
        System.getProperty("udea.render.exampleAssets") ?: error("-Dudea.render.exampleAssets is not set"),
    )

    /** The Scene tab's part of the window [image], [view] being its rectangle there. */
    private fun crop(image: BufferedImage, view: Rect): BufferedImage {
        val left = view.left.toInt().coerceIn(0, image.width - 1)
        val top = view.top.toInt().coerceIn(0, image.height - 1)
        val width = view.width.toInt().coerceAtMost(image.width - left)
        val height = view.height.toInt().coerceAtMost(image.height - top)
        return image.getSubimage(left, top, width, height)
    }

    /** A copy of [t]'s nine numbers, read at one moment: the component itself goes on changing. */
    private fun copyOf(t: Transform3D): Transform3D =
        Transform3D(t.x, t.y, t.z, t.rotationX, t.rotationY, t.rotationZ, t.scaleX, t.scaleY, t.scaleZ)

    private fun Transform3D.position(): WorldPoint = WorldPoint(x, y, z)

    private fun Transform3D.angles(): List<Float> = listOf(rotationX, rotationY, rotationZ)

    private fun Transform3D.fields(): List<Float> = listOf(x, y, z, rotationX, rotationY, rotationZ, scaleX, scaleY, scaleZ)

    private fun Transform3D.differsFrom(other: Transform3D): Boolean = fields() != other.fields()

    private fun assertNear(expected: Float, actual: Float, message: String, tolerance: Float) {
        assertTrue(abs(expected - actual) <= tolerance, "$message: expected $expected, was $actual (tolerance $tolerance)")
    }

    private companion object {
        const val WIDTH = 1280
        const val HEIGHT = 720

        val X = WorldPoint(1f, 0f, 0f)
        val Y = WorldPoint(0f, 1f, 0f)
        val Z = WorldPoint(0f, 0f, 1f)

        /** The Fox's size and facing, as the Animation panel's tests have it: about 1.6 units tall, side on. */
        const val FOX_SCALE = 0.02f
        val FOX_HEADING = (PI / 2).toFloat()

        /** The orbit: round the Fox's middle from ahead and to its left, a little above. */
        const val LOOK_AT_Z = 0.6f
        const val YAW = -55f
        const val PITCH = 22f
        const val DISTANCE = 5f

        /** Where on an arrow a person grabs it, in view pixels out from the Fox: along its shaft. */
        const val ARROW_GRIP = 40f

        /** How far each arrow drag moves the Fox, in world units. */
        const val MOVE = 0.35f

        /** How far each plane square drag moves the Fox along the square's two axes. */
        const val PLANE_U = 0.3f
        const val PLANE_V = -0.2f

        /** The tilt each ring is dragged from, in radians about X and Y: no ring lies along a world axis. */
        const val TILT_X = 0.3f
        const val TILT_Y = -0.25f

        /** The Fox's heading for the local axes drag, in radians: its X is half way between the world's X and Y. */
        val LOCAL_HEADING = (PI / 4).toFloat()

        /** The Z ring's place among the rings the gizmo declares: X's, Y's, Z's. */
        const val Z_RING = 2

        /** How much further out a scale box is pulled: half as far again. */
        const val STRETCH = 1.5f

        /** A scale box pulled by whole pixels lands within this share of its factor. */
        const val STRETCH_TOLERANCE = 0.05f

        /** Less than the middle box's drag up and right grows the Fox by from this view: nearly two. */
        const val UNIFORM_AT_LEAST = 1.5f

        const val GRID = 0.25f
        const val ANGLE_STEP = 15f

        const val COMMIT = "editor.commit_edit"

        /** Floats that went through no pixels. */
        const val TOLERANCE = 1e-3f

        /** Radians a turn may land off by: a few pixels on a ring of [HandlePainter.RING_RADIUS]. */
        const val ANGLE_TOLERANCE = 0.06f

        /** Pixels a drag may land off by, as `GlGizmoDragTest` has it: whole pixels, and the twin's rectangle. */
        const val SCREEN_TOLERANCE = 3f
    }
}
