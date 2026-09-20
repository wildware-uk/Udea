package dev.wildware.udea.render.gl

import com.github.quillraven.fleks.Entity
import dev.wildware.udea.assets.AssetId
import dev.wildware.udea.assets.Model
import dev.wildware.udea.assets.ResPath
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.Ticks
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.core.module.UdeaGameDef
import dev.wildware.udea.core.spatial.AnimationClip
import dev.wildware.udea.core.spatial.Animator
import dev.wildware.udea.core.spatial.AttachedTo
import dev.wildware.udea.core.spatial.ModelNode
import dev.wildware.udea.core.spatial.Transform3D
import dev.wildware.udea.generated.CoreUdeaRegistry
import dev.wildware.udea.render.RenderPhase
import dev.wildware.udea.render.RenderRegistry
import dev.wildware.udea.render.backend.KoolBackend
import dev.wildware.udea.render.backend.WindowConfig
import dev.wildware.udea.render.capture.CaptureRequest
import dev.wildware.udea.render.capture.capture
import dev.wildware.udea.render.draw.SpriteTexture
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
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Parts mounted on the named nodes of a chassis, drawn through a real Kool context (issue #260).
 *
 * ## The picture it reads
 *
 * A chassis with five sockets, an orange turret module plugged into each, and two blocks plugged
 * into the roof turret's own sockets - parts on a part on a chassis: a green one on its roof and a
 * violet one on its muzzle. The chassis's roof socket sits on a ring that turns: the file's one
 * animation spins it through a full turn every 240 ticks, so what the picture must show is a roof
 * module that goes round with the ring while the four on the hull stay put.
 *
 * Four colours that lighting cannot turn into one another: the modules' orange paint (red well
 * above blue), the chassis's blue-grey steel (blue a little above red), the roof block's green
 * and the muzzle block's violet (red and blue both well above green).
 *
 * - **Five modules are mounted, spread over the chassis.** Their orange spans nearly the whole
 *   width of the chassis's steel. A socket that resolved to nothing would stack every module on
 *   the chassis's own origin, which is a narrow column of orange in the middle.
 * - **A part on a part.** The green block is drawn above every module, which is where the roof
 *   turret's own roof socket is.
 * - **A module on an animated node follows the animation.** Half a turn later the violet
 *   block - two mounts from the chassis, on the muzzle of a turret on the turning ring - has moved
 *   across the frame, while the same number of orange pixels is still drawn. Nothing but the live
 *   node can do that: the simulation places a mount from the rest pose, which does not turn.
 * - **The whole assembly rides the chassis.** Driving the chassis sideways moves every module
 *   with it.
 *
 * One `@Test` because Kool allows one context per JVM (see `GlCaptureTest`).
 *
 * ## The numbers in [ChassisNodes]
 *
 * They are what the asset build generates for this fixture, written out by hand because the asset
 * build is not on a renderer's classpath. `GltfNodesTest` reads the same numbers out of the same
 * file, so a rebuilt fixture that moved a socket fails there, naming the socket, rather than
 * drawing a module in the wrong place here.
 */
class GlSocketMountTest {

