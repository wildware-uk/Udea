package dev.wildware.udea.render.model

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import com.github.quillraven.fleks.configureWorld
import dev.wildware.udea.assets.AssetId
import dev.wildware.udea.assets.AssetIndex
import dev.wildware.udea.core.fixtures.testGameContext
import dev.wildware.udea.core.gameContext
import dev.wildware.udea.core.rng.DefaultRngService
import dev.wildware.udea.core.spatial.Drawn
import dev.wildware.udea.render.draw.SpriteTexture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The bridge issue #270 says every game was writing for itself: a `Drawn` the simulation put on an
 * entity becomes a [ModelRenderer], and stays in step with it.
 *
 * No GL here. What [DrawnModels] does is decide *which* entity gets *which* [ModelSource] and when,
 * and none of that needs a context; `GlDrawnModelTest` is the end-to-end one that draws.
 */
class DrawnModelsTest {

    private val ctx = testGameContext(seed = 7L) { rng = DefaultRngService(7L) }
    private val world: World = configureWorld { injectables { gameContext(ctx) } }

    private val chassisModel = mesh("chassis")
    private val turretModel = mesh("turret")

    /** The slot each model is at, as a packed graph would have assigned it. */
    private val library = object : ModelLibrary {
        var lookups = 0
            private set

        override fun modelAt(slot: AssetIndex): ModelSource {
            lookups++
            return when (slot.value) {
                CHASSIS -> chassisModel
                TURRET -> turretModel
                else -> throw ModelLoadException(AssetId("models/none"), "no model at slot $slot")
            }
        }
    }

    private val models = DrawnModels(library).also { it.bind(world) }

    private fun mesh(name: String): MeshModel = MeshModel(
        ModelMesh.box(1f, 1f, 1f),
        ModelMaterial(SpriteTexture.fromRgba(1, 1, ByteArray(4), name)),
    )

    private fun spawn(slot: Int): Entity = world.entity { it += Drawn(slot) }

    private fun rendererOf(entity: Entity): ModelRenderer? = with(world) { entity.getOrNull(ModelRenderer) }

    @Test
    fun `an entity the simulation gave a model draws it, with no game-written bridge`() {
        val part = spawn(CHASSIS)
        assertNull(rendererOf(part), "nothing has run yet")

        models.sync()

        assertSame(chassisModel, assertNotNull(rendererOf(part), "no ModelRenderer was attached").model)
        assertEquals(1, models.changed, "one entity changed")
    }

    @Test
    fun `an entity whose model changes is repointed rather than given a new renderer`() {
        val part = spawn(CHASSIS)
        models.sync()
        val renderer = assertNotNull(rendererOf(part))
        // A game marks what it wants outlined; replacing the component would silently lose that.
        renderer.mask = true

        with(world) { part[Drawn].model = TURRET }
        models.sync()

        assertSame(renderer, rendererOf(part), "the renderer was replaced")
        assertSame(turretModel, renderer.model, "it still draws the old model")
        assertTrue(renderer.mask, "the outline mark did not survive the model change")
    }

    @Test
    fun `a frame in which nothing changed writes nothing and asks the library nothing`() {
        val parts = List(3) { spawn(CHASSIS) }
        models.sync()
        val before = library.lookups
        assertEquals(parts.size, models.changed, "the first frame attached all three")

        models.sync()
        models.sync()

        assertEquals(0, models.changed, "a steady frame changed something")
        assertEquals(before, library.lookups, "a steady frame asked the library for a model")
    }

    @Test
    fun `a Drawn that names nothing takes the model back off`() {
        val part = spawn(CHASSIS)
        models.sync()
        assertNotNull(rendererOf(part), "attached")

        with(world) { part[Drawn].model = Drawn.NONE }
        models.sync()

        assertNull(rendererOf(part), "the model was not taken off")
        assertEquals(1, models.changed, "removing it is a change")
    }

    /**
     * An entity that was `Drawn.NONE` from the start is the empty case, and it is not the same as
     * one this cleared: nothing here has ever managed it, so there is nothing to take off and
     * nothing to report.
     */
    @Test
    fun `an entity that never named a model is left exactly as it was`() {
        val part = spawn(Drawn.NONE)

        models.sync()

        assertNull(rendererOf(part), "nothing was attached")
        assertEquals(0, models.changed, "nothing changed")
        assertEquals(0, library.lookups, "the library was asked about a slot that names nothing")
    }

    /**
     * A renderer a game attached by hand is the game's. Only what this put on an entity is taken
     * off again - otherwise a game that draws something its own way would lose it the first time
     * the entity met a `Drawn` naming nothing.
     */
    @Test
    fun `a renderer the game attached itself is left alone`() {
        val part = world.entity {
            it += Drawn(Drawn.NONE)
            it += ModelRenderer(turretModel)
        }

        models.sync()

        assertSame(turretModel, assertNotNull(rendererOf(part)).model, "the game's own renderer was removed")
        assertEquals(0, models.changed, "nothing should have changed")
    }

    @Test
    fun `an entity with no Drawn at all is not touched`() {
        val plain = world.entity { it += ModelRenderer(chassisModel) }

        models.sync()

        assertSame(chassisModel, assertNotNull(rendererOf(plain)).model)
        assertEquals(0, models.changed, "an entity with no Drawn was changed")
    }

    private companion object {
        const val CHASSIS = 4
        const val TURRET = 9
    }
}
