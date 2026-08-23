package dev.wildware.udea.net.harness

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import com.github.quillraven.fleks.configureWorld
import dev.wildware.udea.core.EngineConfig
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.core.SceneId
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.fixtures.testGameContext
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.core.loop.WorldSimulation
import dev.wildware.udea.core.replication.BitReader
import dev.wildware.udea.core.replication.BitWriter
import dev.wildware.udea.core.replication.ComponentTypeId
import dev.wildware.udea.core.replication.FieldMask
import dev.wildware.udea.core.replication.FieldStore
import dev.wildware.udea.core.replication.MaskOps
import dev.wildware.udea.core.replication.NoSuchFieldIndexException
import dev.wildware.udea.core.replication.Replicator
import dev.wildware.udea.core.rng.DefaultRngService
import dev.wildware.udea.core.snapshot.ComponentRegistry
import dev.wildware.udea.core.snapshot.ComponentSchema
import dev.wildware.udea.core.snapshot.FieldKind
import dev.wildware.udea.core.snapshot.RingConfig
import dev.wildware.udea.core.snapshot.SnapshotRing
import dev.wildware.udea.core.snapshot.SnapshotService
import dev.wildware.udea.core.snapshot.WorldSnapshot
import dev.wildware.udea.core.snapshot.fleksComponentType
import dev.wildware.udea.net.wire.CreateOnlyFields

/**
 * Two replicated components for the networking tests, written by hand.
 *
 * By hand and not generated, for the reason `TransformReplicator` is: `udea-codegen` must not be
 * the author of the specification it is measured against. They also carry the two properties
 * this module needs and `udea-core`'s fixtures do not:
 *
 * - a `@Sim` field (`spawnTick`), so a test can assert it never reaches the wire;
 * - a `@Net(lifetime = OnCreate)` field (`teamId`), so issue #114's stripping has something to
 *   strip. `MoverReplicator` declares it through [CreateOnlyFields], which is the marker
 *   `udea-codegen` will implement when it emits `CREATE_ONLY_MASK`.
 */
internal class Mover(
    /** `@Net`. */
    var x: Float = 0f,
    /** `@Net`. */
    var y: Float = 0f,
    /** `@Net(lifetime = OnCreate)` — set at spawn, must never ride a delta. */
    var teamId: Int = 0,
    /** `@Sim` — rewinds, never reaches a client. */
    var spawnTick: Tick = Tick.ZERO,
) : Component<Mover> {
    override fun type(): ComponentType<Mover> = Mover

    companion object : ComponentType<Mover>()
}

internal object MoverReplicator : Replicator<Mover>, CreateOnlyFields {

    const val X = 0
    const val Y = 1
    const val TEAM_ID = 2
    const val SPAWN_TICK = 3
    const val FIELD_COUNT = 4

    val kinds: List<FieldKind> = listOf(FieldKind.Float, FieldKind.Float, FieldKind.Int, FieldKind.Tick)

    override val typeId: ComponentTypeId = ComponentTypeId(1)

    override val fieldNames: List<String> = listOf("x", "y", "teamId", "spawnTick")

    override val netMask: FieldMask = MaskOps.of(X, Y, TEAM_ID)

    override val allMask: FieldMask = MaskOps.lowest(FIELD_COUNT)

    override val createOnlyMask: FieldMask = MaskOps.of(TEAM_ID)

    override fun capture(component: Mover, store: FieldStore, slot: Int) {
        store.setFloat(slot, X, component.x)
        store.setFloat(slot, Y, component.y)
        store.setInt(slot, TEAM_ID, component.teamId)
        store.setTick(slot, SPAWN_TICK, component.spawnTick)
    }

    override fun diff(store: FieldStore, slotA: Int, slotB: Int): FieldMask {
        var mask = MaskOps.EMPTY
        if (store.getFloat(slotA, X).toRawBits() != store.getFloat(slotB, X).toRawBits()) {
            mask = MaskOps.set(mask, X)
        }
        if (store.getFloat(slotA, Y).toRawBits() != store.getFloat(slotB, Y).toRawBits()) {
            mask = MaskOps.set(mask, Y)
        }
        if (store.getInt(slotA, TEAM_ID) != store.getInt(slotB, TEAM_ID)) mask = MaskOps.set(mask, TEAM_ID)
        if (store.getTick(slotA, SPAWN_TICK) != store.getTick(slotB, SPAWN_TICK)) {
            mask = MaskOps.set(mask, SPAWN_TICK)
        }
        return mask
    }

