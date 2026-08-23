package dev.wildware.udea.agent.host.net

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType
import com.github.quillraven.fleks.World
import dev.wildware.udea.core.EngineConfig
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.core.loop.WorldSimulation
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.core.module.UdeaGameDef
import dev.wildware.udea.core.replication.BitReader
import dev.wildware.udea.core.replication.BitWriter
import dev.wildware.udea.core.replication.ComponentTypeId
import dev.wildware.udea.core.replication.FieldMask
import dev.wildware.udea.core.replication.FieldStore
import dev.wildware.udea.core.replication.MaskOps
import dev.wildware.udea.core.replication.NoSuchFieldIndexException
import dev.wildware.udea.core.replication.Replicator
import dev.wildware.udea.core.snapshot.ComponentRegistry
import dev.wildware.udea.core.snapshot.ComponentSchema
import dev.wildware.udea.core.snapshot.FieldKind
import dev.wildware.udea.core.snapshot.SnapshotRing
import dev.wildware.udea.core.snapshot.SnapshotService
import dev.wildware.udea.core.snapshot.WorldSnapshot
import dev.wildware.udea.core.snapshot.fleksComponentType
import dev.wildware.udea.net.input.MoveInput

/**
 * One replicated body in the debug arena: where it is, and whose input moves it.
 *
 * ## Why the arena has a component of its own rather than the game's
 *
 * `net.spawn_session` has to work in a process whose game has no replicated component at all -
 * which is every process today, and is the normal state of a game half-way through being wired
 * for multiplayer. An agent debugging the *transport* needs something on the wire before the
 * game has anything to put there, and if these tools waited for the game they would be
 * unavailable exactly when they are most useful.
 *
 * So the arena is deliberately small and deliberately not a game: two coordinates and an owner.
 * What it is **not** is a second replication path. Every byte it moves goes through the shipped
 * `ReplicationServer`, `ReplicationClient`, `SnapshotRing` and `SimulatedTransport`; the arena
 * only supplies the world those operate on.
 */
public class NetAvatar(
    /** `@Net` - replicated. The authoritative x, written only by the server. */
    public var x: Float = 0f,
    /** `@Net` - replicated. */
    public var y: Float = 0f,
    /** `@Net` - replicated. Which client's input moves this body; zero for nobody's. */
    public var owner: Int = 0,
    /** `@Sim` - snapshotted, never replicated. Keeps a sim-only field on the arena's wire test. */
    public var spawnTick: Tick = Tick.ZERO,
) : Component<NetAvatar> {

    override fun type(): ComponentType<NetAvatar> = NetAvatar

    override fun toString(): String = "NetAvatar($x, $y, owner=$owner)"

    public companion object : ComponentType<NetAvatar>()
}

/**
 * [NetAvatar]'s `Replicator`, written by hand.
 *
 * Hand-written for the same reason every other declaration in this module is: `udea-agent-host`
 * runs no KSP round. The shape is the one `docs/contracts/replicator.md` fixes and
 * `TransformReplicator` demonstrates, including the rule that floats are compared by raw bits
 * and never by inequality.
 */
public object NetAvatarReplicator : Replicator<NetAvatar> {

    /** Replicated. */
    public const val X: Int = 0

    /** Replicated. */
    public const val Y: Int = 1

    /** Replicated. */
    public const val OWNER: Int = 2

    /** Simulation-only: inside [allMask], outside [netMask]. */
    public const val SPAWN_TICK: Int = 3

    /** Four lowered fields. */
    public const val FIELD_COUNT: Int = 4

    /** One kind per lowered field, index-aligned with [fieldNames]. */
    public val KINDS: List<FieldKind> =
        listOf(FieldKind.Float, FieldKind.Float, FieldKind.Int, FieldKind.Tick)

    override val typeId: ComponentTypeId = ComponentTypeId(1)

    override val fieldNames: List<String> = listOf("x", "y", "owner", "spawnTick")

