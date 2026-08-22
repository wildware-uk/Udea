package dev.wildware.udea.core

import dev.wildware.udea.core.fixtures.RecordingCueSink
import dev.wildware.udea.core.fixtures.RecordingPhysicsWorld
import dev.wildware.udea.core.fixtures.testGameContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * Two simulations in one JVM, which the old engine could not do.
 *
 * `common/UdeaGameManager.kt:82-83` held `lateinit var gameScreen` and `lateinit var
 * gameManager` at file level, assigned from a constructor. A second world overwrote the
 * first one's globals, so every reader — including simulation code such as
 * `Networkable.kt:34` and `CharacterControllerSystem.kt:16` — silently switched to the
 * newer world. Two things need this fixed: the MCP surface (a headless world beside a
 * rendered one) and networking (a server and a client in one process).
 */
class TwoWorldsTest {

    @Test
    fun `two contexts with different seeds simulate independently`() {
        val alpha = testGameContext(seed = 1L, role = NetRole.Server)
        val beta = testGameContext(seed = 2L, role = NetRole.Client)

        val alphaWorld = worldFor(alpha)
        val betaWorld = worldFor(beta)

        repeat(10) {
            alpha.clock.advance()
            alphaWorld.update(alpha.clock.dt)
        }
        repeat(3) {
            beta.clock.advance()
            betaWorld.update(beta.clock.dt)
        }

        // Clocks
        assertEquals(Tick(10), alpha.tick)
        assertEquals(Tick(3), beta.tick)
        assertNotSame(alpha.clock, beta.clock)

        // Systems
        assertEquals(10, alphaWorld.system<TickCountingSystem>().runCount)
        assertEquals(3, betaWorld.system<TickCountingSystem>().runCount)
        assertEquals(Tick(10), alphaWorld.system<TickCountingSystem>().lastSeenTick)
        assertEquals(Tick(3), betaWorld.system<TickCountingSystem>().lastSeenTick)

        // Roles
        assertTrue(alpha.role.isAuthoritative)
        assertTrue(!beta.role.isAuthoritative)

        // Services
        assertNotSame(alpha.physics, beta.physics)
        assertEquals(0, (alpha.physics as RecordingPhysicsWorld).stepCount)
        assertEquals(0, (beta.physics as RecordingPhysicsWorld).stepCount)
    }

    @Test
    fun `entities created in one world are invisible to the other`() {
        val alpha = testGameContext(seed = 1L)
        val beta = testGameContext(seed = 2L)
        val alphaWorld = worldFor(alpha)
        val betaWorld = worldFor(beta)

        repeat(5) { alphaWorld.entity { } }

        assertEquals(5, alphaWorld.numEntities)
        assertEquals(0, betaWorld.numEntities)
    }

    @Test
    fun `different seeds produce different random streams`() {
        val alpha = testGameContext(seed = 1L)
        val beta = testGameContext(seed = 2L)

        val alphaRolls = List(32) { alpha.rng.nextInt(RngStream.Combat, 1_000_000) }
        val betaRolls = List(32) { beta.rng.nextInt(RngStream.Combat, 1_000_000) }

        assertNotEquals(alphaRolls, betaRolls)
    }

    @Test
    fun `the same seed replays the same random stream`() {
        // The control for the test above: divergence must come from the seed, not from noise.
        val first = testGameContext(seed = 99L)
        val second = testGameContext(seed = 99L)

        assertEquals(
            List(32) { first.rng.nextInt(RngStream.Combat, 1_000_000) },
            List(32) { second.rng.nextInt(RngStream.Combat, 1_000_000) },
        )
    }

    @Test
    fun `cues emitted by one simulation do not reach the other`() {
        val alpha = testGameContext(seed = 1L)
        val beta = testGameContext(seed = 2L)

        alpha.cues.emit(Cue(id = 7, tick = alpha.tick))

        assertEquals(1, (alpha.cues as RecordingCueSink).cues.size)
        assertEquals(0, (beta.cues as RecordingCueSink).cues.size)
    }

    @Test
    fun `two contexts can be configured differently in the same JVM`() {
        val fast = testGameContext(config = EngineConfig(tickRate = 128, seed = 1L))
        val standard = testGameContext(config = EngineConfig(tickRate = 60, seed = 1L))

        assertEquals(128, fast.config.tickRate)
        assertEquals(60, standard.config.tickRate)
        assertEquals(1f / 128f, fast.clock.dt)
        assertEquals(1f / 60f, standard.clock.dt)

        repeat(60) {
            fast.clock.advance()
            standard.clock.advance()
        }
        assertEquals(60.0 * (1f / 128f).toDouble(), fast.clock.time)
        assertEquals(60.0 * (1f / 60f).toDouble(), standard.clock.time)
    }
}
