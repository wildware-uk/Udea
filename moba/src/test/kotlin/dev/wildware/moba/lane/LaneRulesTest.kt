package dev.wildware.moba.lane

import dev.wildware.moba.ability.DeathSystem
import dev.wildware.moba.level.Team
import dev.wildware.moba.level.UnitBattleSystem
import dev.wildware.moba.match.MatchRules
import dev.wildware.udea.core.SimClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The lane's rules, as arithmetic, with no world and no ticking.
 *
 * ## Why these are here and not inside the proof
 *
 * Every assertion below is a relationship between two numbers that a balance pass can break
 * without breaking anything visible - a lane moved 100 units south, a respawn cap raised past
 * the corpse timer, a tower range shortened until it no longer reaches the meeting point. Each
 * of those turns the game into a different game silently: the creeps fuse with the brawl, the
 * champion's controls go dead for the rest of the match, the towers become scenery.
 *
 * Pinning them against the *other side's own constant* rather than against a copied literal is
 * the whole point. `LaneGeometry.RESPAWN_CAP_TICKS < DeathSystem.CORPSE_TICKS` stays true when
 * somebody edits `CORPSE_TICKS`, because this reads `CORPSE_TICKS`.
 */
class LaneRulesTest {

    /**
     * The lane is further from the brawl than anything in the brawl can see.
     *
     * `UnitBattleSystem.AGGRO_RADIUS` is how far a unit looks for an enemy. The authored level's
     * northernmost cluster centre is the priest at `y = 0`, scattered by up to
     * `TestLevelScene.SCATTER`. If the closest point of the lane were inside that radius, the
     * soldier line would abandon the camp and walk four hundred units north at a creep - which is
     * exactly the open-field blob this wave exists to stop being.
     */
    @Test
    fun `the lane is outside the brawl's aggro radius`() {
        val northernmostBrawlUnit = 0f + dev.wildware.moba.level.TestLevelScene.SCATTER
        val closestLanePoint = LaneGeometry.LANE_Y
        val gap = closestLanePoint - northernmostBrawlUnit
        assertTrue(
            gap > UnitBattleSystem.AGGRO_RADIUS,
            "the lane's closest point is $gap from the brawl's furthest-north unit, and a unit " +
                "looks ${UnitBattleSystem.AGGRO_RADIUS} for an enemy; the two halves of the " +
                "game would fuse into one blob",
        )
    }

    /**
     * Both towers reach the point the waves meet at.
     *
     * A tower that cannot reach mid is scenery: the wave fight happens out of its range, nothing
     * it does decides anything, and "the structure you push toward" is a sentence about a prop.
     */
    @Test
    fun `a tower reaches the middle of the lane`() {
        val midX = LaneGeometry.PATH_X[LaneGeometry.WAYPOINTS / 2]
        val midY = LaneGeometry.PATH_Y[LaneGeometry.WAYPOINTS / 2]
        for (team in LaneGeometry.TEAMS) {
            val dx = midX - LaneGeometry.towerX(team)
            val dy = midY - LaneGeometry.TOWER_Y
            val distance = kotlin.math.sqrt(dx * dx + dy * dy)
            assertTrue(
                distance < LaneGeometry.TOWER_RANGE,
                "the ${Team.nameOf(team)} tower is $distance from mid and shoots " +
                    "${LaneGeometry.TOWER_RANGE}",
            )
        }
    }

    /**
     * A champion's respawn always lands before its body is swept away.
     *
     * `DeathSystem` removes a body that has lain there for `CORPSE_TICKS` - net id, attributes
     * and abilities with it. A respawn scheduled at or past that point finds nothing to stand up,
     * and the human's controls go dead for the rest of the match.
     */
    @Test
    fun `the respawn cap stays under the corpse timer`() {
        assertTrue(
            LaneGeometry.RESPAWN_CAP_TICKS < DeathSystem.CORPSE_TICKS,
            "a champion capped at ${LaneGeometry.RESPAWN_CAP_TICKS} ticks would be swept away " +
                "at ${DeathSystem.CORPSE_TICKS} before it could stand up",
        )
        val atCap = LaneGeometry.respawnTicks(
            LaneGeometry.MAX_LEVEL,
            MatchRules.RESPAWN_TICKS,
        )
        assertTrue(
            atCap < DeathSystem.CORPSE_TICKS,
            "a level ${LaneGeometry.MAX_LEVEL} champion waits $atCap ticks and is swept at " +
                "${DeathSystem.CORPSE_TICKS}",
        )
    }

