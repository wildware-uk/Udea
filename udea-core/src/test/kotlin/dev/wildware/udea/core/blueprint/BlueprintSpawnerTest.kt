package dev.wildware.udea.core.blueprint

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.EntityCreateContext
import com.github.quillraven.fleks.World
import com.github.quillraven.fleks.entityTagOf
import dev.wildware.udea.core.SceneId
import dev.wildware.udea.core.SimSystem
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.core.module.SimPhase
import dev.wildware.udea.core.module.SimRegistry
import dev.wildware.udea.core.module.UdeaGameDef
import dev.wildware.udea.core.module.UdeaModule
import dev.wildware.udea.core.scene.Scene
import dev.wildware.udea.core.scene.SceneScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Blueprints become entities through the barrier, headless, with the id known up front.
 *
 * The behaviour under test is the one `GameScreen`'s spawn loop had
 * (`common/UdeaGameManager.kt:191-217`) — blueprint components, then per-spawn components and
 * tags, then a defaulted spatial component, then the position override. The *scheduling* it had
 * is what these tests refuse: that loop ran inside a `Gdx.app.postRunnable`, on an unspecified
 * later frame, with a `started` flag guarding `render()` so that "the world is empty" was a
 * state the engine legitimately had.
 *
 * Every test here runs in [RenderMode.Headless] with no GL context, which is the other half of
 * what the old loop could not do.
 */
class BlueprintSpawnerTest {

    @Test
    fun `twenty blueprints spawn headless with their components and tags`() {
        val harness = Harness()
        val ids = harness.spawner.spawnAll(List(20) { SpawnRequest(Grunt) })
        assertEquals(20, ids.size)

        harness.host.run(1)

        assertEquals(20, harness.host.world.numEntities, "the batch must land in one tick")
        for (id in ids) {
            val entity = assertNotNull(harness.netIds.resolveOrNull(id), "$id did not resolve")
            with(harness.host.world) {
                assertEquals(GRUNT_HEALTH, entity[Health].value, "the blueprint's component")
                assertTrue(entity has Hostile, "the blueprint's tag")
            }
        }
    }

    @Test
    fun `per-spawn overrides are applied on top of the blueprint`() {
        val harness = Harness()
        val id = harness.spawner.spawn(
            Grunt,
            overrides = { context, entity ->
                with(context) {
                    entity += Health(ELITE_HEALTH)
                    entity += Elite
                }
            },
        )
        harness.host.run(1)

        val entity = assertNotNull(harness.netIds.resolveOrNull(id))
        with(harness.host.world) {
            assertEquals(ELITE_HEALTH, entity[Health].value, "an override must win over the blueprint")
            assertTrue(entity has Hostile, "and must not remove what the blueprint added")
            assertTrue(entity has Elite)
        }
    }

    @Test
    fun `a spawn is invisible to every system until the next tick`() {
        val harness = Harness(spawnFromSystemAtTick = 3L)
        harness.host.run(6)

        val probe = assertNotNull(harness.probe)
        // The system spawns at the end of tick 3. Every system that runs during tick 3 —
        // including the one that submitted it — must still see the pre-spawn world; the
        // entity appears at the top of tick 4, when the barrier drains.
        assertEquals(0, probe.entitiesSeenAt(3L), "the spawning tick must not observe its own spawn")
        assertEquals(1, probe.entitiesSeenAt(4L), "the spawn lands at the top of the next tick")
        assertNull(
            probe.resolvedOnSpawningTick,
            "the NetId is handed out before the entity exists, so it must not resolve yet",
        )
    }

    @Test
    fun `the placement adds a spatial component when the blueprint did not, and never overwrites one`() {
        val harness = Harness()
        val bare = harness.spawner.spawn(Grunt)
        val placed = harness.spawner.spawn(Positioned)
        harness.host.run(1)

        with(harness.host.world) {
            val bareEntity = assertNotNull(harness.netIds.resolveOrNull(bare))
            assertTrue(bareEntity has Placement, "a blueprint with no Placement must be given one")
            assertEquals(0f, bareEntity[Placement].x, "a defaulted Placement is at the origin")

            val placedEntity = assertNotNull(harness.netIds.resolveOrNull(placed))
            assertEquals(
                AUTHORED_X,
                placedEntity[Placement].x,
                "a blueprint that authored its own Placement must keep the authored values",
            )
        }
    }

