package dev.wildware.udea.net.wire

import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.replication.BitReader
import dev.wildware.udea.core.replication.BitWriter
import dev.wildware.udea.core.replication.FieldMask
import dev.wildware.udea.core.replication.MaskOps
import dev.wildware.udea.core.replication.Replicator
import dev.wildware.udea.core.replication.ComponentTypeId
import dev.wildware.udea.core.snapshot.ColumnarFieldStore
import dev.wildware.udea.core.snapshot.ComponentRegistry
import dev.wildware.udea.core.snapshot.FieldComparison
import dev.wildware.udea.core.snapshot.WorldFieldStore
import dev.wildware.udea.net.bits.MalformedBitStream
import dev.wildware.udea.net.bits.readVarInt
import dev.wildware.udea.net.bits.readZigZag
import dev.wildware.udea.net.bits.writeVarInt
import dev.wildware.udea.net.bits.writeZigZag

/**
 * What a snapshot section says happened to one entity.
 *
 * Creates and destroys ride the snapshot section rather than a reliable side channel, and that
 * is a deliberate design decision rather than a shortcut: because the section is delta-encoded
 * against an **acked** baseline, an unacked create is simply still absent from the baseline and
 * is re-sent next tick, automatically, until it lands. No retransmit bookkeeping, no
 * head-of-line blocking behind a lost reliable message.
 */
public enum class EntityOp(public val code: Int) {

    /** Full state: every `@Net` field, create-only fields included. */
    Create(0),

    /** Delta against the baseline. Create-only fields are stripped (issue #114). */
    Update(1),

    /** The entity no longer exists anywhere. */
    Destroy(2),

    /** The entity still exists but has left this client's relevancy set. */
    Leave(3),
    ;

    public companion object {

        private val byCode: Array<EntityOp> = arrayOf(Create, Update, Destroy, Leave)

        /** Width of the op field. Four ops, two bits, no room for a fifth without a version bump. */
        public const val OP_BITS: Int = 2

        public fun of(code: Int): EntityOp = byCode[code and 0b11]
    }
}

/**
 * Told what a [SnapshotReader] applied, so an ECS layer can push it onto live components.
 *
 * The reader itself only ever touches a [ReplicaStore]. Getting from there onto a Fleks world
 * is `Replicator.apply`, which needs a live component instance and therefore a world — a
 * dependency this module deliberately does not take on. This callback is the seam.
 */
public fun interface SnapshotApplySink {

    /**
     * @param componentIndex dense registry index, or `-1` for a whole-entity op.
     * @param mask fields that were written, empty for [EntityOp.Destroy] and [EntityOp.Leave].
     */
    public fun applied(netId: NetId, op: EntityOp, componentIndex: Int, mask: FieldMask)
}

/**
 * The states a client may be holding for one entity, which is what a delta has to be safe against.
 *
 * Not "the baseline", singular. A client's store moves when it *applies* a packet and the
 * server's baseline moves when it sees one *acknowledged*, so between them sits a round trip's
 * worth of sends that may or may not have landed. Every one of those is a state the client could
 * be sitting on right now, and a delta that is only correct against the acked one is only usually
 * correct. Holding them all is what turns "usually" into "provably".
 *
 * Rows, not copies: each member is a row of a snapshot the ring already holds (spec 3.1), so a
 * set of eighteen in-flight states costs eighteen ints and no world state at all.
 */
public class BaselineSet {

    private var stores = arrayOfNulls<WorldFieldStore>(INITIAL)
    private var rows = IntArray(INITIAL)

    /** How many baselines are in the set. */
    public var size: Int = 0
        private set

    /** Empties the set for the next entity. Keeps the buffers. */
    public fun clear() {
        size = 0
    }

    /** Adds [row] of [store]. [WorldFieldStore.NO_ROW] is a legal member: "absent back then". */
    public fun add(store: WorldFieldStore, row: Int) {
        if (size == stores.size) {
            stores = stores.copyOf(size * 2)
            rows = rows.copyOf(size * 2)
        }
        stores[size] = store
        rows[size] = row
        size++
    }

