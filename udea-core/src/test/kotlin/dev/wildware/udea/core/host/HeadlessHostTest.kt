package dev.wildware.udea.core.host

import com.github.quillraven.fleks.World
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.core.SceneId
import dev.wildware.udea.core.SimSystem
import dev.wildware.udea.core.loop.BarrierAction
import dev.wildware.udea.core.loop.GameLoop
import dev.wildware.udea.core.loop.SimBarrier
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.core.module.SimPhase
import dev.wildware.udea.core.module.SimRegistry
import dev.wildware.udea.core.module.UdeaGameDef
import dev.wildware.udea.core.module.UdeaModule
import dev.wildware.udea.core.physics.PhysicsBody
import dev.wildware.udea.core.scene.MarkerScene
import java.lang.reflect.Modifier
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A 200-entity scene runs ten thousand ticks in a plain JUnit JVM — no window, no GL, no
 * `Gdx.app`, no `postRunnable`.
 *
 * This is the thing `GameScreen` structurally could not do: it owned a viewport, a
 * `SpriteBatch` and a `RayHandler`, so constructing it required a GL context, so nothing it
 * owned could run on a dedicated server or in CI. The absence of GL is not asserted by hoping —
 * a GL call would throw here, and the `udeaVerifyHeadless` bytecode gate in `udea-render`
 * covers the compiled classes of this module as well.
 */
class HeadlessHostTest {

    private companion object {
        /** Enough ticks that the loop is unmistakably running before the stop is sent. */
        const val TICKS_BEFORE_STOP: Int = 100

        /**
         * How long a paused host is watched for illicit progress.
         *
         * An unpaused host of this size runs six figures of ticks in this window, so a pause
         * that does not take is not a close call.
         */
        const val PAUSE_OBSERVATION_MILLIS: Long = 100L

        /**
         * How long one tick is held open while another thread pauses across it.
         *
         * Long enough that "pause() returned early" is a certainty rather than a coin toss:
         * every scheduler in use returns from a flag write in microseconds.
         */
        const val HOLD_MILLIS: Long = 250L
    }

    /** Moves every body a little, so ten thousand ticks are ten thousand ticks of work. */
    private class DriftSystem : SimSystem() {
        private val bodies = world.family { all(PhysicsBody) }

        var updates: Long = 0L
            private set

        override fun onTick() {
            val entities = bodies.entities
            var index = 0
            while (index < entities.size) {
                val body = entities[index][PhysicsBody]
                body.x += body.linearX * ctx.clock.dt
                body.y += body.linearY * ctx.clock.dt
                index++
                updates++
            }
        }
    }

    private class DriftModule : UdeaModule {
        override fun simulation(registry: SimRegistry) {
            registry.add(SimPhase.Movement, { DriftSystem() })
        }
    }

    /** Counts a latch down once per tick, so another thread can wait for real progress. */
    private class TickLatchSystem(private val ticked: CountDownLatch) : SimSystem() {
        override fun onTick() {
            ticked.countDown()
        }
    }

    private class TickLatchModule(private val ticked: CountDownLatch) : UdeaModule {
        override fun simulation(registry: SimRegistry) {
            registry.add(SimPhase.Cleanup, { TickLatchSystem(ticked) })
        }
    }

    /**
     * Holds its first tick open for [HOLD_MILLIS], so another thread can pause mid-tick.
     *
     * Only the first: the point is to make "a tick is in flight" a fact the test controls
     * rather than a window it races for, and every later tick can run at full speed.
     */
    private class SlowTickSystem(private val entered: CountDownLatch) : SimSystem() {

        @Volatile
        var inTick: Boolean = false
            private set

        private var held = false

        override fun onTick() {
            if (held) return
            held = true
            inTick = true
            entered.countDown()
            Thread.sleep(HOLD_MILLIS)
            inTick = false
        }
    }

    private class SlowTickModule(private val entered: CountDownLatch) : UdeaModule {
        var system: SlowTickSystem? = null
            private set

        override fun simulation(registry: SimRegistry) {
            registry.add(SimPhase.Gameplay, { SlowTickSystem(entered).also { system = it } })
        }
    }

