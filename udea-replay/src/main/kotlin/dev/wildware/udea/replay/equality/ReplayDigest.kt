package dev.wildware.udea.replay.equality

import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.replication.ComponentTypeId
import dev.wildware.udea.core.snapshot.FieldKind

/**
 * Which part of a world one digest cell came from.
 *
 * The scopes are not a taxonomy for its own sake: together they cover **every** value
 * `WorldHasher.hash(WorldSnapshot)` folds, in the order it folds them. That is the property the
 * whole cross-OS gate rests on, because it is what turns "the hashes differ" into "this field of
 * this entity differs". [ReplayDigestWriter] refolds its own cells and refuses to write a tick
 * whose fold does not reproduce the hash, so the coverage is enforced on every tick of every run
 * rather than asserted once in a test.
 *
 * [Component] is the interesting one and the rest are the reason a bare hash mismatch can never
 * be reported: a run that diverges only in its random streams, its id allocator or its clock has
 * no differing *field*, and without [Rng], [Handles] and [Clock] cells there would be nothing to
 * name.
 */
public enum class DigestScope {

    /** `WorldFieldStore.rowCount`: how many entities the capture held. */
    RowCount,

    /** A roster row: its `NetId` at [ReplayDigestCells.ROSTER_NET_ID], then its presence words. */
    Roster,

    /** A component type's id, folded before its slot count so the fold order matches the hash. */
    ComponentType,

    /** How many slots a component type occupied. */
    ComponentSlots,

    /** One lowered field of one component of one entity: the cell a divergence report names. */
    Component,

    /** `SimClock.tick`, folded so a replay one tick out is caught as a tick and not as drift. */
    Clock,

    /** One word of `RngService.saveState()`. */
    Rng,

    /** One word of the `NetIdIndex` allocator state. */
    Handles,
}

/** Field indices [DigestScope.Roster] and [DigestScope.Handles] use for their non-field values. */
public object ReplayDigestCells {

    /** The roster row's own `NetId`. Presence words use field indices `0..presenceWordCount-1`. */
    public const val ROSTER_NET_ID: Int = -1

    /** `HandleState.nextFresh`. */
    public const val HANDLE_NEXT_FRESH: Int = 0

    /** `HandleState.highWater`. */
    public const val HANDLE_HIGH_WATER: Int = 1

    /** `HandleState.freeCount`. */
    public const val HANDLE_FREE_COUNT: Int = 2

    /** The first free-list entry. Entry `k` occupies `HANDLE_FREE_BASE + 2k` and `+ 2k + 1`. */
    public const val HANDLE_FREE_BASE: Int = 3

    /** No component type applies to this cell. */
    public const val NO_TYPE_ID: Int = -1

    /** No field index applies to this cell. */
    public const val NO_FIELD: Int = -1
}

/**
 * One component type, as a reader with no game on its classpath needs to see it.
 *
 * A digest is compared by a join step that has downloaded two files and has neither game nor
 * `ComponentRegistry`, so the names and the storage kinds travel in the file. Without the kinds a
 * report could name a field and then print its raw bits, which sends a reader looking for a
 * `1069547520` that never appears anywhere in the simulation.
 */
public class DigestComponentInfo(
    /** The stable id. Cells carry this rather than a dense index, which is registry-relative. */
    public val typeId: ComponentTypeId,
    /** `ComponentSchema.typeName`, the short name a divergence report has always printed. */
    public val typeName: String,
    /**
     * `ReplicatedComponentType.componentClass.qualifiedName`.
     *
     * Spec 7 asks a cross-OS failure to name the component's FQN, and two games can both have a
     * `Transform`. Falls back to [typeName] only for a local or anonymous class, which no
     * registered component is.
     */
    public val componentFqn: String,
    /** Index-aligned with the mask bit and the store field index — the frozen invariant. */
    public val fieldNames: List<String>,
    /** Index-aligned with [fieldNames]; what turns a folded `Long` back into a value. */
    public val fieldKinds: List<FieldKind>,
) {

    init {
        require(fieldNames.size == fieldKinds.size) {
            "$typeName carries ${fieldNames.size} field name(s) and ${fieldKinds.size} kind(s); " +
                "fieldNames[i], FieldMask bit i and FieldStore field i are the same index"
        }
    }

    /** The name of [field], or a bracketed placeholder when a stream names one this does not. */
    public fun nameOf(field: Int): String =
        if (field in fieldNames.indices) fieldNames[field] else "<field $field>"

    /** [bits] rendered as the value it was captured from. */
    public fun render(field: Int, bits: Long): String =
        if (field in fieldKinds.indices) renderBits(fieldKinds[field], bits) else bits.toString()

    override fun toString(): String = "$componentFqn(${fieldNames.size} fields)"

    /** Whether [other] describes the same component, field for field. */
    public fun agreesWith(other: DigestComponentInfo): Boolean =
        typeId == other.typeId &&
            componentFqn == other.componentFqn &&
            fieldNames == other.fieldNames &&
            fieldKinds == other.fieldKinds

    public companion object {

        /**
         * One folded `Long` rendered as the value `ColumnarFieldStore.valueAt` would have shown.
         *
         * `hashableBits` widens through `toLong()`, so an `Int`-group kind sign-extends and
         * `toInt()` is the exact inverse. An [FieldKind.Object] field folds its `hashCode` and
         * not its value, so the value is genuinely not recoverable and the rendering says so
         * rather than printing a number that looks like state.
         */
        public fun renderBits(kind: FieldKind, bits: Long): String = when (kind) {
            FieldKind.Bool -> (bits.toInt() != 0).toString()
            FieldKind.Int -> bits.toInt().toString()
            FieldKind.Long -> bits.toString()
            FieldKind.Float -> renderFloat(bits)
            FieldKind.NetId -> NetId.ofRaw(bits.toInt()).toString()
            FieldKind.Tick -> Tick(bits).toString()
            FieldKind.Object -> "<hashCode ${bits.toInt()}>"
        }

        /**
         * A float, with its raw bits beside it.
         *
         * The bits are not decoration. The failure class this gate exists for is a last-bit
         * difference from a `Math.sin` that two JVMs are not obliged to round the same way
         * (`determinism-audit.md` §3.1), and `0.030715168` printed twice tells a reader nothing
         * about whether the two runs agreed.
         */
        private fun renderFloat(bits: Long): String {
            val raw = bits.toInt()
            return "${Float.fromBits(raw)} (0x${raw.toUInt().toString(HEX).padStart(HEX_DIGITS, '0')})"
        }

        private const val HEX: Int = 16
        private const val HEX_DIGITS: Int = 8
    }
}

