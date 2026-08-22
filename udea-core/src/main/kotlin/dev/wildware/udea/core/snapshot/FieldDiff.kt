package dev.wildware.udea.core.snapshot

import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.replication.ComponentTypeId

/**
 * Where two [WorldFieldStore]s disagree, as a pooled list of `(netId, componentType, field)`.
 *
 * This is the primitive behind `net.desync_report(tick)` and behind [DivergenceReport]: it is
 * what lets a mismatch be reported as *this entity's this field* rather than as two hashes
 * that are not equal. Spec 3.1 is explicit that a desync report is a field-by-field
 * `FieldStore` comparison and not a byte diff, and this is the field-by-field part.
 *
 * ## Why a `NetId` and not a slot
 *
 * A slot is a row index inside one store. A diff spans two stores, whose rows line up only
 * when their populations happen to match, so a slot pair is ambiguous exactly when it matters
 * most — when one side has an entity the other does not. A [NetId] means the same entity in
 * both, and it is what a divergence report, a tool call and a packet all speak anyway
 * (spec 5, "Entity identity"). The row is recoverable through [WorldFieldStore.rowOf].
 *
 * ## Pooled
 *
 * Three parallel `IntArray`s, grown by doubling and reused across calls. [clear] keeps them.
 * A diff walked once per desync report would not need this; a diff walked by a delta encoder
 * once per client per tick does.
 */
public class FieldDiff(initialCapacity: Int = DEFAULT_CAPACITY) {

    init {
        require(initialCapacity > 0) { "initialCapacity must be positive, was $initialCapacity" }
    }

    private var netIds = IntArray(initialCapacity)
    private var typeIds = IntArray(initialCapacity)
    private var fields = IntArray(initialCapacity)

    /** Entries recorded since the last [clear]. */
    public var size: Int = 0
        private set

    /** True when the two stores agreed about everything. */
    public val isEmpty: Boolean get() = size == 0

    /** The entity entry [index] is about. */
    public fun netIdAt(index: Int): NetId = NetId.ofRaw(netIds[checked(index)])

    /** The component type entry [index] is about. */
    public fun typeIdAt(index: Int): ComponentTypeId = ComponentTypeId(typeIds[checked(index)])

    /**
     * The lowered field index entry [index] is about, or [PRESENCE] when the disagreement is
     * that the component exists on one side and not the other.
     */
    public fun fieldAt(index: Int): Int = fields[checked(index)]

    /** True when entry [index] reports a presence difference rather than a value difference. */
    public fun isPresenceAt(index: Int): Boolean = fieldAt(index) == PRESENCE

    /** Drops every entry and keeps the backing arrays. */
    public fun clear() {
        size = 0
    }

    /** Appends one disagreement, growing the backing arrays by doubling if it has to. */
    internal fun add(netId: NetId, typeId: ComponentTypeId, field: Int) {
        if (size == netIds.size) {
            val capacity = netIds.size * 2
            netIds = netIds.copyOf(capacity)
            typeIds = typeIds.copyOf(capacity)
            fields = fields.copyOf(capacity)
        }
        netIds[size] = netId.raw
        typeIds[size] = typeId.raw
        fields[size] = field
        size++
    }

    private fun checked(index: Int): Int {
        require(index in 0 until size) { "diff entry out of range: $index (0 until $size)" }
        return index
    }

    override fun toString(): String = "FieldDiff($size difference(s))"

    public companion object {
        /**
         * The field index that means "this component is on one side and not the other".
         *
         * Negative so it can never collide with a real lowered field index, and named so a
         * reader of a divergence report is not left wondering what field -1 is.
         */
        public const val PRESENCE: Int = -1

        /** A busy tick's worth of differences without a regrow. */
        private const val DEFAULT_CAPACITY: Int = 64
    }
}
