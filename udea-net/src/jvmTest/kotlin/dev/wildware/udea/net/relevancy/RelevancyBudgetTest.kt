package dev.wildware.udea.net.relevancy

import dev.wildware.udea.net.transport.PeerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The fog solve's cost, at 5v5 scale, as a number a CI run can fail on.
 *
 * Spec 7 puts "fog-of-war shadowcasting at 10Hz becomes the dominant server cost" at the top of
 * the risk table and asks for the solve to be **budgeted and instrumented from the first
 * commit**. This is that gate. It is deliberately not a timing test: standards §4 forbids the
 * wall clock inside simulation code, and a millisecond threshold is a different number on a
 * laptop, a CI runner and a server, so it would either be so loose it never fires or so tight it
 * always does. Distance tests and cell visits are what the solve actually spends; they are
 * identical on every machine and they move the instant somebody reintroduces an all-pairs loop.
 */
class RelevancyBudgetTest {

    @Test
    fun `a 5v5 lane solves inside its budget`() {
        val lane = lane()
        repeat(TICKS) { lane.solve() }

        val stats = lane.fog.stats
        assertEquals(0L, stats.overBudgetSolves, "some solve exceeded ${lane.fog.settings.distanceTestBudget}")
        assertTrue(
            stats.distanceTestsPerSolve < NAIVE_TESTS_PER_SOLVE,
            "the grid must beat the all-pairs loop it replaces: ${stats.distanceTestsPerSolve} " +
                "tests a solve against a naive $NAIVE_TESTS_PER_SOLVE",
        )
    }

    @Test
    fun `vision is solved per team and not per client`() {
        val lane = lane()
        for (client in 1..CLIENTS) lane.fog.assign(PeerId.client(client), (client - 1) % TEAMS)
        repeat(TICKS) { lane.solve() }
        for (client in 1..CLIENTS) {
            for (body in lane.bodies) lane.fog.isRelevant(PeerId.client(client), body.netId)
        }

        val stats = lane.fog.stats
        assertEquals(
            (TICKS * TEAMS).toLong(),
            stats.teamSolves,
            "spec 7's 5x saving is exactly this: two passes a tick for ten clients, not ten",
        )
        assertEquals((CLIENTS * lane.bodies.size).toLong(), stats.clientQueries)
        assertTrue(
            stats.clientQueries > stats.teamSolves * 2,
            "the per-client half must be the cheap half, or the split has bought nothing",
        )
    }

    @Test
    fun `the budget counter fires when the budget is unreachable`() {
        // The mutation, checked in: the same lane under a budget of one must report every solve
        // as over budget. Without this the zero in the first test could mean "the counter is
        // never incremented" rather than "the solve is cheap".
        val lane = lane(FogSettings(distanceTestBudget = 1L))
        repeat(TICKS) { lane.solve() }

        assertEquals(TICKS.toLong(), lane.fog.stats.overBudgetSolves)
    }

    @Test
    fun `the solve allocates no grid buckets after the first tick`() {
        val lane = lane()
        lane.solve()
        val afterFirst = lane.fog.grid.size
        repeat(TICKS) { lane.solve() }

        assertEquals(afterFirst, lane.fog.grid.size, "the roster is fixed, so the bucket count must be")
        assertEquals(BODIES.toLong() * (TICKS + 1), lane.fog.stats.entitiesObserved)
    }

    /** Ten heroes with sight, and enough creeps to make an all-pairs loop hurt. */
    private fun lane(settings: FogSettings = FogSettings()): FogFixture {
        val fixture = FogFixture(settings = settings, teams = TEAMS, cellSize = 16f, cells = 64)
        for (hero in 0 until HEROES) {
            val team = hero % TEAMS
            fixture.add(x = 100f + hero * 40f, y = 200f + team * 300f, team = team, sight = HERO_SIGHT)
        }
        for (creep in 0 until BODIES - HEROES) {
            val team = creep % TEAMS
            fixture.add(
                x = 40f + (creep * 37 % 900).toFloat(),
                y = 40f + (creep * 61 % 900).toFloat(),
                team = team,
            )
        }
        return fixture
    }

    private companion object {

        /** Two sides. */
        const val TEAMS: Int = 2

        /** 5v5. */
        const val HEROES: Int = 10

        /** Ten clients on two teams: the ratio spec 7's saving is stated against. */
        const val CLIENTS: Int = 10

        /** A full lane: heroes plus several creep waves plus towers. */
        const val BODIES: Int = 250

        /** Two seconds at the 10Hz cadence spec 7 names for the solve. */
        const val TICKS: Int = 20

        const val HERO_SIGHT: Float = 120f

        /**
         * What "for every entity, test every source" would cost: `bodies x sources x teams`.
         *
         * The grid has to beat this or it is pure overhead, and the number is spelled out here
         * rather than left implicit because "we added a spatial index" is a claim that is very
         * easy to make and very easy to make wrong — see [FogOfWar]'s note on which way round the
         * index goes.
         */
        const val NAIVE_TESTS_PER_SOLVE: Long = (BODIES * HEROES).toLong()
    }
}
