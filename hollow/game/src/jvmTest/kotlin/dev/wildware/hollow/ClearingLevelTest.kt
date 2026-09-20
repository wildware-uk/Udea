package dev.wildware.hollow

import com.github.quillraven.fleks.Entity
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.core.spatial.Transform3D
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The clearing loads headless (issue #249): the bundled `levels/clearing.udealevel`, played by the
 * same `HollowGame` every launcher builds, in `RenderMode.Headless` - no context, no window.
 *
 * Asserts the shape the issue describes rather than a count of entities: one ground, a sun, a ring
 * of trees and stones around an open middle, and a `Transform3D` and a `NetId` on every entity.
 */
class ClearingLevelTest {

    private val host = HollowGame.host(RenderMode.Headless).also(HollowGame::seed)
    private val world = host.world

    private fun entities(): List<Entity> = buildList { world.forEach { add(it) } }

    private fun props(kind: PropKind): List<Entity> = with(world) {
        entities().filter { it.getOrNull(Scenery)?.prop?.kind == kind }
    }

    @Test
    fun `every entity stands somewhere and can be named`() {
        val netIds = host.ctx[CoreModule.NET_IDS]
        val all = entities()
        assertTrue(all.isNotEmpty(), "the clearing loaded no entities")
        with(world) {
            for (entity in all) {
                assertTrue(entity has Transform3D, "$entity has no Transform3D")
                assertTrue(!netIds.netIdOf(entity).isNone, "$entity has no NetId")
                assertTrue(entity has Scenery || entity has Sunlight, "$entity is neither scenery nor the sun")
            }
        }
    }

    @Test
    fun `one ground under everything, at the origin and level`() {
        val grounds = props(PropKind.Ground)
        assertEquals(1, grounds.size, "grounds")
        with(world) {
            val at = grounds.single()[Transform3D]
            assertEquals(0f, at.x)
            assertEquals(0f, at.y)
            assertEquals(0f, at.z)
            assertEquals(0f, at.rotationX)
            assertEquals(0f, at.rotationY)
        }
    }

    @Test
    fun `one sun, shining down`() {
        val suns = with(world) { entities().filter { it has Sunlight } }
        assertEquals(1, suns.size, "suns")
        with(world) {
            val sun = suns.single()[Sunlight]
            assertTrue(sun.directionZ < 0f, "the sun shines up: $sun")
            assertTrue(sun.intensity > 0f, "the sun is dark: $sun")
        }
    }

    @Test
    fun `trees ring an open middle, all the way round`() {
        val trees = props(PropKind.Tree)
        val radii = with(world) { trees.map { hypot(it[Transform3D].x, it[Transform3D].y) } }
        assertTrue(trees.size >= RING_MINIMUM, "a ring of ${trees.size} trees")
        assertTrue(radii.min() >= OPEN_MIDDLE, "a tree stands ${radii.min()} m from the middle")
        // All the way round: every eighth of the circle has a tree in it.
        val octants = with(world) {
            trees.map {
                val at = it[Transform3D]
                ((kotlin.math.atan2(at.y, at.x) + Math.PI) / (Math.PI / 4)).toInt().coerceAtMost(7)
            }.toSet()
        }
        assertEquals((0..7).toSet(), octants, "octants with a tree")
    }

    @Test
    fun `stones stand round the edge of the clearing`() {
        val stones = props(PropKind.Stone)
        assertTrue(stones.size >= STONE_MINIMUM, "${stones.size} stones")
        val nearest = with(world) { stones.minOf { hypot(it[Transform3D].x, it[Transform3D].y) } }
        assertTrue(nearest >= STONE_CLEARANCE, "a stone stands $nearest m from the middle")
    }

    @Test
    fun `a level saved mid-game resumes at the tick it was saved on`() {
        // The bundled clearing is saved at tick 0, where a fresh clock already stands; a level saved
        // later - from the editor, mid-play - is the case where seeding must restore the clock, not
        // only the entities. The game that saves it is empty, and #246's follow-up only half fixed
        // that: `Interp` and `Interp3D` are `PresentationOnly` now and a save skips them, but
        // `ScenerySystem` gives every piece of scenery a `ModelRenderer`, which is presentation
        // state that carries no such marker, so a clearing that has run a tick still cannot be
        // saved as it stands. #255 (H7) owns that decision; when it lands, this saves the running
        // clearing instead.
        val playing = HollowGame.host(RenderMode.Headless, level = ByteArray(0))
        playing.run(SAVED_AFTER_TICKS)
        val savedAt = playing.tick
        val saved = playing.game.levels.saveNow()
        playing.stop()
        val resumed = HollowGame.host(RenderMode.Headless, level = saved).also(HollowGame::seed)
        // `seed` runs one tick to apply what it queued.
        assertEquals(savedAt + 1L, resumed.tick)
        resumed.stop()
    }

    private companion object {
        /** Trees enough to read as a ring rather than a few trees. */
        const val RING_MINIMUM = 24

        /** Metres from the middle kept clear of trees: the arena. */
        const val OPEN_MIDDLE = 10f

        const val STONE_MINIMUM = 8

        /** Metres from the middle kept clear of stones. */
        const val STONE_CLEARANCE = 6f

        const val SAVED_AFTER_TICKS = 50
    }
}
