package dev.wildware.udea.nav

import dev.wildware.udea.core.identity.NetId
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The first acceptance criterion of issue #264, as a test: **a hundred units ordered across a map
 * with obstacles arrive without overlapping, inside a tick budget.**
 *
 * The map is 32m x 32m on half-metre cells, with two buildings between the units and their goal, so
 * every unit has to route around something rather than walk in a straight line, and the way past
 * them is a gap narrower than the crowd. The order is one point for all hundred, which is the group
 * case a flow field exists for: they arrive as a packed blob around it rather than stacking on it.
 *
 * ## The two things "without overlapping" means here, measured separately
 *
 * **Settled**, once every unit has arrived: units are touching and not inside one another, to
 * within [SETTLED_TOLERANCE]. That is the criterion's plain reading, and it is what a screenshot
 * of the end state shows.
 *
 * **In transit**, on every tick of the walk: a crowd squeezing past a corner compresses, because a
 * unit's route is decided at cell resolution and a unit walking along a wall has its body over the
 * footprint (see `NavGrid`'s KDoc). That compression is bounded rather than absent, and the bound
 * is [TRANSIT_TOLERANCE] - a regression fence, not a claim that it is zero. The test prints what it
 * actually measured on both counts, so a change that makes the crowd squash harder is visible in
 * the log before it breaks the fence.
 */
class NavCrowdTest {

    /**
     * 20 seconds at 60Hz.
     *
     * The walk itself is about 26m at 3 m/s, which is 8.7 seconds; the rest is the budget for
     * getting round the buildings and for the crowd sorting itself out at the far end.
     */
    private val tickBudget = 1200

    private val unitCount = 100

    private val radius = 0.3f

    private var worstOverlap = 0f

    private var worstAt = ""

    @Test
    fun `a hundred units ordered across a map with obstacles arrive without overlapping`() {
        val scene = crowdScene()
        val units = spawnCrowd(scene)

        scene.spawnBuilding(x = 0f, y = 4f, halfWidth = 4f, halfDepth = 1f)
        scene.spawnBuilding(x = 2f, y = -5f, halfWidth = 1f, halfDepth = 5f)

        for (unit in units) scene.order(unit, x = 12f, y = 0f)

        var arrivedAt = -1
        for (tick in 1..tickBudget) {
            scene.step()
            measureOverlap(scene, units, tick)
            assertTrue(
                worstOverlap <= TRANSIT_TOLERANCE,
                "the crowd compressed by ${worstOverlap}m, past the ${TRANSIT_TOLERANCE}m fence, at $worstAt",
            )
            if (units.all { scene.agentOf(it).state == NavState.Arrived }) {
                arrivedAt = tick
                break
            }
        }

        val stragglers = units.count { scene.agentOf(it).state != NavState.Arrived }
        assertTrue(arrivedAt > 0, "$stragglers of $unitCount units had not arrived after $tickBudget ticks")
        println(
            "NavCrowdTest: $unitCount units arrived at tick $arrivedAt of $tickBudget; " +
                "worst compression in transit ${worstOverlap}m of a ${radius + radius}m contact " +
                "distance, at $worstAt",
        )

        worstOverlap = 0f
        worstAt = ""
        measureOverlap(scene, units, arrivedAt)
        println("NavCrowdTest: settled overlap ${worstOverlap}m at $worstAt")
        assertTrue(
            worstOverlap <= SETTLED_TOLERANCE,
            "units are inside one another by ${worstOverlap}m once arrived, at $worstAt",
        )

        // And they arrived *around* the goal rather than on it. A hundred discs of radius 0.3 pack
        // into a disc of about 3.6m; six is that with room for the shell that stops against it.
        for (unit in units) {
            val transform = scene.transformOf(unit)
            val distance = sqrt((transform.x - 12f) * (transform.x - 12f) + transform.y * transform.y)
            assertTrue(distance < 6f, "unit $unit stopped ${distance}m from the goal")
        }
    }

    @Test
    fun `no unit ends its walk standing inside a building`() {
        val scene = crowdScene()
        val units = spawnCrowd(scene)
        scene.spawnBuilding(x = 0f, y = 4f, halfWidth = 4f, halfDepth = 1f)
        scene.spawnBuilding(x = 2f, y = -5f, halfWidth = 1f, halfDepth = 5f)
        for (unit in units) scene.order(unit, x = 12f, y = 0f)

        repeat(tickBudget) { scene.step() }

        val grid = scene.navigation.grid
        for (unit in units) {
            val transform = scene.transformOf(unit)
            val cell = grid.cellAt(transform.x, transform.y)
            assertTrue(cell.isValid, "unit $unit walked off the grid, to (${transform.x}, ${transform.y})")
            assertTrue(
                !grid.isBlocked(cell),
                "unit $unit is standing in a building at (${transform.x}, ${transform.y})",
            )
        }
    }

    @Test
    fun `a unit ordered onto a building walks to the ground beside it instead`() {
        val scene = crowdScene()
        scene.spawnBuilding(x = 0f, y = 0f, halfWidth = 2f, halfDepth = 2f)
        val unit = scene.spawnUnit(x = -10f, y = 0f, radius = radius)

        scene.order(unit, x = 0f, y = 0f)
        repeat(600) { scene.step() }

        val agent = scene.agentOf(unit)
        assertEquals(NavState.Arrived, agent.state, "a goal on a footprint is answered by the ground beside it")
        val transform = scene.transformOf(unit)
        val grid = scene.navigation.grid
        assertTrue(!grid.isBlocked(grid.cellAt(transform.x, transform.y)))
        // Beside the building, and not necessarily on the face it came from: the goal is resolved
        // from the point that was ordered, not from where each unit happens to be standing, so that
        // a group sent to a building all converge on one spot. See `Navigation.nearestOpen`.
        val outside = maxOf(abs(transform.x), abs(transform.y))
        assertTrue(outside > 2f, "it is outside the footprint, at (${transform.x}, ${transform.y})")
        assertTrue(outside < 3f, "it stopped against the building, at (${transform.x}, ${transform.y})")
    }

    @Test
    fun `a unit with nowhere to go reports that it is unreachable`() {
        val scene = crowdScene()
        // A room with no door, and a goal inside it.
        for (offset in -4..4) {
            scene.spawnBuilding(x = offset.toFloat(), y = 4f, halfWidth = 0.5f, halfDepth = 0.5f)
            scene.spawnBuilding(x = offset.toFloat(), y = -4f, halfWidth = 0.5f, halfDepth = 0.5f)
            scene.spawnBuilding(x = 4f, y = offset.toFloat(), halfWidth = 0.5f, halfDepth = 0.5f)
            scene.spawnBuilding(x = -4f, y = offset.toFloat(), halfWidth = 0.5f, halfDepth = 0.5f)
        }
        val unit = scene.spawnUnit(x = -10f, y = -10f, radius = radius)

        scene.order(unit, x = 0f, y = 0f)
        repeat(10) { scene.step() }

        assertEquals(NavState.Unreachable, scene.agentOf(unit).state)
    }

    private fun crowdScene(): NavScene = NavScene(
        NavGridLayout(originX = -16f, originY = -16f, cellSize = 0.5f, width = 64, height = 64),
    )

    /** Ten rows of ten, on the left of the map, spaced so they start clear of each other. */
    private fun spawnCrowd(scene: NavScene): List<NetId> = buildList {
        for (row in 0 until 10) {
            for (column in 0 until 10) {
                add(
                    scene.spawnUnit(
                        x = -13f + column * 0.8f,
                        y = -4f + row * 0.8f,
                        radius = radius,
                        // 3 m/s at 60Hz.
                        speed = 0.05f,
                    ),
                )
            }
        }
    }

    /**
     * Records the worst overlap over every pair, and where it was.
     *
     * Measured on **every** tick rather than at the end alone, because a crowd that passed through
     * itself and came out the other side would satisfy an end-state check while looking like a bug
     * on screen.
     */
    private fun measureOverlap(scene: NavScene, units: List<NetId>, tick: Int) {
        for (first in units.indices) {
            val a = scene.transformOf(units[first])
            for (second in first + 1 until units.size) {
                val b = scene.transformOf(units[second])
                val dx = a.x - b.x
                val dy = a.y - b.y
                val overlap = radius + radius - sqrt(dx * dx + dy * dy)
                if (overlap > worstOverlap) {
                    worstOverlap = overlap
                    worstAt = "tick $tick, ${units[first]} at (${a.x}, ${a.y}) and " +
                        "${units[second]} at (${b.x}, ${b.y})"
                }
            }
        }
    }

    private companion object {

        /**
         * How far two units may be inside one another once they have all arrived, in metres.
         *
         * A centimetre, against a measured 3.6mm on this scenario - two units standing beside each
         * other, touching. This is the acceptance criterion's fence and it is tight on purpose: a
         * settled crowd either is or is not stacked up, and 1cm on a 600mm contact distance is not.
         */
        const val SETTLED_TOLERANCE: Float = 0.01f

        /**
         * How far two units may be inside one another *while walking*, in metres.
         *
         * 18cm, against a measured 13.8cm where the crowd funnels past a building corner. It is a
         * regression fence rather than a claim of zero: a unit's route is decided at cell
         * resolution, so a unit walking along a wall already has its body over the footprint, and
         * the crowd behind it has nowhere to be pushed to. `NavMoveSystem.PRESS_ALLOWANCE` is what
         * bounds it, and refusing all compression instead deadlocks the crowd in the gap - measured:
         * ninety-nine of the hundred were still walking after twenty seconds.
         */
        const val TRANSIT_TOLERANCE: Float = 0.18f
    }
}
