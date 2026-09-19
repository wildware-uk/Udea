package dev.wildware.udea.render.model

import com.github.quillraven.fleks.Family
import com.github.quillraven.fleks.World
import com.github.quillraven.fleks.World.Companion.family
import com.github.quillraven.fleks.configureWorld
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.core.SimSystem
import dev.wildware.udea.core.fixtures.testGameContext
import dev.wildware.udea.core.gameContext
import dev.wildware.udea.core.loop.WorldSimulation
import dev.wildware.udea.core.spatial.Transform3D
import dev.wildware.udea.render.interp.Interp3DSnapshotSystem
import dev.wildware.udea.render.interp.PoseSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Where [ModelRenderSystem] draws a model: the GL-free half of it (issue #246).
 *
 * The property that matters is that a model with a `Transform3D` is drawn *between ticks* in a
 * world that runs `RenderModule`'s 3D snapshot system - before issue #246 it was drawn where the
 * transform stood whatever the alpha, so a model moving at 60Hz on a faster display hopped.
 */
class ModelPlacerTest {

    @Test
    fun `a model in a world with the 3D snapshot system is drawn between the last two ticks`() {
        val fixture = Fixture(snapshots = true)
        fixture.sim.step()
        fixture.sim.step()

        assertEquals(listOf(3f, 1.5f, 0.75f), fixture.placedAt(alpha = 0.5f))
        assertEquals(listOf(4f, 2f, 1f), fixture.placedAt(alpha = 1f))
    }

    @Test
    fun `a model in a world without it is drawn where its transform stands`() {
        val fixture = Fixture(snapshots = false)
        fixture.sim.step()
        fixture.sim.step()

        assertEquals(listOf(4f, 2f, 1f), fixture.placedAt(alpha = 0.5f))
    }

    @Test
    fun `an entity with no transform is placed by the lift on the ground plane`() {
        val lift = PoseSource { _, _, _, into ->
            into.x = 7f
            into.y = -1f
            into.angle = 0.5f
            true
        }
        val fixture = Fixture(snapshots = true, lift = lift)
        val bare = fixture.world.entity { }

        assertEquals(true, fixture.placer.place(fixture.world, bare, 0.5f))
        val placed = fixture.placer.placed
        assertEquals(listOf(7f, -1f, 0f, 0.5f, 1f), listOf(placed.x, placed.y, placed.z, placed.rotationZ, placed.scaleX))
    }

    @Test
    fun `an entity with neither is not placed`() {
        val fixture = Fixture(snapshots = true)
        val bare = fixture.world.entity { }

        assertFalse(fixture.placer.place(fixture.world, bare, 0.5f))
    }

    /** One entity walking (2, 1, 0.5) a tick from the origin; the placer bound to its world. */
    private class Fixture(snapshots: Boolean, lift: PoseSource? = null) {
        val ctx: GameContext = testGameContext(seed = 3L)
        val world: World = configureWorld {
            injectables { gameContext(ctx) }
            systems {
                add(StepSystem())
                if (snapshots) add(Interp3DSnapshotSystem())
            }
        }
        val sim = WorldSimulation(ctx, world)
        val placer = ModelPlacer(lift).also { it.bind(world, ctx.clock) }
        val walker = world.entity { it += Transform3D() }

        fun placedAt(alpha: Float): List<Float> {
            check(placer.place(world, walker, alpha))
            return listOf(placer.placed.x, placer.placed.y, placer.placed.z)
        }
    }

    private class StepSystem : SimSystem() {
        private val transforms: Family = world.family { all(Transform3D) }

        override fun onTick() {
            transforms.forEach { entity ->
                val t = entity[Transform3D]
                t.x += 2f
                t.y += 1f
                t.z += 0.5f
            }
        }
    }
}