    /** The store of baseline [position]. */
    public fun storeAt(position: Int): WorldFieldStore = stores[position]!!

    /** The row of baseline [position], or [WorldFieldStore.NO_ROW]. */
    public fun rowAt(position: Int): Int = rows[position]

    private companion object {
        private const val INITIAL: Int = 8
    }
}

/**
 * Writes the delta snapshot section: self-describing, length-prefixed, delta-encoded.
 *
 * ## Layout
 *
 * ```
 * repeat:
 *   zigzag indexDelta        -- NetId.index minus the previous entity's; 0 terminates
 *   u8     generation
 *   u2     op
 *   if op is Create or Update:
 *     repeat:
 *       varint typeIdDelta   -- ComponentTypeId minus the previous one; 0 terminates
 *       <Replicator.write>   -- the field mask, then the selected fields
 *                            -- an all-zero field mask is "this component was removed"
 * ```
 *
 * ## The removal record spends a code point that was already unreachable
 *
 * A component the server drops has to be sayable, or a client keeps it for ever - which is what
 * `moba` did with `Combatant` on a dead unit. It is spelled as a field mask with no bits set
 * rather than a fifth [EntityOp], because the writer skips a component it has nothing to say
 * about instead of emitting an empty mask for it: the encoding was unreachable, so spending it
 * costs no bits, moves no existing field, and needs no `op` widening (two bits, four ops, full).
 *
 * Every one of those pieces is a direct answer to `PacketUtil.kt:122-129` and `:168-181`, which
 * streamed a Fleks `ComponentBag` in bag order with **no type tag and no length prefix**. A
 * client whose component set differed did not fail: it read `Attributes` bytes into
 * `Transform.position` and carried on. Here a component is named by an id from the one
 * project-wide assignment, the field mask says which fields follow, and the frame around the
 * section says how long the whole thing is — so a disagreement is a decode error at a named
 * component, not a plausible-looking wrong number.
 *
 * ## Terminators rather than counts
 *
 * Both lists end with a zero delta instead of being count-prefixed. A count has to be known
 * before the list is written, which for a **budgeted** packer it is not: how many entities fit
 * is discovered by writing them until the datagram is full (issue #107). A terminator costs one
 * byte and removes the need either to buffer the section twice or to back-patch a count.
 *
 * ## Why the entity delta is signed
 *
 * A packer orders entities by *priority*, not by id (issue #107), so the index sequence in a
 * datagram goes up and down. An unsigned ascending delta would force the packer either to sort
 * its selection — which it cannot do before knowing what fits — or to give up prioritised
 * packing. Zig-zag folding costs the same byte for a small step in either direction. The chain
 * starts at `-1`, which is what keeps a delta of zero free to mean "section ends": no two
 * entities in one section share an index, so a zero step is otherwise unreachable.
 */