    @Test
    fun `five modules and a module on a module are drawn at their sockets, animated node included`() {
        GlAvailability.require()
        val assets = exampleAssets()
        val chassisModel = loadModel(assets, Model(AssetId("models/chassis"), ResPath("models/chassis/chassis.glb")))
        val turretModel = loadModel(assets, Model(AssetId("models/turret"), ResPath("models/chassis/turret.glb")))

        withStage { backend, host, system ->
            val slot = checkNotNull(backend.pipeline?.capture) { "the pipeline has no capture slot" }
            val netIds = host.ctx[CoreModule.NET_IDS]
            val world = host.world
            lateinit var chassis: Entity

            backend.onRenderThread {
                fun spawn(build: (com.github.quillraven.fleks.EntityCreateContext.(Entity) -> Unit)): Entity =
                    world.entity(build).also(netIds::allocate)

                chassis = spawn {
                    it += Transform3D()
                    it += ModelRenderer(model = chassisModel)
                    // The file's one clip, which turns the roof ring. Its length is what the asset
                    // build reads off the file: 96 frames at 24 fps is four seconds, 240 ticks.
                    it += Animator().apply { play(SPIN, Tick.ZERO) }
                }
                val chassisId = netIds.netIdOf(chassis)
                val roof = spawn {
                    it += Transform3D()
                    it += ModelRenderer(model = turretModel)
                    it += AttachedTo(chassisId, ChassisNodes.socketRoof)
                }
                for (socket in listOf(
                    ChassisNodes.socketLeft,
                    ChassisNodes.socketRight,
                    ChassisNodes.socketFront,
                    ChassisNodes.socketRear,
                )) {
                    spawn {
                        it += Transform3D()
                        it += ModelRenderer(model = turretModel)
                        it += AttachedTo(chassisId, socket)
                    }
                }
                // Two parts on a part: a green block on the roof turret's own socket, and a
                // violet one on its muzzle, which is far enough off the ring's axis to sweep when it turns.
                val roofId = netIds.netIdOf(roof)
                spawn {
                    it += Transform3D()
                    it += ModelRenderer(ModelMesh.box(0.5f, 0.5f, 0.8f), ModelMaterial(flat(60, 210, 90)))
                    it += AttachedTo(roofId, TurretNodes.socketTop)
                }
                spawn {
                    it += Transform3D()
                    it += ModelRenderer(ModelMesh.box(0.6f, 0.6f, 0.6f), ModelMaterial(flat(210, 70, 225)))
                    it += AttachedTo(roofId, TurretNodes.socketMuzzle)
                }
                host.loop.stepTicks(1)
            }

            // One frame for Kool to finish making the nodes and decoding nothing (see GlModelRenderTest).
            slot.capture(CaptureRequest())
            val mounted = capture(slot, "issue260-chassis-five-modules.png")

            assertEquals(ENTITIES, backend.onRenderThread { system.drawnCount }, "every entity is drawn")

            // 1. Five modules, spread over the chassis rather than stacked on its origin.
            val orange = span(mounted, ::isPaint)
            val steel = span(mounted, ::isSteel)
            assertTrue(orange.count > MIN_PAINT_PIXELS, "the modules are not drawn: $orange")
            assertTrue(
                orange.width > steel.width * MIN_SPREAD,
                "the modules are bunched rather than spread over the chassis's sockets: orange $orange, steel $steel",
            )

            // 2. A part on a part: the green block on the roof turret's socket is above every
            // module, which is where that socket is.
            val green = span(mounted, ::isRoofBlock)
            assertTrue(green.count > MIN_BLOCK_PIXELS, "the block on the roof turret's socket is not drawn: $green")
            assertTrue(
                green.top < orange.top,
                "the block must sit above the turret it is mounted on: block $green, modules $orange",
            )

            // 3. Half of the ring's turn later, the block on the roof turret's muzzle has gone
            // round with the ring - two mounts away from the chassis, through an animated node. Half
            // a turn rather than a quarter because it is the one that crosses the frame: the muzzle
            // is on the far side of the ring's axis from where it started.
            val muzzle = span(mounted, ::isMuzzleBlock)
            assertTrue(muzzle.count > MIN_BLOCK_PIXELS, "the block on the muzzle is not drawn: $muzzle")
            backend.onRenderThread { host.loop.stepTicks(HALF_SPIN_TICKS) }
            slot.capture(CaptureRequest())
            val turned = capture(slot, "issue260-animated-socket-half-turn.png")
            val turnedMuzzle = span(turned, ::isMuzzleBlock)
            val turnedOrange = span(turned, ::isPaint)
            assertTrue(
                abs(turnedMuzzle.centreX - muzzle.centreX) > MIN_SWING_PX,
                "the block on the turning mount did not move: $muzzle then $turnedMuzzle",
            )
            assertTrue(
                abs(turnedOrange.count - orange.count) < orange.count / PAINT_TOLERANCE,
                "the modules on the hull should still all be drawn: $orange then $turnedOrange",
            )

            // 4. The whole assembly rides the chassis.
            backend.onRenderThread {
                with(world) { chassis[Transform3D].y = DRIVE_TO_Y }
                host.loop.stepTicks(1)
            }
            slot.capture(CaptureRequest())
            val driven = capture(slot, "issue260-assembly-rides-the-chassis.png")
            val drivenOrange = span(driven, ::isPaint)
            val drivenGreen = span(driven, ::isRoofBlock)
            assertTrue(
                drivenOrange.centreX - turnedOrange.centreX > MIN_DRIVE_PX,
                "the modules did not follow the chassis: $turnedOrange then $drivenOrange",
            )
            assertTrue(
                drivenGreen.count > MIN_BLOCK_PIXELS,
                "the block two mounts down went missing when the chassis moved: $drivenGreen",
            )

            // A frame every eighth of a turn, for a person to look at the ring going round.
            backend.onRenderThread { with(world) { chassis[Transform3D].y = 0f } }
            for (step in 0 until SEQUENCE_FRAMES) {
                backend.onRenderThread { host.loop.stepTicks(SPIN_TICKS / SEQUENCE_FRAMES) }
                slot.capture(CaptureRequest())
                capture(slot, "issue260-spin-%02d.png".format(step))
            }
        }
    }