    override fun write(store: FieldStore, slot: Int, mask: FieldMask, out: BitWriter) {
        if (MaskOps.isEmpty(mask)) return
        MaskOps.writeTo(mask, out, FIELD_COUNT)
        if (MaskOps.test(mask, X)) out.writeFloat(store.getFloat(slot, X))
        if (MaskOps.test(mask, Y)) out.writeFloat(store.getFloat(slot, Y))
        if (MaskOps.test(mask, TEAM_ID)) out.writeInt(store.getInt(slot, TEAM_ID))
        if (MaskOps.test(mask, SPAWN_TICK)) out.writeLong(store.getTick(slot, SPAWN_TICK).value)
    }

    override fun read(src: BitReader, store: FieldStore, slot: Int): FieldMask {
        val mask = MaskOps.readFrom(src, FIELD_COUNT)
        if (MaskOps.test(mask, X)) store.setFloat(slot, X, src.readFloat())
        if (MaskOps.test(mask, Y)) store.setFloat(slot, Y, src.readFloat())
        if (MaskOps.test(mask, TEAM_ID)) store.setInt(slot, TEAM_ID, src.readInt())
        if (MaskOps.test(mask, SPAWN_TICK)) store.setTick(slot, SPAWN_TICK, Tick(src.readLong()))
        return mask
    }

    override fun apply(store: FieldStore, slot: Int, component: Mover, mask: FieldMask) {
        if (MaskOps.test(mask, X)) component.x = store.getFloat(slot, X)
        if (MaskOps.test(mask, Y)) component.y = store.getFloat(slot, Y)
        if (MaskOps.test(mask, TEAM_ID)) component.teamId = store.getInt(slot, TEAM_ID)
        if (MaskOps.test(mask, SPAWN_TICK)) component.spawnTick = store.getTick(slot, SPAWN_TICK)
    }

    override fun getField(component: Mover, fieldIndex: Int): Any? = when (fieldIndex) {
        X -> component.x
        Y -> component.y
        TEAM_ID -> component.teamId
        SPAWN_TICK -> component.spawnTick
        else -> throw NoSuchFieldIndexException("Mover", fieldIndex, FIELD_COUNT)
    }

    override fun setField(component: Mover, fieldIndex: Int, value: Any?) {
        when (fieldIndex) {
            X -> component.x = value as Float
            Y -> component.y = value as Float
            TEAM_ID -> component.teamId = value as Int
            SPAWN_TICK -> component.spawnTick = value as Tick
            else -> throw NoSuchFieldIndexException("Mover", fieldIndex, FIELD_COUNT)
        }
    }
}

/** A second component, so a packet has to name which one it carries. */
internal class Vitals(
    var hp: Int = 100,
    var shielded: Boolean = false,
) : Component<Vitals> {
    override fun type(): ComponentType<Vitals> = Vitals

    companion object : ComponentType<Vitals>()
}

internal object VitalsReplicator : Replicator<Vitals> {

    const val HP = 0
    const val SHIELDED = 1
    const val FIELD_COUNT = 2

    val kinds: List<FieldKind> = listOf(FieldKind.Int, FieldKind.Bool)

    override val typeId: ComponentTypeId = ComponentTypeId(2)

    override val fieldNames: List<String> = listOf("hp", "shielded")

    override val netMask: FieldMask = MaskOps.lowest(FIELD_COUNT)

    override val allMask: FieldMask = MaskOps.lowest(FIELD_COUNT)

    override fun capture(component: Vitals, store: FieldStore, slot: Int) {
        store.setInt(slot, HP, component.hp)
        store.setBoolean(slot, SHIELDED, component.shielded)
    }

    override fun diff(store: FieldStore, slotA: Int, slotB: Int): FieldMask {
        var mask = MaskOps.EMPTY
        if (store.getInt(slotA, HP) != store.getInt(slotB, HP)) mask = MaskOps.set(mask, HP)
        if (store.getBoolean(slotA, SHIELDED) != store.getBoolean(slotB, SHIELDED)) {
            mask = MaskOps.set(mask, SHIELDED)
        }
        return mask
    }

    override fun write(store: FieldStore, slot: Int, mask: FieldMask, out: BitWriter) {
        if (MaskOps.isEmpty(mask)) return
        MaskOps.writeTo(mask, out, FIELD_COUNT)
        if (MaskOps.test(mask, HP)) out.writeInt(store.getInt(slot, HP))
        if (MaskOps.test(mask, SHIELDED)) out.writeBoolean(store.getBoolean(slot, SHIELDED))
    }

    override fun read(src: BitReader, store: FieldStore, slot: Int): FieldMask {
        val mask = MaskOps.readFrom(src, FIELD_COUNT)
        if (MaskOps.test(mask, HP)) store.setInt(slot, HP, src.readInt())
        if (MaskOps.test(mask, SHIELDED)) store.setBoolean(slot, SHIELDED, src.readBoolean())
        return mask
    }

