package dev.wildware.hollow.render

import dev.wildware.hollow.HollowGame
import dev.wildware.hollow.Prop
import dev.wildware.hollow.Scenery
import dev.wildware.hollow.Sunlight
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.render.draw.Rgba
import dev.wildware.udea.render.draw.SpriteTexture
import dev.wildware.udea.render.model.MeshModel
import dev.wildware.udea.render.model.ModelLight
import dev.wildware.udea.render.model.ModelMaterial
import dev.wildware.udea.render.model.ModelMesh
import dev.wildware.udea.render.model.ModelRenderer
import java.util.EnumMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [ScenerySystem] against the real clearing, headless: the models are built-in meshes standing in for
 * the `.glb` files, which is all the system can tell apart - it hands on whatever the lookup gives.
 * No GL: a `SpriteTexture` uploads nothing until it is drawn.
 */
class ScenerySystemTest {

    private val host = HollowGame.host(RenderMode.Headless).also(HollowGame::seed)
    private val world = host.world
    private val texture = SpriteTexture.fromRgba(1, 1, byteArrayOf(-1, -1, -1, -1), "white")
    private val models = Prop.entries.associateWithTo(EnumMap(Prop::class.java)) {
        MeshModel(ModelMesh.box(1f, 1f, 1f), ModelMaterial(texture))
    }
    private val light = ModelLight()
    private val system = ScenerySystem({ models.getValue(it) }, light).also { it.onBind(world, host.ctx) }

    @Test
    fun `every prop is given the model for its prop`() {
        system.sync()
        var props = 0
        with(world) {
            world.family { all(Scenery) }.forEach { entity ->
                assertSame(models.getValue(entity[Scenery].prop), entity[ModelRenderer].model, "$entity")
                props++
            }
        }
        assertTrue(props > 0, "the clearing has no props")
    }

    @Test
    fun `a prop that changes is drawn with its new model on the next frame`() {
        system.sync()
        val oak = with(world) { world.family { all(Scenery) }.entities.first { it[Scenery].prop != Prop.OAK } }
        with(world) { oak[Scenery].prop = Prop.OAK }
        system.sync()
        assertSame(models.getValue(Prop.OAK), with(world) { oak[ModelRenderer].model })
    }

    @Test
    fun `the level's sun lights the models`() {
        system.sync()
        val sun = with(world) { world.family { all(Sunlight) }.entities.single()[Sunlight] }
        assertEquals(sun.directionX, light.directionX)
        assertEquals(sun.directionY, light.directionY)
        assertEquals(sun.directionZ, light.directionZ)
        assertEquals(sun.intensity, light.intensity)
        assertEquals(Rgba.of(sun.red, sun.green, sun.blue), light.color)
        assertEquals(Rgba.of(sun.ambientRed, sun.ambientGreen, sun.ambientBlue), light.ambient)
    }
}