    @Test
    fun `a 200-entity scene runs 10000 ticks with no window and no GL context`() {
        val scene = MarkerScene(SceneId("arena"), seed = 31_337L, entityCount = 200, withBodies = true)
        val def = UdeaGameDef(listOf(DriftModule()))
        def.core.scenes.register(scene)
        val host = GameHost(RenderMode.Headless, def)
        host.ctx.scenes.requestScene(scene.id)

        host.run(10_000)

        assertEquals(10_000L, host.totalTicks)
        assertEquals(10_000L, host.tick.value)
        assertEquals(200, host.world.numEntities)
        assertEquals(200, host.ctx.physics.bodyCount, "the scene's bodies are all still there")
        assertEquals(
            200L * 10_000L,
            host.world.system<DriftSystem>().updates,
            "every entity was updated on every tick",
        )
        assertNull(host.presentation, "Headless has no Presentation")
    }

    @Test
    fun `stop() ends an unpaced headless run from inside a system`() {
        val scene = MarkerScene(SceneId("arena"), seed = 5L, entityCount = 3)
        val def = UdeaGameDef(emptyList())
        def.core.scenes.register(scene)
        val host = GameHost(RenderMode.Headless, def)
        host.ctx.scenes.requestScene(scene.id)

        // A barrier action stops the host at a tick boundary, which is the shape an agent's
        // `stop` tool call has.
        host.ctx[SimBarrier.KEY].submit(
            object : BarrierAction {
                override val label: String get() = "stop the host"

                override fun apply(world: World, ctx: GameContext) {
                    host.stop()
                }
            },
        )

        host.run()

        assertTrue(!host.running, "run() returned because stop() was called")
        assertEquals(1L, host.tick.value, "and it returned after the tick in flight, not mid-tick")
    }

    @Test
    fun `stop() from another thread ends the run, and running is volatile so it can`() {
        // run()'s KDoc says stop() may come from "an agent on another thread", and that claim
        // is only true if the loop re-reads the field. `while (running) step()` over a plain
        // var is a loop the JIT is entitled to hoist the read out of, and then a dedicated
        // server's stop tool call never returns and the process hangs with no error at all.
        // Asserted structurally as well as functionally: whether a *particular* JIT hoists on a
        // *particular* run is not something a test can pin, but the modifier is.
        assertTrue(
            Modifier.isVolatile(GameHost::class.java.getDeclaredField("running").modifiers),
            "GameHost.running must be @Volatile: run() reads it every tick and stop() is " +
                "documented as callable from another thread",
        )

        val ticked = CountDownLatch(TICKS_BEFORE_STOP)
        val scene = MarkerScene(SceneId("arena"), seed = 5L, entityCount = 3)
        val def = UdeaGameDef(listOf(TickLatchModule(ticked)))
        def.core.scenes.register(scene)
        val host = GameHost(RenderMode.Headless, def)
        host.ctx.scenes.requestScene(scene.id)

        // The run happens on its own thread and the stop comes from this one, which is the
        // arrangement a dedicated server has: the loop owns a thread, the tool call arrives on
        // another. The latch is counted down from inside a system, so "it is really running"
        // is established with a happens-before rather than by sleeping and hoping.
        val runner = thread(name = "host-runner") { host.run() }
        assertTrue(
            ticked.await(10L, TimeUnit.SECONDS),
            "the host never reached $TICKS_BEFORE_STOP ticks on its own thread",
        )

        host.stop()
        runner.join(TimeUnit.SECONDS.toMillis(10))

        assertTrue(!runner.isAlive, "run() never returned after a cross-thread stop()")
        assertTrue(!host.running)
        assertTrue(
            host.tick.value >= TICKS_BEFORE_STOP,
            "it stopped where the other thread asked, at ${host.tick.value}",
        )
    }

