package dev.wildware.moba.match

import dev.wildware.moba.level.Team

/**
 * When a match ends, who won, and how long the parts of the loop last.
 *
 * ## Why the rule is a pure function and not a method on the system
 *
 * [decide] takes three counts and an elapsed tick count and returns a team id. It touches no
 * world, no clock and no component, so the table of cases below is a *test* rather than a
 * description - `MatchRulesTest` walks the interesting ones directly, and none of them needs
 * twenty-seven units and four hundred ticks of fighting to reach. That matters because the
 * cases that get a win condition wrong are the ones a played match almost never produces: two
 * sides dying on the same tick, the clock running out on a tie, everybody dead at once.
 *
 * ## The rule
 *
 * Last faction standing, with a clock behind it:
 *
 * | living sides | elapsed | result |
 * |---|---|---|
 * | 2 or 3 | under [MATCH_LIMIT_TICKS] | [UNDECIDED] - keep fighting |
 * | exactly 1 | any | that side wins |
 * | 0 | any | [Team.NONE] - a draw; the last two killed each other |
 * | 2 or 3 | at or over [MATCH_LIMIT_TICKS] | the side with the most living units, or [Team.NONE] on a tie |
 *
 * The clock is not decoration. Two sides that cannot reach each other - a pathing failure, a
 * balance change that makes a unit outrun what chases it - would otherwise leave the match
 * running for the life of the process, which is the exact "sits permanently static" failure the
 * loop exists to end. With it, every match resolves.
 */
public object MatchRules {

    /**
     * What [decide] returns while the match is still on.
     *
     * `Int.MIN_VALUE` and not `null`, because this is called once per tick and a nullable `Int`
     * return is a box per call on a per-tick path. It is also outside every [Team] constant, so
     * a caller that forgets to check it fails a `when` rather than crediting a win to team
     * -2147483648.
     */
    public const val UNDECIDED: Int = Int.MIN_VALUE

    /**
     * How long a match may run before the clock decides it. Ninety seconds at 60Hz.
     *
     * Long enough that the level's own fight is never cut short by it - a converging battle
     * resolves in about thirty seconds - and short enough that the stand-off it exists for is
     * over quickly.
     *
     * That stand-off is real and measured rather than hypothetical, and the number is set for it.
     * `level/test_level` resolves cleanly on the spawn layout the default engine seed produces,
     * and on four other spawn seeds it does **not**: hurt units rout, `ability/passive_health_regen`
     * heals them back up, and a handful of survivors back away from each other for as long as you
     * let them. In headless that includes the player's own elite orc, which nothing steers and
     * which alone keeps its side alive. Without a clock, a match on such a layout never ends -
     * which is precisely the "sits permanently static" failure the loop exists to remove, and it
     * would have been reintroduced by the restart on the very first reseed. Fixing the stand-off
     * belongs to `UnitBattleSystem` and `UnitBrain`; guaranteeing a result belongs here.
     */
    public const val MATCH_LIMIT_TICKS: Long = 5_400L

    /**
     * How long the result stands before the next match is queued. Five seconds at 60Hz.
     *
     * The same order as `DeathSystem.CORPSE_TICKS`, and for the same reason: it is how long a
     * human needs to read what happened on the field before it is cleared away.
     */
    public const val RESULT_TICKS: Long = 300L

    /**
     * How long a dead player lies there before standing up. Three seconds at 60Hz.
     *
     * It **must** stay below `DeathSystem.CORPSE_TICKS` (300). A body is removed from the world
     * once it has lain there that long, taking its net id, its abilities and its attributes with
     * it - so a respawn timer at or past that point would find nothing to stand up, and the
     * player's controls would go dead permanently, which is the bug this whole file exists to
     * remove. `MatchRulesTest` pins the relationship rather than leaving it to a comment.
     */
    public const val RESPAWN_TICKS: Long = 180L

    /**
     * The result, given the living counts and how long the match has run.
     *
     * @param elapsedTicks ticks since `MatchState.startedTick`. Ticks, not seconds: a match must
     *   resolve identically whatever rate the host runs at.
     * @return a [Team] constant, [Team.NONE] for a draw, or [UNDECIDED] to keep fighting.
     */
    public fun decide(orcAlive: Int, soldierAlive: Int, undeadAlive: Int, elapsedTicks: Long): Int {
        require(orcAlive >= 0 && soldierAlive >= 0 && undeadAlive >= 0) {
            "living counts cannot be negative, were orc=$orcAlive soldier=$soldierAlive undead=$undeadAlive"
        }
        val standing = (if (orcAlive > 0) 1 else 0) +
            (if (soldierAlive > 0) 1 else 0) +
            (if (undeadAlive > 0) 1 else 0)
        if (standing == 0) return Team.NONE
        if (standing == 1) return soleSurvivor(orcAlive, soldierAlive, undeadAlive)
        if (elapsedTicks < MATCH_LIMIT_TICKS) return UNDECIDED
        return onCount(orcAlive, soldierAlive, undeadAlive)
    }

    /** The one side with anything left. Only called when exactly one has. */
    private fun soleSurvivor(orcAlive: Int, soldierAlive: Int, undeadAlive: Int): Int = when {
        orcAlive > 0 -> Team.ORC
        soldierAlive > 0 -> Team.SOLDIER
        else -> Team.UNDEAD
    }

    /**
     * The side with the most living units, or [Team.NONE] when the top count is shared.
     *
     * A shared top count is a draw and not a tie-break on team id: breaking it by id would make
     * "orc" the standing answer to every stalemate, which reads as a bug in the fight rather
     * than as the clock running out.
     */
    private fun onCount(orcAlive: Int, soldierAlive: Int, undeadAlive: Int): Int {
        val best = maxOf(orcAlive, maxOf(soldierAlive, undeadAlive))
        val tied = (if (orcAlive == best) 1 else 0) +
            (if (soldierAlive == best) 1 else 0) +
            (if (undeadAlive == best) 1 else 0)
        if (tied > 1) return Team.NONE
        return soleSurvivor(
            orcAlive = if (orcAlive == best) orcAlive else 0,
            soldierAlive = if (soldierAlive == best) soldierAlive else 0,
            undeadAlive = if (undeadAlive == best) undeadAlive else 0,
        )
    }
}