    override fun apply(store: FieldStore, slot: Int, component: Vitals, mask: FieldMask) {
        if (MaskOps.test(mask, HP)) component.hp = store.getInt(slot, HP)
        if (MaskOps.test(mask, SHIELDED)) component.shielded = store.getBoolean(slot, SHIELDED)
    }

    override fun getField(component: Vitals, fieldIndex: Int): Any? = when (fieldIndex) {
        HP -> component.hp
        SHIELDED -> component.shielded
        else -> throw NoSuchFieldIndexException("Vitals", fieldIndex, FIELD_COUNT)
    }

    override fun setField(component: Vitals, fieldIndex: Int, value: Any?) {
        when (fieldIndex) {
            HP -> component.hp = value as Int
            SHIELDED -> component.shielded = value as Boolean
            else -> throw NoSuchFieldIndexException("Vitals", fieldIndex, FIELD_COUNT)
        }
    }
}

/** The registry both peers in a test share. */
internal object NetTestComponents {

    fun registry(): ComponentRegistry = ComponentRegistry(
        listOf(
            fleksComponentType(
                MoverReplicator,
                ComponentSchema.of(MoverReplicator, "Mover", MoverReplicator.kinds),
                Mover,
            ) { Mover() },
            fleksComponentType(
                VitalsReplicator,
                ComponentSchema.of(VitalsReplicator, "Vitals", VitalsReplicator.kinds),
                Vitals,
            ) { Vitals() },
        ),
    )
}

/**
 * A headless server world: real Fleks, real `NetIdIndex`, real `SnapshotService`, real ring.
 *
 * Nothing here is a stand-in for the snapshot spine. The replication tests capture through the
 * same `SnapshotService` the engine uses and read baselines out of the same `SnapshotRing` that
 * backs time travel, because "the ring is the baseline store" (spec 3.1) is a claim about *this*
 * ring and would be untested against a purpose-built double.
 */
internal class NetTestWorld(
    seed: Long = 20_260_823L,
    val registry: ComponentRegistry = NetTestComponents.registry(),
    ringConfig: RingConfig = RingConfig(),
) {

    val netIds: NetIdIndex = NetIdIndex(capacity = 4096, entityCapacity = 4096)
    val world: World = configureWorld {}
    val ctx: GameContext = testGameContext(
        config = EngineConfig(seed = seed),
        configure = { rng = DefaultRngService(seed) },
    )
    val ring: SnapshotRing = SnapshotRing(registry, ringConfig)
    val snapshots: SnapshotService = SnapshotService(registry, world, ctx, netIds)

    /**
     * The real simulation, used only to advance the clock.
     *
     * `SimClock.advance` is `internal` to `udea-core` on purpose — only the kernel moves time —
     * so a test outside that module cannot fake a tick even if it wanted to. Driving the real
     * `WorldSimulation` is therefore not ceremony: it is the only way to advance a tick, and it
     * means these tests capture on exactly the cadence an assembled game does.
     */
    val sim: WorldSimulation = WorldSimulation(ctx, world)

    init {
        ctx.scenes.requestScene(SceneId("arena"))
    }

    /** Spawns an entity carrying [Mover], and [Vitals] when [withVitals]. */
    fun spawn(x: Float, y: Float, teamId: Int = 0, withVitals: Boolean = true): NetId {
        val entity: Entity = world.entity {
            it += Mover(x, y, teamId, ctx.clock.tick)
            if (withVitals) it += Vitals()
        }
        return netIds.allocate(entity)
    }

    /** Removes [netId] from the world and frees its id. */
    fun despawn(netId: NetId) {
        val entity = netIds.resolveOrNull(netId) ?: return
        netIds.free(netId)
        world -= entity
    }

    /** The live [Mover] of [netId]. */
    fun mover(netId: NetId): Mover {
        val entity = netIds.resolveOrNull(netId) ?: error("$netId is not live")
        return with(world) { entity[Mover] }
    }

    /** The live [Vitals] of [netId]. */
    fun vitals(netId: NetId): Vitals {
        val entity = netIds.resolveOrNull(netId) ?: error("$netId is not live")
        return with(world) { entity[Vitals] }
    }

    /** Advances the clock and captures the world into the ring. Returns the committed snapshot. */
    fun captureTick(): WorldSnapshot {
        sim.step()
        val slot = ring.acquire()
        snapshots.captureInto(slot)
        ring.commit(slot)
        return slot
    }
}
