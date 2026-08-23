package dev.wildware.udea.agent

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType
import dev.wildware.udea.agent.query.AgentComponentType
import dev.wildware.udea.agent.query.agentComponent
import dev.wildware.udea.core.fixtures.Vec2
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.replication.BitReader
import dev.wildware.udea.core.replication.BitWriter
import dev.wildware.udea.core.replication.ComponentTypeId
import dev.wildware.udea.core.replication.FieldMask
import dev.wildware.udea.core.replication.FieldStore
import dev.wildware.udea.core.replication.MaskOps
import dev.wildware.udea.core.replication.NoSuchFieldIndexException
import dev.wildware.udea.core.replication.Replicator

/**
 * Four replicated components and their hand-written `Replicator`s, for the agent tests.
 *
 * `udea-core`'s fixtures cannot be reused directly: `TransformReplicator` is the contract's
 * executable specification and its `Transform` is deliberately *not* a Fleks component, while
 * the query engine only means anything against components on entities in a real world.
 *
 * They are hand-written rather than generated for the same reason the contract's specification
 * is: `udea-codegen` will emit exactly this shape, and a test that measured the generator
 * against the generator's own output would measure nothing. Between them they cover the field
 * types the surface has to render - a lowered composite (`position.x`/`position.y`), a plain
 * float, an `Int`, and a `NetId`, which is the one an agent must be able to feed back in.
 *
 * The `agentWritable` masks are deliberately mixed: `@Net(agentWritable = false)` is the
 * default (spec 5), so a surface where everything is writable would never exercise the
 * distinction `describe_entity` exists to publish.
 */

// --- Transform -------------------------------------------------------------------------------

/** Where an entity is, and which way it faces. `rotation` is `@Sim`: it rewinds, it is not sent. */
internal class Transform(
    val position: Vec2 = Vec2(),
    var rotation: Float = 0f,
) : Component<Transform> {
    override fun type(): ComponentType<Transform> = Transform

    companion object : ComponentType<Transform>()
}

internal object TransformReplicator : Replicator<Transform> {
    const val POSITION_X = 0
    const val POSITION_Y = 1
    const val ROTATION = 2
    const val FIELD_COUNT = 3

    override val typeId: ComponentTypeId = ComponentTypeId(1)

    override val fieldNames: List<String> = listOf("position.x", "position.y", "rotation")

    override val netMask: FieldMask = MaskOps.of(POSITION_X, POSITION_Y)

    override val allMask: FieldMask = MaskOps.lowest(FIELD_COUNT)

    override fun capture(component: Transform, store: FieldStore, slot: Int) {
        store.setFloat(slot, POSITION_X, component.position.x)
        store.setFloat(slot, POSITION_Y, component.position.y)
        store.setFloat(slot, ROTATION, component.rotation)
    }

    override fun diff(store: FieldStore, slotA: Int, slotB: Int): FieldMask =
        floatDiff(store, slotA, slotB, FIELD_COUNT)

    override fun write(store: FieldStore, slot: Int, mask: FieldMask, out: BitWriter) {
        writeFloats(store, slot, mask, out, FIELD_COUNT)
    }

    override fun read(src: BitReader, store: FieldStore, slot: Int): FieldMask =
        readFloats(src, store, slot, FIELD_COUNT)

    override fun apply(store: FieldStore, slot: Int, component: Transform, mask: FieldMask) {
        if (MaskOps.test(mask, POSITION_X)) component.position.x = store.getFloat(slot, POSITION_X)
        if (MaskOps.test(mask, POSITION_Y)) component.position.y = store.getFloat(slot, POSITION_Y)
        if (MaskOps.test(mask, ROTATION)) component.rotation = store.getFloat(slot, ROTATION)
    }