public class SnapshotWriter(

    /** The component registry both peers share. */
    public val registry: ComponentRegistry,
) {

    private val single = BaselineSet()

    private var previousIndex = NO_PREVIOUS

    /** Starts a section. Resets the delta chain. */
    public fun begin() {
        previousIndex = NO_PREVIOUS
    }

    /**
     * The delta chain's current position, to be handed back to [rewindTo].
     *
     * A budgeted packer writes an entity and only then discovers it did not fit, at which point
     * it truncates the bit buffer. Truncating the bytes is not enough: the chain would still be
     * standing at the entity that was thrown away, and every subsequent delta would be computed
     * from an index the receiver never sees. This pair keeps the two rollbacks together.
     */
    public fun cursor(): Int = previousIndex

    /** Restores the delta chain to a position returned by [cursor]. */
    public fun rewindTo(cursor: Int) {
        previousIndex = cursor
    }

    /**
     * Writes a full-state `Create` for [row] of [current].
     *
     * Uses [LifetimePolicy.fullMask], so `@Net(lifetime = OnCreate)` fields are present here and
     * only here. Writes every component the entity carries whose `@Net` mask is non-empty.
     *
     * @return the number of components written.
     */
    public fun writeCreate(out: BitWriter, current: WorldFieldStore, row: Int): Int {
        single.clear()
        return writeEntity(out, current, row, EntityOp.Create, single)
    }

    /**
     * Writes a delta `Update` for [row] against [baselineRow] of [baseline].
     *
     * Emits nothing at all — not even an entity header — when no replicated field differs, which
     * is what makes a quiet entity free. A component present now and absent from the baseline is
     * written in full, because the baseline holds no bits for it to be a delta against.
     *
     * @return the number of components written; zero means nothing was emitted.
     */
    public fun writeUpdate(
        out: BitWriter,
        current: WorldFieldStore,
        row: Int,
        baseline: WorldFieldStore,
        baselineRow: Int,
    ): Int {
        single.clear()
        single.add(baseline, baselineRow)
        return writeEntity(out, current, row, EntityOp.Update, single)
    }

    /**
     * Writes a delta `Update` for [row] that is correct against **every** state in [baselines].
     *
     * One baseline is not enough and that is the whole convergence fix. A delta names only the
     * fields that differ from the state it was diffed against, so it is only safe to apply to
     * that exact state — and the state a client is holding is the newest packet it *applied*,
     * while the server's baseline is the newest packet it saw *acknowledged*. Those are a round
     * trip apart. A field that changes and changes back inside that window equals the baseline
     * again when the packer looks at it, so it is omitted, and the client keeps the intermediate
     * value for ever.
     *
     * The set of states the client can be holding is small, known and named by [baselines]: the
     * acked baseline, plus every send since that has not been acknowledged. Writing the union of
     * the differences against all of them means that whichever one the client actually has, every
     * field that could be wrong is overwritten with an absolute value, and every field not
     * written is provably already right. There is no probability in it and no repair message.
     *
     * A component absent now but present in any of [baselines] is written as a **removal**
     * record, which is how a component the server dropped stops existing on a client.
     *
     * @return the number of component records written; zero means nothing was emitted.
     */
    public fun writeUpdate(
        out: BitWriter,
        current: WorldFieldStore,
        row: Int,
        baselines: BaselineSet,
    ): Int = writeEntity(out, current, row, EntityOp.Update, baselines)

    /** Writes a [EntityOp.Destroy] or [EntityOp.Leave] record for [netId]. */
    public fun writeRemoval(out: BitWriter, netId: NetId, op: EntityOp) {
        require(op == EntityOp.Destroy || op == EntityOp.Leave) { "$op is not a removal" }
        writeEntityHeader(out, netId, op)
    }

    /** Closes the section. */
    public fun end(out: BitWriter) {
        out.writeZigZag(0)
    }

    public companion object {

        /** The index the delta chain starts from, so an entity at index 0 still encodes non-zero. */
        public const val NO_PREVIOUS: Int = -1
    }

    private fun writeEntity(
        out: BitWriter,
        current: WorldFieldStore,
        row: Int,
        op: EntityOp,
        baselines: BaselineSet,
    ): Int {
        val netId = current.netIdAt(row)
        var written = 0
        var previousTypeId = -1
        var headerWritten = false
        for (componentIndex in 0 until registry.size) {
            val replicator = registry.typeAt(componentIndex).replicator
            val present = current.isPresent(row, componentIndex)

            if (!present) {
                // Present in a state the client may be holding and gone now: say so, or the
                // client keeps a component the server has dropped for the rest of the session.
                if (op == EntityOp.Create || !anyBaselineHas(baselines, componentIndex)) continue
                if (!headerWritten) {
                    writeEntityHeader(out, netId, op)
                    headerWritten = true
                }
                out.writeVarInt(replicator.typeId.raw - previousTypeId)
                previousTypeId = replicator.typeId.raw
                MaskOps.writeTo(MaskOps.EMPTY, out, replicator.fieldNames.size)
                written++
                continue
            }

            val slot = current.componentSlotAt(row, componentIndex)
            val store = current.storeAt(componentIndex)
            val mask = if (op == EntityOp.Create || !allBaselinesHave(baselines, componentIndex)) {
                // At least one state the client might hold has no bits for this component, so a
                // delta would be a delta against nothing. Full mask, whatever the entity's op is.
                LifetimePolicy.fullMask(replicator)
            } else {
                val delta = LifetimePolicy.deltaMask(replicator)
                var changed = MaskOps.EMPTY
                for (position in 0 until baselines.size) {
                    val baseline = baselines.storeAt(position)
                    val baselineRow = baselines.rowAt(position)
                    changed = MaskOps.or(
                        changed,
                        diffAcross(
                            replicator,
                            store,
                            slot,
                            baseline.storeAt(componentIndex),
                            baseline.componentSlotAt(baselineRow, componentIndex),
                        ),
                    )
                    if (MaskOps.containsAll(changed, delta)) break
                }
                MaskOps.and(changed, delta)
            }
            if (MaskOps.isEmpty(mask)) continue

            if (!headerWritten) {
                writeEntityHeader(out, netId, op)
                headerWritten = true
            }
            out.writeVarInt(replicator.typeId.raw - previousTypeId)
            previousTypeId = replicator.typeId.raw
            replicator.write(store, slot, mask, out)
            written++
        }
        if (headerWritten) out.writeVarInt(0)
        return written
    }

    /** Whether every baseline in [baselines] carries [componentIndex]. False for an empty set. */
    private fun allBaselinesHave(baselines: BaselineSet, componentIndex: Int): Boolean {
        if (baselines.size == 0) return false
        for (position in 0 until baselines.size) {
            val row = baselines.rowAt(position)
            if (row == WorldFieldStore.NO_ROW) return false
            if (!baselines.storeAt(position).isPresent(row, componentIndex)) return false
        }
        return true
    }

    /** Whether any baseline in [baselines] carries [componentIndex]. */
    private fun anyBaselineHas(baselines: BaselineSet, componentIndex: Int): Boolean {
        for (position in 0 until baselines.size) {
            val row = baselines.rowAt(position)
            if (row == WorldFieldStore.NO_ROW) continue
            if (baselines.storeAt(position).isPresent(row, componentIndex)) return true
        }
        return false
    }

    private fun writeEntityHeader(out: BitWriter, netId: NetId, op: EntityOp) {
        val delta = netId.index - previousIndex
        require(delta != 0) {
            "$netId repeats the previous entity in this section; a zero index step is the " +
                "section terminator and no entity may encode as one"
        }
        out.writeZigZag(delta)
        out.writeBits(netId.generation, NetId.GENERATION_BITS)
        out.writeBits(op.code, EntityOp.OP_BITS)
        previousIndex = netId.index
    }

    /**
     * Fields that differ between two slots that live in **different stores**.
     *
     * `Replicator.diff` compares two slots of one store, which is right for the snapshot ring
     * (capture then diff against the previous slot) and wrong here: current and baseline are two
     * ring entries, each with its own columns. Comparison goes through
     * `ColumnarFieldStore.fieldEquals`, whose bitwise semantics `Replicator.diff` is contractually
     * required to match — so the two agree about `NaN` and about `-0.0`, and a delta converges.
     */
    private fun diffAcross(
        replicator: Replicator<*>,
        store: ColumnarFieldStore,
        slot: Int,
        baselineStore: ColumnarFieldStore,
        baselineSlot: Int,
    ): FieldMask {
        var mask = MaskOps.EMPTY
        val fields = replicator.fieldNames.size
        for (field in 0 until fields) {
            if (!store.fieldEquals(slot, baselineStore, baselineSlot, field, FieldComparison.Bitwise)) {
                mask = MaskOps.set(mask, field)
            }
        }
        return mask
    }
}