    /** A respawn gets longer with level, and starts at the flat one `RespawnSystem` writes. */
    @Test
    fun `a respawn scales with level`() {
        val base = MatchRules.RESPAWN_TICKS
        assertEquals(base, LaneGeometry.respawnTicks(1, base), "level one is the flat timer")
        assertTrue(
            LaneGeometry.respawnTicks(5, base) > LaneGeometry.respawnTicks(2, base),
            "a farmed champion must pay more for dying than an unfarmed one",
        )
    }

    /** The experience curve rises, starts at zero, and level two costs what the constant says. */
    @Test
    fun `the experience curve rises from zero`() {
        assertEquals(0, LaneGeometry.xpForLevel(1), "level one is free")
        assertEquals(LaneGeometry.XP_PER_LEVEL, LaneGeometry.xpForLevel(2))
        var level = 2
        while (level <= LaneGeometry.MAX_LEVEL) {
            val step = LaneGeometry.xpForLevel(level) - LaneGeometry.xpForLevel(level - 1)
            val previous = LaneGeometry.xpForLevel(level - 1) -
                LaneGeometry.xpForLevel((level - 2).coerceAtLeast(1))
            assertTrue(
                step >= previous,
                "level $level costs $step and level ${level - 1} cost $previous; the curve must " +
                    "not get cheaper",
            )
            level++
        }
    }

    /**
     * The wave clock, in seconds, against the tick rate the game runs at.
     *
     * The constants are ticks - everything in this package is - and a tick count agrees with
     * nothing on its own. This is the one place the *durations* the design is written in are
     * pinned: three seconds to the first wave, ten seconds between waves, one second between a
     * tower's shots. `LaneProofTest` then proves the simulation honours the constants, and the
     * two together are the claim "a wave every ten seconds" without a literal 600 in a system.
     *
     * It is deliberately arithmetic against [SimClock.DEFAULT_TICK_RATE] rather than against a
     * second copy of 600: a game that changed its tick rate would have to revisit these numbers,
     * and this is what would tell it so.
     */
    @Test
    fun `the wave clock is the durations the design is written in`() {
        val rate = SimClock.DEFAULT_TICK_RATE
        assertEquals(3L * rate, LaneGeometry.FIRST_WAVE_TICK, "three seconds to the first wave")
        assertEquals(10L * rate, LaneGeometry.WAVE_INTERVAL_TICKS, "ten seconds between waves")
        assertEquals(rate, LaneGeometry.TOWER_COOLDOWN_TICKS, "one second between tower shots")
        assertEquals(3L * rate, LaneGeometry.TOWER_AGGRO_MEMORY_TICKS, "three seconds of tower memory")
    }

    /** Both ends of the lane are real waypoints, and the two teams walk it in opposite ways. */
    @Test
    fun `the two teams walk the same polyline in opposite directions`() {
        assertEquals(LaneGeometry.WAYPOINTS, LaneGeometry.PATH_X.size)
        assertEquals(LaneGeometry.WAYPOINTS, LaneGeometry.PATH_Y.size)
        assertEquals(1, LaneGeometry.heading(Team.SOLDIER))
        assertEquals(-1, LaneGeometry.heading(Team.UNDEAD))
        assertEquals(0, LaneGeometry.startWaypoint(Team.SOLDIER))
        assertEquals(LaneGeometry.WAYPOINTS - 1, LaneGeometry.startWaypoint(Team.UNDEAD))
        assertTrue(
            LaneGeometry.towerX(Team.SOLDIER) < LaneGeometry.towerX(Team.UNDEAD),
            "each tower must stand in its own half",
        )
    }
}
