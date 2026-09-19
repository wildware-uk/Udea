package dev.wildware.moba.match

import dev.wildware.moba.level.Team
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The win condition, on the cases a played match almost never produces.
 *
 * Two sides dying on the same tick, the clock running out on a level score, everybody dead at
 * once: each of those is one branch of [MatchRules.decide], each is what a wrong win condition
 * gets wrong, and none of them shows up reliably in twenty-seven units fighting for forty
 * seconds. `MatchProofTest` plays the real thing; this walks the corners.
 */
class MatchRulesTest {

    @Test
    fun `three sides alive inside the clock is undecided`() {
        assertEquals(
            MatchRules.UNDECIDED,
            MatchRules.decide(orcAlive = 5, soldierAlive = 11, undeadAlive = 10, elapsedTicks = 0L),
        )
    }

    @Test
    fun `two sides alive inside the clock is undecided`() {
        assertEquals(
            MatchRules.UNDECIDED,
            MatchRules.decide(orcAlive = 1, soldierAlive = 0, undeadAlive = 1, elapsedTicks = 900L),
        )
    }

    @Test
    fun `the last side standing wins`() {
        assertEquals(
            Team.UNDEAD,
            MatchRules.decide(orcAlive = 0, soldierAlive = 0, undeadAlive = 3, elapsedTicks = 900L),
        )
    }

    @Test
    fun `one unit left is enough to win`() {
        assertEquals(
            Team.ORC,
            MatchRules.decide(orcAlive = 1, soldierAlive = 0, undeadAlive = 0, elapsedTicks = 12L),
        )
    }

    /**
     * The case a "last side standing" rule written as `firstOrNull { alive > 0 }` gets wrong: it
     * answers `null` and the match runs for ever over an empty field.
     */
    @Test
    fun `everybody dead on the same tick is a draw and not a stalemate`() {
        assertEquals(
            Team.NONE,
            MatchRules.decide(orcAlive = 0, soldierAlive = 0, undeadAlive = 0, elapsedTicks = 900L),
        )
    }

    @Test
    fun `the clock decides a stalemate on the living count`() {
        assertEquals(
            Team.SOLDIER,
            MatchRules.decide(
                orcAlive = 2,
                soldierAlive = 7,
                undeadAlive = 1,
                elapsedTicks = MatchRules.MATCH_LIMIT_TICKS,
            ),
        )
    }

    /**
     * A shared top count is a draw, not a win for the lowest team id.
     *
     * Breaking the tie by id would make "orc" the standing answer to every stalemate, which reads
     * as a bug in the fight rather than as the clock running out.
     */
    @Test
    fun `the clock running out on a level count is a draw`() {
        assertEquals(
            Team.NONE,
            MatchRules.decide(
                orcAlive = 4,
                soldierAlive = 4,
                undeadAlive = 1,
                elapsedTicks = MatchRules.MATCH_LIMIT_TICKS + 1L,
            ),
        )
    }

    /** One tick before the limit is still a fight. The boundary, from the undecided side. */
    @Test
    fun `the clock does not decide one tick early`() {
        assertEquals(
            MatchRules.UNDECIDED,
            MatchRules.decide(
                orcAlive = 4,
                soldierAlive = 4,
                undeadAlive = 1,
                elapsedTicks = MatchRules.MATCH_LIMIT_TICKS - 1L,
            ),
        )
    }

    /**
     * The respawn timer must fire before `DeathSystem` clears the body away.
     *
     * A body is removed from the world once it has lain there `CORPSE_TICKS`, taking its net id,
     * its abilities and its attribute vector with it. A respawn scheduled at or past that point
     * would find nothing to stand up and the player's controls would be dead for the rest of the
     * match - the exact bug the respawn exists to remove. Pinned here rather than left to a
     * comment, because the two constants live in different packages and neither file's tests
     * would notice the other moving.
     */
    @Test
    fun `a respawn lands before the corpse is cleared away`() {
        assertTrue(
            MatchRules.RESPAWN_TICKS < dev.wildware.moba.ability.DeathSystem.CORPSE_TICKS,
            "RESPAWN_TICKS=${MatchRules.RESPAWN_TICKS} must be under " +
                "DeathSystem.CORPSE_TICKS=${dev.wildware.moba.ability.DeathSystem.CORPSE_TICKS}, " +
                "or the body a respawn wants to stand up has already been removed",
        )
    }

    /** A match must be allowed to run longer than one result stands for. */
    @Test
    fun `a match may run far longer than a result stands`() {
        assertTrue(
            MatchRules.MATCH_LIMIT_TICKS > MatchRules.RESULT_TICKS * 4L,
            "a match limit of ${MatchRules.MATCH_LIMIT_TICKS} ticks is not meaningfully longer " +
                "than the ${MatchRules.RESULT_TICKS}-tick result it is followed by",
        )
    }

    @Test
    fun `negative counts are refused rather than silently decided`() {
        val failure = runCatching {
            MatchRules.decide(orcAlive = -1, soldierAlive = 0, undeadAlive = 0, elapsedTicks = 0L)
        }.exceptionOrNull()
        assertTrue(
            failure is IllegalArgumentException,
            "a negative living count means a counter went wrong upstream and the match must not " +
                "quietly award a win off it; got $failure",
        )
    }
}