    override val netMask: FieldMask = MaskOps.of(X, Y, OWNER)

    override val allMask: FieldMask = MaskOps.lowest(FIELD_COUNT)

    override fun capture(component: NetAvatar, store: FieldStore, slot: Int) {
        store.setFloat(slot, X, component.x)
        store.setFloat(slot, Y, component.y)
        store.setInt(slot, OWNER, component.owner)
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
        if (store.getInt(slotA, OWNER) != store.getInt(slotB, OWNER)) mask = MaskOps.set(mask, OWNER)
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
        if (MaskOps.test(mask, OWNER)) out.writeInt(store.getInt(slot, OWNER))
        if (MaskOps.test(mask, SPAWN_TICK)) out.writeLong(store.getTick(slot, SPAWN_TICK).value)
    }

    override fun read(src: BitReader, store: FieldStore, slot: Int): FieldMask {
        val mask = MaskOps.readFrom(src, FIELD_COUNT)
        if (MaskOps.test(mask, X)) store.setFloat(slot, X, src.readFloat())
        if (MaskOps.test(mask, Y)) store.setFloat(slot, Y, src.readFloat())
        if (MaskOps.test(mask, OWNER)) store.setInt(slot, OWNER, src.readInt())
        if (MaskOps.test(mask, SPAWN_TICK)) store.setTick(slot, SPAWN_TICK, Tick(src.readLong()))
        return mask
    }

    override fun apply(store: FieldStore, slot: Int, component: NetAvatar, mask: FieldMask) {
        if (MaskOps.test(mask, X)) component.x = store.getFloat(slot, X)
        if (MaskOps.test(mask, Y)) component.y = store.getFloat(slot, Y)
        if (MaskOps.test(mask, OWNER)) component.owner = store.getInt(slot, OWNER)
        if (MaskOps.test(mask, SPAWN_TICK)) component.spawnTick = store.getTick(slot, SPAWN_TICK)
    }

    override fun getField(component: NetAvatar, fieldIndex: Int): Any? = when (fieldIndex) {
        X -> component.x
        Y -> component.y
        OWNER -> component.owner
        SPAWN_TICK -> component.spawnTick
        else -> throw NoSuchFieldIndexException(TYPE_NAME, fieldIndex, FIELD_COUNT)
    }

    override fun setField(component: NetAvatar, fieldIndex: Int, value: Any?) {
        when (fieldIndex) {
            X -> component.x = expect<Float>(fieldIndex, value)
            Y -> component.y = expect<Float>(fieldIndex, value)
            OWNER -> component.owner = expect<Int>(fieldIndex, value)
            SPAWN_TICK -> component.spawnTick = expect<Tick>(fieldIndex, value)
            else -> throw NoSuchFieldIndexException(TYPE_NAME, fieldIndex, FIELD_COUNT)
        }
    }

    private inline fun <reified V : Any> expect(fieldIndex: Int, value: Any?): V =
        value as? V ?: throw IllegalArgumentException(
            "$TYPE_NAME.${fieldNames[fieldIndex]} expects ${V::class.simpleName}, got " +
                if (value == null) "null" else "${value::class.simpleName}",
        )

    private const val TYPE_NAME: String = "NetAvatar"
}

/**
 * The authoritative world a [NetSession] replicates: a real Fleks world, a real [NetIdIndex],
 * a real [SnapshotService] and the real [SnapshotRing].
 *
 * ## The ring is the baseline store, and there is not a second one
 *
 * Spec 3.1 says the snapshot ring *is* the replication baseline store. That claim is only worth
 * anything if the baselines actually come out of the ring a rewind would read, so this class
 * captures through [SnapshotService] into [ring] and hands that same [ring] to
 * `ReplicationServer`. No per-client copy of the world exists anywhere in this module.
 *
 * ## Movement is the server's, and only the server's
 *
 * [applyInput] is the only thing that moves an avatar, it takes a decoded [MoveInput], and it is
 * called from the server endpoint after that command came out of the server's own
 * `JitterBuffer`. There is no path from a client to a position: `ReplicationServer.onPacket`
 * accepts acks and input frames and has no branch that writes a replicated field, and nothing on
 * a client can reach this class at all.
 */
