package dev.wildware.udea.editor.gl

import dev.wildware.udea.agent.AgentBridge
import dev.wildware.udea.agent.AgentCommand
import dev.wildware.udea.agent.AgentResult
import dev.wildware.udea.agent.activity.AgentSessions
import dev.wildware.udea.assets.AssetId
import dev.wildware.udea.assets.Model
import dev.wildware.udea.assets.ResPath
import dev.wildware.udea.core.Ticks
import dev.wildware.udea.core.blueprint.BlueprintId
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.core.module.UdeaGameDef
import dev.wildware.udea.core.snapshot.ComponentRegistry
import dev.wildware.udea.core.snapshot.SnapshotService
import dev.wildware.udea.core.snapshot.WorldHasher
import dev.wildware.udea.core.spatial.AnimationClip
import dev.wildware.udea.core.spatial.Animator
import dev.wildware.udea.core.spatial.Transform3D
import dev.wildware.udea.editor.AnimatedModel
import dev.wildware.udea.editor.EditorAnimation
import dev.wildware.udea.editor.EditorSession
import dev.wildware.udea.editor.EditorSpawn
import dev.wildware.udea.editor.EditorTools
import dev.wildware.udea.editor.EditorViews
import dev.wildware.udea.editor.gizmo.HandlePainter
import dev.wildware.udea.generated.CoreUdeaRegistry
import dev.wildware.udea.render.RenderPhase
import dev.wildware.udea.render.RenderRegistry
import dev.wildware.udea.render.backend.KoolBackend
import dev.wildware.udea.render.backend.WindowConfig
import dev.wildware.udea.render.capture.CaptureRequest
import dev.wildware.udea.render.capture.CaptureResult
import dev.wildware.udea.render.capture.FrameCaptureSlot
import dev.wildware.udea.render.capture.capture
import dev.wildware.udea.render.model.ModelCamera
import dev.wildware.udea.render.model.ModelLight
import dev.wildware.udea.render.model.ModelPreview
import dev.wildware.udea.render.model.ModelRenderSystem
import dev.wildware.udea.render.model.ModelRenderer
import dev.wildware.udea.render.model.ModelSkeleton
import dev.wildware.udea.render.model.loadModel
import dev.wildware.udea.render.view.EditorCamera
import dev.wildware.udea.render.view.ViewDimension
import dev.wildware.udea.render.view.ViewPoint
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Animation panel's scrub preview and bone overlay, through a real Kool context, on the Khronos
 * Fox (issue #243).
 *
 * The editor is a real [EditorSession] with the panel, over a real backend drawing the Fox with its
 * `ModelRenderSystem`. Its tool calls are answered by hand, as `EditorSessionTest` answers them: the
 * only one this test needs is `editor.selection`, which says the Fox is selected. The panel's own
 * controls - preview, scrub - are pressed through its state, on the render thread.
 *
 * The world is paused before the first frame and never ticks, so every picture here differs from
 * another only by what the editor did.
 *
 * - **Scrubbing** moves the Fox in the Scene tab: two clip times draw two different silhouettes.
 *   The capturable frame - `render.screenshot` - is the same to the byte at both, and the world's
 *   `WorldHasher` hash is the same before, during and after. Leaving the preview draws the Scene tab
 *   exactly as it was before it.
 * - **The bone overlay** draws a dot on every joint the renderer reports, at the pixel the Scene
 *   camera puts it, and the joints lie on the Fox: each is within a few pixels of the Fox's own
 *   silhouette, drawn without the overlay. Between two clip times the joints move. Not one overlay
 *   pixel is in the capturable frame, nor in the Game tab with its gizmo toggle on.
 *
 * One `@Test`: Kool allows one context per JVM.
 */
class GlAnimationPreviewTest {

    private val walk = AnimationClip(index = 1, name = "Walk", length = Ticks(43L))
    private val clips = listOf(
        AnimationClip(index = 0, name = "Survey", length = Ticks(205L)),
        walk,
        AnimationClip(index = 2, name = "Run", length = Ticks(70L)),
    )

    private val camera = ModelCamera().apply { lookAt(0f, -5.5f, 1.6f, 0f, 0f, 0.8f) }
    private val light = ModelLight(directionX = -0.4f, directionY = 0.7f, directionZ = -1f)

    @Test
    fun `scrubbing moves the fox in the Scene tab alone, and its bones are drawn on its joints`() {
        GlAvailabilityHere.require()
        val fox = loadModel(exampleAssets(), Model(AssetId("models/fox"), ResPath("models/fox/Fox.glb")))

        val registry = RenderRegistry()
        lateinit var models: ModelRenderSystem
        registry.register(RenderPhase.World, { resources -> ModelRenderSystem(resources, camera, light).also { models = it } })
        val backend = KoolBackend.start(
            RenderMode.Offscreen,
            WindowConfig(title = "udea-animation-preview", windowWidth = WIDTH, windowHeight = HEIGHT, renderWidth = WIDTH, renderHeight = HEIGHT),
            registry,
        )
        try {
            val host = GameHost(RenderMode.Offscreen, UdeaGameDef(registry = CoreUdeaRegistry, modules = emptyList()), backend)
            host.loop.paused = true
            backend.drive(host)
            val slot = backend.pipeline!!.capture!!
            val views = EditorViews(
                backend.openSceneView(EditorCamera().apply { dimension = ViewDimension.ThreeD }),
                backend.openGameView(),
            )
            val netIds = host.ctx[CoreModule.NET_IDS]
            val id: NetId = backend.onRenderThread {
                val entity = host.world.entity {
                    it += Transform3D(rotationZ = (PI / 2).toFloat(), scaleX = SCALE, scaleY = SCALE, scaleZ = SCALE)
                    it += ModelRenderer(model = fox)
                    it += Animator().apply { play(walk, host.tick) }
                }
                netIds.allocate(entity)
            }
            awaitFox { decode(slot.capture(CaptureRequest()).bytes) }

            val bridge = AgentBridge()
            val author = AgentSessions().intern("editor")
            val session: EditorSession = backend.onRenderThread {
                EditorSession(
                    tools = EditorTools(bridge, author),
                    tick = { host.tick },
                    paused = { true },
                    spawn = EditorSpawn("Spawn", BlueprintId("skeleton"), 0f, 0f),
                    views = views,
                    animation = EditorAnimation(host.world, netIds, listOf(AnimatedModel(fox, clips)), models),
                )
            }
            val panel = checkNotNull(session.animation)
            val hasher = SnapshotService(ComponentRegistry(listOf(Animator.snapshotType())), host.world, host.ctx, netIds)
            fun worldHash(): Long = backend.onRenderThread { WorldHasher.hash(hasher.capture()) }

            // Select the Fox, as `editor.selection` would say after a click or an agent's `editor.select`.
            editorFrames(backend, session, bridge, id)
            backend.onRenderThread { views.scene.showGizmos = false }
            val hash = worldHash()
            val simulated = frame(backend, slot, views)
            backend.onRenderThread { views.scene.showGizmos = true }
            val simulatedWithBones = frame(backend, slot, views)

            // 1. Scrub to two clip times: the Scene tab moves; the capture and the world do not.
            backend.onRenderThread { panel.preview(true) }
            val poses = LinkedHashMap<Long, Frame>()
            val bare = LinkedHashMap<Long, BufferedImage>()
            val skeletons = LinkedHashMap<Long, List<FloatArray>>()
            for (at in SCRUB_TICKS) {
                backend.onRenderThread { panel.scrub(at) }
                editorFrames(backend, session, bridge, id)
                assertEquals(ModelPreview.Pose(id, walk, Ticks(at)), backend.onRenderThread { views.scene.modelPreview })
                poses[at] = frame(backend, slot, views)
                skeletons[at] = skeleton(backend, models, id, views)
                backend.onRenderThread { views.scene.showGizmos = false }
                bare[at] = frame(backend, slot, views).scene
                backend.onRenderThread { views.scene.showGizmos = true }
                save(poses.getValue(at).scene, "animation-preview-scene-at-$at.png")
            }
            save(simulated.capture, "animation-preview-capture.png")
            val first = SCRUB_TICKS.first()
            val last = SCRUB_TICKS.last()
            val moved = moved(bare.getValue(first), bare.getValue(last))
            println("GlAnimationPreviewTest: $moved fox pixels moved in the Scene tab between clip ticks $first and $last")
            assertTrue(moved >= MIN_MOVED_PIXELS, "scrubbing did not move the fox in the Scene tab: $moved pixels")
            // The preview stands in for the simulated fox rather than beside it: where the simulated
            // pose stood and the previewed one does not, the background shows.
            val uncovered = uncovered(simulated.scene, bare.getValue(last))
            println("GlAnimationPreviewTest: $uncovered pixels of the simulated fox uncovered at clip tick $last")
            assertTrue(uncovered >= MIN_MOVED_PIXELS, "the simulated fox is still drawn under the preview: $uncovered pixels uncovered")
            for ((at, pose) in poses) {
                assertContentEquals(pixels(simulated.capture), pixels(pose.capture), "scrubbing to $at changed the capturable frame")
            }
            assertEquals(hash, worldHash(), "scrubbing changed the world")

            // 2. The bones: a dot on every joint, on the fox, moving with the clip, in no capture.
            val scene = checkNotNull(views.scene.camera)
            for ((at, joints) in skeletons) {
                val picture = poses.getValue(at).scene
                val silhouette = bare.getValue(at)
                var dotted = 0
                var onFox = 0
                for (joint in joints) {
                    val view = ViewPoint()
                    check(backend.onRenderThread { scene.project(joint[0], joint[1], joint[2], view) }) { "a joint is behind the camera" }
                    val x = view.x.toInt()
                    val y = picture.height - 1 - view.y.toInt()
                    if (near(picture, x, y, DOT_REACH, ::isMark)) dotted++
                    if (near(silhouette, x, y, FOX_REACH, ::isFox)) onFox++
                }
                println("GlAnimationPreviewTest: clip tick $at: ${joints.size} joints, $dotted dotted, $onFox on the fox")
                assertTrue(joints.size >= MIN_JOINTS, "the fox's skeleton has ${joints.size} joints")
                assertEquals(joints.size, dotted, "at clip tick $at not every joint has its dot where the Scene camera puts it")
                assertTrue(onFox >= joints.size * ON_FOX_SHARE, "at clip tick $at only $onFox of ${joints.size} joints are on the fox")

                // A line from each joint up to the one it hangs from: yellow halfway along every bone
                // long enough on screen for its middle to be clear of both dots.
                val hanging = joints.filter { it[3].toInt() != ModelSkeleton.ROOT }
                assertTrue(hanging.size >= joints.size / 2, "at clip tick $at only ${hanging.size} of ${joints.size} joints hang from another")
                var bones = 0
                var lined = 0
                for (joint in hanging) {
                    val parent = joints[joint[3].toInt()]
                    val a = ViewPoint()
                    val b = ViewPoint()
                    backend.onRenderThread {
                        scene.project(joint[0], joint[1], joint[2], a)
                        scene.project(parent[0], parent[1], parent[2], b)
                    }
                    if (hypot(a.x - b.x, a.y - b.y) < MIN_BONE_PIXELS) continue
                    bones++
                    val x = ((a.x + b.x) / 2f).toInt()
                    val y = picture.height - 1 - ((a.y + b.y) / 2f).toInt()
                    if (near(picture, x, y, LINE_REACH, ::isMark)) lined++
                }
                println("GlAnimationPreviewTest: clip tick $at: $lined of $bones bones drawn")
                assertTrue(bones >= MIN_BONES, "at clip tick $at only $bones bones are long enough to see")
                assertEquals(bones, lined, "at clip tick $at not every bone has its line")
                assertEquals(0, count(poses.getValue(at).capture, ::isMark), "the bone overlay reached the capturable frame")
            }
            val travelled = skeletons.getValue(first).zip(skeletons.getValue(last)).maxOf { (a, b) -> distance(a, b) }
            assertTrue(travelled >= MIN_JOINT_TRAVEL, "no joint moved between clip ticks $first and $last: at most $travelled units")
            assertTrue(count(simulatedWithBones.scene, ::isMark) >= MIN_MARK_PIXELS, "the Scene tab drew no bones for the selected fox")

            // The Game tab, its gizmo toggle on: the overlay is the Scene tab's alone, so no bones here
            // either, and none in the capturable frame.
            backend.onRenderThread { views.game.showGizmos = true }
            val gameTab = frame(backend, slot, views)
            save(gameTab.game, "animation-preview-game-tab.png")
            assertEquals(0, count(gameTab.game, ::isMark), "the Game tab drew bones through the game's 2D camera")
            assertEquals(0, count(gameTab.capture, ::isMark), "the bone overlay reached the capturable frame")
            backend.onRenderThread { views.game.showGizmos = false }

            // The Scene tab in 2D still draws the fox through its 3D orbit, but would place a gizmo
            // through its 2D camera: there is nowhere right for a joint, so no bone is drawn.
            backend.onRenderThread { scene.dimension = ViewDimension.TwoD }
            val flat = frame(backend, slot, views)
            save(flat.scene, "animation-preview-scene-2d.png")
            assertEquals(0, count(flat.scene, ::isMark), "the Scene tab in 2D drew bones through its 2D camera")
            backend.onRenderThread { scene.dimension = ViewDimension.ThreeD }

            // 3. Leaving the preview draws the simulated pose again, exactly.
            backend.onRenderThread { panel.preview(false) }
            editorFrames(backend, session, bridge, id)
            val after = frame(backend, slot, views)
            assertEquals(null, backend.onRenderThread { views.scene.modelPreview })
            assertContentEquals(pixels(simulatedWithBones.scene), pixels(after.scene), "leaving the preview did not bring back the simulated pose")
            assertEquals(hash, worldHash(), "the preview changed the world")

            // 4. The model on its own: the Fox asset alone in the Scene tab, whole, at its middle,
            // at a size to look at, turning; the capture and the world untouched.
            backend.onRenderThread {
                views.scene.showGizmos = false
                panel.showModel(panel.models.single())
            }
            editorFrames(backend, session, bridge, id)
            val shown = frame(backend, slot, views)
            editorFrames(backend, session, bridge, id)
            val turned = frame(backend, slot, views)
            save(shown.scene, "animation-preview-model.png")
            save(turned.scene, "animation-preview-model-turned.png")
            assertModelShown(shown.scene)
            assertTrue(moved(shown.scene, turned.scene) >= MIN_MOVED_PIXELS, "the model preview does not turn")
            // The entity's own fox is not drawn with it: where that fox stood and the model does not,
            // the background shows.
            val alone = uncovered(simulated.scene, shown.scene)
            println("GlAnimationPreviewTest: $alone pixels of the entity's fox uncovered by the model preview")
            assertTrue(alone >= MIN_MOVED_PIXELS, "the entity's fox is drawn under the model preview: $alone pixels uncovered")
            assertContentEquals(pixels(simulated.capture), pixels(shown.capture), "the model preview changed the capturable frame")
            assertEquals(hash, worldHash(), "the model preview changed the world")

            // The selected fox is hidden while the model shows, so its bones are not drawn either.
            backend.onRenderThread { views.scene.showGizmos = true }
            val withOverlay = frame(backend, slot, views)
            assertEquals(0, count(withOverlay.scene, ::isMark), "the hidden fox's bones are drawn over the model preview")

            // Side on, in a tall narrow Scene tab: whole, its length fitted to the width.
            backend.onRenderThread {
                views.scene.showGizmos = false
                views.scene.modelPreview = ModelPreview.Asset(fox, null, Ticks(0L), SIDE_ON)
                views.scene.resizeTo(NARROW_WIDTH, HEIGHT)
            }
            frame(backend, slot, views)
            val narrow = frame(backend, slot, views)
            save(narrow.scene, "animation-preview-model-narrow.png")
            assertEquals(NARROW_WIDTH, narrow.scene.width, "the Scene view did not take its narrow size")
            assertModelShown(narrow.scene)
        } finally {
            backend.close()
        }
    }

    // --- fixture -------------------------------------------------------------------------

    private class Frame(val capture: BufferedImage, val scene: BufferedImage, val game: BufferedImage)

    /**
     * A few editor frames on the render thread, answering the editor's calls between them as the
     * simulation would: `editor.selection` with [selected] as the editor's, the Inspector's
     * `editor.common_fields` with no shared field, and everything else with an empty answer. Enough for a read to be sent, answered and delivered.
     */
    private fun editorFrames(backend: KoolBackend, session: EditorSession, bridge: AgentBridge, selected: NetId) {
        repeat(EDITOR_FRAMES) {
            backend.onRenderThread {
                session.frame()
                val sent = ArrayList<AgentCommand>()
                bridge.drain(sent)
                for (command in sent) {
                    val answer = when (command.name) {
                        "editor.selection" -> """{"you":"editor","authors":[{"author":"editor","ids":[${selected.raw}]}]}"""
                        "editor.history" -> """{"author":"editor","size":0,"edits":[]}"""
                        "editor.common_fields" -> """{"fields":[]}"""
                        else -> "{}"
                    }
                    bridge.complete(command.id, AgentResult.Ok(answer))
                }
            }
        }
    }

    /** The capturable frame and both tabs, requested between two frames so one frame serves all three. */
    private fun frame(backend: KoolBackend, slot: FrameCaptureSlot, views: EditorViews): Frame {
        val (capture, scene, game) = backend.onRenderThread { Triple(slot.submit(CaptureRequest()), views.scene.capture(), views.game.capture()) }
        return Frame(decode(await(capture).bytes), decode(await(scene).bytes), decode(await(game).bytes))
    }

    /** The Scene tab's skeleton for [id], as the overlay reads it, each joint `x, y, z`. */
    private fun skeleton(backend: KoolBackend, models: ModelRenderSystem, id: NetId, views: EditorViews): List<FloatArray> =
        backend.onRenderThread {
            val out = ModelSkeleton()
            check(models.skeletonOf(id, views.scene, out)) { "the renderer has no skeleton for the fox" }
            List(out.size) { floatArrayOf(out.x(it), out.y(it), out.z(it), out.parent(it).toFloat()) }
        }

    private fun awaitFox(capture: () -> BufferedImage) {
        var frames = 0
        do {
            val pixels = count(capture(), ::isFox)
            frames++
        } while (pixels < MIN_FOX_PIXELS && frames < FRAME_BUDGET)
    }

    private fun await(result: Deferred<CaptureResult>): CaptureResult =
        runBlocking { withTimeout(CAPTURE_TIMEOUT_MILLIS) { result.await() } }

    private fun isFox(pixel: Int): Boolean =
        ((pixel ushr 16) and 0xFF) > BACKGROUND || ((pixel ushr 8) and 0xFF) > BACKGROUND || (pixel and 0xFF) > BACKGROUND

    /** The overlay's yellow, `HandlePainter.MARK` - every mark's colour - and nothing on the fox. */
    private fun isMark(pixel: Int): Boolean {
        val expected = HandlePainter.MARK
        val r = (pixel ushr 16) and 0xFF
        val g = (pixel ushr 8) and 0xFF
        val b = pixel and 0xFF
        return abs(r - (expected.r * 255).toInt()) <= MARK_TOLERANCE &&
            abs(g - (expected.g * 255).toInt()) <= MARK_TOLERANCE &&
            abs(b - (expected.b * 255).toInt()) <= MARK_TOLERANCE
    }

    /** Whether a pixel within [reach] of ([x], [y]) in [image] passes [test]. */
    private fun near(image: BufferedImage, x: Int, y: Int, reach: Int, test: (Int) -> Boolean): Boolean {
        for (dy in -reach..reach) for (dx in -reach..reach) {
            val px = x + dx
            val py = y + dy
            if (px in 0 until image.width && py in 0 until image.height && test(image.getRGB(px, py))) return true
        }
        return false
    }

    private fun moved(a: BufferedImage, b: BufferedImage): Int {
        var n = 0
        for (y in 0 until a.height) for (x in 0 until a.width) if (isFox(a.getRGB(x, y)) != isFox(b.getRGB(x, y))) n++
        return n
    }

    /** The model preview in [image]: whole, at its middle, and at a size to look at. */
    private fun assertModelShown(image: BufferedImage) {
        val box = foxBox(image)
        println("GlAnimationPreviewTest: the model preview covers $box of ${image.width}x${image.height}")
        assertTrue(box.left > 0 && box.top > 0 && box.right < image.width - 1 && box.bottom < image.height - 1, "the model preview is cut off by the view's edge: $box")
        val largest = maxOf(box.width.toFloat() / image.width, box.height.toFloat() / image.height)
        assertTrue(largest in MODEL_SHARE, "the model preview fills $largest of the view")
        val centreX = (box.left + box.right) / 2f / image.width
        val centreY = (box.top + box.bottom) / 2f / image.height
        assertTrue(abs(centreX - 0.5f) < MODEL_OFF_CENTRE && abs(centreY - 0.5f) < MODEL_OFF_CENTRE, "the model preview is off the view's middle: $box")
    }

    /** The smallest rectangle holding every fox pixel of [image]. */
    private fun foxBox(image: BufferedImage): Box {
        var left = image.width
        var top = image.height
        var right = -1
        var bottom = -1
        for (y in 0 until image.height) for (x in 0 until image.width) {
            if (!isFox(image.getRGB(x, y))) continue
            left = minOf(left, x)
            top = minOf(top, y)
            right = maxOf(right, x)
            bottom = maxOf(bottom, y)
        }
        check(right >= 0) { "no fox in the picture" }
        return Box(left, top, right, bottom)
    }

    /** A rectangle of pixels, its edges inclusive, from the top left. */
    private data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left + 1
        val height: Int get() = bottom - top + 1
    }

    /** Pixels that are fox in [before] and background in [after]. */
    private fun uncovered(before: BufferedImage, after: BufferedImage): Int {
        var n = 0
        for (y in 0 until before.height) for (x in 0 until before.width) if (isFox(before.getRGB(x, y)) && !isFox(after.getRGB(x, y))) n++
        return n
    }

    private fun count(image: BufferedImage, test: (Int) -> Boolean): Int {
        var n = 0
        for (y in 0 until image.height) for (x in 0 until image.width) if (test(image.getRGB(x, y))) n++
        return n
    }

    private fun distance(a: FloatArray, b: FloatArray): Float {
        val dx = a[0] - b[0]
        val dy = a[1] - b[1]
        val dz = a[2] - b[2]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun pixels(image: BufferedImage): IntArray = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)

    private fun decode(png: ByteArray): BufferedImage = ImageIO.read(ByteArrayInputStream(png))
        ?: error("the captured bytes are not a decodable image")

    private fun exampleAssets(): Path = Path.of(
        System.getProperty("udea.render.exampleAssets") ?: error("-Dudea.render.exampleAssets is not set"),
    )

    private fun save(image: BufferedImage, name: String) {
        val dir = System.getProperty("udea.render.glReportDir") ?: return
        File(dir).mkdirs()
        ImageIO.write(image, "png", File(dir, name))
    }

    private companion object {
        /** The model preview's larger side, as a share of the view's: seen whole, and not a speck. */
        val MODEL_SHARE = 0.25f..0.9f

        /** How far the model preview's middle may sit from the view's, as a share of the view. */
        const val MODEL_OFF_CENTRE = 0.15f

        /** A Scene tab narrower than it is tall, as the editor's is between its docked panels. */
        const val NARROW_WIDTH = 200

        /** The Fox's heading at which its length runs across the view. */
        const val SIDE_ON = 90f

        const val WIDTH = 480
        const val HEIGHT = 320

        /** World units per unit of the file: a fox about 1.6 tall. */
        const val SCALE = 0.02f

        /** Walk's clip ticks the scrubber visits: its start, a quarter and a half of its 43-tick stride. */
        val SCRUB_TICKS = listOf(0L, 11L, 21L)

        /** Enough editor frames for a read to be sent, answered, and its answer delivered and drawn. */
        const val EDITOR_FRAMES = 4

        const val BACKGROUND = 12
        const val MIN_FOX_PIXELS = 5_000
        const val FRAME_BUDGET = 400
        const val MIN_MOVED_PIXELS = 150
        const val CAPTURE_TIMEOUT_MILLIS = 20_000L

        /** A skinned fox has dozens of joints; fewer than this is not its skeleton. */
        const val MIN_JOINTS = 10

        /** A joint's dot is drawn centred on its pixel; this far off still counts as on it. */
        const val DOT_REACH = 2

        /** A joint lies inside the body, or at a paw or ear tip within this many pixels of it. */
        const val FOX_REACH = 4

        /** The share of joints that must be on the fox's silhouette. */
        const val ON_FOX_SHARE = 0.9

        /** A bone shorter than this on screen has its middle under its two dots. */
        const val MIN_BONE_PIXELS = 16f

        /** A bone's middle is on its line within this many pixels. */
        const val LINE_REACH = 1

        /** The fox's legs, spine and tail each have bones longer than that. */
        const val MIN_BONES = 8

        /** World units the farthest-moving joint must travel between two clip times a half stride apart. */
        const val MIN_JOINT_TRAVEL = 0.05f

        /** Overlay pixels a drawn skeleton leaves at the very least. */
        const val MIN_MARK_PIXELS = 100

        /** How far a channel may be from the overlay's yellow: blending at a dot's edge. */
        const val MARK_TOLERANCE = 6
    }
}
