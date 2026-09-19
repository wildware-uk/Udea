package dev.wildware.udea.core.level

import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.udea.core.RngStream
import dev.wildware.udea.core.SceneId
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.loop.barrier
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.core.module.UdeaGameDef
import dev.wildware.udea.core.movement.MoverState
import dev.wildware.udea.generated.CoreUdeaRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A level file used as a scene: what a match restart reloads (issue #192).
 *
 * The saved world is not a fresh one. It has run, it has freed an id so its `NetId`s have a hole,
 * and its clock and streams have moved - so a populate that copied those across, or that kept the
 * saved indices, would show here rather than pass by accident on a world that never had them.
 */
class LevelSceneTest {

    private val sceneId = SceneId("level/saved")

    /** A game that has lived a little, saved. Its x values are 10, 30 and 40: the 20 was freed. */
    private fun savedLevel(): ByteArray {
        val host = GameHost(RenderMode.Headless, UdeaGameDef(CoreUdeaRegistry, emptyList()))
        val netIds = host.ctx[CoreModule.NET_IDS]
        host.run(90)
        host.ctx.rng.nextLong(RngStream.Spawn)
        val entities = listOf(10f, 20f, 30f, 40f).map { x -> host.world.entity { it += MoverState(x = x, y = -x) } }
        val ids = entities.map(netIds::allocate)
        netIds.free(ids[1])
        host.world -= entities[1]
        val save = host.game.levels.save(host.ctx.barrier)
        host.run(1)
        return assertIs<LevelOutcome.Completed<ByteArray>>(save.outcome, "save: ${save.outcome}").value
    }

    /** A game with the level registered as a scene, that has run [ticks] and drawn once from `Combat`. */
    private fun game(bytes: ByteArray, ticks: Int = 25): GameHost {
        val definition = UdeaGameDef(CoreUdeaRegistry, emptyList())
        definition.core.scenes.register(LevelScene(sceneId, bytes))
        return GameHost(RenderMode.Headless, definition).also {
            it.run(ticks)
            it.ctx.rng.nextLong(RngStream.Combat)
        }
    }

    /** Each live `NetId` and the x of the mover it names, in `NetId` order. */
    private fun movers(host: GameHost): List<Pair<NetId, Float>> {
        val out = ArrayList<Pair<NetId, Float>>()
        host.ctx[CoreModule.NET_IDS].forEachLive { netId, entity ->
            out += netId to with(host.world) { entity[MoverState].x }
        }
        return out.sortedBy { it.first.index }
    }

    @Test
    fun `a level scene puts the saved entities into the world and leaves the clock and the streams running`() {
        val bytes = savedLevel()
        val host = game(bytes)
        // The same history with no scene loaded, to read what the streams would have said next.
        val control = game(bytes)

        host.ctx.scenes.requestScene(sceneId)
        host.run(1)
        control.run(1)

        assertEquals(sceneId, host.ctx.scenes.activeSceneId)
        assertEquals(listOf(10f, 30f, 40f), movers(host).map { it.second }, "the saved movers, in saved NetId order")
        assertEquals(3, host.world.family { all(MoverState) }.numEntities)
        with(host.world) {
            val mover = host.world.family { all(MoverState) }.entities.first { it[MoverState].x == 30f }
            assertEquals(-30f, mover[MoverState].y, "every saved field comes back, not only the one sorted on")
        }
        // Minted fresh from zero, as any scene's ids are, rather than the saved indices 0, 2 and 3.
        assertEquals(listOf(0, 1, 2), movers(host).map { it.first.index })
        assertEquals(Tick(26), host.tick, "a scene swap must not move the clock back to the tick the level was saved at")
        for (stream in RngStream.entries) {
            assertEquals(control.ctx.rng.nextLong(stream), host.ctx.rng.nextLong(stream), "stream $stream")
        }
    }

    @Test
    fun `loading the level scene again lays it out the same and the old ids read stale`() {
        val host = game(savedLevel())
        host.ctx.scenes.requestScene(sceneId)
        host.run(1)
        val first = movers(host)
        host.world.family { all(MoverState) }.forEach { with(host.world) { it[MoverState].x += 1000f } }

        host.ctx.scenes.requestScene(sceneId)
        host.run(1)
        val second = movers(host)

        assertEquals(first.map { it.second }, second.map { it.second }, "the reload is the level, not the world it replaced")
        assertEquals(first.map { it.first.index }, second.map { it.first.index })
        assertNotEquals(first.map { it.first }, second.map { it.first }, "a reload bumps generations like any scene swap")
        for ((stale, _) in first) {
            assertNull(host.ctx[CoreModule.NET_IDS].resolveOrNull(stale), "$stale still resolves after a reload")
        }
    }

    @Test
    fun `a level scene over bytes that are not a level fails its swap and leaves no scene`() {
        val host = game(byteArrayOf(1, 2, 3))
        host.ctx.scenes.requestScene(sceneId)
        host.run(1)

        assertNull(host.ctx.scenes.activeSceneId)
        assertEquals(0, host.world.numEntities)
        assertTrue(host.game.ctx.scenes.toString().contains("swaps=0"), host.game.ctx.scenes.toString())
    }
}