    @Test
    fun `time pause stops a free-running host, and its ticks are the loop's`() {
        // `host.time` is the agent's whole handle on time, and `run()` is the only continuous
        // headless mode the kernel ships. When run() called Simulation.step() directly the two
        // did not meet: pause() reported the game frozen while it ran on at millions of ticks,
        // totalTicks stayed at zero, and — worse — TimeControl.rewind's forced SimBarrier drain
        // relies on that pause having actually stopped the simulation before it fires.
        // Structural as well as functional, for the reason the `running` test gives: whether a
        // particular JIT hoists a particular read out of run()'s loop is not something a test
        // can pin, but the modifier that forbids it is.
        assertTrue(
            Modifier.isVolatile(GameLoop::class.java.getDeclaredField("paused").modifiers),
            "GameLoop.paused must be @Volatile: run() reads it every tick and TimeControl.pause() " +
                "is documented as an agent call from another thread",
        )

        val ticked = CountDownLatch(TICKS_BEFORE_STOP)
        val scene = MarkerScene(SceneId("arena"), seed = 5L, entityCount = 3)
        val def = UdeaGameDef(listOf(TickLatchModule(ticked)))
        def.core.scenes.register(scene)
        val host = GameHost(RenderMode.Headless, def)
        host.ctx.scenes.requestScene(scene.id)

        val runner = thread(name = "host-runner") { host.run() }
        try {
            assertTrue(
                ticked.await(10L, TimeUnit.SECONDS),
                "the host never reached $TICKS_BEFORE_STOP ticks on its own thread",
            )

            // Returns only at a tick boundary, so everything the simulation thread wrote is
            // visible here and the readings below are of a whole tick rather than a torn one.
            host.time.pause()
            val atPause = host.tick.value

            assertTrue(host.time.paused)
            assertEquals(
                atPause,
                host.totalTicks,
                "every tick run() runs must go through the loop, or totalTicks — the number the " +
                    "agent is given for how far the game has got — is fiction",
            )

            // The only way to show a stopped loop is to give it the chance to run and find that
            // it did not. A thousand ticks is microseconds of work for this scene.
            Thread.sleep(PAUSE_OBSERVATION_MILLIS)
            assertEquals(
                atPause,
                host.tick.value,
                "time.pause() must stop run(), not merely report that it did",
            )
            assertEquals(atPause, host.totalTicks)

            host.time.resume()
            assertTrue(
                awaitTickPast(host, atPause),
                "resume() must hand the loop back; it was still frozen at ${host.tick.value}",
            )
        } finally {
            host.stop()
            runner.join(TimeUnit.SECONDS.toMillis(10))
        }

        assertTrue(!runner.isAlive, "run() never returned after stop()")
    }

    /** Spins until the host's clock passes [tick], or gives up. Returns whether it moved. */
    private fun awaitTickPast(host: GameHost, tick: Long): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            if (host.tick.value > tick) return true
            Thread.onSpinWait()
        }
        return false
    }

    @Test
    fun `pause returns only once the tick in flight has finished`() {
        // The property `TimeControl.rewind` rests on. It restores a snapshot by forcing a
        // SimBarrier drain on the caller's thread, and the only thing that makes a forced drain
        // safe is that no system is running when it happens. Setting a flag does not achieve
        // that on a host whose simulation owns a thread: the tick already inside world.update
        // runs on, and the restore tears the world underneath it. SimBarrier's own re-entrancy
        // guard cannot catch it — that guard is per-object state, not a cross-thread one.
        val entered = CountDownLatch(1)
        val module = SlowTickModule(entered)
        val scene = MarkerScene(SceneId("arena"), seed = 5L, entityCount = 3)
        val def = UdeaGameDef(listOf(module))
        def.core.scenes.register(scene)
        val host = GameHost(RenderMode.Headless, def)
        host.ctx.scenes.requestScene(scene.id)

        val runner = thread(name = "host-runner") { host.run() }
        try {
            assertTrue(
                entered.await(10L, TimeUnit.SECONDS),
                "the held tick never started, so there was no tick in flight to pause against",
            )
            val system = checkNotNull(module.system)

            host.time.pause()

            assertTrue(
                !system.inTick,
                "pause() returned while a system was still inside its tick; a rewind's forced " +
                    "barrier drain would have applied a whole-world restore on top of it",
            )
        } finally {
            host.stop()
            host.time.resume()
            runner.join(TimeUnit.SECONDS.toMillis(10))
        }
    }

    @Test
    fun `two headless hosts in one JVM do not see each other`() {
        fun build(seed: Long): GameHost {
            val scene = MarkerScene(SceneId("arena"), seed = seed, entityCount = 4)
            val def = UdeaGameDef(emptyList())
            def.core.scenes.register(scene)
            return GameHost(RenderMode.Headless, def).also { it.ctx.scenes.requestScene(scene.id) }
        }

        val alpha = build(1L)
        val beta = build(2L)

        alpha.run(50)
        beta.run(7)

        assertEquals(50L, alpha.tick.value)
        assertEquals(7L, beta.tick.value, "the second host has its own clock")
        assertTrue(
            alpha.ctx[CoreModule.NET_IDS] !== beta.ctx[CoreModule.NET_IDS],
            "and its own id space; the old file-level globals made this impossible",
        )
    }
}
