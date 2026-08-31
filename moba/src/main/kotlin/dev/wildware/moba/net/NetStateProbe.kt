package dev.wildware.moba.net

import com.github.quillraven.fleks.World
import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.moba.lane.LaneCreep
import dev.wildware.moba.level.GameUnit
import dev.wildware.udea.core.replication.MaskOps
import dev.wildware.udea.core.snapshot.ColumnarFieldStore
import dev.wildware.udea.core.snapshot.ComponentRegistry
import dev.wildware.udea.core.snapshot.FieldKind
import dev.wildware.udea.core.snapshot.WorldFieldStore
import dev.wildware.udea.net.wire.VisibilityPolicy

/**
 * "Do these three processes hold the same world?", answered as one number.
 *
 * ## Why this is not [dev.wildware.udea.core.snapshot.WorldHasher]
 *
 * `WorldHasher` folds **every** captured field, which is `Replicator.allMask` - `@Net` and
 * `@Sim` together. That is exactly right for a determinism gate over two runs of the *same*
 * simulation, and exactly wrong for a server-versus-client agreement check, because `@Sim`
 * fields are by definition not on the wire: `Player.moveX` is the client's own input,
 * `Abilities.instances` is server-side activation bookkeeping. Hashing those would report a
 * desync on every tick of a perfectly converged session and hide any real one under the noise.
 *
 * So this folds the `@Net` set and only the `@Net` set - the closure of what replication
 * actually promises to deliver. A difference here is a genuine desync.
 *
 * ## Minus the owner-only fields, since issue #167
 *
 * `@Net(visibility = OwnerOnly)` narrowed what that promise is. A champion's `Inventory` is
 * replicated to the connection that drives it and stripped from every other client's packet, so
 * a server-versus-client fold that included it reports a difference on **every** champion the
 * comparing client does not own, in a perfectly converged session - which is the `@Sim` mistake
 * in a new place. What every relevant client is promised is `netMask and ownerOnlyMask.inv()`,
 * and that is what folds here.
 *
 * The narrowing is real and worth stating rather than hiding: this number no longer says
 * anything about whether an owner received its own private fields. That claim needs to know who
 * owns what, which this has no way to be told, so it is made where the ownership is - `moba`'s
 * `InventoryVisibilityTest` and `udea-net`'s `OwnerOnlyVisibilityTest`, both against live
 * clients.
 *
 * ## Canonical, not bitwise
 *
 * `-0.0f` folds as `0.0f` and every `NaN` folds as one value, matching
 * `FieldComparison.Canonical`. Quantisation means a client's float is the server's float run
 * through a fixed-point codec and back, so two peers can hold values that are equal and not
 * bit-identical; a bitwise fold would call that a desync.
 *
 * FNV-1a, for the reason `WorldHasher` gives: eight lines, no table, no allocation, and no
 * platform-dependent intrinsic, so the number means the same thing on two machines.
 */
public object NetStateProbe {

    private const val OFFSET_BASIS: Long = -0x340d631b7bdddcdbL
    private const val PRIME: Long = 0x100000001b3L

    /** What a `null` object field folds as. Any fixed value would do; this one is not a hash. */
    private const val NULL_OBJECT: Long = 0x5AFE_0000_0000_0001L

    /** Every `NaN` folds as this, so two peers holding different NaN payloads still agree. */
    private const val CANONICAL_NAN: Long = 0x7FC0_0000L

    /**
     * Folds every `@Net` field of every entity in [fields], in ascending [dev.wildware.udea.core.identity.NetId] order.
     *
     * Row order is not an implementation detail here: capture appends in ascending id
     * (`NetIdIndex.forEachLive`), so two processes holding the same live set produce the same
     * row order however their free lists churned - which is what makes this comparable at all
     * rather than an allocation-order detector.
     */
    public fun netHash(fields: WorldFieldStore): Long {
        val registry = fields.registry
        var hash = fold(OFFSET_BASIS, fields.rowCount.toLong())
        for (row in 0 until fields.rowCount) hash = foldRow(hash, fields, row, registry, ALL)
        return hash
    }

