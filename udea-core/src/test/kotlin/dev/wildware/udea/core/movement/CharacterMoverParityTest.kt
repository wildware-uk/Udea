package dev.wildware.udea.core.movement

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Phase 3 exit criterion: a scripted input sequence produces **bit-identical** state on two
 * independently constructed movers over independently built geometry.
 *
 * ## Why two of everything, and not one mover run twice
 *
 * One mover run twice proves the function is repeatable, which is nearly free and nearly
 * worthless: it holds even if the mover keeps hidden per-instance state that happens to be reset
 * between runs. Two movers over two `StaticCollision`s is the server-and-client shape - the
 * client did not receive the server's grid, it built its own from the same asset - and it fails
 * if the geometry's layout depends on anything but the segments, or if the mover carries state
 * from one entity into another.
 *
 * ## Field by field, on the raw bits
 *
 * The brief says "compared field-by-field, not by hash", and it is right for a reason worth
 * writing down: a hash tells you the run diverged and nothing about *when* or in *which field*,
 * and the answer to both is what makes a desync findable. [MoverState.sameAs] compares
 * `toRawBits`, so `0f` and `-0f` are the divergence they actually are - `==` reports them equal
 * and the two states would then serialise to different bytes.
 */
class CharacterMoverParityTest {

    @Test
    fun `600 scripted steps are bit-identical on two independently built movers`() {
        val leftGeometry = MoverScenario.geometry()
        val rightGeometry = MoverScenario.geometry()
        val left = CharacterMover()
        val right = CharacterMover()
        val leftState = MoverScenario.start()
        val rightState = MoverScenario.start()
        val leftConfig = MoverScenario.config()
        val rightConfig = MoverScenario.config()
        val leftIntent = MoveIntent()
        val rightIntent = MoveIntent()

        for (step in 0 until MoverScenario.STEPS) {
            left.move(
                leftState,
                MoverScenario.script(step, leftIntent),
                leftConfig,
                leftGeometry,
                MoverScenario.DT,
            )
            right.move(
                rightState,
                MoverScenario.script(step, rightIntent),
                rightConfig,
                rightGeometry,
                MoverScenario.DT,
            )
            assertTrue(
                leftState.sameAs(rightState),
                "diverged at step $step: server $leftState vs client $rightState",
            )
        }
    }

    @Test
    fun `the scripted run actually collides, so parity is not parity over free fall`() {
        // Without this, the test above passes for a mover that ignores geometry entirely - which
        // is exactly the failure mode a determinism test is least able to notice on its own.
        val mover = CharacterMover()
        val state = MoverScenario.start()
        val config = MoverScenario.config()
        val geometry = MoverScenario.geometry()
        val intent = MoveIntent()

        var contacts = 0L
        var groundedTicks = 0
        var highest = state.y
        var lowest = state.y
        for (step in 0 until MoverScenario.STEPS) {
            mover.move(state, MoverScenario.script(step, intent), config, geometry, MoverScenario.DT)
            contacts += mover.lastContactCount.toLong()
            if (state.grounded) groundedTicks++
            if (state.y > highest) highest = state.y
            if (state.y < lowest) lowest = state.y
        }

        assertTrue(contacts > 0, "the scripted run never touched a wall")
        assertTrue(
            groundedTicks > MoverScenario.STEPS / 4,
            "the mover was grounded on only $groundedTicks of ${MoverScenario.STEPS} ticks; " +
                "the script is not walking it along the floor",
        )
        assertTrue(highest - lowest > 0.5f, "the mover never left the floor; the jumps are inert")
        assertTrue(
            state.y > -1f,
            "the mover ended at y=${state.y}: it fell through the floor rather than colliding",
        )
    }

    @Test
    fun `two geometries built from the same segments query identically`() {
        // The half of parity that is not the mover: the broadphase. A grid whose layout depended
        // on anything but the segments would hand two processes two candidate orders, and the
        // mover would resolve the same corner two ways.
        val left = MoverScenario.geometry()
        val right = MoverScenario.geometry()
        assertEquals(left.segmentCount, right.segmentCount)

        val leftScratch = CollisionScratch(left.segmentCount)
        val rightScratch = CollisionScratch(right.segmentCount)
        var probes = 0
        var x = -22f
        while (x <= 26f) {
            var y = -2f
            while (y <= 16f) {
                val leftCount = left.query(x - 1f, y - 1f, x + 1f, y + 1f, leftScratch)
                val rightCount = right.query(x - 1f, y - 1f, x + 1f, y + 1f, rightScratch)
                assertEquals(leftCount, rightCount, "candidate count differs at ($x, $y)")
                for (index in 0 until leftCount) {
                    assertEquals(
                        leftScratch.indices[index],
                        rightScratch.indices[index],
                        "candidate $index differs at ($x, $y)",
                    )
                }
                probes++
                y += 1f
            }
            x += 1f
        }
        assertTrue(probes > 400, "the sweep probed only $probes boxes")
    }

    @Test
    fun `candidates come back in ascending segment index`() {
        // The mover resolves contacts in the order the query returns them, and a corner is two
        // walls that disagree - so the order is behaviour, not presentation.
        val geometry = MoverScenario.geometry()
        val scratch = CollisionScratch(geometry.segmentCount)
        var checked = 0
        var x = -22f
        while (x <= 26f) {
            var y = -2f
            while (y <= 16f) {
                val count = geometry.query(x - 2f, y - 2f, x + 2f, y + 2f, scratch)
                for (index in 1 until count) {
                    assertTrue(
                        scratch.indices[index - 1] < scratch.indices[index],
                        "candidates out of order at ($x, $y): " +
                            "${scratch.indices[index - 1]} then ${scratch.indices[index]}",
                    )
                }
                if (count > 1) checked++
                y += 2f
            }
            x += 2f
        }
        assertTrue(checked > 0, "no probe returned more than one candidate, so nothing was ordered")
    }
}
