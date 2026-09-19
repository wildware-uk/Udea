package dev.wildware.moba.level

import dev.wildware.moba.MobaGame
import dev.wildware.moba.entry.MobaEntry
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.level.LevelOutcome
import dev.wildware.udea.core.loop.barrier
import java.nio.file.Path
import kotlin.io.path.createParentDirectories
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

/**
 * Issue #192's one-off conversion: saves the world `level/test_level.udea.kts` builds as a level file.
 *
 * Run by `:moba:desktop:udeaConvertTestLevel`. It exists for exactly one commit - the one where the
 * script and the file sit side by side - because the next commit deletes the script, and after
 * that there is nothing left for it to convert. To regenerate `moba/game/levels/test_level.udealevel`,
 * check that commit out and run the task.
 *
 * ## Which moment is saved
 *
 * The save is queued on the barrier directly behind the scene swap, so it runs in the same drain,
 * after `TestLevelScene.populate` and before any system has ticked. That is the world the script
 * built and nothing else: no match entity, no lane, no creeps, which the systems add on the first
 * tick of every boot anyway.
 *
 * ## What else it writes
 *
 * [LevelRoster] of the booted game - the same boot every entry point does - into
 * `test_level.roster.txt`. That file is what `TestLevelRosterTest` compares a boot against once the
 * script is gone.
 */
internal object TestLevelConversion {

    @JvmStatic
    fun main(args: Array<String>) {
        val levelOut = Path.of(requireNotNull(System.getProperty(LEVEL_OUT)) { "-D$LEVEL_OUT is not set" })
        val rosterOut = Path.of(requireNotNull(System.getProperty(ROSTER_OUT)) { "-D$ROSTER_OUT is not set" })

        val host = MobaGame.host(RenderMode.Headless)
        host.ctx.scenes.requestScene(TestLevelScene.ID)
        val save = host.game.levels.save(host.ctx.barrier)
        host.run(1)
        val outcome = save.outcome
        check(outcome is LevelOutcome.Completed<ByteArray>) { "the save did not complete: $outcome" }
        val bytes = outcome.value

        levelOut.createParentDirectories()
        levelOut.writeBytes(bytes)
        println("level        $levelOut bytes=${bytes.size}")

        val booted = MobaGame.host(RenderMode.Headless)
        MobaEntry.seed(booted)
        val roster = LevelRoster.of(booted)
        rosterOut.createParentDirectories()
        rosterOut.writeText(roster.joinToString(separator = "\n", postfix = "\n"))
        println("roster       $rosterOut lines=${roster.size}")
        println("boot tick    ${booted.tick}")
        println("boot         ${roster.first()}")
    }

    private const val LEVEL_OUT = "udea.testlevel.out"
    private const val ROSTER_OUT = "udea.testlevel.roster"
}
