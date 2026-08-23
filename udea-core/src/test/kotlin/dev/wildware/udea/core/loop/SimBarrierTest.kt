package dev.wildware.udea.core.loop

import com.github.quillraven.fleks.World
import com.github.quillraven.fleks.configureWorld
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.core.Log
import dev.wildware.udea.core.SimSystem
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.fixtures.RecordingCueSink
import dev.wildware.udea.core.fixtures.QueueingSceneManager
import dev.wildware.udea.core.fixtures.RecordingPhysicsWorld
import dev.wildware.udea.core.fixtures.testGameContext
import dev.wildware.udea.core.gameContext
import dev.wildware.udea.core.rng.DefaultRngService
import dev.wildware.udea.core.rng.SimRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicIntegerArray
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Creates one entity when the barrier drains it, and remembers which tick that was. */
private class SpawnAction(override val label: String) : BarrierAction {
    var appliedAtTick: Tick? = null
        private set

    override fun apply(world: World, ctx: GameContext) {
        world.entity { }
        appliedAtTick = ctx.clock.tick
    }
}

/** Records how many entities existed on each tick it ran, and can submit from inside one. */
private class ProbeSystem : SimSystem() {

    private val observed = ArrayList<Int>()

    /** Entity count as seen from inside `onTick`, one entry per tick, in tick order. */
    val entityCountPerTick: List<Int> get() = observed

    /** Runs at the start of every `onTick`, given the entity count at that moment. */
    var duringTick: ((Int) -> Unit)? = null

    override fun onTick() {
        duringTick?.invoke(world.numEntities)
        observed += world.numEntities
    }
}

/** Collects what was logged, so a test can assert on a diagnostic rather than eyeball it. */
private class CapturingLog : Log {
    val errors: MutableList<Pair<String, Throwable?>> = ArrayList()

    override fun debug(message: String): Unit = Unit

    override fun info(message: String): Unit = Unit

    override fun warn(message: String): Unit = Unit

    override fun error(message: String, cause: Throwable?) {
        errors += message to cause
    }
}

/**
 * The one guarantee `SimBarrier` sells: **no system ever observes a torn world.**
 *
 * Asserted directly, from inside a running system, rather than by checking the queue's
 * mechanics — a queue that drains in the right order at the wrong moment would pass a
 * mechanical test and still let a system iterate a family halfway through a scene swap.
 */
class SimBarrierTest {

    private class Fixture(
        val ctx: GameContext,
        val world: World,
        val probe: ProbeSystem,
        val sim: WorldSimulation,
    ) {
        val barrier: SimBarrier get() = sim.barrier
    }

    private fun fixture(log: Log = Log.NoOp): Fixture {
        val ctx = gameContext {
            this.log = log
            rng = DefaultRngService(0L)
            physics = RecordingPhysicsWorld()
            scenes = QueueingSceneManager()
            cues = RecordingCueSink()
            simBarrier()
        }
        // Constructed inside `systems { }`: SimSystem resolves its context from the world
        // being configured, so it cannot be built outside a world configuration at all.
        var probe: ProbeSystem? = null
        val world = configureWorld {
            injectables { gameContext(ctx) }
            systems { probe = ProbeSystem().also { add(it) } }
        }
        return Fixture(ctx, world, checkNotNull(probe), WorldSimulation(ctx, world))
    }

    @Test
    fun `a mutation submitted mid-tick is invisible for the rest of that tick`() {
        val f = fixture()
        val action = SpawnAction("spawn-one")

        f.probe.duringTick = { entitiesBeforeSubmit ->
            if (f.ctx.clock.tick == Tick.ZERO) {
                f.barrier.submit(action)
                // The assertion this whole class exists for: a system that went on to iterate
                // a family here sees exactly the world it saw before its own submission.
                assertEquals(
                    entitiesBeforeSubmit,
                    f.world.numEntities,
                    "a submitted mutation must not land inside the tick that submitted it",
                )
            }
        }

        f.sim.step() // tick 0: the probe submits and sees nothing
        f.sim.step() // tick 1: the drain applies it before the probe runs

        assertEquals(listOf(0, 1), f.probe.entityCountPerTick)
        assertEquals(Tick(1), action.appliedAtTick, "it lands at the top of the next tick")
        assertEquals(0, f.barrier.pendingCount())
        assertEquals(1L, f.barrier.totalDrained)
    }

    @Test
    fun `the barrier drains before any system runs`() {
        val f = fixture()
        val action = SpawnAction("pre-existing")
        f.barrier.submit(action)

        f.sim.step()

        assertEquals(
            listOf(1),
            f.probe.entityCountPerTick,
            "the drain is at the top of step(), so phase zero already sees the new entity",
        )
        assertEquals(Tick(0), action.appliedAtTick)
    }