/**
 * Reads a section written by [SnapshotWriter] into a [ReplicaStore].
 *
 * Every failure is loud. An `Update` for an entity the store does not hold, a component id this
 * build does not know, a generation that does not match — each throws [MalformedBitStream]
 * rather than being skipped, because the alternative is the old behaviour: keep going and hold
 * a world that is quietly wrong.
 */
public class SnapshotReader(

    /** The component registry both peers share. */
    public val registry: ComponentRegistry,
) {

    /**
     * Applies the whole section to [into].
     *
     * @return how many entity records were read.
     */
    public fun read(src: BitReader, into: ReplicaStore, sink: SnapshotApplySink = NO_SINK): Int {
        require(into.registry === registry) { "reader and store disagree about the component registry" }
        var previousIndex = SnapshotWriter.NO_PREVIOUS
        var entities = 0
        while (true) {
            val indexDelta = src.readZigZag()
            if (indexDelta == 0) return entities
            val index = previousIndex + indexDelta
            if (index < 0 || index >= NetId.MAX_INDICES) {
                throw MalformedBitStream("snapshot names NetId index $index, over the ${NetId.MAX_INDICES} limit")
            }
            previousIndex = index
            val generation = src.readBits(NetId.GENERATION_BITS)
            val op = EntityOp.of(src.readBits(EntityOp.OP_BITS))
            val netId = NetId.of(index, generation)
            entities++

            when (op) {
                EntityOp.Destroy, EntityOp.Leave -> {
                    into.destroy(netId)
                    sink.applied(netId, op, NO_COMPONENT, MaskOps.EMPTY)
                }

                // A Create is a full state, and it is also the recovery a client gets when its
                // baseline aged out of the ring. Dropping the row first is what makes it mean
                // *this and nothing else*: keeping the old row would leave components the entity
                // no longer carries attached to it, which is the same hole a removal record
                // closes for the delta path.
                EntityOp.Create -> {
                    into.destroy(netId)
                    readComponents(src, into, netId, into.createRow(netId), op, sink)
                }

                EntityOp.Update -> {
                    val row = into.rowOf(netId)
                    if (row == ReplicaStore.ABSENT) {
                        throw MalformedBitStream(
                            "snapshot carries an Update for $netId, which this client does not " +
                                "hold; the baseline the packet names does not match the state it " +
                                "was applied to",
                        )
                    }
                    readComponents(src, into, netId, row, op, sink)
                }
            }
        }
    }

    private fun readComponents(
        src: BitReader,
        into: ReplicaStore,
        netId: NetId,
        row: Int,
        op: EntityOp,
        sink: SnapshotApplySink,
    ) {
        var previousTypeId = -1
        while (true) {
            val typeDelta = src.readVarInt()
            if (typeDelta == 0) return
            val rawTypeId = previousTypeId + typeDelta
            previousTypeId = rawTypeId
            val typeId = ComponentTypeId(rawTypeId)
            if (typeId !in registry) {
                throw MalformedBitStream(
                    "snapshot names $typeId on $netId, which this build has no component type " +
                        "for; the peers are not speaking the same protocol",
                )
            }
            val componentIndex = registry.indexOf(typeId)
            val replicator = registry.typeAt(componentIndex).replicator
            val slot = into.claimSlot(row, componentIndex)
            val mask = replicator.read(src, into.storeAt(componentIndex), slot)
            // An all-zero field mask is the component removal record. It is free rather than a
            // fifth `EntityOp` because the writer never emits an empty mask for a component that
            // is still there - it skips it - so the code point was already unreachable, and
            // spending it costs no bits and does not move a single existing field on the wire.
            if (MaskOps.isEmpty(mask)) into.releaseSlot(row, componentIndex)
            sink.applied(netId, op, componentIndex, mask)
        }
    }

    public companion object {

        /** [SnapshotApplySink.applied]'s `componentIndex` for a whole-entity op. */
        public const val NO_COMPONENT: Int = -1

        private val NO_SINK = SnapshotApplySink { _, _, _, _ -> }
    }
}
