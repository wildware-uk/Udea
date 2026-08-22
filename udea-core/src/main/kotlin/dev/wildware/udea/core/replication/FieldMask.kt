package dev.wildware.udea.core.replication

/**
 * The set of component fields a [Replicator] operation applies to.
 *
 * ## The extension point
 *
 * A [FieldMask] is *opaque*. Its storage is `internal`, there are no bitwise operators on
 * it, and no code outside `udea-core` can see that it is currently one `Long`. Every
 * operation goes through [MaskOps]. That is deliberate and it is the mitigation for the
 * named risk in spec 7: `Replicator<T>` couples four modules, and the extension everyone
 * can already see coming is a component with more than 64 replicated fields, which forces
 * `Long` to `LongArray`. Because the storage is invisible and the operations are behind
 * [MaskOps], that widening changes one file in `udea-core` and breaks nothing.
 *
 * Concretely: never write `mask and other`, never store a mask in a game component, never
 * pass one as a `Long`. If something needs a raw word — the wire encoder does — it asks
 * [MaskOps.wordCount] and [MaskOps.word], which already generalise to more than one word.
 *
 * ## Bit positions
 *
 * Bit `i` corresponds to `Replicator.fieldNames[i]`. That alignment is part of the frozen
 * contract: `desync_report(tick)` reports a differing tick *and field*, which it can only
 * do by naming `fieldNames[i]` for each set bit of the difference.
 */
@JvmInline
public value class FieldMask internal constructor(internal val bits: Long) {
    /**
     * Debug-only rendering. Deliberately not a bit accessor: it round-trips through nothing
     * and no protocol may parse it.
     */
    override fun toString(): String = "FieldMask(${MaskOps.cardinality(this)} of ${MaskOps.MAX_FIELDS})"
}

/** Callback for [MaskOps.forEachSetBit]. A `fun interface` so the loop allocates nothing. */
public fun interface FieldIndexVisitor {
    public fun visit(fieldIndex: Int)
}

/**
 * Every operation on a [FieldMask].
 *
 * The complete list is here on purpose. If a caller needs a bit operation that is not on
 * this object, the fix is to add it here — not to unwrap the mask — because only code in
 * this file has to change when the mask widens past 64 fields.
 */
public object MaskOps {

    /** How many fields one mask can address today. A component exceeding this is a KSP error. */
    public const val MAX_FIELDS: Int = 64

    /** The mask that selects nothing. */
    public val EMPTY: FieldMask = FieldMask(0L)

    /** The mask that selects every addressable field. */
    public val ALL: FieldMask = FieldMask(-1L)

    /** The mask selecting exactly [fieldIndex]. */
    public fun single(fieldIndex: Int): FieldMask {
        checkIndex(fieldIndex)
        return FieldMask(1L shl fieldIndex)
    }

    /** The mask selecting each of [fieldIndices]. */
    public fun of(vararg fieldIndices: Int): FieldMask {
        var bits = 0L
        for (fieldIndex in fieldIndices) {
            checkIndex(fieldIndex)
            bits = bits or (1L shl fieldIndex)
        }
        return FieldMask(bits)
    }

    /** The mask selecting fields `0 until fieldCount`. */
    public fun lowest(fieldCount: Int): FieldMask {
        require(fieldCount in 0..MAX_FIELDS) {
            "fieldCount must be in 0..$MAX_FIELDS, was $fieldCount"
        }
        return if (fieldCount == MAX_FIELDS) ALL else FieldMask((1L shl fieldCount) - 1L)
    }

    public fun set(mask: FieldMask, fieldIndex: Int): FieldMask {
        checkIndex(fieldIndex)
        return FieldMask(mask.bits or (1L shl fieldIndex))
    }

    public fun clear(mask: FieldMask, fieldIndex: Int): FieldMask {
        checkIndex(fieldIndex)
        return FieldMask(mask.bits and (1L shl fieldIndex).inv())
    }

    public fun test(mask: FieldMask, fieldIndex: Int): Boolean {
        checkIndex(fieldIndex)
        return (mask.bits ushr fieldIndex) and 1L != 0L
    }

    public fun and(a: FieldMask, b: FieldMask): FieldMask = FieldMask(a.bits and b.bits)

    public fun or(a: FieldMask, b: FieldMask): FieldMask = FieldMask(a.bits or b.bits)