    override fun getField(component: Transform, fieldIndex: Int): Any? = when (fieldIndex) {
        POSITION_X -> component.position.x
        POSITION_Y -> component.position.y
        ROTATION -> component.rotation
        else -> throw NoSuchFieldIndexException("Transform", fieldIndex, FIELD_COUNT)
    }

    override fun setField(component: Transform, fieldIndex: Int, value: Any?) {
        val float = requireNotNull(value as? Float) { "Transform fields are floats, got $value" }
        when (fieldIndex) {
            POSITION_X -> component.position.x = float
            POSITION_Y -> component.position.y = float
            ROTATION -> component.rotation = float
            else -> throw NoSuchFieldIndexException("Transform", fieldIndex, FIELD_COUNT)
        }
    }
}

// --- Health ----------------------------------------------------------------------------------

/** Current and maximum hit points. Both replicated; only `current` is agent-writable. */
internal class Health(
    var current: Float = 100f,
    var max: Float = 100f,
) : Component<Health> {
    override fun type(): ComponentType<Health> = Health

    companion object : ComponentType<Health>()
}

internal object HealthReplicator : Replicator<Health> {
    const val CURRENT = 0
    const val MAX = 1
    const val FIELD_COUNT = 2

    override val typeId: ComponentTypeId = ComponentTypeId(2)

    override val fieldNames: List<String> = listOf("current", "max")

    override val netMask: FieldMask = MaskOps.lowest(FIELD_COUNT)

    override val allMask: FieldMask = MaskOps.lowest(FIELD_COUNT)

    override fun capture(component: Health, store: FieldStore, slot: Int) {
        store.setFloat(slot, CURRENT, component.current)
        store.setFloat(slot, MAX, component.max)
    }

    override fun diff(store: FieldStore, slotA: Int, slotB: Int): FieldMask =
        floatDiff(store, slotA, slotB, FIELD_COUNT)

    override fun write(store: FieldStore, slot: Int, mask: FieldMask, out: BitWriter) {
        writeFloats(store, slot, mask, out, FIELD_COUNT)
    }

    override fun read(src: BitReader, store: FieldStore, slot: Int): FieldMask =
        readFloats(src, store, slot, FIELD_COUNT)

    override fun apply(store: FieldStore, slot: Int, component: Health, mask: FieldMask) {
        if (MaskOps.test(mask, CURRENT)) component.current = store.getFloat(slot, CURRENT)
        if (MaskOps.test(mask, MAX)) component.max = store.getFloat(slot, MAX)
    }

    override fun getField(component: Health, fieldIndex: Int): Any? = when (fieldIndex) {
        CURRENT -> component.current
        MAX -> component.max
        else -> throw NoSuchFieldIndexException("Health", fieldIndex, FIELD_COUNT)
    }

    override fun setField(component: Health, fieldIndex: Int, value: Any?) {
        val float = requireNotNull(value as? Float) { "Health fields are floats, got $value" }
        when (fieldIndex) {
            CURRENT -> component.current = float
            MAX -> component.max = float
            else -> throw NoSuchFieldIndexException("Health", fieldIndex, FIELD_COUNT)
        }
    }
}

// --- Team ------------------------------------------------------------------------------------

/** Which side an entity is on, and who it is currently allied to. */
internal class Team(
    var team: Int = 0,
    var ally: NetId = NetId.NONE,
) : Component<Team> {
    override fun type(): ComponentType<Team> = Team

    companion object : ComponentType<Team>()
}

internal object TeamReplicator : Replicator<Team> {
    const val TEAM = 0
    const val ALLY = 1
    const val FIELD_COUNT = 2

    override val typeId: ComponentTypeId = ComponentTypeId(3)

    override val fieldNames: List<String> = listOf("team", "ally")

    override val netMask: FieldMask = MaskOps.of(TEAM)

    override val allMask: FieldMask = MaskOps.lowest(FIELD_COUNT)

    override fun capture(component: Team, store: FieldStore, slot: Int) {
        store.setInt(slot, TEAM, component.team)
        store.setNetId(slot, ALLY, component.ally)
    }