    // --- the fixture's nodes -------------------------------------------------------------------

    /**
     * What the asset build generates for `models/chassis/chassis.glb`, by hand.
     *
     * Four sockets on the hull and one on the ring that turns, each with its own frame: the end
     * sockets are turned to face along the chassis and scaled to 0.6, because a small socket
     * takes a small module. See this class's KDoc for why they are written out here.
     */
    private object ChassisNodes {
        val socketRoof = ModelNode(index = 1, name = "socket_roof", z = 0.87f)
        val socketFront = ModelNode(
            index = 3, name = "socket_front", x = 1.2f, z = 0.55f,
            qy = QUARTER_TURN_AXIS, qw = QUARTER_TURN_AXIS,
            scaleX = 0.6f, scaleY = 0.6f, scaleZ = 0.6f,
        )
        val socketLeft = ModelNode(
            index = 4, name = "socket_left", x = 0.35f, y = 0.7f, z = 0.6f,
            qx = -QUARTER_TURN_AXIS, qw = QUARTER_TURN_AXIS,
        )
        val socketRear = ModelNode(
            index = 5, name = "socket_rear", x = -1.2f, z = 0.55f,
            qy = -QUARTER_TURN_AXIS, qw = QUARTER_TURN_AXIS,
            scaleX = 0.6f, scaleY = 0.6f, scaleZ = 0.6f,
        )
        val socketRight = ModelNode(
            index = 6, name = "socket_right", x = 0.35f, y = -0.7f, z = 0.6f,
            qx = QUARTER_TURN_AXIS, qw = QUARTER_TURN_AXIS,
        )
    }

    /**
     * What the asset build generates for `models/chassis/turret.glb`: a half-scale socket on the
     * roof and one at the muzzle, turned to point the way the barrel does.
     */
    private object TurretNodes {
        val socketMuzzle = ModelNode(
            index = 0, name = "socket_muzzle", x = 0.85f, z = 0.3f,
            qy = QUARTER_TURN_AXIS, qw = QUARTER_TURN_AXIS,
            scaleX = 0.35f, scaleY = 0.35f, scaleZ = 0.35f,
        )
        val socketTop = ModelNode(
            index = 1, name = "socket_top", z = 0.42f,
            scaleX = 0.5f, scaleY = 0.5f, scaleZ = 0.5f,
        )
    }

    // --- fixture -------------------------------------------------------------------------------

    private fun withStage(block: (KoolBackend, GameHost, ModelRenderSystem) -> Unit) {
        val registry = RenderRegistry()
        lateinit var system: ModelRenderSystem
        // Front, left and above, framing a chassis 2.4 long and about 1.4 tall with its modules on.
        val camera = ModelCamera().apply { lookAt(3.6f, -4.6f, 3.0f, 0f, 0f, 0.7f) }
        val light = ModelLight(directionX = -0.4f, directionY = 0.7f, directionZ = -0.9f)
        registry.register(RenderPhase.World, { resources ->
            ModelRenderSystem(resources, camera, light).also { system = it }
        })
        val backend = KoolBackend.start(
            RenderMode.Offscreen,
            WindowConfig(
                title = "udea-socket-mount-test",
                windowWidth = WIDTH,
                windowHeight = HEIGHT,
                renderWidth = WIDTH,
                renderHeight = HEIGHT,
            ),
            registry,
        )
        try {
            val host = GameHost(
                RenderMode.Offscreen,
                UdeaGameDef(registry = CoreUdeaRegistry, modules = emptyList()),
                backend,
            )
            host.loop.paused = true
            backend.drive(host)
            block(backend, host, system)
        } finally {
            backend.close()
        }
    }

    private fun exampleAssets(): Path = Path.of(
        System.getProperty("udea.render.exampleAssets") ?: error("-Dudea.render.exampleAssets is not set"),
    )

    /** One flat colour, as a texture: what a built-in mesh's material needs. */
    private fun flat(r: Int, g: Int, b: Int): SpriteTexture = SpriteTexture.fromRgba(
        1,
        1,
        byteArrayOf(r.toByte(), g.toByte(), b.toByte(), -1),
        "socket-mount-$r-$g-$b",
    )

    /** The orange paint of a module: red well above blue, green in between. */
    private fun isPaint(r: Int, g: Int, b: Int): Boolean = r > b + HUE_MARGIN && r > g + HUE_MARGIN / 2 && r > FLOOR