public class NetArena(
    /** The engine seed. Every draw in the arena and on the wire derives from it. */
    public val seed: Long,
) {

    private val definition = UdeaGameDef(
        modules = emptyList(),
        config = EngineConfig(seed = seed),
        entityCapacity = ENTITY_CAPACITY,
    )

    private val core: CoreModule = definition.core

    private val game = definition.build()

    /** The component registry both ends of the session share. */
    public val registry: ComponentRegistry = ComponentRegistry(
        listOf(
            fleksComponentType(
                NetAvatarReplicator,
                ComponentSchema.of(NetAvatarReplicator, "NetAvatar", NetAvatarReplicator.KINDS),
                NetAvatar,
            ) { NetAvatar() },
        ),
    )

    /** The authoritative world. */
    public val world: World get() = game.world

    /** Engine services: the clock the arena reads and the RNG the snapshot captures. */
    public val ctx: GameContext get() = game.ctx

    /** The one place a [NetId] becomes an entity. */
    public val netIds: NetIdIndex get() = core.netIds

    /** Baselines and rewind, in one structure (spec 3.1). */
    public val ring: SnapshotRing = SnapshotRing(registry)

    private val snapshots = SnapshotService(registry, game.world, game.ctx, core.netIds)

    private val simulation: WorldSimulation get() = game.simulation

    /** The tick the arena is about to simulate. */
    public val tick: Tick get() = game.ctx.clock.tick

    /** Spawns an avatar at ([x], [y]), moved by [owner]'s input. */
    public fun spawn(owner: Int, x: Float, y: Float): NetId {
        val entity = game.world.entity { it += NetAvatar(x, y, owner, game.ctx.clock.tick) }
        return core.netIds.allocate(entity)
    }

    /** The live component behind [netId]. */
    public fun avatar(netId: NetId): NetAvatar {
        val entity = core.netIds.resolveOrNull(netId) ?: error("$netId is not live in this arena")
        return with(game.world) { entity[NetAvatar] }
    }

    /**
     * Applies one decoded command to [netId]'s avatar.
     *
     * Displacement per tick rather than a velocity integrated over `dt`, because the arena has no
     * physics and the property under test is that *the command arrived and the server acted on
     * it* - a simpler rule makes a moved position easier for an agent to read as evidence.
     */
    public fun applyInput(netId: NetId, command: MoveInput) {
        val avatar = avatar(netId)
        avatar.x += command.moveX * UNITS_PER_TICK
        avatar.y += command.moveY * UNITS_PER_TICK
    }

    /** Steps the simulation one tick, then captures the world into [ring]. */
    public fun captureTick(): WorldSnapshot {
        simulation.step()
        val slot = ring.acquire()
        snapshots.captureInto(slot)
        ring.commit(slot)
        return slot
    }

    /** The newest committed capture, or null before the first tick. */
    public fun newest(): WorldSnapshot? = ring.newestTick()?.let(ring::nearestAtOrBefore)

    /** The newest capture at or before [target], or null when the ring holds nothing that old. */
    public fun stateAt(target: Tick): WorldSnapshot? = ring.nearestAtOrBefore(target)

    override fun toString(): String = "NetArena(seed=$seed, tick=${tick.value})"

    public companion object {

        /**
         * How far a fully deflected axis moves a body in one tick.
         *
         * A tenth of a world unit: at the engine's 60Hz that is six units a second, so a quarter
         * second of held input is unmistakably non-zero at the precision a tool result prints,
         * and a few hundred ticks still does not reach float imprecision.
         */
        public const val UNITS_PER_TICK: Float = 0.1f

        /** One avatar per client plus slack. A debug arena is not a lane. */
        private const val ENTITY_CAPACITY: Int = 64
    }
}