    override fun diff(store: FieldStore, slotA: Int, slotB: Int): FieldMask {
        var mask = MaskOps.EMPTY
        if (store.getInt(slotA, TEAM) != store.getInt(slotB, TEAM)) mask = MaskOps.set(mask, TEAM)
        if (store.getNetId(slotA, ALLY) != store.getNetId(slotB, ALLY)) mask = MaskOps.set(mask, ALLY)
        return mask
    }

    override fun write(store: FieldStore, slot: Int, mask: FieldMask, out: BitWriter) {
        if (MaskOps.isEmpty(mask)) return
        MaskOps.writeTo(mask, out, FIELD_COUNT)
        if (MaskOps.test(mask, TEAM)) out.writeInt(store.getInt(slot, TEAM))
        if (MaskOps.test(mask, ALLY)) out.writeInt(store.getNetId(slot, ALLY).raw)
    }

    override fun read(src: BitReader, store: FieldStore, slot: Int): FieldMask {
        val mask = MaskOps.readFrom(src, FIELD_COUNT)
        if (MaskOps.test(mask, TEAM)) store.setInt(slot, TEAM, src.readInt())
        if (MaskOps.test(mask, ALLY)) store.setNetId(slot, ALLY, NetId.ofRaw(src.readInt()))
        return mask
    }

    override fun apply(store: FieldStore, slot: Int, component: Team, mask: FieldMask) {
        if (MaskOps.test(mask, TEAM)) component.team = store.getInt(slot, TEAM)
        if (MaskOps.test(mask, ALLY)) component.ally = store.getNetId(slot, ALLY)
    }

    override fun getField(component: Team, fieldIndex: Int): Any? = when (fieldIndex) {
        TEAM -> component.team
        ALLY -> component.ally
        else -> throw NoSuchFieldIndexException("Team", fieldIndex, FIELD_COUNT)
    }

    override fun setField(component: Team, fieldIndex: Int, value: Any?) {
        when (fieldIndex) {
            TEAM -> component.team = requireNotNull(value as? Int) { "Team.team is an Int, got $value" }
            ALLY -> component.ally = requireNotNull(value as? NetId) { "Team.ally is a NetId, got $value" }
            else -> throw NoSuchFieldIndexException("Team", fieldIndex, FIELD_COUNT)
        }
    }
}

// --- Champion --------------------------------------------------------------------------------

/** The marker a `with` filter selects on, carrying one field so it is a real component. */
internal class Champion(var level: Int = 1) : Component<Champion> {
    override fun type(): ComponentType<Champion> = Champion

    companion object : ComponentType<Champion>()
}

internal object ChampionReplicator : Replicator<Champion> {
    const val LEVEL = 0
    const val FIELD_COUNT = 1

    override val typeId: ComponentTypeId = ComponentTypeId(4)

    override val fieldNames: List<String> = listOf("level")

    override val netMask: FieldMask = MaskOps.lowest(FIELD_COUNT)

    override val allMask: FieldMask = MaskOps.lowest(FIELD_COUNT)

    override fun capture(component: Champion, store: FieldStore, slot: Int) {
        store.setInt(slot, LEVEL, component.level)
    }

    override fun diff(store: FieldStore, slotA: Int, slotB: Int): FieldMask =
        if (store.getInt(slotA, LEVEL) != store.getInt(slotB, LEVEL)) {
            MaskOps.single(LEVEL)
        } else {
            MaskOps.EMPTY
        }

    override fun write(store: FieldStore, slot: Int, mask: FieldMask, out: BitWriter) {
        if (MaskOps.isEmpty(mask)) return
        MaskOps.writeTo(mask, out, FIELD_COUNT)
        out.writeInt(store.getInt(slot, LEVEL))
    }

