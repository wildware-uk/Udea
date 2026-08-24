package dev.wildware.moba.lane

import dev.wildware.moba.level.Team

/**
 * Where the lane is, how long a wave takes, and what a creep is worth.
 *
 * ## Why the lane is Kotlin and not an authored asset
 *
 * `assets/level/test_level.udea.kts` is the authored roster and it is load-bearing - deleting an
 * entity from it removes a unit from the game with nothing recompiled. A **lane** is not a list
 * of entities: it is a polyline that two spawners walk in opposite directions, and the asset DSL
 * has no `polyline` kind. Authoring one is an `AssetScope` change (`udea-assets`), not a game's,
 * so the geometry is written here, once, as constants a test can name - and the level asset keeps
 * owning the roster it already owns.
 *
 * ## Where it is, and why it is up there
 *
 * The lane runs across the top of the field at [LANE_Y] and above, and the three-faction brawl
 * `level/test_level` places is still down at `y = 0`. The gap is not decoration and it is not
 * arbitrary: `UnitBattleSystem.AGGRO_RADIUS` is 300 world units, and every clearing in the
 * authored level sits between `y = -90` and `y = +40`. Putting the lane's lowest point at 380
 * means the closest brawl unit is more than 300 from the closest lane unit, so
 *
 *  - no soldier in the camp abandons the brawl to walk 400 units north at a creep, and
 *  - no creep abandons the lane to walk south at the brawl.
 *
 * Shrink that gap and the two halves of the game fuse into one blob, which is exactly the
 * "open field" this wave exists to stop being. The relationship is pinned by
 * `LaneGeometryTest`, not left to this paragraph.
 *
 * ## Ticks, everywhere
 *
 * Every duration here is a tick count. Nothing in this package reads a wall clock, so a wave
 * arrives on the same tick on a 30Hz server and a 144Hz client, and a `time.rewind` puts the
 * wave timer back exactly where it was.
 */
public object LaneGeometry {

    /** How many waypoints the lane has. Both teams walk the same polyline, opposite ways. */
    public const val WAYPOINTS: Int = 5

    /**
     * Waypoint x, index 0 at the soldier end.
     *
     * Two flat arrays rather than an array of points, because the march reads them once per
     * creep per tick and an array of objects would be a pointer chase per read.
     */
    public val PATH_X: FloatArray = floatArrayOf(-300f, -150f, 0f, 150f, 300f)

    /** Waypoint y. The bend is what makes this read as a lane rather than as a ruler line. */
    public val PATH_Y: FloatArray = floatArrayOf(380f, 470f, 420f, 470f, 380f)

    /** The lowest point on the path. The number the separation from the brawl is measured at. */
    public const val LANE_Y: Float = 380f

    /** Waypoint index a [Team.SOLDIER] creep starts at and marches away from. */
    public const val SOLDIER_END: Int = 0

    /** Waypoint index a [Team.UNDEAD] creep starts at. */
    public const val UNDEAD_END: Int = WAYPOINTS - 1

    /** How close a creep gets to a waypoint before it aims at the next one. */
    public const val WAYPOINT_RADIUS: Float = 26f

    /** World units per tick a marching creep walks. Its `UnitKind.moveSpeed` is for chasing. */
    public const val MARCH_SPEED: Float = 0.7f

    /** Half-width of the box a wave is scattered over, so three creeps are not one sprite. */
    public const val SPAWN_SCATTER: Float = 26f

    /** The [Team.SOLDIER] tower's x. Inside its own half, in range of the meeting point. */
    public const val SOLDIER_TOWER_X: Float = -140f

    /** @see SOLDIER_TOWER_X */
    public const val UNDEAD_TOWER_X: Float = 140f

    /** Both towers' y. Just off the path, so a creep walks past rather than into one. */
    public const val TOWER_Y: Float = 440f

    /**
     * How far a tower shoots, in world units.
     *
     * Larger than the distance from either tower to the middle waypoint (141.4), so the wave
     * fight at mid happens under fire from both towers rather than out of reach of either -
     * which is the difference between a tower being the thing you push toward and a tower being
     * scenery at the edge of the frame. `LaneGeometryTest` pins the inequality.
     */
    public const val TOWER_RANGE: Float = 150f

    /** Health a tower removes per shot. */
    public const val TOWER_DAMAGE: Float = 22f

    /** Ticks between a tower's shots. One second at 60Hz. */
    public const val TOWER_COOLDOWN_TICKS: Int = 60

