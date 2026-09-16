package dev.wildware.udea.net.relevancy

import dev.wildware.udea.net.transport.PeerId
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Phase 4 exit criterion: walking a unit along a fog boundary produces **zero** Leave/Create
 * oscillation.
 *
 * ## What "along the boundary" has to mean for this to be a real test
 *
 * A unit that walks *through* a boundary and keeps going is easy and proves nothing: it crosses
 * once. The failure spec 7 warns about is a unit that travels *tangentially* at the sight
 * radius, where the in-or-out decision is decided by the last bit of a float and flips on every
 * tick. So the walker here follows the circle of the observer's exact sight radius, carrying a
 * deterministic sub-hundredth wobble that guarantees the raw predicate `distance <= radius`
 * changes answer every single tick.
 *
 * ## The control arm is the mutation test, checked in
 *
 * [oscillationIsRealWithoutTheAntiFlickerBand] runs the same walk with both mitigations disabled
 * and asserts the flicker actually happens. Without it, [aBoundaryWalkProducesNoOscillation]
 * could be passing because the walk is trivial rather than because the design works — the exact
 * way a test that cannot fail gets shipped. The two run the same path and differ only in
 * [FogSettings], so the delta is attributable.
 */
class FogBoundaryWalkTest {

    private val blue = PeerId.client(1)

    @Test
    fun `a boundary walk produces no oscillation`() {
        val walk = walk(FogSettings())

        assertEquals(1, walk.enters, "the walker enters vision once and stays; it entered ${walk.enters} times")
        assertEquals(0, walk.leaves, "zero Leave events is the exit criterion; there were ${walk.leaves}")
        assertEquals(0L, walk.fog.stats.left, "no team saw anything leave")
        assertTrue(walk.visibleTicks > TICKS - 2, "it should be visible for essentially the whole walk")
        assertEquals(walk.observer, walk.source, "the granting source is the observer, named, for free")
    }

    @Test
    fun `oscillation is real without the anti-flicker band`() {
        val walk = walk(FogSettings(hysteresis = 0f, lingerTicks = 0))

        assertTrue(
            walk.leaves > TICKS / 4,
            "the control arm must actually flicker or the criterion test proves nothing; it left " +
                "${walk.leaves} time(s) in $TICKS ticks",
        )
        // Enters and leaves alternate, so they differ by at most one depending on which side of
        // the wobble the walk happens to end on. Asserting a fixed pair would be asserting the
        // parity of TICKS rather than the flicker.
        assertTrue(
            abs(walk.enters - walk.leaves) <= 1,
            "a flicker is an alternating sequence; ${walk.enters} enter(s) against ${walk.leaves} leave(s)",
        )
    }

    @Test
    fun `the radius band alone stops it`() {
        val walk = walk(FogSettings(hysteresis = FogSettings.DEFAULT_HYSTERESIS, lingerTicks = 0))

        assertEquals(0, walk.leaves, "hysteresis alone must hold the spatial case")
        assertEquals(1, walk.enters)
    }

    @Test
    fun `linger alone stops it`() {
        val walk = walk(FogSettings(hysteresis = 0f, lingerTicks = FogSettings.DEFAULT_LINGER_TICKS))

        assertEquals(0, walk.leaves, "linger alone must hold the temporal case")
        assertEquals(1, walk.enters)
    }

    @Test
    fun `a unit that genuinely leaves still leaves exactly once`() {
        // Hysteresis must not make fog sticky: a unit that walks out and keeps going has to be
        // hidden, once, and re-entering has to be one Create and not a second stream of them.
        val fixture = FogFixture()
        fixture.assign(blue, BLUE)
        fixture.add(x = 128f, y = 128f, team = BLUE, sight = 20f)
        val walker = fixture.add(x = 128f, y = 128f, team = RED)

        for (step in 0 until 60) {
            walker.x = 128f + step
            fixture.solve()
        }
        val seen = fixture.fog.vision[BLUE]
        assertEquals(1, seen.leaveCount(walker.netId), "it walked out once")
        assertEquals(1, seen.enterCount(walker.netId))

        for (step in 59 downTo 0) {
            walker.x = 128f + step
            fixture.solve()
        }
        assertEquals(2, seen.enterCount(walker.netId), "walking back in is one more entry, not a stream")
        assertEquals(1, seen.leaveCount(walker.netId))
        assertTrue(fixture.fog.isRelevant(blue, walker.netId))
    }

    /** What one walk produced. */
    private class Walk(
        val fog: FogOfWar,
        val enters: Int,
        val leaves: Int,
        val visibleTicks: Int,
        val observer: dev.wildware.udea.core.identity.NetId,
        val source: dev.wildware.udea.core.identity.NetId,
    )

    /**
     * Walks a red unit tangentially around a blue observer's sight radius for [TICKS] ticks.
     *
     * The wobble is deterministic and alternates sign every tick, so the naked predicate
     * `distance <= sightRadius` flips on every single tick of the walk. Nothing here is seeded
     * randomness — a flicker test that only sometimes flickers is worse than none.
     */
    private fun walk(settings: FogSettings): Walk {
        val fixture = FogFixture(settings)
        fixture.assign(blue, BLUE)
        val observer = fixture.add(x = CENTRE, y = CENTRE, team = BLUE, sight = SIGHT)
        val walker = fixture.add(x = CENTRE + SIGHT, y = CENTRE, team = RED)
        var visibleTicks = 0
        var source = dev.wildware.udea.core.identity.NetId.NONE
        for (step in 0 until TICKS) {
            val angle = step * ANGLE_STEP
            val radius = SIGHT + if (step % 2 == 0) -WOBBLE else WOBBLE
            walker.x = CENTRE + radius * cos(angle)
            walker.y = CENTRE + radius * sin(angle)
            fixture.solve()
            if (fixture.fog.isRelevant(blue, walker.netId)) {
                visibleTicks++
                if (fixture.fog.vision[BLUE].reasonFor(walker.netId) == VisionReason.Sighted) {
                    source = fixture.fog.vision[BLUE].sourceOf(walker.netId)
                }
            }
        }
        val seen = fixture.fog.vision[BLUE]
        return Walk(
            fog = fixture.fog,
            enters = seen.enterCount(walker.netId),
            leaves = seen.leaveCount(walker.netId),
            visibleTicks = visibleTicks,
            observer = observer.netId,
            source = source,
        )
    }

    private companion object {

        const val BLUE: Int = 0
        const val RED: Int = 1

        /** Four seconds at 60Hz: long enough that a per-tick flicker is unmissable. */
        const val TICKS: Int = 240

        const val CENTRE: Float = 128f
        const val SIGHT: Float = 20f

        /** Radians per tick. Most of a full circuit over the walk, so it is a walk and not a jiggle. */
        const val ANGLE_STEP: Float = 0.02f

        /**
         * A hundredth of a percent of the radius, alternating sign.
         *
         * Deliberately far smaller than the 10% hysteresis band and far larger than float error,
         * so the control arm flickers for a reason a reader can see rather than by luck.
         */
        const val WOBBLE: Float = 0.002f
    }
}