    @Test
    fun `an action that submits another action is not processed by the same drain`() {
        val f = fixture()
        val second = SpawnAction("second")
        val first = object : BarrierAction {
            override val label: String = "first"
            override fun apply(world: World, ctx: GameContext) {
                world.entity { }
                f.barrier.submit(second)
            }
        }

        f.barrier.submit(first)
        f.sim.step()

        assertEquals(1, f.barrier.drainedThisTick, "the batch is detached before the first action")
        assertEquals(1, f.barrier.pendingCount(), "so the follow-up waits for the next tick")
        assertEquals(listOf(1), f.probe.entityCountPerTick)
        assertNull(second.appliedAtTick)

        f.sim.step()

        assertEquals(1, f.barrier.drainedThisTick)
        assertEquals(listOf(1, 2), f.probe.entityCountPerTick)
        assertEquals(Tick(1), second.appliedAtTick)
    }

    @Test
    fun `a nested drain is refused instead of silently destroying the queue`() {
        // The shape spec 5 prescribes for an agent mutation is a BarrierAction, and
        // SnapshotTimeTravel.restoreNearestAtOrBefore calls barrier.drain() directly and by
        // design. Put those two together and drain re-enters. What that used to do: the inner
        // call swaps `batch` and `inbox`, so the list the outer call is walking becomes the
        // live inbox, and the outer `finally { running.clear() }` empties it — every action
        // submitted since the drain started is gone, pendingCount() says 0 and failedActions
        // says 0. It also applies `queued` inside the very drain the KDoc promises it cannot
        // land in.
        val log = CapturingLog()
        val f = fixture(log)
        val queued = SpawnAction("submitted-before-the-nested-drain")
        val lost = SpawnAction("submitted-after-the-nested-drain")
        var reachedTheLine = false
        val reentrant = object : BarrierAction {
            override val label: String = "re-enters-the-drain"
            override fun apply(world: World, ctx: GameContext) {
                f.barrier.submit(queued)
                f.barrier.drain(world, ctx)
                reachedTheLine = true
                f.barrier.submit(lost)
            }
        }

        f.barrier.submit(reentrant)
        f.sim.step()

        assertEquals(1L, f.barrier.failedActions, "re-entering is a failure, not a nested drain")
        assertTrue(!reachedTheLine, "the action aborted at the re-entry rather than carrying on")
        assertNull(queued.appliedAtTick, "nothing lands inside the drain that is already running")
        assertEquals(1, f.barrier.pendingCount(), "and nothing queued was destroyed")

        val (message, cause) = log.errors.single()
        assertTrue("re-enters-the-drain" in message, "the label must name the culprit: $message")
        assertTrue(
            "already running" in assertNotNull(cause).message.orEmpty(),
            "the cause must say what went wrong: ${cause.message}",
        )

        f.sim.step()

        assertEquals(Tick(1), queued.appliedAtTick, "the queued action lands on the next tick")
        assertNull(lost.appliedAtTick, "and the line after the failed drain never ran")
        assertEquals(0, f.barrier.pendingCount())
    }

    @Test
    fun `a throwing action is logged with its label and does not stop the drain or the tick`() {
        val log = CapturingLog()
        val f = fixture(log)
        val exploding = object : BarrierAction {
            override val label: String = "explode-on-purpose"
            override fun apply(world: World, ctx: GameContext) {
                throw IllegalStateException("boom")
            }
        }
        val after = SpawnAction("after-the-explosion")

        f.barrier.submit(exploding)
        f.barrier.submit(after)
        f.sim.step()

        assertEquals(1L, f.barrier.failedActions)
        assertEquals(2, f.barrier.drainedThisTick)
        assertEquals(Tick(0), after.appliedAtTick, "the following action still applied")
        assertEquals(listOf(1), f.probe.entityCountPerTick, "and the tick still completed")
        assertEquals(Tick(1), f.ctx.clock.tick, "and the clock still advanced")

        val (message, cause) = log.errors.single()
        assertTrue("explode-on-purpose" in message, "the label must name the culprit: $message")
        assertTrue("t0" in message, "the tick must be in the message: $message")
        assertEquals("boom", assertNotNull(cause).message)
    }

    @Test
    fun `an Error escapes the drain instead of being absorbed`() {
        // The catch is there so one bad tool call cannot strand the rest of the batch. An
        // Error is the opposite case: after an OutOfMemoryError, StackOverflowError or
        // LinkageError inside an action the world is in exactly the undefined state this
        // class exists to prevent, and continuing to tick over it is worse than stopping.
        // AssertionError matters for a second reason — swallowing it would make an assertion
        // written inside a BarrierAction pass silently.
        val log = CapturingLog()
        val f = fixture(log)
        val after = SpawnAction("must-not-run")

        f.barrier.submit(object : BarrierAction {
            override val label: String = "assertion-inside-an-action"
            override fun apply(world: World, ctx: GameContext) {
                throw AssertionError("an assertion inside a barrier action")
            }
        })
        f.barrier.submit(after)

        val escaped = assertFailsWith<AssertionError> { f.sim.step() }
        assertEquals("an assertion inside a barrier action", escaped.message)
        assertEquals(0L, f.barrier.failedActions, "an Error is not a contained failure")
        assertEquals(emptyList(), log.errors.map { it.first }, "and it is not logged-and-continued")
        assertNull(after.appliedAtTick, "the drain stopped rather than carrying on")
    }

