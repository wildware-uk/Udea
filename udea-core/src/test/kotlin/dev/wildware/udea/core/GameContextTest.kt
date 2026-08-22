package dev.wildware.udea.core

import com.github.quillraven.fleks.World
import com.github.quillraven.fleks.configureWorld
import dev.wildware.udea.core.fixtures.DeterministicRngService
import dev.wildware.udea.core.fixtures.QueueingSceneManager
import dev.wildware.udea.core.fixtures.RecordingCueSink
import dev.wildware.udea.core.fixtures.RecordingPhysicsWorld
import dev.wildware.udea.core.fixtures.SimpleEventBus
import dev.wildware.udea.core.fixtures.testGameContext
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Records that it ran and which tick it saw, so a test can prove it read the injected context. */
internal class TickCountingSystem : SimSystem() {
    var runCount: Int = 0
        private set

    var lastSeenTick: Tick = Tick.ZERO
        private set

    override fun onTick() {
        runCount++
        lastSeenTick = tick
    }
}

/** Builds a world around [ctx] with a single [TickCountingSystem]. */
internal fun worldFor(ctx: GameContext): World = configureWorld {
    injectables { gameContext(ctx) }
    systems { add(TickCountingSystem()) }
}

class GameContextTest {

    @Test
    fun `a context and a Fleks world are built with no LibGDX application and no window`() {
        // Nothing here touches Gdx, a GL context or a window. That is the point of
        // udea-core: the kernel is headless by construction, not by configuration.
        val ctx = testGameContext(seed = 42L)
        val world = worldFor(ctx)

        val entity = world.entity { }
        world.update(ctx.clock.dt)

        assertEquals(1, world.numEntities)
        assertTrue(world.contains(entity))
        assertEquals(1, world.system<TickCountingSystem>().runCount)
    }

    @Test
    fun `GameContext is the only registered Fleks injectable`() {
        val ctx = testGameContext()
        val world = worldFor(ctx)

        // Fleks keeps its injectable map internal, so this reads it the only way a test can.
        val injectables = registeredInjectables(world)

        assertEquals(
            setOf(GameContext.INJECT_NAME),
            injectables.keys,
            "GameContext is the sole injectable; a second one means a system has a hidden dependency",
        )
        assertSame(ctx, injectables.getValue(GameContext.INJECT_NAME))
        assertEquals(
            emptyMap(),
            world.unusedInjectables(),
            "the context must actually be consumed, or the injection is decorative",
        )
    }

    /** `World.injectables` is internal to Fleks; read it reflectively, name to injected object. */
    private fun registeredInjectables(world: World): Map<String, Any?> {
        val raw = World::class.java.getMethod("getInjectables").invoke(world) as Map<*, *>
        val injObj = Class.forName("com.github.quillraven.fleks.Injectable").getMethod("getInjObj")
        return raw.entries.associate { (key, value) -> key as String to injObj.invoke(value) }
    }

    @Test
    fun `a system sees the context it was configured with`() {
        val ctx = testGameContext()
        val world = worldFor(ctx)

        assertSame(ctx, world.system<TickCountingSystem>().ctx)
    }

    @Test
    fun `a system reads the tick from the context, not from a global`() {
        val ctx = testGameContext()
        val world = worldFor(ctx)

        ctx.clock.moveTo(Tick(77))
        world.update(ctx.clock.dt)

        assertEquals(Tick(77), world.system<TickCountingSystem>().lastSeenTick)
    }

    @Test
    fun `the context is constructor-injected and has no static mutable state`() {
        val ctx = testGameContext(seed = 7L, role = NetRole.Server)

        // Every collaborator arrives through the constructor: there is nothing to reach for.
        assertEquals(NetRole.Server, ctx.role)
        assertEquals(7L, ctx.config.seed)
        assertEquals(7L, ctx.rng.seed)
        assertEquals(Tick.ZERO, ctx.tick)

        val mutableStatics = GameContext::class.java.declaredFields
            .filterNot { it.isSynthetic }
            .filter { Modifier.isStatic(it.modifiers) && !Modifier.isFinal(it.modifiers) }
        assertEquals(
            emptyList(),
            mutableStatics.map { it.name },
            "GameContext replaced two file-level globals; it must not become one",
        )
    }

    @Test
    fun `a missing service names every absent service at construction`() {
        val failure = assertFailsWith<MissingServiceException> {
            gameContext {
                rng = DeterministicRngService(0L)
                physics = RecordingPhysicsWorld()
            }
        }

        val message = failure.message.orEmpty()
        assertTrue("scenes" in message, message)
        assertTrue("events" in message, message)
        assertTrue("cues" in message, message)
        assertFalse("rng" in message, message)
        assertFalse("physics" in message, message)
    }

    @Test
    fun `log defaults to discarding output`() {
        val ctx = testGameContext()
        assertSame(Log.NoOp, ctx.log)
        ctx.log.error("nothing happens", IllegalStateException("and nothing throws"))
    }

    @Test
    fun `a clock disagreeing with the configured tick rate is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            gameContext {
                config = EngineConfig(tickRate = 60)
                clock = SimClock(30)
                rng = DeterministicRngService(0L)
                physics = RecordingPhysicsWorld()
                scenes = QueueingSceneManager()
                events = SimpleEventBus()
                cues = RecordingCueSink()
            }
        }
    }

    @Test
    fun `a module contributes a service without editing GameContext`() {
        val ctx = testGameContext {
            service(PATHFINDING, StubPathfinder("grid"))
        }

        assertEquals("grid", ctx[PATHFINDING].name)
        assertEquals("grid", ctx.getOrNull(PATHFINDING)?.name)
        assertTrue(PATHFINDING in ctx)
        assertEquals(setOf(PATHFINDING), ctx.serviceKeys)
    }

    @Test
    fun `an unregistered service key fails loudly`() {
        val ctx = testGameContext()

        assertNull(ctx.getOrNull(PATHFINDING))
        assertFalse(PATHFINDING in ctx)
        val failure = assertFailsWith<MissingServiceException> { ctx[PATHFINDING] }
        assertTrue("pathfinding" in failure.message.orEmpty(), failure.message.orEmpty())
    }

    @Test
    fun `registering a key twice is rejected rather than silently overwriting`() {
        assertFailsWith<IllegalArgumentException> {
            testGameContext {
                service(PATHFINDING, StubPathfinder("first"))
                service(PATHFINDING, StubPathfinder("second"))
            }
        }
    }

    @Test
    fun `two keys with the same name are different keys`() {
        val other = serviceKey<StubPathfinder>("pathfinding")
        val ctx = testGameContext { service(PATHFINDING, StubPathfinder("grid")) }

        assertTrue(PATHFINDING in ctx)
        assertFalse(other in ctx, "keys are identity-compared, so the declaring module owns them")
    }

    internal class StubPathfinder(val name: String)

    private companion object {
        val PATHFINDING: ServiceKey<StubPathfinder> = serviceKey("pathfinding")
    }
}
