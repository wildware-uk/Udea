package dev.wildware.udea.core.level

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.udea.core.RngStream
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.loop.BarrierAction
import dev.wildware.udea.core.loop.barrier
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.core.module.UdeaGameDef
import dev.wildware.udea.core.movement.MoverState
import dev.wildware.udea.core.physics.BodyHandle
import dev.wildware.udea.core.physics.BodyPose
import dev.wildware.udea.core.physics.Box
import dev.wildware.udea.core.physics.PhysicsBody
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * A level file carries a whole world and puts it back into another game exactly (issue #191).
 *
 * The fixture is deliberately **not** empty and not freshly allocated: ids have been freed so the
 * `NetId` allocator has a free queue, one entity has no `NetId` at all, components hold `Entity`
 * and `NetId` references to other entities, the clock has moved and the streams have been drawn
 * from. Each of those is a state an empty world satisfies by accident.
 */
class LevelServiceTest {

    private fun host(): GameHost = GameHost(RenderMode.Headless, UdeaGameDef(emptyList()))

    private fun levels(host: GameHost, hooks: LevelHooks = LevelHooks(), vararg extra: LevelComponent<*>): LevelService =
        LevelService(host.world, host.ctx, host.ctx[CoreModule.NET_IDS], hooks) {
            LevelComponentModule.discover() + TestComponents(extra.toList())
        }

    private class TestComponents(override val components: List<LevelComponent<*>>) : LevelComponentModule {
        override val moduleName: String = "CoreLevelTest"
    }

    private val linkComponent = LevelComponent(Link::class, Link.serializer())

    /** Saves through [levels] and drains the barrier, as a paused editor would. */
    private fun save(host: GameHost, levels: LevelService): ByteArray {
        val action = levels.save(host.ctx.barrier)
        host.ctx.barrier.drain(host.world, host.ctx)
        return assertIs<LevelOutcome.Completed<ByteArray>>(action.outcome, "save: ${action.outcome}").value
    }

    private fun load(host: GameHost, levels: LevelService, bytes: ByteArray) {
        val action = levels.load(levels.read(bytes), host.ctx.barrier)
        host.ctx.barrier.drain(host.world, host.ctx)
        assertIs<LevelOutcome.Completed<*>>(action.outcome, "load: ${action.outcome}")
    }

    /** A world that has lived a little: see the class KDoc. */
    private fun populated(host: GameHost): Populated {
        val world = host.world
        val netIds = host.ctx[CoreModule.NET_IDS]
        host.run(37)
        val doomed = List(3) { index -> world.entity { it += MoverState(x = index.toFloat()) } }
        val doomedIds = doomed.map(netIds::allocate)
        val body = world.entity {
            it += PhysicsBody(x = 4f, y = -2f, linearX = 1.5f)
            it += Box(halfWidth = 0.5f, halfHeight = 0.25f)
        }
        val bodyId = netIds.allocate(body)
        val anonymous = world.entity { it += MoverState(x = 9f, y = 8f, grounded = true) }
        val linker = world.entity { it += Link(target = body, targetId = bodyId, readyAt = Tick(412)) }
        val linkerId = netIds.allocate(linker)
        // Freed in an order that is not allocation order, so the free queue is not sorted.
        netIds.free(doomedIds[1])
        world -= doomed[1]
        netIds.free(doomedIds[0])
        world -= doomed[0]
        host.ctx.rng.nextLong(RngStream.Combat)
        host.ctx.rng.nextInt(RngStream.Spawn, 1000)
        return Populated(body, bodyId, anonymous, linker, linkerId)
    }

    private class Populated(
        val body: Entity,
        val bodyId: NetId,
        val anonymous: Entity,
        val linker: Entity,
        val linkerId: NetId,
    )

    @Test
    fun `a level loads into a fresh game as the world, ids, clock and streams it was saved from`() {
        val original = host()
        val saved = populated(original)
        val bytes = save(original, levels(original, extra = arrayOf(linkComponent)))

        val fresh = host()
        // Something already in the fresh world, which the load must replace rather than add to.
        fresh.world.entity { it += MoverState(x = -1f) }
        load(fresh, levels(fresh, extra = arrayOf(linkComponent)), bytes)

        val netIds = fresh.ctx[CoreModule.NET_IDS]
        with(fresh.world) {
            assertEquals(original.world.numEntities, numEntities, "entity count")
            val link = saved.linker[Link]
            assertEquals(saved.body, link.target, "an Entity inside a component still names the same entity")
            assertEquals(saved.bodyId, link.targetId)
            assertEquals(Tick(412), link.readyAt)
            assertEquals(saved.linker, netIds.resolveOrNull(saved.linkerId), "NetId binding")
            assertEquals(saved.body, netIds.resolveOrNull(saved.bodyId), "NetId binding")
            assertEquals(9f, saved.anonymous[MoverState].x)
            assertTrue(saved.anonymous[MoverState].grounded)
            assertEquals(1.5f, saved.body[PhysicsBody].linearX)
        }
        assertEquals(original.tick, fresh.tick, "clock")
        for (stream in RngStream.entries) {
            assertEquals(original.ctx.rng.nextLong(stream), fresh.ctx.rng.nextLong(stream), "stream $stream")
        }

        // The allocator: the spawns after the load get the ids the original game hands out, free queue first.
        val originalIds = original.ctx[CoreModule.NET_IDS]
        repeat(3) {
            val a = originalIds.allocate(original.world.entity())
            val b = netIds.allocate(fresh.world.entity())
            assertEquals(a, b, "allocation ${it + 1} after the load")
        }
    }

    @Test
    fun `a loaded body is rebuilt in physics from its component`() {
        val original = host()
        val saved = populated(original)
        val bytes = save(original, levels(original, extra = arrayOf(linkComponent)))

        val fresh = host()
        load(fresh, levels(fresh, extra = arrayOf(linkComponent)), bytes)

        val handle = with(fresh.world) { saved.body[PhysicsBody].handle }
        assertNotEquals(BodyHandle.NONE, handle, "the loaded PhysicsBody has no live body")
        val pose = fresh.ctx.physics.poseOf(handle, BodyPose())
        assertEquals(4f to -2f, pose.x to pose.y)
        assertEquals(saved.bodyId, fresh.ctx.physics.ownerOf(handle))
    }

    @Test
    fun `saving the loaded world again reproduces the file byte for byte`() {
        val original = host()
        populated(original)
        val bytes = save(original, levels(original, extra = arrayOf(linkComponent)))

        val fresh = host()
        val levels = levels(fresh, extra = arrayOf(linkComponent))
        load(fresh, levels, bytes)

        assertContentEquals(bytes, save(fresh, levels))
    }

    @Test
    fun `a component no module lists refuses the save and names the class`() {
        val host = host()
        populated(host)
        // The same world, saved by a service that was never told about Link.
        val action = levels(host).save(host.ctx.barrier)
        host.ctx.barrier.drain(host.world, host.ctx)

        val failed = assertIs<LevelOutcome.Failed>(action.outcome)
        assertIs<LevelSaveException>(failed.cause)
        assertContains(failed.cause.message.orEmpty(), Link::class.qualifiedName!!)
    }

    @Test
    fun `a save waits for the barrier, and sees what was queued ahead of it`() {
        val host = host()
        val levels = levels(host)
        host.time.pause()
        host.ctx.barrier.submit(SpawnMover(x = 77f))
        val action = levels.save(host.ctx.barrier)

        assertEquals(LevelOutcome.Pending, action.outcome, "a save must not read the world beside the barrier")
        host.ctx.barrier.drain(host.world, host.ctx)
        val bytes = assertIs<LevelOutcome.Completed<ByteArray>>(action.outcome).value

        val fresh = host()
        load(fresh, levels(fresh), bytes)
        val xs = fresh.world.family { all(MoverState) }.entities.map { with(fresh.world) { it[MoverState].x } }
        assertEquals(listOf(77f), xs, "the spawn queued before the save is in the level")
    }

    private class SpawnMover(private val x: Float) : BarrierAction {
        override val label: String = "spawn a mover"
        override fun apply(world: World, ctx: dev.wildware.udea.core.GameContext) {
            world.entity { it += MoverState(x = x) }
        }
    }

    @Test
    fun `a load waits for the barrier`() {
        val original = host()
        populated(original)
        val bytes = save(original, levels(original, extra = arrayOf(linkComponent)))

        val fresh = host()
        val levels = levels(fresh, extra = arrayOf(linkComponent))
        val action = levels.load(levels.read(bytes), fresh.ctx.barrier)
        assertEquals(LevelOutcome.Pending, action.outcome)
        assertEquals(0, fresh.world.numEntities, "reading a level touched the world")

        fresh.run(1)

        assertIs<LevelOutcome.Completed<*>>(action.outcome)
        assertEquals(original.tick + 1, fresh.tick, "the load landed at the top of the step, which then ran")
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `a level from another format version is refused by number, before anything else is read`() {
        val host = host()
        val bytes = Cbor.encodeToByteArray(LevelHeader.serializer(), LevelHeader(LevelFormat.VERSION + 1))

        val refused = assertFailsWith<LevelFormatException> { levels(host).read(bytes) }

        assertContains(refused.message.orEmpty(), "version ${LevelFormat.VERSION + 1}")
    }

    @Test
    fun `bytes that are not a level are refused without touching the world`() {
        val host = host()
        host.world.entity { it += MoverState(x = 5f) }

        assertFailsWith<LevelFormatException> { levels(host).read(byteArrayOf(1, 2, 3, 4, 5)) }
        assertEquals(1, host.world.numEntities)
    }

    @Test
    fun `a level whose ids contradict each other is refused before the world is touched`() {
        val original = host()
        val saved = populated(original)
        val bytes = save(original, levels(original, extra = arrayOf(linkComponent)))
        val format = LevelFormat(LevelComponentModule.discover() + TestComponents(listOf(linkComponent)), LevelHooks())
        val document = format.decode(bytes)
        assertTrue(document.handles.freeIndices.isNotEmpty(), "the fixture has no freed id to contradict")

        fun corrupted(netIds: List<LevelNetId>): ByteArray = format.encode(
            LevelDocument(
                document.formatVersion, document.tick, document.rng, document.world, netIds,
                document.handles, document.sections,
            ),
        )
        val boundTwice = corrupted(document.netIds + LevelNetId(saved.anonymous, saved.bodyId))
        val boundWhileFree = corrupted(
            document.netIds + LevelNetId(saved.anonymous, NetId.of(document.handles.freeIndices[0], 0)),
        )

        val fresh = host()
        fresh.world.entity { it += MoverState(x = 5f) }
        val levels = levels(fresh, extra = arrayOf(linkComponent))
        for ((name, corrupt) in listOf("bound twice" to boundTwice, "bound while free" to boundWhileFree)) {
            val refused = assertFailsWith<LevelFormatException>(name) { levels.read(corrupt) }
            assertContains(refused.message.orEmpty(), "none, free, repeated or out of range", message = name)
        }
        assertEquals(1, fresh.world.numEntities)
    }

    @Test
    fun `a level that holds a component this game does not have is refused`() {
        val original = host()
        populated(original)
        val bytes = save(original, levels(original, extra = arrayOf(linkComponent)))

        val fresh = host()
        val refused = assertFailsWith<LevelFormatException> { levels(fresh).read(bytes) }

        assertContains(refused.message.orEmpty(), "Link")
    }

    @Test
    fun `a component that gained a field and lost another still loads an old level`() {
        val original = host()
        original.world.entity { it += GaugeV1(level = 3, retired = "gone") }
        val bytes = save(original, levels(original, extra = arrayOf(LevelComponent(GaugeV1::class, GaugeV1.serializer()))))

        val fresh = host()
        load(fresh, levels(fresh, extra = arrayOf(LevelComponent(GaugeV2::class, GaugeV2.serializer()))), bytes)

        val gauges = fresh.world.family { all(GaugeV2) }.entities
        assertEquals(1, gauges.size)
        with(fresh.world) {
            assertEquals(3, gauges[0][GaugeV2].level, "the kept field")
            assertEquals(GaugeV2.ADDED_DEFAULT, gauges[0][GaugeV2].added, "the new field takes its default")
        }
    }

    @Test
    fun `a section saves world-level state and puts it back after the entities`() {
        val original = host()
        populated(original)
        val originalCounter = Counter(next = 41)
        val bytes = save(original, levels(original, hooks(originalCounter), linkComponent))

        val fresh = host()
        val freshCounter = Counter(next = 0)
        load(fresh, levels(fresh, hooks(freshCounter), linkComponent), bytes)

        assertEquals(41, freshCounter.next)
        assertEquals(original.world.numEntities, freshCounter.entitiesSeenOnLoad, "the section loaded before the world")
    }

    @Test
    fun `a level missing a section this game saves is refused`() {
        val original = host()
        val bytes = save(original, levels(original))

        val fresh = host()
        val refused = assertFailsWith<LevelFormatException> { levels(fresh, hooks(Counter(0))).read(bytes) }

        assertContains(refused.message.orEmpty(), Counter.NAME)
    }

    @Test
    fun `two sections under one name are refused when the game is built`() {
        val hooks = LevelHooks()
        hooks.section(Counter(0))
        assertFailsWith<IllegalArgumentException> { hooks.section(Counter(1)) }
    }

    private fun hooks(counter: Counter): LevelHooks = LevelHooks().also { it.section(counter) }

    @Serializable
    private class SavedCounter(val next: Int)

    private class Counter(var next: Int) : LevelSection<SavedCounter> {
        var entitiesSeenOnLoad = -1
        override val name: String = NAME
        override val serializer = SavedCounter.serializer()
        override fun save(world: World): SavedCounter = SavedCounter(next)
        override fun load(world: World, saved: SavedCounter) {
            next = saved.next
            entitiesSeenOnLoad = world.numEntities
        }

        companion object {
            const val NAME: String = "core-test.counter"
        }
    }
}

/** A component that refers to other entities, both ways a component can. */
@Serializable
internal class Link(
    var target: Entity = Entity.NONE,
    var targetId: NetId = NetId.NONE,
    var readyAt: Tick = Tick(0),
) : Component<Link> {
    override fun type(): ComponentType<Link> = Link

    companion object : ComponentType<Link>()
}

/** One component, as it was when a level was saved... */
@Serializable
@SerialName("dev.wildware.udea.core.level.Gauge")
internal class GaugeV1(val level: Int = 0, val retired: String = "") : Component<GaugeV1> {
    override fun type(): ComponentType<GaugeV1> = GaugeV1

    companion object : ComponentType<GaugeV1>()
}

/** ...and as it is in the build loading it: `retired` removed, `added` new. */
@Serializable
@SerialName("dev.wildware.udea.core.level.Gauge")
internal class GaugeV2(val level: Int = 0, val added: Int = ADDED_DEFAULT) : Component<GaugeV2> {
    override fun type(): ComponentType<GaugeV2> = GaugeV2

    companion object : ComponentType<GaugeV2>() {
        const val ADDED_DEFAULT: Int = 7
    }
}