    @Test
    fun `an Error aborting a drain does not re-apply the batch on the next tick`() {
        // The companion to the test above. The Error is deliberately not caught, so the drain
        // unwinds partway through the batch. If the batch buffer is not cleared on the way
        // out, the next drain's swap moves it back into the inbox and every action in it runs
        // a second time — including the ones that already succeeded before the Error. That is
        // the double-applied, torn state the class exists to prevent, and it is reachable by
        // any embedder or harness that catches the Error and keeps ticking, which is exactly
        // what this test does.
        val f = fixture()
        val before = SpawnAction("applied-before-the-error")

        f.barrier.submit(before)
        f.barrier.submit(object : BarrierAction {
            override val label: String = "aborts-the-drain"
            override fun apply(world: World, ctx: GameContext) {
                throw AssertionError("abort")
            }
        })

        assertFailsWith<AssertionError> { f.sim.step() }
        assertEquals(1, f.world.numEntities, "the first action applied once before the abort")
        assertEquals(
            0,
            f.barrier.pendingCount(),
            "the aborted batch must not be left where the next swap can queue it again",
        )

        // Recover and keep ticking, the way a harness or a supervising embedder would. Two
        // ticks, not one: an uncleared batch takes one swap to travel back into `inbox` and a
        // second to be drained out of it, so a single step would miss the re-application.
        f.sim.step()
        f.sim.step()

        assertEquals(
            1,
            f.world.numEntities,
            "the aborted batch must not be drained again: the action that already applied " +
                "would spawn a second entity",
        )
        assertEquals(0, f.barrier.pendingCount(), "and nothing may be left queued")
    }

    @Test
    fun `submissions from other threads while the loop steps are applied exactly once`() {
        val f = fixture()
        val total = 10_000
        val submitters = 4
        val applied = AtomicIntegerArray(total)
        val start = CountDownLatch(1)

        val threads = (0 until submitters).map { slot ->
            thread(name = "sim-barrier-submitter-$slot") {
                val random = SimRandom(seed = slot.toLong())
                start.await()
                var id = slot
                while (id < total) {
                    val actionId = id
                    f.barrier.submit(object : BarrierAction {
                        override val label: String = "counted"
                        override fun apply(world: World, ctx: GameContext) {
                            applied.incrementAndGet(actionId)
                        }
                    })
                    // Randomised pacing so the drains and the submits genuinely interleave
                    // instead of lining up on one thread's schedule.
                    if (random.nextInt(64) == 0) Thread.yield()
                    id += submitters
                }
            }
        }

        start.countDown()
        while (threads.any { it.isAlive } || f.barrier.pendingCount() > 0) {
            f.sim.step()
        }
        threads.forEach { it.join(TimeUnit.SECONDS.toMillis(10)) }

        val wrong = (0 until total).filter { applied.get(it) != 1 }
        assertTrue(
            wrong.isEmpty(),
            "${wrong.size} of $total actions were lost or duplicated, first bad id ${wrong.firstOrNull()}",
        )
        assertEquals(total.toLong(), f.barrier.totalDrained)
        assertEquals(0, f.barrier.pendingCount())
        assertEquals(0L, f.barrier.failedActions)
    }

    @Test
    fun `the barrier the simulation drains is the one exposed on the context`() {
        val f = fixture()

        assertSame(f.barrier, f.ctx.barrier)
        assertSame(f.barrier, f.ctx.barrierOrNull())
    }

    @Test
    fun `a context with no barrier gives its simulation a private one`() {
        val ctx = testGameContext()
        val world = configureWorld { injectables { gameContext(ctx) } }
        val sim = WorldSimulation(ctx, world)
        val action = SpawnAction("private")

        assertNull(ctx.barrierOrNull())
        sim.barrier.submit(action)
        sim.step()

        assertEquals(Tick(0), action.appliedAtTick)
    }

    @Test
    fun `pendingCount reports what has not landed yet`() {
        val f = fixture()

        assertEquals(0, f.barrier.pendingCount())
        f.barrier.submit(SpawnAction("a"))
        f.barrier.submit(SpawnAction("b"))
        assertEquals(2, f.barrier.pendingCount())

        f.sim.step()

        assertEquals(0, f.barrier.pendingCount())
        assertEquals(2, f.barrier.drainedThisTick)
    }

    @Test
    fun `actions apply in submission order`() {
        val f = fixture()
        val order = ArrayList<String>()

        listOf("first", "second", "third").forEach { name ->
            f.barrier.submit(object : BarrierAction {
                override val label: String = name
                override fun apply(world: World, ctx: GameContext) {
                    order += name
                }
            })
        }
        f.sim.step()

        assertEquals(listOf("first", "second", "third"), order)
    }

    @Test
    fun `the simulation advances the clock exactly once per step`() {
        val f = fixture()

        repeat(10) { f.sim.step() }

        assertEquals(Tick(10), f.ctx.clock.tick)
        assertEquals(10L, f.sim.stepCount)
        assertEquals(10, f.probe.entityCountPerTick.size)
    }
}