    @Test
    fun `a position override moves the entity after the blueprint and the default have run`() {
        val harness = Harness()
        val moved = harness.spawner.spawn(Positioned, SpawnPosition(9f, -4f))
        harness.host.run(1)

        with(harness.host.world) {
            val entity = assertNotNull(harness.netIds.resolveOrNull(moved))
            assertEquals(9f, entity[Placement].x, "the position override must beat the authored value")
            assertEquals(-4f, entity[Placement].y)
        }
    }

    @Test
    fun `the NetId returned before the drain resolves to the created entity after it`() {
        val harness = Harness()
        val id = harness.spawner.spawn(Grunt)

        assertNull(
            harness.netIds.resolveOrNull(id),
            "the entity does not exist until the barrier drains, and the index must say so",
        )
        assertEquals(1, harness.netIds.reservedCount)
        assertEquals(0, harness.host.world.numEntities)

        harness.host.run(1)

        val entity = assertNotNull(harness.netIds.resolveOrNull(id), "the id must resolve after the drain")
        assertEquals(id, harness.netIds.netIdOf(entity), "and the entity must resolve back to it")
        assertEquals(0, harness.netIds.reservedCount, "the reservation is spent")
    }

    @Test
    fun `a reserved id is not offered to the next allocation`() {
        val harness = Harness()
        val queued = harness.spawner.spawn(Grunt)
        val direct = harness.netIds.allocate(harness.host.world.entity { })

        assertTrue(
            queued != direct,
            "an id reserved for a queued spawn must never be handed out again, got $queued twice",
        )
    }

    @Test
    fun `a batch of twenty is one barrier entry, not twenty`() {
        val harness = Harness()
        harness.spawner.spawnAll(List(20) { SpawnRequest(Grunt) })

        assertEquals(
            1,
            harness.barrier.pendingCount(),
            "twenty separate actions would let a scene swap or a restore interleave inside a " +
                "batch the caller asked for as a unit",
        )

        harness.host.run(1)
        assertEquals(20, harness.host.world.numEntities)
        assertEquals(20L, harness.spawner.spawnedCount)
    }

    @Test
    fun `a spawner with no placement refuses a position instead of dropping it`() {
        val harness = Harness(withPlacement = false)

        val failure = assertFailsWith<IllegalArgumentException> {
            harness.spawner.spawn(Grunt, SpawnPosition(1f, 2f))
        }
        assertTrue(
            failure.message.orEmpty().contains("SpawnPlacement"),
            "the message must name what is missing, was '${failure.message}'",
        )
        assertEquals(0, harness.barrier.pendingCount(), "a refused spawn must queue nothing")
        assertEquals(0, harness.netIds.reservedCount, "and must reserve no id")
    }

    @Test
    fun `a scene populates through the same instantiation an agent spawn takes`() {
        val harness = Harness(sceneEntities = SCENE_ENTITIES)
        harness.host.run(1)

        assertEquals(SCENE_ENTITIES, harness.host.world.numEntities)
        assertEquals(SCENE_ENTITIES.toLong(), harness.spawner.spawnedCount)
        with(harness.host.world) {
            val entity = assertNotNull(harness.netIds.resolveOrNull(NetId.of(0, 0)))
            assertEquals(GRUNT_HEALTH, entity[Health].value)
            assertTrue(entity has Hostile)
            assertTrue(entity has Placement, "the scene path must default the placement too")
        }
    }

    @Test
    fun `an empty batch queues nothing`() {
        val harness = Harness()
        assertEquals(emptyList(), harness.spawner.spawnAll(emptyList()))
        assertEquals(0, harness.barrier.pendingCount())
    }

    // --- fixtures --------------------------------------------------------------------------

    /**
     * A real [GameHost] in [RenderMode.Headless] with a spawner registered on its context.
     *
     * The order is the one a real host has no choice about. `CoreModule` builds the barrier and
     * the id index in its constructor, so they exist as soon as the [UdeaGameDef] does and
     * before the world; the spawner is built from them and handed to the module, which
     * registers it on the context and passes it to the system factory. Nothing here is static
     * and nothing is set after the world exists.
     */
    private class Harness(
        withPlacement: Boolean = true,
        spawnFromSystemAtTick: Long? = null,
        sceneEntities: Int = 0,
    ) {
        private val module = SpawnModule(spawnFromSystemAtTick)

        private val definition = UdeaGameDef(modules = listOf(module))

        val barrier = definition.core.barrier
        val netIds: NetIdIndex = definition.core.netIds

        val spawner = BlueprintSpawner(
            barrier = barrier,
            netIds = netIds,
            placement = if (withPlacement) PlacementIsThisGamesTransform else null,
        )

        val host: GameHost

        init {
            module.spawner = spawner
            if (sceneEntities > 0) definition.core.scenes.load(GruntScene(spawner, sceneEntities))
            host = GameHost(RenderMode.Headless, definition)
        }

        /** The probe system, when the module was asked to build one. */
        val probe: SpawnProbeSystem? get() = module.probe
    }

