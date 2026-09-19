package dev.wildware.moba.level

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.moba.MobaGame
import dev.wildware.moba.entry.MobaEntry
import dev.wildware.moba.entry.MobaLaunchLevel
import dev.wildware.moba.lane.LaneCreep
import dev.wildware.udea.core.RngStream
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.level.LevelOutcome
import dev.wildware.udea.core.loop.barrier
import dev.wildware.udea.core.module.CoreModule
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * `moba` boots from a level file: `levels/test_level.udealevel` by default, or the one `-Plevel`
 * names (issue #192).
 */
class MobaLevelLaunchTest {

    private val projectDir: Path = Path.of(
        System.getProperty("udea.moba.projectDir") ?: error("udea.moba.projectDir is not set; the test task must pass it"),
    )

    /** The checked-in level, in `:moba:game`'s tree. */
    private val checkedIn: Path = projectDir.resolve("../game/levels/test_level.udealevel").normalize()

    /** Units on the field per team, leaving out the creeps the lane sends. */
    private fun census(host: GameHost): Map<String, Int> {
        val counts = sortedMapOf<String, Int>()
        with(host.world) {
            host.world.family { all(GameUnit) }.forEach { entity ->
                if (LaneCreep in entity) return@forEach
                val team = Team.nameOf(entity[GameUnit].team)
                counts[team] = (counts[team] ?: 0) + 1
            }
        }
        return counts
    }

    @Test
    fun `with no level named the game boots the checked-in test level`() {
        assertContentEquals(
            checkedIn.readBytes(),
            MobaLaunchLevel.bytes(named = null),
            "the bundled level is not the file in moba/game/levels",
        )
        assertContentEquals(checkedIn.readBytes(), MobaLaunchLevel.bytes(named = "  "), "a blank -Plevel")
        val host = MobaGame.host(RenderMode.Headless)
        MobaEntry.seed(host)
        assertEquals(mapOf("orc" to 5, "soldier" to 12, "undead" to 10), census(host))
    }

    @Test
    fun `a level named on the launch line is the level the game boots and restarts into`() {
        // An edited level: the test level with every skeleton taken off the field.
        val editor = MobaGame.host(RenderMode.Headless)
        MobaEntry.seed(editor)
        val netIds = editor.ctx[CoreModule.NET_IDS]
        with(editor.world) {
            val undead = ArrayList<Entity>()
            editor.world.family { all(GameUnit) }.forEach { if (it[GameUnit].team == Team.UNDEAD) undead += it }
            for (entity in undead) {
                netIds.free(netIds.netIdOf(entity))
                editor.world -= entity
            }
        }
        val save = editor.game.levels.save(editor.ctx.barrier)
        editor.ctx.barrier.drain(editor.world, editor.ctx)
        val file = Files.createTempFile("issue192-", ".udealevel")
        file.writeBytes(assertIs<LevelOutcome.Completed<ByteArray>>(save.outcome, "save: ${save.outcome}").value)

        val host = MobaGame.host(RenderMode.Headless, level = MobaLaunchLevel.bytes(named = file.toString()))
        MobaEntry.seed(host)
        assertEquals(mapOf("orc" to 5, "soldier" to 12), census(host), "the boot")

        // A restart is a swap back to the launch level, and it must be this one and not the default.
        host.ctx.scenes.requestScene(MobaLevel.SCENE_ID)
        host.run(1)
        assertEquals(mapOf("orc" to 5, "soldier" to 12), census(host), "the reload")
    }

    /**
     * A boot starts at the saved moment, not only with the saved entities: the clock and every
     * random stream come back from the file. A level saved mid-match would otherwise boot with its
     * cooldowns and respawn timers measured against a clock that restarted at zero.
     */
    @Test
    fun `a launch level brings back the clock and the random streams it was saved with`() {
        val editor = MobaGame.host(RenderMode.Headless)
        MobaEntry.seed(editor)
        editor.run(40)
        editor.ctx.rng.nextLong(RngStream.Spawn)
        val save = editor.game.levels.save(editor.ctx.barrier)
        // The tick after the save, on the game that saved it: the moment a boot must reproduce.
        editor.run(1)
        val bytes = assertIs<LevelOutcome.Completed<ByteArray>>(save.outcome, "save: ${save.outcome}").value

        val host = MobaGame.host(RenderMode.Headless, level = bytes)
        MobaEntry.seed(host)

        assertEquals(editor.tick, host.tick, "the boot tick continues from the saved tick")
        for (stream in RngStream.entries) {
            assertEquals(editor.ctx.rng.nextLong(stream), host.ctx.rng.nextLong(stream), "stream $stream")
        }
    }

    @Test
    fun `a launch level that does not exist fails and names the path`() {
        val missing = projectDir.resolve("levels/no_such_level.udealevel").toString()
        val failure = assertFailsWith<IllegalArgumentException> { MobaLaunchLevel.bytes(named = missing) }
        assertContains(failure.message.orEmpty(), missing)
    }
}
