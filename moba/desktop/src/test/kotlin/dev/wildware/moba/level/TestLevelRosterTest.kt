package dev.wildware.moba.level

import dev.wildware.moba.MobaGame
import dev.wildware.moba.entry.MobaEntry
import dev.wildware.udea.core.host.RenderMode
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The game boots with the same units in the same places the authored script put them (issue #192).
 *
 * `test_level.roster.txt` was written by `:moba:desktop:udeaConvertTestLevel` from a boot of
 * `level/test_level.udea.kts`, in the commit that also saved that world as
 * `moba/game/levels/test_level.udealevel` - before the script was deleted. So this compares a boot
 * today against a boot of the script: the whole-world hash, and every entity's `NetId`, team, unit
 * kind, player flag, the exact bits of its position and the names of its components.
 *
 * The comparison is made at boot, after the one tick `MobaEntry.seed` runs, and not later. What a
 * match does from there is the replay fixtures' business, not this file's.
 */
class TestLevelRosterTest {

    @Test
    fun `a boot places every unit where the authored test level placed it`() {
        val expected = requireNotNull(javaClass.getResource(GOLDEN)) { "$GOLDEN is not on the test classpath" }
            .readText()
            .lines()
            .filter { it.isNotEmpty() }
        val host = MobaGame.host(RenderMode.Headless)
        MobaEntry.seed(host)
        val actual = LevelRoster.of(host)
        assertEquals(expected.joinToString("\n"), actual.joinToString("\n"))
    }

    private companion object {
        const val GOLDEN = "/levels/test_level.roster.txt"
    }
}