    /**
     * Every component. The default [unitHash] filter, named so a caller narrowing the fold has
     * to say so at the call site rather than by passing a lambda that looks like a detail.
     */
    public val ALL: (String) -> Boolean = { true }

    /** One entity: its id, then every `@Net` field of every component [include] admits. */
    private fun foldRow(
        seed: Long,
        fields: WorldFieldStore,
        row: Int,
        registry: ComponentRegistry,
        include: (String) -> Boolean,
    ): Long {
        var hash = fold(seed, fields.netIdAt(row).raw.toLong())
        for (component in 0 until registry.size) {
            if (!fields.isPresent(row, component)) continue
            val type = registry.typeAt(component)
            // What every relevant client is promised, which since issue #167 is not the whole
            // `@Net` set: an owner-only field reaches one connection, so folding it would report
            // a desync against every client that is not that one.
            val mask = VisibilityPolicy.visibleMask(type.replicator, recipientOwnsEntity = false)
            if (MaskOps.isEmpty(mask)) continue
            if (!include(type.componentClass.simpleName ?: "")) continue
            val schema = type.schema
            val store = fields.storeAt(component)
            val slot = fields.componentSlotAt(row, component)
            hash = fold(hash, schema.typeId.raw.toLong())
            for (field in 0 until schema.fieldCount) {
                if (!MaskOps.test(mask, field)) continue
                hash = fold(hash, bitsOf(store, schema.kindOf(field), slot, field))
            }
        }
        return hash
    }

    /**
     * [netHash] restricted to the entities that carry [GameUnit]: the 27-unit battle itself.
     *
     * The headline claim, and separated from the whole-world fold for a reason that is a property
     * of the protocol rather than a convenience. `ReplicationServer` will not put a create for a
     * **recycled** `NetId` index in the same section as the `Destroy` it replaces - one section
     * addresses each index once, and a client that saw the create before the destroy would delete
     * the entity it had just been given - so a recycled index waits exactly one acknowledgement.
     * `moba` recycles indices constantly, because projectiles spawn and die every few ticks, so at
     * any given tick a whole-world fold is very likely to be one short. The units are not
     * recycled inside a match, so this number is comparable tick for tick.
     */
    public fun unitHash(fields: WorldFieldStore, include: (String) -> Boolean = ALL): Long {
        val registry = fields.registry
        val unit = (0 until registry.size).firstOrNull {
            registry.typeAt(it).componentClass == GameUnit::class
        } ?: error("this registry has no GameUnit; the battle cannot be identified")
        var hash = OFFSET_BASIS
        var rows = 0L
        var body = OFFSET_BASIS
        for (row in 0 until fields.rowCount) {
            if (!fields.isPresent(row, unit)) continue
            rows++
            body = foldRow(body, fields, row, registry, include)
        }
        hash = fold(hash, rows)
        return fold(hash, body)
    }

    /**
     * How many roster [GameUnit]s this world holds. The headline "27" of the example's battle.
     *
     * [LaneCreep] is excluded, and that is a correctness fix rather than tidiness. A creep also
     * carries [GameUnit], and `LaneModule` fields a wave every six hundred ticks and lets it die
     * on the way down the lane - so the number of creeps alive is a function of the tick. A
     * client and a server sample this at *different* ticks by construction (that is what a
     * network is), so a census that counted creeps would compare two different moments and report
     * a disagreement that is really a wave landing between two reads. `MobaUdpTwoProcessTest`
     * found exactly that: the client held 30 where the level seeds 28.
     *
     * The creeps are **not** excused from the proof. [unitHash] folds every row carrying
     * [GameUnit], creeps included, and that hash is compared client against server - so a creep
     * that replicated wrongly still fails. What is excluded here is only the count, which is the
     * one number a moving population cannot make comparable.
     */
    public fun unitCount(world: World): Int =
        world.family { all(GameUnit).none(LaneCreep) }.entities.size

    /** How many entities carry a replicated component at all, for a fuller census. */
    public fun entityCount(fields: WorldFieldStore): Int = fields.rowCount

