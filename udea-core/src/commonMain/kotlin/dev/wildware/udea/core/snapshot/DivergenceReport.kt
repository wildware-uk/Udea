package dev.wildware.udea.core.snapshot

import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.replication.ComponentTypeId

/**
 * One field two runs disagreed about.
 *
 * `(netId, component, field)` is the triple spec 3.1 says `desync_report(tick)` must produce,
 * and it is only expressible because `fieldNames[i]` is index-aligned with mask bit `i` and
 * store field `i` — the invariant the frozen contract exists to protect.
 */
public data class FieldDivergence(
    /** The entity. Meaningful in both runs, unlike a Fleks `Entity` or a store row. */
    public val netId: NetId,
    public val componentTypeId: ComponentTypeId,
    public val componentName: String,
    /** The lowered field index, or [FieldDiff.PRESENCE] when the component itself is one-sided. */
    public val fieldIndex: Int,
    /** `fieldNames[fieldIndex]`, or `<presence>` for a one-sided component. */
    public val fieldName: String,
    /** The value the baseline run held, rendered. `<absent>` when it had no such component. */
    public val expected: String,
    /** The value the compared run held, rendered. */
    public val actual: String,
) {
    override fun toString(): String =
        "$netId $componentName.$fieldName: expected $expected, got $actual"
}

/**
 * Why two runs of the same simulation stopped agreeing: the first tick, and the exact fields.
 *
 * A bare hash mismatch tells an agent — or a person at 1am — that something is wrong and
 * nothing about what, which is why spec 7 pairs the [WorldHasher] gate with this. Phase 7
 * extends the same shape to cross-OS replay equality, so the shape is fixed now: a tick, the
 * two hashes, and a list of [FieldDivergence].
 *
 * ## Comparison is canonical, deliberately
 *
 * [WorldFieldStore.diffInto] defaults to bit-identical comparison, which is what a delta
 * encoder needs. A divergence report uses [FieldComparison.Canonical] instead, so that the
 * fields it names are exactly the fields the hash disagreed about. Reporting a `-0.0f` the
 * hash folded away would send a reader hunting a difference that is not there.
 */
public class DivergenceReport(
    /** The first tick whose hashes differed. */
    public val tick: Tick,
    /** The hash the baseline run recorded at [tick]. */
    public val expectedHash: Long,
    /** The hash the compared run produced at [tick]. */
    public val actualHash: Long,
    /** Every field that differs, roster order. Empty when only non-field state diverged. */
    public val fields: List<FieldDivergence>,
) {

    /** True when the two runs agreed at this tick after all. */
    public val isIdentical: Boolean get() = expectedHash == actualHash && fields.isEmpty()

    /**
     * The assertion message: the tick, both hashes, and the differing fields.
     *
     * Capped, because a world that has genuinely diverged usually diverges everywhere, and
     * one screenful of the first few is more useful than ten thousand lines of the rest.
     */
    public fun describe(): String {
        if (isIdentical) return "no divergence at $tick"
        val builder = StringBuilder()
        builder.append("divergence first observed at ").append(tick)
            .append(": expected hash ").append(expectedHash)
            .append(", got ").append(actualHash)
        if (fields.isEmpty()) {
            builder.append("\n  no field differs; the divergence is in the clock, the random ")
                .append("streams or the id allocator, none of which are fields")
            return builder.toString()
        }
        builder.append("\n  ").append(fields.size).append(" differing field(s):")
        for (divergence in fields.take(MAX_REPORTED)) builder.append("\n    ").append(divergence)
        if (fields.size > MAX_REPORTED) {
            builder.append("\n    ... and ").append(fields.size - MAX_REPORTED).append(" more")
        }
        return builder.toString()
    }

    override fun toString(): String = describe()

    public companion object {

        /** Matches the diagnostics cap in spec 5: one screenful, root cause first. */
        public const val MAX_REPORTED: Int = 25

        /**
         * The first tick at which two hash streams differ, or `null` when they agree.
         *
         * Both streams are indexed from [firstTick], one entry per tick. A length mismatch is
         * a bug in the harness rather than a divergence, so it fails loudly.
         */
        public fun firstDivergingTick(
            expected: LongArray,
            actual: LongArray,
            firstTick: Tick,
        ): Tick? {
            require(expected.size == actual.size) {
                "hash streams have different lengths (${expected.size} and ${actual.size}); " +
                    "the two runs did not run the same number of ticks"
            }
            for (offset in expected.indices) {
                if (expected[offset] != actual[offset]) return firstTick + offset.toLong()
            }
            return null
        }

        /**
         * Compares two captured worlds field by field and reports what differs.
         *
         * [scratch] is a caller-owned [FieldDiff] so a harness that compares every tick does
         * not allocate one per comparison.
         */
        public fun compare(
            tick: Tick,
            expected: WorldSnapshot,
            actual: WorldSnapshot,
            scratch: FieldDiff = FieldDiff(),
        ): DivergenceReport {
            expected.fields.diffInto(actual.fields, scratch, FieldComparison.Canonical)

            val divergences = ArrayList<FieldDivergence>(scratch.size)
            for (entry in 0 until scratch.size) {
                divergences += describeEntry(entry, scratch, expected, actual)
            }
            return DivergenceReport(
                tick = tick,
                expectedHash = WorldHasher.hash(expected),
                actualHash = WorldHasher.hash(actual),
                fields = divergences,
            )
        }

        private fun describeEntry(
            entry: Int,
            diff: FieldDiff,
            expected: WorldSnapshot,
            actual: WorldSnapshot,
        ): FieldDivergence {
            val netId = diff.netIdAt(entry)
            val typeId = diff.typeIdAt(entry)
            val fieldIndex = diff.fieldAt(entry)
            val component = expected.registry.indexOf(typeId)
            val schema = expected.registry.schemaAt(component)
            return FieldDivergence(
                netId = netId,
                componentTypeId = typeId,
                componentName = schema.typeName,
                fieldIndex = fieldIndex,
                fieldName = if (fieldIndex == FieldDiff.PRESENCE) PRESENCE_NAME else schema.nameOf(fieldIndex),
                expected = render(expected, netId, component, fieldIndex),
                actual = render(actual, netId, component, fieldIndex),
            )
        }

        /** One side's value, or why there is not one. */
        private fun render(
            snapshot: WorldSnapshot,
            netId: NetId,
            component: Int,
            fieldIndex: Int,
        ): String {
            val row = snapshot.fields.rowOf(netId)
            if (row == WorldFieldStore.NO_ROW) return "<no such entity>"
            if (!snapshot.fields.isPresent(row, component)) return ABSENT
            if (fieldIndex == FieldDiff.PRESENCE) return PRESENT
            val slot = snapshot.fields.componentSlotAt(row, component)
            return snapshot.fields.storeAt(component).valueAt(slot, fieldIndex).toString()
        }

        private const val PRESENCE_NAME: String = "<presence>"
        private const val ABSENT: String = "<component absent>"
        private const val PRESENT: String = "<component present>"
    }
}