    /** Fields in [a] that are not in [b]. */
    public fun andNot(a: FieldMask, b: FieldMask): FieldMask = FieldMask(a.bits and b.bits.inv())

    public fun isEmpty(mask: FieldMask): Boolean = mask.bits == 0L

    public fun isNotEmpty(mask: FieldMask): Boolean = mask.bits != 0L

    /** True when every field of [subset] is also in [mask]. */
    public fun containsAll(mask: FieldMask, subset: FieldMask): Boolean =
        mask.bits and subset.bits == subset.bits

    public fun cardinality(mask: FieldMask): Int = java.lang.Long.bitCount(mask.bits)

    /** Lowest set field index, or -1 when [mask] is empty. */
    public fun lowestSetBit(mask: FieldMask): Int =
        if (mask.bits == 0L) -1 else java.lang.Long.numberOfTrailingZeros(mask.bits)

    /** Lowest set field index at or above [from], or -1 if there is none. */
    public fun nextSetBit(mask: FieldMask, from: Int): Int {
        if (from >= MAX_FIELDS) return -1
        val start = if (from < 0) 0 else from
        val remaining = mask.bits ushr start
        return if (remaining == 0L) -1 else start + java.lang.Long.numberOfTrailingZeros(remaining)
    }

    /** Visits each set field index in ascending order. Allocation-free. */
    public fun forEachSetBit(mask: FieldMask, visitor: FieldIndexVisitor) {
        var remaining = mask.bits
        while (remaining != 0L) {
            val fieldIndex = java.lang.Long.numberOfTrailingZeros(remaining)
            visitor.visit(fieldIndex)
            remaining = remaining and (remaining - 1L)
        }
    }

    // --- raw word access: the only sanctioned escape hatch, for wire encoding ---

    /**
     * How many 64-bit words a mask occupies. One today; the whole point of the accessor is
     * that a wire encoder written against it keeps working when that becomes more than one.
     */
    public fun wordCount(): Int = 1

    /** Word [wordIndex] of [mask], least significant first. */
    public fun word(mask: FieldMask, wordIndex: Int): Long {
        require(wordIndex in 0 until wordCount()) {
            "word index out of range: $wordIndex (0 until ${wordCount()})"
        }
        return mask.bits
    }

    /** Rebuilds a mask from words produced by [word], least significant first. */
    public fun fromWords(words: LongArray): FieldMask {
        require(words.size == wordCount()) {
            "expected ${wordCount()} mask word(s), got ${words.size}"
        }
        return FieldMask(words[0])
    }

    // --- wire encoding, kept here so widening the mask does not touch a Replicator ---

    /**
     * Writes the low [fieldCount] bits of [mask] to [out].
     *
     * Every generated `Replicator.write` calls this rather than encoding the mask itself,
     * so mask framing lives in exactly one place. Writes `fieldCount` bits, never more:
     * a 3-field component spends 3 bits on its mask, not 64.
     */
    public fun writeTo(mask: FieldMask, out: BitWriter, fieldCount: Int) {
        require(fieldCount in 0..MAX_FIELDS) {
            "fieldCount must be in 0..$MAX_FIELDS, was $fieldCount"
        }
        var written = 0
        while (written < fieldCount) {
            val chunk = if (fieldCount - written > 32) 32 else fieldCount - written
            out.writeBits((mask.bits ushr written).toInt(), chunk)
            written += chunk
        }
    }

    /** Reads a mask written by [writeTo] with the same [fieldCount]. */
    public fun readFrom(src: BitReader, fieldCount: Int): FieldMask {
        require(fieldCount in 0..MAX_FIELDS) {
            "fieldCount must be in 0..$MAX_FIELDS, was $fieldCount"
        }
        var bits = 0L
        var read = 0
        while (read < fieldCount) {
            val chunk = if (fieldCount - read > 32) 32 else fieldCount - read
            val word = src.readBits(chunk).toLong() and ((1L shl chunk) - 1L)
            bits = bits or (word shl read)
            read += chunk
        }
        return FieldMask(bits)
    }

    private fun checkIndex(fieldIndex: Int) {
        require(fieldIndex in 0 until MAX_FIELDS) {
            "field index out of range: $fieldIndex (0 until $MAX_FIELDS)"
        }
    }
}