    override fun read(src: BitReader, store: FieldStore, slot: Int): FieldMask {
        val mask = MaskOps.readFrom(src, FIELD_COUNT)
        if (MaskOps.test(mask, LEVEL)) store.setInt(slot, LEVEL, src.readInt())
        return mask
    }

    override fun apply(store: FieldStore, slot: Int, component: Champion, mask: FieldMask) {
        if (MaskOps.test(mask, LEVEL)) component.level = store.getInt(slot, LEVEL)
    }

    override fun getField(component: Champion, fieldIndex: Int): Any? =
        if (fieldIndex == LEVEL) component.level else throw NoSuchFieldIndexException("Champion", fieldIndex, FIELD_COUNT)

    override fun setField(component: Champion, fieldIndex: Int, value: Any?) {
        if (fieldIndex != LEVEL) throw NoSuchFieldIndexException("Champion", fieldIndex, FIELD_COUNT)
        component.level = requireNotNull(value as? Int) { "Champion.level is an Int, got $value" }
    }
}

// --- shared helpers ---------------------------------------------------------------------------

/** Bit-identical float comparison, as `docs/contracts/replicator.md` requires of every `diff`. */
private fun floatDiff(store: FieldStore, slotA: Int, slotB: Int, fieldCount: Int): FieldMask {
    var mask = MaskOps.EMPTY
    for (field in 0 until fieldCount) {
        if (store.getFloat(slotA, field).toRawBits() != store.getFloat(slotB, field).toRawBits()) {
            mask = MaskOps.set(mask, field)
        }
    }
    return mask
}

private fun writeFloats(
    store: FieldStore,
    slot: Int,
    mask: FieldMask,
    out: BitWriter,
    fieldCount: Int,
) {
    if (MaskOps.isEmpty(mask)) return
    MaskOps.writeTo(mask, out, fieldCount)
    for (field in 0 until fieldCount) {
        if (MaskOps.test(mask, field)) out.writeFloat(store.getFloat(slot, field))
    }
}

private fun readFloats(src: BitReader, store: FieldStore, slot: Int, fieldCount: Int): FieldMask {
    val mask = MaskOps.readFrom(src, fieldCount)
    for (field in 0 until fieldCount) {
        if (MaskOps.test(mask, field)) store.setFloat(slot, field, src.readFloat())
    }
    return mask
}

// --- the agent-facing index -------------------------------------------------------------------

/** [Transform], with position writable and rotation not. */
internal fun transformAccess(): AgentComponentType = agentComponent(
    name = "Transform",
    replicator = TransformReplicator,
    componentType = Transform,
    agentWritableFields = setOf(TransformReplicator.POSITION_X, TransformReplicator.POSITION_Y),
)

/** [Health], with `current` writable and `max` not. */
internal fun healthAccess(): AgentComponentType = agentComponent(
    name = "Health",
    replicator = HealthReplicator,
    componentType = Health,
    agentWritableFields = setOf(HealthReplicator.CURRENT),
)

/** [Team], read-only: the spec 5 default. */
internal fun teamAccess(): AgentComponentType =
    agentComponent(name = "Team", replicator = TeamReplicator, componentType = Team)

/** [Champion], read-only. */
internal fun championAccess(): AgentComponentType =
    agentComponent(name = "Champion", replicator = ChampionReplicator, componentType = Champion)

/**
 * [Health] registered under a second name, so a bare field name can genuinely be ambiguous.
 *
 * Two components carrying `current` is what a game looks like the moment it grows a second
 * resource pool, and the resolution rule has to have an answer for it.
 */
internal fun transformAliasAccess(name: String): AgentComponentType =
    agentComponent(name = name, replicator = TransformReplicator, componentType = Transform)

/**
 * [Health] registered under a second name, so a bare field name can genuinely be ambiguous.
 */
internal fun agentComponentAlias(name: String): AgentComponentType =
    agentComponent(name = name, replicator = HealthReplicator, componentType = Health)
