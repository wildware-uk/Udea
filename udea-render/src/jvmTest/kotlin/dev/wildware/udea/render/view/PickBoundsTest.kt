package dev.wildware.udea.render.view

import com.github.quillraven.fleks.World
import com.github.quillraven.fleks.configureWorld
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.core.fixtures.testGameContext
import dev.wildware.udea.core.gameContext
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.core.loop.WorldSimulation
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.core.physics.PhysicsBody
import dev.wildware.udea.render.FrameTime
import dev.wildware.udea.render.RenderPhase
import dev.wildware.udea.render.RenderRegistry
import dev.wildware.udea.render.camera.CameraRig
import dev.wildware.udea.render.draw.SpriteRegion
import dev.wildware.udea.render.draw.SpriteRenderSystem
import dev.wildware.udea.render.draw.SpriteRenderer
import dev.wildware.udea.render.draw.SpriteTexture
import dev.wildware.udea.render.interp.InterpSnapshotSystem
import dev.wildware.udea.render.interp.Interpolator
import dev.wildware.udea.render.model.MeshModel
import dev.wildware.udea.render.model.ModelBounds
import dev.wildware.udea.render.model.ModelMaterial
import dev.wildware.udea.render.model.ModelMesh
import dev.wildware.udea.render.support.testTargets
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the engine's own render systems report through [PickBounds] (issue #235): the rectangle or
 * box each entity is drawn over, by its `NetId`, so the editor picks what a person sees.
 */
class PickBoundsTest {

    private val netIds = NetIdIndex()

    private val ctx: GameContext = testGameContext(seed = 3L) { service(CoreModule.NET_IDS, netIds) }

    private val world: World = configureWorld {
        injectables { gameContext(ctx) }
        systems { add(InterpSnapshotSystem()) }
    }

    private val interpolator = Interpolator(ctx.clock, world.system<InterpSnapshotSystem>())

    private val rig = CameraRig(netIds, interpolator, FixedFrameTime)

    @Test
    fun `the sprite system reports each sprite's drawn rectangle, turned with its body, by NetId`() {
        // 16 wide and 8 tall, turned a quarter: it covers 8 across and 16 up about (10, 20).
        val turned = sprite(x = 10f, y = 20f, angle = (PI / 2).toFloat())
        val upright = sprite(x = -5f, y = 0f, angle = 0f)
        val unnamed = world.entity {
            it += PhysicsBody(x = 100f, y = 100f)
            it += SpriteRenderer(region = region(), width = WIDTH, height = HEIGHT)
        }
        WorldSimulation(ctx, world).step()
        val sprites = spriteSystem()

        val reported = Recorder().also { sprites.reportPickBounds(it) }.rects

        assertEquals(setOf(turned, upright), reported.keys, "reported $reported; the sprite with no NetId ($unnamed) must be left out")
        assertRect(floatArrayOf(6f, 12f, 14f, 28f), reported.getValue(turned), "the turned sprite")
        assertRect(floatArrayOf(-13f, -4f, 3f, 4f), reported.getValue(upright), "the upright sprite")
    }

    @Test
    fun `a model's box is its mesh placed by the transform it is drawn with`() {
        val entity = NetId.of(index = 4, generation = 0)
        val recorder = Recorder()
        // A 2 x 4 x 6 box at (1, 2, 3), turned a quarter about Z: 4 across, 2 deep, 6 tall.
        ModelBounds().report(
            entity, MeshModel(ModelMesh.box(2f, 4f, 6f), ModelMaterial(SpriteTexture.whitePixel("pick-box"))),
            x = 1f, y = 2f, z = 3f,
            rotationX = 0f, rotationY = 0f, rotationZ = (PI / 2).toFloat(),
            scaleX = 1f, scaleY = 1f, scaleZ = 1f,
            out = recorder,
        )

        val box = recorder.boxes.getValue(entity)
        val expected = floatArrayOf(-1f, 1f, 0f, 3f, 3f, 6f)
        assertTrue(expected.indices.all { abs(expected[it] - box[it]) < EPSILON }, "the box is ${box.toList()}, not ${expected.toList()}")
    }

    // --- fixture -------------------------------------------------------------------------

    private fun spriteSystem(): SpriteRenderSystem {
        var built: SpriteRenderSystem? = null
        val pipeline = RenderRegistry().apply {
            register(RenderPhase.World, { rig })
            register(RenderPhase.World, { resources -> SpriteRenderSystem(resources, rig, interpolator).also { built = it } })
        }.build(world, ctx, testTargets(width = 640, height = 360))
        // Where a sprite is reported is where the last frame drew it.
        pipeline.render(0f)
        return checkNotNull(built)
    }

    private fun sprite(x: Float, y: Float, angle: Float): NetId {
        val entity = world.entity {
            it += PhysicsBody(x = x, y = y, angle = angle)
            it += SpriteRenderer(region = region(), width = WIDTH, height = HEIGHT)
        }
        return netIds.allocate(entity)
    }

    private fun region(): SpriteRegion = SpriteRegion(SpriteTexture.fromRgba(2, 2, ByteArray(2 * 2 * 4), "pick-sprite"))

    private fun assertRect(expected: FloatArray, actual: FloatArray, what: String) {
        assertTrue(expected.indices.all { abs(expected[it] - actual[it]) < EPSILON }, "$what was reported over ${actual.toList()}, not ${expected.toList()}")
    }

    private class Recorder : PickSink {
        val rects = LinkedHashMap<NetId, FloatArray>()
        val boxes = LinkedHashMap<NetId, FloatArray>()

        override fun rect(entity: NetId, minX: Float, minY: Float, maxX: Float, maxY: Float) {
            rects[entity] = floatArrayOf(minX, minY, maxX, maxY)
        }

        override fun box(entity: NetId, minX: Float, minY: Float, minZ: Float, maxX: Float, maxY: Float, maxZ: Float) {
            boxes[entity] = floatArrayOf(minX, minY, minZ, maxX, maxY, maxZ)
        }
    }

    private object FixedFrameTime : FrameTime {
        override val frameSeconds: Float = 1f / 60f
    }

    private companion object {
        const val WIDTH = 16f
        const val HEIGHT = 8f
        const val EPSILON = 1e-3f
    }
}
