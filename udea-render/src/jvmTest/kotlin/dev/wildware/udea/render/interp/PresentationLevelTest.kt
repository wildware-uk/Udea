package dev.wildware.udea.render.interp

import com.github.quillraven.fleks.Entity
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.core.module.UdeaGameDef
import dev.wildware.udea.core.physics.Box
import dev.wildware.udea.core.physics.PhysicsBody
import dev.wildware.udea.core.spatial.Transform3D
import dev.wildware.udea.generated.CoreUdeaRegistry
import dev.wildware.udea.render.RenderModule
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A world that has run `RenderModule` saves and loads as a level (issue #246, reopened).
 *
 * After one tick every `Transform3D` carries an [Interp3D] and every `PhysicsBody` an [Interp]:
 * pose history for the renderer, which is not `@Serializable` and is not level content. Before
 * this fix `saveNow` refused the whole world over them. The fixture has ticked, has moved both
 * entities between ticks so the records hold two different poses, and is saved from the state a
 * running game is in - not from a freshly built world, where no record exists yet and the save
 * passes by accident.
 */
class PresentationLevelTest {

    private fun host(withRender: Boolean): GameHost = GameHost(
        RenderMode.Headless,
        UdeaGameDef(CoreUdeaRegistry, if (withRender) listOf(RenderModule()) else emptyList()),
    )

    private class Spawned(val model: NetId, val body: NetId)

    /** Two entities, one 3D and one 2D, ticked and moved so the pose records are not trivial. */
    private fun lived(host: GameHost): Spawned {
        val netIds = host.ctx[CoreModule.NET_IDS]
        val model = host.world.entity { it += Transform3D(x = 1f, y = 2f, z = 3f, rotationZ = 0.25f, scaleX = 2f) }
        val body = host.world.entity {
            it += PhysicsBody(x = 4f, y = -2f)
            it += Box(halfWidth = 0.5f, halfHeight = 0.25f)
        }
        val ids = Spawned(netIds.allocate(model), netIds.allocate(body))
        host.run(3)
        with(host.world) {
            model[Transform3D].x = 7f
            model[Transform3D].rotationZ = 1.5f
        }
        host.run(2)
        return ids
    }

    private fun entity(host: GameHost, id: NetId): Entity =
        assertNotNull(host.ctx[CoreModule.NET_IDS].resolveOrNull(id), "$id is not bound")

    @Test
    fun `a world that has run RenderModule saves, and the level holds no presentation state`() {
        val rendered = host(withRender = true)
        val ids = lived(rendered)
        with(rendered.world) {
            // The precondition the defect needs: both records exist before the save.
            assertTrue(Interp3D in entity(rendered, ids.model), "no Interp3D after five ticks")
            assertTrue(Interp in entity(rendered, ids.body), "no Interp after five ticks")
        }

        val bytes = rendered.game.levels.saveNow()

        // The same world, built without the module and lived identically, has no pose records at all.
        // Byte-identical files mean nothing of either record was written.
        val plain = host(withRender = false)
        lived(plain)
        assertContentEquals(plain.game.levels.saveNow(), bytes, "the level differs from one saved with no renderer")
    }

    @Test
    fun `loading the level recreates the pose records from the loaded world on the next tick`() {
        val saved = host(withRender = true)
        val ids = lived(saved)
        val bytes = saved.game.levels.saveNow()

        val fresh = host(withRender = true)
        fresh.game.levels.loadNow(fresh.game.levels.read(bytes))
        val model = entity(fresh, ids.model)
        val body = entity(fresh, ids.body)
        with(fresh.world) {
            assertFalse(Interp3D in model, "an Interp3D came back from the file")
            assertFalse(Interp in body, "an Interp came back from the file")
        }

        fresh.run(1)

        with(fresh.world) {
            val record = assertNotNull(model.getOrNull(Interp3D), "no Interp3D after the first tick of the loaded world")
            assertEquals(7f, record.lastX, "Interp3D records the loaded Transform3D")
            assertEquals(1.5f, record.lastHeading)
            val interp = assertNotNull(body.getOrNull(Interp), "no Interp after the first tick of the loaded world")
            assertEquals(4f, interp.prevX, "Interp records the loaded PhysicsBody")
            assertEquals(-2f, interp.prevY)
        }
        // The second time through: with its records back, the loaded world saves again, and matches
        // the original ticked the same once more.
        saved.run(1)
        assertContentEquals(saved.game.levels.saveNow(), fresh.game.levels.saveNow(), "the round trip changed the world")
    }
}