/**
 * What one platform's replay of one fixture produced, tick by tick.
 *
 * ## Why the values and not only the hash
 *
 * A `.udearep` stores one `Long` per tick, and `ReplayWorld`'s KDoc is explicit that this makes
 * naming a field impossible from the file alone: hashes are one-way. Within one process that is
 * solved by rewinding a snapshot ring, which is what `BaselineSnapshots` does. Across two
 * machines there is no ring to rewind — the other run finished on another continent — so the
 * values have to travel, and this is the file they travel in.
 *
 * They travel as the **folded** values rather than as a snapshot, which is what keeps this from
 * being "a separate snapshot codec": nothing here can restore a world, the cells are exactly the
 * inputs `WorldHasher` folds, and [ReplayDigestWriter] proves that by refolding them.
 *
 * ## Layout
 *
 * Cells are held in parallel primitive arrays with a per-tick offset table, so a 3600-tick
 * fixture is a handful of arrays rather than several hundred thousand objects. [cellsOf] gives a
 * tick's half-open range into them.
 */
public class ReplayDigest internal constructor(
    /** What this stream is, where it ran, and what it ran. */
    public val header: ReplayDigestHeader,
    private val hashes: LongArray,
    private val offsets: IntArray,
    private val scopes: ByteArray,
    private val netIds: IntArray,
    private val typeIds: IntArray,
    private val fields: IntArray,
    private val values: LongArray,
) {

    /** How many ticks this stream covers. */
    public val tickCount: Int get() = hashes.size

    /** The first tick, from the recording that produced it. */
    public val firstTick: Tick get() = header.firstTick

    /** The tick at [index], counting from [firstTick]. */
    public fun tickAt(index: Int): Tick = firstTick + index.toLong()

    /** `WorldHasher.hash(WorldSnapshot)` at [index]. */
    public fun hashAt(index: Int): Long = hashes[index]

    /** The half-open cell range belonging to tick [index]. */
    public fun cellsOf(index: Int): IntRange = offsets[index] until offsets[index + 1]

    /** The scope of cell [cell]. */
    public fun scopeAt(cell: Int): DigestScope = SCOPES[scopes[cell].toInt()]

    /** The entity cell [cell] belongs to, or [NetId.NONE] when it belongs to no entity. */
    public fun netIdAt(cell: Int): NetId = NetId.ofRaw(netIds[cell])

    /** The component type of cell [cell], or [ReplayDigestCells.NO_TYPE_ID]. */
    public fun typeIdAt(cell: Int): Int = typeIds[cell]

    /** The field index of cell [cell]; scope-dependent, see [ReplayDigestCells]. */
    public fun fieldAt(cell: Int): Int = fields[cell]

    /** The folded value of cell [cell]: exactly what `WorldHasher.fold` was given. */
    public fun valueAt(cell: Int): Long = values[cell]

    /**
     * The identity of cell [cell], packed so two streams' cells can be matched by equality.
     *
     * Scope, `NetId`, component type and field index together name a cell across two machines;
     * a cell *index* does not, because two runs that disagree about the roster hold different
     * numbers of cells at the same tick and every index after the difference is off by one.
     */
    public fun keyAt(cell: Int): DigestCellKey =
        DigestCellKey(scopeAt(cell), netIds[cell], typeIds[cell], fields[cell])

    /** The component table entry for [typeIdRaw], or `null` when this stream has no such type. */
    public fun componentOf(typeIdRaw: Int): DigestComponentInfo? = header.componentOf(typeIdRaw)

    override fun toString(): String =
        "ReplayDigest(${header.label}, ${header.fixture}, $tickCount ticks, ${values.size} cells)"

    internal companion object {
        val SCOPES: Array<DigestScope> = DigestScope.entries.toTypedArray()
    }
}

/**
 * The identity of one cell, as a value: what makes two streams' cells comparable.
 *
 * A `data class` rather than a packed `Long` because it is built once per compared cell inside an
 * offline join step, never on a simulation path, and a packed key is unreadable in exactly the
 * failure it exists to explain.
 */
public data class DigestCellKey(
    /** Which part of the world. */
    public val scope: DigestScope,
    /** `NetId.raw`, or `NetId.NONE.raw` for a cell that belongs to no entity. */
    public val netIdRaw: Int,
    /** `ComponentTypeId.raw`, or [ReplayDigestCells.NO_TYPE_ID]. */
    public val typeIdRaw: Int,
    /** Scope-dependent; see [ReplayDigestCells]. */
    public val field: Int,
) {

    /** The entity this cell belongs to, or [NetId.NONE]. */
    public val netId: NetId get() = NetId.ofRaw(netIdRaw)
}