    /**
     * Publishes the spawner on the context and builds the probe system against it.
     *
     * The system takes what it needs in its constructor — `ctx.blueprints`, resolved once at
     * construction — rather than holding the context and reaching through it per tick.
     */
    private class SpawnModule(private val spawnAtTick: Long?) : UdeaModule {

        /** Set before the definition is built; the two hooks below both run during `build()`. */
        var spawner: BlueprintSpawner? = null

        var probe: SpawnProbeSystem? = null
            private set

        override fun context(builder: dev.wildware.udea.core.GameContextBuilder) {
            builder.blueprintSpawner(checkNotNull(spawner) { "wire the spawner before building" })
        }

        override fun simulation(registry: SimRegistry) {
            val at = spawnAtTick ?: return
            registry.add(SimPhase.Gameplay, { ctx ->
                SpawnProbeSystem(ctx.blueprints, ctx[CoreModule.NET_IDS], at).also { probe = it }
            })
        }
    }

    /** Counts what the world holds at the end of each tick, and spawns once from inside a tick. */
    private class SpawnProbeSystem(
        private val spawner: BlueprintSpawner,
        private val netIds: NetIdIndex,
        private val spawnAtTick: Long,
    ) : SimSystem() {

        private val seen = HashMap<Long, Int>()

        var resolvedOnSpawningTick: Entity? = null
            private set

        fun entitiesSeenAt(tick: Long): Int = seen.getValue(tick)

        override fun onTick() {
            if (tick.value == spawnAtTick) {
                resolvedOnSpawningTick = netIds.resolveOrNull(spawner.spawn(Grunt))
            }
            seen[tick.value] = world.numEntities
        }
    }

    /** A scene that builds its entities through the spawner rather than by hand. */
    private class GruntScene(
        private val spawner: BlueprintSpawner,
        private val count: Int,
    ) : Scene {
        override val id: SceneId = SceneId("grunts")
        override val seed: Long = 1L

        override fun populate(scope: SceneScope) {
            repeat(count) { spawner.spawnNow(scope.world, SpawnRequest(Grunt)) }
        }
    }

    private companion object {
        const val SCENE_ENTITIES: Int = 4
        const val GRUNT_HEALTH: Float = 60f
        const val ELITE_HEALTH: Float = 250f
        const val AUTHORED_X: Float = 3f
    }
}

// --- the game's own types, which the kernel deliberately does not name -------------------------

/** Stands in for the game's `Transform`: the kernel has no spatial component of its own. */
internal class Placement(var x: Float = 0f, var y: Float = 0f) : Component<Placement> {
    override fun type(): ComponentType<Placement> = Placement

    companion object : ComponentType<Placement>()
}

internal class Health(var value: Float = 100f) : Component<Health> {
    override fun type(): ComponentType<Health> = Health

    companion object : ComponentType<Health>()
}

internal val Hostile = entityTagOf()

internal val Elite = entityTagOf()

/** What "place this entity" means to this game, and the only code that names [Placement]. */
internal object PlacementIsThisGamesTransform : SpawnPlacement {

    override fun defaultIfAbsent(world: World, entity: Entity) {
        with(world) {
            if (entity has Placement) return
            entity.configure { it += Placement() }
        }
    }

    override fun moveTo(world: World, entity: Entity, x: Float, y: Float) {
        with(world) {
            val placement = entity[Placement]
            placement.x = x
            placement.y = y
        }
    }
}

/** A blueprint with a component and a tag and no placement of its own. */
internal object Grunt : Blueprint {
    override val id: BlueprintId = BlueprintId("unit/grunt")

    override fun configure(context: EntityCreateContext, entity: Entity) {
        with(context) {
            entity += Health(60f)
            entity += Hostile
        }
    }
}

/** A blueprint that authors its own placement, so the default must not overwrite it. */
internal object Positioned : Blueprint {
    override val id: BlueprintId = BlueprintId("unit/positioned")

    override fun configure(context: EntityCreateContext, entity: Entity) {
        with(context) {
            entity += Health(60f)
            entity += Placement(x = 3f, y = 7f)
        }
    }
}
