package dev.wildware.udea.core.identity

/**
 * The identity of a simulated entity everywhere outside the Fleks world (spec 5,
 * "Entity identity").
 *
 * A Fleks `Entity` is a slot index into one particular world's entity service. It is
 * meaningful only inside that world, in that process, at that moment. The old code shipped
 * one across the wire in `EntityCreate.entity`, which meant one machine's slot index was
 * read as another machine's slot index. A [NetId] is the replacement, and it never leaves
 * `udea-core`'s control: nothing serialised, snapshotted or exposed to an agent tool may be
 * typed `Entity`.
 *
 * ## Bit layout
 *
 * ```
 *  31          24 23        16 15                     0
 * +--------------+------------+-----------------------+
 * |   reserved   | generation |         index         |
 * |   (8 bits)   |  (8 bits)  |       (16 bits)       |
 * +--------------+------------+-----------------------+
 * ```
 *
 * The layout is pinned by a golden test: changing it is a deliberate golden update, because
 * the wire format (Phase 3, `udea-net`) and every snapshot depend on it.
 *
 * [NONE] is the all-ones word (`-1`), which the reserved byte makes unreachable for any
 * valid id.
 *
 * ## Why a generation counter
 *
 * The index is dense and recycled, so without a generation a reference held across a free
 * would silently resolve to whatever entity now occupies the slot — a use-after-free that
 * presents as a gameplay bug on the wrong entity. The generation makes a stale reference
 * **detectable**: [NetIdIndex.resolveOrNull] returns `null` instead of aliasing. It is 8
 * bits, so a single index must be recycled 256 times before a stale id can alias again; the
 * FIFO free list in [NetIdIndex] is what makes reaching that in practice unlikely, because
 * a freed index goes to the back of the queue rather than being handed straight back out.
 */
@JvmInline
public value class NetId private constructor(public val raw: Int) : Comparable<NetId> {

    /** The dense slot index, `0 until` [MAX_INDICES]. */
    public val index: Int get() = raw and INDEX_MASK

    /** The recycle counter for [index], `0 until` [GENERATION_MODULUS]. */
    public val generation: Int get() = (raw ushr INDEX_BITS) and GENERATION_MASK

    /** True for [NONE], the id that names no entity. */
    public val isNone: Boolean get() = raw == NONE_RAW

    /**
     * Total order by index, tie-broken by generation.
     *
     * Snapshot capture and full writes iterate in this order so that two processes that
     * hold the same live set produce byte-identical output regardless of the order the ids
     * were allocated in. Within one live set indices are unique, so the tie-break never
     * fires; it exists only to make the ordering total.
     */
    override fun compareTo(other: NetId): Int {
        val byIndex = index.compareTo(other.index)
        return if (byIndex != 0) byIndex else generation.compareTo(other.generation)
    }

    override fun toString(): String = if (isNone) "NetId.NONE" else "NetId(#$index@$generation)"

    public companion object {
        public const val INDEX_BITS: Int = 16
        public const val GENERATION_BITS: Int = 8

        /** How many distinct indices exist: 65 536. */
        public const val MAX_INDICES: Int = 1 shl INDEX_BITS

        /** Generations wrap here: 256. */
        public const val GENERATION_MODULUS: Int = 1 shl GENERATION_BITS

        internal const val INDEX_MASK: Int = MAX_INDICES - 1
        internal const val GENERATION_MASK: Int = GENERATION_MODULUS - 1
        private const val NONE_RAW: Int = -1

        /** The id that names no entity. Resolves to `null` in every index. */
        public val NONE: NetId = NetId(NONE_RAW)

        /**
         * Packs [index] and [generation]. Both are range-checked: a caller that computes
         * either gets a typed failure rather than a silently truncated id.
         */
        public fun of(index: Int, generation: Int): NetId {
            require(index in 0 until MAX_INDICES) {
                "NetId index out of range: $index (0 until $MAX_INDICES)"
            }
            require(generation in 0 until GENERATION_MODULUS) {
                "NetId generation out of range: $generation (0 until $GENERATION_MODULUS)"
            }
            return NetId(index or (generation shl INDEX_BITS))
        }

        /**
         * Rebuilds an id from its packed word, as read off the wire or out of a
         * [dev.wildware.udea.core.replication.FieldStore].
         *
         * Any word with a non-zero reserved byte other than [NONE]'s is rejected, so a
         * corrupt or hostile packet cannot conjure an id whose bits mean something else in
         * a future layout.
         */
        public fun ofRaw(raw: Int): NetId {
            if (raw == NONE_RAW) return NONE
            require(raw ushr (INDEX_BITS + GENERATION_BITS) == 0) {
                "NetId raw word has reserved bits set: 0x${raw.toUInt().toString(16)}"
            }
            return NetId(raw)
        }
    }
}
