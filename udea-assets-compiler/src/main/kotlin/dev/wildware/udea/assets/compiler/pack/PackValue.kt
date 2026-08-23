package dev.wildware.udea.assets.compiler.pack

import dev.wildware.udea.assets.pack.ValueTag

/**
 * A value on its way into a bundle, after the DSL's `Any?` has been given a type and before it
 * becomes bytes.
 *
 * The intermediate step earns its keep twice. It is where a reference stops being an id string
 * and becomes the `u32` slot the runtime resolves by ([Ref]), which cannot happen while the
 * value is still `Any?` because the slot of an asset is not known until every asset is known.
 * And it is what makes the writer testable without a byte comparison: two packs of the same
 * tree can be compared as [PackValue] trees, so a determinism failure says *which field*
 * differs rather than *which byte*.
 */
public sealed interface PackValue {

    /** The tag this value writes. */
    public val tag: Int

    public data object Null : PackValue {
        override val tag: Int get() = ValueTag.NULL
    }

    public data class Bool(public val value: Boolean) : PackValue {
        override val tag: Int get() = ValueTag.BOOL
    }

    public data class I32(public val value: Int) : PackValue {
        override val tag: Int get() = ValueTag.INT
    }

    public data class I64(public val value: Long) : PackValue {
        override val tag: Int get() = ValueTag.LONG
    }

    public data class F32(public val value: Float) : PackValue {
        override val tag: Int get() = ValueTag.FLOAT

        init {
            require(!value.isNaN()) {
                "NaN has 16,777,214 bit patterns and no way to choose between them, so it cannot " +
                    "be written deterministically; a field that means 'absent' should be null"
            }
        }
    }

    public data class Text(public val value: String) : PackValue {
        override val tag: Int get() = ValueTag.TEXT
    }

    public data class Path(public val value: String) : PackValue {
        override val tag: Int get() = ValueTag.PATH
    }

    /**
     * A resolved reference: the **slot** of the target, never its name.
     *
     * [id] is carried alongside for diagnostics only; it is not written. That asymmetry is the
     * point of the patch (Trello #32) - the id lives once in the target's own record, and every
     * reference to it costs four bytes.
     */
    public data class Ref(public val index: Int, public val id: String) : PackValue {
        override val tag: Int get() = ValueTag.REF

        init {
            require(index >= 0) {
                "reference to '$id' was never patched (index $index). A bundle with an " +
                    "unresolved reference in it must not be written: the reader would have to " +
                    "fall back to a string lookup, which is the cost this format removes."
            }
        }
    }

    public data class Vec(public val x: Float, public val y: Float) : PackValue {
        override val tag: Int get() = ValueTag.VEC
    }

    public data class Items(public val values: List<PackValue>) : PackValue {
        override val tag: Int get() = ValueTag.LIST
    }

    /**
     * Named fields.
     *
     * [values] is a `SortedMap` rather than a `Map` in spirit: [Fields.of] sorts, and nothing
     * else constructs one. Iteration order of a `HashMap` is the single most common source of a
     * non-reproducible build output, so the type that would allow it does not exist here.
     */
    public data class Fields(public val values: List<Pair<String, PackValue>>) : PackValue {
        override val tag: Int get() = ValueTag.STRUCT

        init {
            val names = values.map { it.first }
            require(names == names.sorted()) { "struct fields must be sorted; got $names" }
            require(names.size == names.distinct().size) {
                "struct declares a field twice: ${names.groupingBy { it }.eachCount().filterValues { it > 1 }.keys}"
            }
        }

        public companion object {
            public fun of(fields: Map<String, PackValue>): Fields =
                Fields(fields.entries.sortedBy { it.key }.map { it.key to it.value })
        }
    }

    /** Every string this value and its children write, for the string table. */
    public fun strings(): List<String> = when (this) {
        is Text -> listOf(value)
        is Path -> listOf(value)
        is Items -> values.flatMap { it.strings() }
        is Fields -> values.flatMap { listOf(it.first) + it.second.strings() }
        else -> emptyList()
    }
}

/** One asset as it will be written: id, kind name, and sorted fields. */
public data class PackedAsset(
    public val id: String,
    public val kind: String,
    public val fields: PackValue.Fields,
) {
    /** Every string this record contributes to the table, its own id and kind included. */
    public fun strings(): List<String> = listOf(id, kind) + fields.strings()
}
