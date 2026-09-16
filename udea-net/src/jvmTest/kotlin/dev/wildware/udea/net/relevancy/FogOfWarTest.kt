package dev.wildware.udea.net.relevancy

import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.net.transport.PeerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

import kotlin.test.assertTrue

/**
 * What one team may know, and why.
 *
 * Every test here asserts a property the design *claims* rather than a shape it happens to have:
 * that an unsighted enemy is refused, that an ally needs nobody to look at it, that the granting
 * source is named and is the nearest one, that a client with no team is fail-closed, and — the
 * one spec 7 says burns days when it is wrong — that leaving vision and ceasing to exist produce
 * different events.
 */
class FogOfWarTest {

    private val blue = PeerId.client(1)
    private val red = PeerId.client(2)

    @Test
    fun `an enemy no source covers is refused`() {
        val fixture = FogFixture()
        fixture.assign(blue, BLUE)
        fixture.add(x = 10f, y = 10f, team = BLUE, sight = 5f)
        val enemy = fixture.add(x = 100f, y = 100f, team = RED)
        fixture.solve()

        assertFalse(fixture.fog.isRelevant(blue, enemy.netId), "an unsighted enemy must not be relevant")
        assertEquals(VisionReason.Hidden, fixture.fog.vision[BLUE].reasonFor(enemy.netId))
        assertEquals(NetId.NONE, fixture.fog.vision[BLUE].sourceOf(enemy.netId))
    }

    @Test
    fun `an ally is relevant with nothing looking at it`() {
        val fixture = FogFixture()
        fixture.assign(blue, BLUE)
        // Sight zero on both: nothing on this team can see anything, and they still see each other.
        val ally = fixture.add(x = 200f, y = 200f, team = BLUE, sight = 0f)
        fixture.add(x = 10f, y = 10f, team = BLUE, sight = 0f)
        fixture.solve()

        assertTrue(fixture.fog.isRelevant(blue, ally.netId))
        assertEquals(VisionReason.OwnTeam, fixture.fog.vision[BLUE].reasonFor(ally.netId))
        assertEquals(ally.netId, fixture.fog.vision[BLUE].sourceOf(ally.netId), "an ally grants itself")
    }

    @Test
    fun `the nearest source is the one reported as granting`() {
        val fixture = FogFixture()
        fixture.assign(blue, BLUE)
        val far = fixture.add(x = 0f, y = 0f, team = BLUE, sight = 60f)
        val near = fixture.add(x = 40f, y = 0f, team = BLUE, sight = 60f)
        val enemy = fixture.add(x = 50f, y = 0f, team = RED)
        fixture.solve()

        val seen = fixture.fog.vision[BLUE]
        assertTrue(seen.canSee(enemy.netId))
        assertEquals(near.netId, seen.sourceOf(enemy.netId), "the nearer of two covering sources wins")
        assertEquals(10f, seen.distanceOf(enemy.netId))
        assertFalse(far.netId == seen.sourceOf(enemy.netId))
    }

    @Test
    fun `the two teams get different answers from one solve`() {
        val fixture = FogFixture()
        fixture.assign(blue, BLUE)
        fixture.assign(red, RED)
        val scout = fixture.add(x = 100f, y = 100f, team = BLUE, sight = 20f)
        val hidden = fixture.add(x = 10f, y = 10f, team = RED)
        fixture.add(x = 105f, y = 100f, team = RED)
        fixture.solve()

        assertFalse(fixture.fog.isRelevant(blue, hidden.netId), "blue cannot see a distant red")
        assertTrue(fixture.fog.isRelevant(red, hidden.netId), "red always sees its own")
        assertFalse(fixture.fog.isRelevant(red, scout.netId), "red has no vision at all here")
        assertEquals(2L, fixture.fog.stats.teamSolves, "one solve, two teams")
    }

    @Test
    fun `a client with no team is shown nothing and the counter says why`() {
        val fixture = FogFixture()
        val ally = fixture.add(x = 10f, y = 10f, team = BLUE, sight = 50f)
        fixture.solve()

        assertFalse(fixture.fog.isRelevant(blue, ally.netId), "an unassigned client fails closed")
        assertEquals(1L, fixture.fog.stats.unassignedQueries)
        assertFalse(RelevancyReport.of(fixture.fog, blue).assigned)
    }

    @Test
    fun `an entity that walks out of vision leaves, and is not destroyed`() {
        val fixture = FogFixture(FogSettings(hysteresis = 0f, lingerTicks = 0))
        fixture.assign(blue, BLUE)
        fixture.add(x = 0f, y = 0f, team = BLUE, sight = 20f)
        val enemy = fixture.add(x = 10f, y = 0f, team = RED)
        fixture.solve()
        assertTrue(fixture.fog.isRelevant(blue, enemy.netId))

        enemy.x = 100f
        fixture.solve()

        assertFalse(fixture.fog.isRelevant(blue, enemy.netId))
        assertEquals(1, fixture.fog.leftCount(blue), "walking into fog is a Leave")
        assertEquals(enemy.netId, fixture.fog.leftAt(blue, 0))
        assertEquals(1, fixture.fog.vision[BLUE].leaveCount(enemy.netId))
    }