    /**
     * One field's value as bits, through the public typed accessors.
     *
     * `ColumnarFieldStore.hashableBits` does exactly this and is `internal` to `udea-core`, so
     * this is the same fold written from outside the module. An object field folds its
     * `hashCode`, which is only meaningful because `ColumnarFieldStore.setObject` refuses any
     * value that is not a `StableHash` or a primitive - a field whose hash varied per process
     * could never have been put in the column in the first place.
     */
    private fun bitsOf(store: ColumnarFieldStore, kind: FieldKind, slot: Int, field: Int): Long =
        when (kind) {
            FieldKind.Bool -> if (store.getBoolean(slot, field)) 1L else 0L
            FieldKind.Int -> store.getInt(slot, field).toLong()
            FieldKind.Long -> store.getLong(slot, field)
            FieldKind.Float -> canonicalFloatBits(store.getFloat(slot, field))
            FieldKind.NetId -> store.getNetId(slot, field).raw.toLong()
            FieldKind.Tick -> store.getTick(slot, field).value
            FieldKind.Object -> store.getObject(slot, field)?.hashCode()?.toLong() ?: NULL_OBJECT
        }

    private fun canonicalFloatBits(value: Float): Long = when {
        value.isNaN() -> CANONICAL_NAN
        value == 0f -> 0L
        else -> value.toRawBits().toLong() and 0xFFFF_FFFFL
    }

    private fun fold(hash: Long, value: Long): Long {
        var result = hash
        var remaining = value
        repeat(Long.SIZE_BYTES) {
            result = (result xor (remaining and 0xFFL)) * PRIME
            remaining = remaining ushr 8
        }
        return result
    }

    /**
     * The first [limit] `@Net` fields on which [left] and [right] disagree.
     *
     * What a bare hash cannot give you. A number that differs sends its reader to a debugger;
     * `#14@0 Position.x 12.5 vs 12.0` sends them to the system that wrote it. Entities present on
     * one side and not the other are reported as such rather than skipped, because a missing
     * create and a wrong field are different defects with the same hash symptom.
     */
    public fun differences(
        left: WorldFieldStore,
        right: WorldFieldStore,
        limit: Int = 12,
    ): List<String> {
        val out = ArrayList<String>()
        val registry = left.registry
        for (row in 0 until left.rowCount) {
            if (out.size >= limit) break
            val netId = left.netIdAt(row)
            val other = right.rowOf(netId)
            if (other == WorldFieldStore.NO_ROW) {
                out += "$netId is on the left and missing from the right"
                continue
            }
            for (component in 0 until registry.size) {
                val type = registry.typeAt(component)
                val mask = type.replicator.netMask
                if (MaskOps.isEmpty(mask)) continue
                val here = left.isPresent(row, component)
                val there = right.isPresent(other, component)
                val name = type.schema.typeName
                if (here != there) {
                    out += "$netId $name present=$here vs $there"
                    continue
                }
                if (!here) continue
                val schema = type.schema
                val leftSlot = left.componentSlotAt(row, component)
                val rightSlot = right.componentSlotAt(other, component)
                for (field in 0 until schema.fieldCount) {
                    if (!MaskOps.test(mask, field)) continue
                    val a = bitsOf(left.storeAt(component), schema.kindOf(field), leftSlot, field)
                    val b = bitsOf(right.storeAt(component), schema.kindOf(field), rightSlot, field)
                    if (a == b) continue
                    out += "$netId $name.${schema.nameOf(field)} " +
                        "${left.storeAt(component).valueAt(leftSlot, field)} vs " +
                        "${right.storeAt(component).valueAt(rightSlot, field)}"
                }
            }
        }
        for (row in 0 until right.rowCount) {
            if (out.size >= limit) break
            val netId = right.netIdAt(row)
            if (left.rowOf(netId) == WorldFieldStore.NO_ROW) {
                out += "$netId is on the right and missing from the left"
            }
        }
        return out
    }

    /** Names the components this hash actually covers, for a report that has to be honest. */
    public fun coveredComponents(registry: ComponentRegistry): List<String> =
        (0 until registry.size)
            .map { registry.typeAt(it) }
            .filter { MaskOps.isNotEmpty(it.replicator.netMask) }
            .map { it.schema.typeName }
}
