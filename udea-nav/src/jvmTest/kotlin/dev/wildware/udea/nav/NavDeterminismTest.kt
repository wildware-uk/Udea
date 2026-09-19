package dev.wildware.udea.nav

import dev.wildware.udea.core.identity.NetId
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The second acceptance criterion of issue #264: **the same orders give the same paths on server,
 * client and replay.**
 *
 * Three legs, which is what those three words mean to a simulation that has no network in the test:
 *
 * - **server and client** are two worlds built and stepped independently from the same orders. A
 *   client re-runs the same systems over the same replicated components, so if two identical worlds
 *   in one process disagree, no two machines ever will.
 * - **replay** is a third world fast-forwarded through the same ticks, which is exactly what
 *   `udea-replay` does with a `.udearep`.
 * - and a fourth leg the criterion does not ask for but prediction needs: a world **rewound** to a
 *   snapshot halfway and re-simulated. It is the one that would catch a route remembered outside
 *   the components, because a restore puts the components back and nothing else.
 *
 * What is compared is `WorldHasher.hash` of a full capture on every tick - every field of every
 * nav component and of `Transform3D`, folded - and, for the walk itself, the actual positions of a
 * named unit tick by tick.
 */
class NavDeterminismTest {

    @Test
    fun `two worlds given the same orders walk the same route tick for tick`() {
        val server = crowd()
        val client = crowd()

        val serverHashes = server.run(TICKS)
        val clientHashes = client.run(TICKS)

        assertEquals(-1, firstDifference(serverHashes, clientHashes), "first tick whose world hash differs")
    }

    @Test
    fun `a replayed world reaches the same positions as the one it replays`() {
        val original = crowd()
        val replay = crowd()

        val walked = original.walk(TICKS)
        val replayed = replay.walk(TICKS)

        for (tick in walked.indices) {
            assertEquals(
                walked[tick].toRawBits(),
                replayed[tick].toRawBits(),
                "the replayed unit is somewhere else at tick $tick",
            )
        }
    }

    @Test
    fun `a world rewound to a snapshot re-simulates the ticks it had already run`() {
        val scene = crowd()
        repeat(CUT) { scene.step() }
        val captured = scene.snapshots.capture()
        val first = scene.run(TICKS - CUT)

        scene.snapshots.applyNow(captured)
        val second = scene.run(TICKS - CUT)

        assertEquals(-1, firstDifference(first, second), "first re-simulated tick whose world hash differs")
    }

    @Test
    fun `a rewind puts an order back the way it was`() {
        val scene = crowd()
        val unit = scene.spawnUnit(x = -10f, y = -10f)
        scene.order(unit, x = 10f, y = 10f)
        repeat(CUT) { scene.step() }
        val captured = scene.snapshots.capture()
        val goalAtCut = scene.agentOf(unit).goalX

        // The future gives it a different order, and then the future is undone.
        scene.order(unit, x = -12f, y = -12f)
        repeat(60) { scene.step() }
        scene.snapshots.applyNow(captured)

        assertEquals(goalAtCut, scene.agentOf(unit).goalX, "the order the snapshot held is the order it has")
        assertEquals(NavState.Moving, scene.agentOf(unit).state)
    }

    @Test
    fun `the route a unit is given does not depend on how many units asked before it`() {
        // The same question, asked of a fresh service and of one that has answered a thousand
        // others: a cache that let an earlier answer change a later one would show up here, and
        // nothing else in this file would catch it.
        val scene = crowd()
        val grid = scene.navigation.grid
        val cold = NavPath()
        assertEquals(
            NavRouteOutcome.Found,
            scene.navigation.path(-13f, -4f, 12f, 0f, radius = 0.3f, out = cold),
        )
        val coldRoute = (0 until cold.size).map { cold.cellAt(it) }

        repeat(1000) { step ->
            val warm = NavPath()
            scene.navigation.path(
                grid.originX + (step % 60) * 0.5f + 0.25f,
                grid.originY + (step % 50) * 0.5f + 0.25f,
                12f,
                0f,
                radius = 0.3f,
                out = warm,
            )
        }

        val warm = NavPath()
        assertEquals(
            NavRouteOutcome.Found,
            scene.navigation.path(-13f, -4f, 12f, 0f, radius = 0.3f, out = warm),
        )
        assertEquals(coldRoute.size, warm.size, "the warm service answered a route of a different length")
        for (step in coldRoute.indices) {
            assertEquals(coldRoute[step], warm.cellAt(step), "the warm service routed through a different cell")
        }
    }