    /** The chassis's blue-grey steel. */
    private fun isSteel(r: Int, g: Int, b: Int): Boolean = b > r + HUE_MARGIN / 3 && b > FLOOR

    /** The green block on the roof turret's own socket. */
    private fun isRoofBlock(r: Int, g: Int, b: Int): Boolean =
        g > r + HUE_MARGIN && g > b + HUE_MARGIN && g > FLOOR

    /**
     * The violet block on the roof turret's muzzle: red and blue both well above green, which no
     * other colour in the frame is. The green block's own lit face is blue enough to read as a
     * cyan, which is why this one is not one.
     */
    private fun isMuzzleBlock(r: Int, g: Int, b: Int): Boolean =
        r > g + HUE_MARGIN && b > g + HUE_MARGIN && b > FLOOR

    /** Where the pixels a test matches are: how many, their box, and the middle of it. */
    private data class Span(val count: Int, val left: Int, val right: Int, val top: Int, val bottom: Int) {
        val width: Int get() = if (count == 0) 0 else right - left + 1
        val centreX: Float get() = if (count == 0) 0f else (left + right) / 2f
    }

    private fun span(image: BufferedImage, matches: (Int, Int, Int) -> Boolean): Span {
        var count = 0
        var left = image.width
        var right = -1
        var top = image.height
        var bottom = -1
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val pixel = image.getRGB(x, y)
                if (!matches((pixel ushr 16) and 0xFF, (pixel ushr 8) and 0xFF, pixel and 0xFF)) continue
                count++
                left = minOf(left, x)
                right = maxOf(right, x)
                top = minOf(top, y)
                bottom = maxOf(bottom, y)
            }
        }
        return if (count == 0) Span(0, 0, 0, 0, 0) else Span(count, left, right, top, bottom)
    }

    private fun capture(slot: dev.wildware.udea.render.capture.FrameCaptureSlot, name: String): BufferedImage {
        val image = decode(slot.capture(CaptureRequest()).bytes)
        save(image, name)
        return image
    }

    private fun decode(png: ByteArray): BufferedImage = ImageIO.read(ByteArrayInputStream(png))
        ?: error("the captured bytes are not a decodable image")

    /** Writes [image] where a person can look at it: the frames the assertions read. */
    private fun save(image: BufferedImage, name: String) {
        val dir = System.getProperty("udea.render.glReportDir") ?: return
        File(dir).mkdirs()
        ImageIO.write(image, "png", File(dir, name))
    }

    private companion object {
        const val WIDTH = 640
        const val HEIGHT = 420

        /** The chassis, five modules and the two blocks on the roof turret. */
        const val ENTITIES = 8

        /** `sin(45 degrees)`, which is `cos(45 degrees)`: a quarter turn about one axis. */
        val QUARTER_TURN_AXIS = sin(PI / 4).toFloat()

        /** The file's ring animation: 96 frames at 24 fps, which the asset build reads as 240 ticks. */
        val SPIN = AnimationClip(index = 0, name = "Spin", length = Ticks(240L))
        const val SPIN_TICKS = 240
        const val HALF_SPIN_TICKS = SPIN_TICKS / 2

        /** Where the chassis drives to, in world units: to its own left, which is right on screen. */
        const val DRIVE_TO_Y = 2.5f

        /** How many frames the spin sequence saves for a person to look at. */
        const val SEQUENCE_FRAMES = 8

        /** A pixel this dark is the black background or a face in shadow, not a colour. */
        const val FLOOR = 40

        /** How far apart two channels must be to be a hue rather than a grey. */
        const val HUE_MARGIN = 30

        /** Below this the modules are not drawn at all. Each is a 0.6m box at about 4m. */
        const val MIN_PAINT_PIXELS = 1_500
        const val MIN_BLOCK_PIXELS = 120

        /** The modules must span most of the chassis's own width: they are on its sockets. */
        const val MIN_SPREAD = 0.8f

        /**
         * How far the block must swing across the frame in half the ring's turn. The muzzle is
         * 0.85m off the axis, so half a turn carries it 1.7m across, which at this camera is about
         * 175px - and a part left on the rest pose would move nothing at all.
         */
        const val MIN_SWING_PX = 100f

        /** How far the modules must move when the chassis drives 2.5m across the frame. */
        const val MIN_DRIVE_PX = 60f

        /** The orange pixel count may vary by a sixth between frames: the modules turn with the ring. */
        const val PAINT_TOLERANCE = 6
    }
}