    @Test
    fun `an entity that ceases to exist produces no leave at all`() {
        // The distinction spec 7 says burns days: a client that treats leaving relevancy as death
        // plays a death animation for a unit that walked into a bush. So the two must not share a
        // path, and an entity the world no longer offers must NOT land on the leave list - the
        // packer's own Destroy walk owns it.
        val fixture = FogFixture(FogSettings(hysteresis = 0f, lingerTicks = 0))
        fixture.assign(blue, BLUE)
        val scout = fixture.add(x = 0f, y = 0f, team = BLUE, sight = 20f)
        val enemy = fixture.add(x = 10f, y = 0f, team = RED)
        fixture.solve()
        assertTrue(fixture.fog.isRelevant(blue, enemy.netId))

        fixture.solveWith(listOf(scout))

        assertFalse(fixture.fog.isRelevant(blue, enemy.netId), "it is gone, so it is not relevant")
        assertEquals(0, fixture.fog.leftCount(blue), "a destroyed entity must not also be a Leave")
        assertEquals(0, fixture.fog.vision[BLUE].leaveCount(enemy.netId))
    }

    @Test
    fun `a recycled index does not inherit the previous occupant's visibility`() {
        val fixture = FogFixture(FogSettings(hysteresis = 0f, lingerTicks = 0))
        fixture.assign(blue, BLUE)
        val scout = fixture.add(x = 0f, y = 0f, team = BLUE, sight = 20f)
        val enemy = fixture.add(x = 5f, y = 0f, team = RED)
        fixture.solve()
        assertTrue(fixture.fog.isRelevant(blue, enemy.netId))

        // The same index, one generation on, standing far away: a different entity entirely.
        val reused = Body(NetId.of(enemy.netId.index, enemy.netId.generation + 1), 200f, 200f, RED)
        fixture.solveWith(listOf(scout, reused))

        assertFalse(fixture.fog.isRelevant(blue, reused.netId), "the new occupant is unsighted")
        assertFalse(fixture.fog.isRelevant(blue, enemy.netId), "the stale id resolves to nothing")
        assertEquals(0, fixture.fog.vision[BLUE].enterCount(reused.netId), "counters reset with the slot")
    }

    @Test
    fun `weight falls off with distance and never reaches zero`() {
        val fixture = FogFixture()
        fixture.assign(blue, BLUE)
        fixture.add(x = 0f, y = 0f, team = BLUE, sight = 200f)
        val near = fixture.add(x = 1f, y = 0f, team = RED)
        val far = fixture.add(x = 190f, y = 0f, team = RED)
        fixture.solve()

        val nearWeight = fixture.fog.weightOf(blue, near.netId)
        val farWeight = fixture.fog.weightOf(blue, far.netId)
        assertTrue(nearWeight > farWeight, "a near enemy must outrank a far one, was $nearWeight vs $farWeight")
        assertTrue(farWeight >= fixture.fog.settings.minWeight, "a far enemy is deprioritised, never starved")
        assertTrue(nearWeight <= 1f)
    }

    @Test
    fun `an entity offered on a team the session does not have is refused by name`() {
        val fixture = FogFixture(teams = 2)
        fixture.fog.beginSolve(dev.wildware.udea.core.Tick(1))
        val failure = assertFailsWith<IllegalArgumentException> {
            fixture.fog.observe(NetId.of(0, 0), 0f, 0f, team = 7, sightRadius = 1f)
        }
        assertTrue("team 7" in failure.message.orEmpty(), "the message must name the offending value")
    }

    @Test
    fun `the report names the granting source for every visible entity`() {
        val fixture = FogFixture()
        fixture.assign(blue, BLUE)
        val scout = fixture.add(x = 0f, y = 0f, team = BLUE, sight = 30f)
        val enemy = fixture.add(x = 20f, y = 0f, team = RED)
        fixture.add(x = 300f, y = 300f, team = RED)
        fixture.solve()

        val report = RelevancyReport.of(fixture.fog, blue)
        assertTrue(report.assigned)
        assertEquals(2, report.entries.size, "the scout and the sighted enemy, and nothing else")
        val entry = report.entries.single { it.netId == enemy.netId }
        assertEquals(scout.netId, entry.source)
        assertEquals(VisionReason.Sighted, entry.reason)
        assertEquals(20f, entry.distance)
        assertEquals(fixture.lastTick, entry.since, "it became visible on the tick just solved")
        assertEquals(fixture.lastTick, report.tick)
    }

    private companion object {
        const val BLUE: Int = 0
        const val RED: Int = 1
    }
}