    @Test
    fun `a unit walks a route as long as the one nav path reports, and ends where it ends`() {
        // What the tool promises, and what it does not. It reports **an** optimal route from the
        // point asked about: the same A*, through the same `Navigation`, and `NavPathfinderTest`
        // pins that a unit's next hop is that route's second cell. What it does not promise is the
        // exact cells, because a unit re-asks from every cell it enters and open ground holds many
        // routes of identical cost - so the walk is as long as the reported one and ends where it
        // ends, rather than being it step for step.
        val scene = emptyMap()
        val unit = scene.spawnUnit(x = -13f, y = -8f)
        val reported = NavPath()
        assertEquals(
            NavRouteOutcome.Found,
            scene.navigation.path(-13f, -8f, 12f, -8f, radius = 0.3f, out = reported),
        )

        scene.order(unit, x = 12f, y = -8f)
        var stepped = 0
        while (scene.agentOf(unit).state == NavState.Moving && stepped < TICKS) {
            scene.step()
            stepped++
        }

        assertEquals(NavState.Arrived, scene.agentOf(unit).state)
        val grid = scene.navigation.grid
        val transform = scene.transformOf(unit)
        // At the end of the reported route, or in the cell before it: a unit stops as soon as the
        // goal is inside its own radius, which is a third of a cell short of standing on it.
        val stopped = grid.cellAt(transform.x, transform.y)
        val stepsAway = maxOf(
            abs(grid.cellX(stopped) - grid.cellX(reported.destination)),
            abs(grid.cellY(stopped) - grid.cellY(reported.destination)),
        )
        assertTrue(
            stepsAway <= 1,
            "the unit stopped in $stopped, $stepsAway cells from where the reported route ends " +
                "(${reported.destination})",
        )
        // The reported cost is in the integer units of `NavSteps`: ten to a straight cell step.
        val metres = reported.cost.toFloat() / NavSteps.STRAIGHT * grid.cellSize
        val expectedTicks = metres / 0.05f
        assertTrue(
            stepped <= expectedTicks * 1.2f,
            "the walk took $stepped ticks, where the reported ${reported.cost / 10} cells of route " +
                "is about ${expectedTicks.toInt()}",
        )
    }

    /**
     * The scenario every leg runs: a crowd, two buildings and one order, on one seed.
     *
     * Deliberately the crowd rather than a single unit. A flow field, a crowd tally and a hundred
     * units resolving overlaps in `NetId` order are all things that could differ between two
     * machines if any of them read something the components do not hold.
     */
    /** The same map, with the buildings but nobody on it. */
    private fun emptyMap(): NavScene {
        val scene = NavScene(
            NavGridLayout(originX = -16f, originY = -16f, cellSize = 0.5f, width = 64, height = 64),
        )
        scene.spawnBuilding(x = 0f, y = 4f, halfWidth = 4f, halfDepth = 1f)
        scene.spawnBuilding(x = 2f, y = -5f, halfWidth = 1f, halfDepth = 5f)
        return scene
    }

    private fun crowd(): NavScene {
        val scene = NavScene(
            NavGridLayout(originX = -16f, originY = -16f, cellSize = 0.5f, width = 64, height = 64),
        )
        scene.spawnBuilding(x = 0f, y = 4f, halfWidth = 4f, halfDepth = 1f)
        scene.spawnBuilding(x = 2f, y = -5f, halfWidth = 1f, halfDepth = 5f)
        for (row in 0 until 5) {
            for (column in 0 until 8) {
                val unit = scene.spawnUnit(x = -13f + column * 0.8f, y = -4f + row * 0.8f)
                scene.order(unit, x = 12f, y = 0f)
            }
        }
        return scene
    }

    /** The x and y of the lowest-`NetId` unit after each of [ticks] ticks, interleaved. */
    private fun NavScene.walk(ticks: Int): FloatArray {
        val unit = NetId.of(index = 2, generation = 0)
        val trace = FloatArray(ticks * 2)
        repeat(ticks) { tick ->
            step()
            val transform = transformOf(unit)
            trace[tick * 2] = transform.x
            trace[tick * 2 + 1] = transform.y
        }
        return trace
    }

    /** The first index at which the two streams differ, or `-1`. */
    private fun firstDifference(first: LongArray, second: LongArray): Int {
        assertEquals(first.size, second.size)
        for (index in first.indices) if (first[index] != second[index]) return index
        return -1
    }

    private companion object {
        const val TICKS: Int = 600

        /** Where the rewind leg captures and comes back to. */
        const val CUT: Int = 240
    }
}