    /**
     * How long a tower remembers a champion hitting one of its allies, in ticks.
     *
     * Three seconds. The rule in issue #130 - creeps first, unless a champion attacks an ally in
     * range - needs a window, because damage is an instant and aggro is a state. Too short and a
     * champion trades one hit and is never punished; too long and one stray arrow keeps a tower
     * pointed at a champion for the rest of the wave.
     */
    public const val TOWER_AGGRO_MEMORY_TICKS: Long = 180L

    /** Ticks from the lane opening to the first wave. Three seconds at 60Hz. */
    public const val FIRST_WAVE_TICK: Long = 180L

    /** Ticks between waves. Ten seconds at 60Hz. */
    public const val WAVE_INTERVAL_TICKS: Long = 600L

    /** Creeps per team per wave. Three a side, six on the field per wave. */
    public const val CREEPS_PER_WAVE: Int = 3

    /**
     * Gold for the killing blow on a creep, and for nothing else.
     *
     * The whole of the laning phase is this number being reachable one way and unreachable every
     * other way: chip a creep to one health, let a tower finish it, and you get nothing. So
     * `LaneEconomyTest` asserts the non-last-hit case is exactly zero rather than merely smaller.
     */
    public const val CREEP_GOLD: Int = 40

    /** Experience for a creep death, to every enemy champion within [XP_RADIUS]. */
    public const val CREEP_XP: Int = 30

    /**
     * How close a champion stands to share a creep's experience.
     *
     * Bigger than a melee reach, because experience is for being in the lane and gold is for
     * landing the blow - two different rewards for two different things is the whole reason a
     * laning phase has any texture at all.
     */
    public const val XP_RADIUS: Float = 220f

    /** Experience the first level-up costs. See [xpForLevel] for the curve it seeds. */
    public const val XP_PER_LEVEL: Int = 120

    /** The cap. A champion stops levelling here. */
    public const val MAX_LEVEL: Int = 18

    /** Maximum health added per level. */
    public const val HEALTH_PER_LEVEL: Float = 40f

    /** `strength` added per level, which is what `MeleeAttackExec` reads for damage. */
    public const val STRENGTH_PER_LEVEL: Float = 2f

    /** Extra ticks on a champion's respawn per level past the first. */
    public const val RESPAWN_TICKS_PER_LEVEL: Long = 6L

    /**
     * The most ticks a champion may lie dead.
     *
     * A hard ceiling, and not a taste call: `DeathSystem.CORPSE_TICKS` is 300, and a body that
     * has lain there that long is removed - net id, attributes, abilities and all. A respawn
     * scheduled at or past it would find nothing to stand up and the human's controls would go
     * dead for the rest of the match. `LaneRulesTest` pins the inequality against
     * `DeathSystem.CORPSE_TICKS` itself rather than against a copy of the number.
     */
    public const val RESPAWN_CAP_TICKS: Long = 290L

    /** Total experience needed to reach [level]. Level 1 is zero. */
    public fun xpForLevel(level: Int): Int {
        require(level >= 1) { "levels start at 1, asked for $level" }
        // 1 -> 0, 2 -> 120, 3 -> 360, 4 -> 720: a triangular curve, so each level costs more
        // than the last without a table to keep in step with `MAX_LEVEL`.
        val steps = level - 1
        return XP_PER_LEVEL * steps * (steps + 1) / 2
    }

    /** How long a champion at [level] lies dead, in ticks, capped at [RESPAWN_CAP_TICKS]. */
    public fun respawnTicks(level: Int, baseTicks: Long): Long {
        val scaled = baseTicks + RESPAWN_TICKS_PER_LEVEL * (level - 1).coerceAtLeast(0)
        return if (scaled > RESPAWN_CAP_TICKS) RESPAWN_CAP_TICKS else scaled
    }

    /** Which waypoint a creep on [team] starts at. */
    public fun startWaypoint(team: Int): Int = if (team == Team.SOLDIER) SOLDIER_END else UNDEAD_END

    /** Which way along the path a creep on [team] walks: `+1` or `-1`. */
    public fun heading(team: Int): Int = if (team == Team.SOLDIER) 1 else -1

    /** Where a tower on [team] stands. */
    public fun towerX(team: Int): Float =
        if (team == Team.SOLDIER) SOLDIER_TOWER_X else UNDEAD_TOWER_X

    /** The two sides that field creeps and towers. The orcs are the third party in this game. */
    public val TEAMS: IntArray = intArrayOf(Team.SOLDIER, Team.UNDEAD)
}
