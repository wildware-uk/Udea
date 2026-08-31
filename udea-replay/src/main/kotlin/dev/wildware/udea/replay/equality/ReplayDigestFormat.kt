package dev.wildware.udea.replay.equality

import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.replication.ComponentTypeId
import dev.wildware.udea.core.snapshot.ComponentRegistry
import dev.wildware.udea.core.snapshot.FieldComparison
import dev.wildware.udea.core.snapshot.FieldKind
import dev.wildware.udea.core.snapshot.WorldFieldStore
import dev.wildware.udea.core.snapshot.WorldHasher
import dev.wildware.udea.core.snapshot.WorldSnapshot
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** A `.udeaeq` file is not one this build can read. */
public class ReplayDigestFormatException(message: String) : IllegalArgumentException(message)

/**
 * The `.udeaeq` cross-platform digest stream: magic, version, limits, and where they come from.
 *
 * ## Streamed and gzipped, rather than built in memory
 *
 * `ReplayFormat`'s `ByteSink` builds a whole `.udearep` in a growable array, which is right for a
 * file that is one input sample per tick. A digest is the *state* per tick and is two orders of
 * magnitude larger, so it is written through a `DataOutputStream` over a `GZIPOutputStream` and
 * read back the same way: bounded memory on the writing side, and the redundancy between one tick
 * and the next — which is nearly all of it — costs almost nothing on disk.
 *
 * Big-endian, because `DataOutputStream` is, and it is specified rather than platform-dependent.
 * That is the whole requirement for a file two operating systems have to agree about.
 */
public object ReplayDigestFormat {

    /** `UDEAEQ01` in ASCII. Fails a truncated or mistyped file at byte 0 rather than at tick 0. */
    public const val MAGIC: Long = 0x5544454145513031L

    /** Bumped when the layout changes. A reader refuses a version it does not know. */
    public const val VERSION: Int = 1

    /** The extension a digest stream is written under. */
    public const val EXTENSION: String = ".udeaeq"

    /**
     * The largest cell count one tick may carry.
     *
     * Not a guess at a world size: it is the point past which a corrupt length prefix would have
     * a reader allocate an arbitrary array. Roughly a thousand entities of a hundred fields, an
     * order of magnitude above the largest world the engine's own budgets admit.
     */
    public const val MAX_CELLS_PER_TICK: Int = 1 shl 21

    /** Matches `ReplayFormat.MAX_TICKS`: a digest cannot cover more ticks than a recording holds. */
    public const val MAX_TICKS: Int = 60 * 60 * 60 * 24

    /** Guards a corrupt string length the same way [MAX_CELLS_PER_TICK] guards a cell count. */
    public const val MAX_COMPONENTS: Int = 4096
}

/**
 * Turns one captured world into the cells a cross-machine comparison can name.
 *
 * ## The contract this class keeps
 *
 * [writeTick] emits exactly the values `WorldHasher.hash(WorldSnapshot)` folds, in exactly the
 * order it folds them, and then **refolds them and compares**. A tick whose cells do not
 * reproduce the recorded hash is refused, loudly, at the tick it happened.
 *
 * That check is the reason `ReplayEquality` can promise something a hash stream cannot: if two
 * runs' hashes differ at a tick, some cell at that tick differs, so there is always something to
 * name. A world that diverged only in its random streams reports `<rng>.word[0]`; one that
 * diverged only in its id allocator reports `<handles>.nextFresh`. "Hash mismatch at tick N" with
 * nothing after it is not a state this can reach.
 *
 * It also catches the failure that would otherwise be silent: `WorldHasher.hash` growing a new
 * folded input that nobody added a cell for. The gate would keep passing and would quietly stop
 * covering the new state; instead the next run fails at tick zero saying so.
 */
public class ReplayDigestWriter internal constructor(
    private val out: DataOutputStream,
    private val header: ReplayDigestHeader,
) : AutoCloseable {

    private var ticksWritten: Int = 0

    private val scratch: CellBuffer = CellBuffer()
    private var slotOwnerScratch: IntArray = IntArray(INITIAL_SLOT_OWNERS)

    init {
        out.writeLong(ReplayDigestFormat.MAGIC)
        out.writeInt(ReplayDigestFormat.VERSION)
        out.writeUTF(header.label)
        out.writeUTF(header.fixture)
        out.writeUTF(header.gameId)
        out.writeUTF(header.gameVersion)
        out.writeUTF(header.jvm)
        out.writeUTF(header.os)
        out.writeLong(header.firstTick.value)
        out.writeInt(header.tickCount)
        out.writeInt(header.components.size)
        for (component in header.components) {
            out.writeInt(component.typeId.raw)
            out.writeUTF(component.typeName)
            out.writeUTF(component.componentFqn)
            out.writeInt(component.fieldNames.size)
            for (field in component.fieldNames.indices) {
                out.writeUTF(component.fieldNames[field])
                out.writeInt(component.fieldKinds[field].ordinal)
            }
        }
    }

    /** How many ticks have been written so far. */
    public val tickCount: Int get() = ticksWritten

    /**
     * Writes one tick's cells and its hash.
     *
     * @throws IllegalStateException when the cells do not refold to [WorldHasher.hash], which
     *   means this class and [WorldHasher] no longer agree about what a world is.
     */
    public fun writeTick(snapshot: WorldSnapshot) {
        val expectedHash = WorldHasher.hash(snapshot)
        val cells = collect(snapshot)

        var folded = WorldHasher.OFFSET_BASIS
        for (index in 0 until cells.size) folded = WorldHasher.fold(folded, cells.valueAt(index))
        check(folded == expectedHash) {
            "the digest's ${cells.size} cell(s) at ${snapshot.tick} fold to $folded but " +
                "WorldHasher.hash gives $expectedHash. WorldHasher folds a value this writer " +
                "does not emit a cell for, so a divergence in it could only ever be reported as " +
                "a bare hash mismatch. Add the cell here rather than relaxing this check."
        }

        out.writeLong(expectedHash)
        out.writeInt(cells.size)
        for (index in 0 until cells.size) {
            out.writeByte(cells.scopeAt(index))
            out.writeInt(cells.netIdAt(index))
            out.writeInt(cells.typeIdAt(index))
            out.writeInt(cells.fieldAt(index))
            out.writeLong(cells.valueAt(index))
        }
        ticksWritten++
    }

    override fun close() {
        out.flush()
        out.close()
    }

    /**
     * Every folded value of [snapshot], in `WorldHasher`'s canonical order.
     *
     * Reads exactly like `WorldHasher.hash`'s two methods, deliberately: they are two walks of
     * the same order, and keeping them line-for-line comparable is what makes the refold check
     * above a check somebody can maintain rather than a mystery when it fires.
     */
    private fun collect(snapshot: WorldSnapshot): CellBuffer {
        val buffer = scratch
        buffer.clear()
        val fields = snapshot.fields

        buffer.add(DigestScope.RowCount, NetId.NONE.raw, NO_TYPE, NO_FIELD, fields.rowCount.toLong())
        for (row in 0 until fields.rowCount) {
            val netId = fields.netIdAt(row).raw
            buffer.add(DigestScope.Roster, netId, NO_TYPE, ReplayDigestCells.ROSTER_NET_ID, netId.toLong())
            for (word in 0 until fields.presenceWordCount) {
                buffer.add(DigestScope.Roster, netId, NO_TYPE, word, fields.presenceWordAt(row, word))
            }
        }

        collectComponents(fields, buffer)

        buffer.add(DigestScope.Clock, NetId.NONE.raw, NO_TYPE, NO_FIELD, snapshot.tick.value)
        for (word in snapshot.rng.indices) {
            buffer.add(DigestScope.Rng, NetId.NONE.raw, NO_TYPE, word, snapshot.rng[word])
        }

        val handles = snapshot.handles
        buffer.add(
            DigestScope.Handles, NetId.NONE.raw, NO_TYPE,
            ReplayDigestCells.HANDLE_NEXT_FRESH, handles.nextFresh.toLong(),
        )
        buffer.add(
            DigestScope.Handles, NetId.NONE.raw, NO_TYPE,
            ReplayDigestCells.HANDLE_HIGH_WATER, handles.highWater.toLong(),
        )
        buffer.add(
            DigestScope.Handles, NetId.NONE.raw, NO_TYPE,
            ReplayDigestCells.HANDLE_FREE_COUNT, handles.freeCount.toLong(),
        )
        for (position in 0 until handles.freeCount) {
            val base = ReplayDigestCells.HANDLE_FREE_BASE + position * HANDLE_FREE_STRIDE
            buffer.add(
                DigestScope.Handles, NetId.NONE.raw, NO_TYPE, base,
                handles.freeIndexAt(position).toLong(),
            )
            buffer.add(
                DigestScope.Handles, NetId.NONE.raw, NO_TYPE, base + 1,
                handles.freeGenerationAt(position).toLong(),
            )
        }
        return buffer
    }

    /**
     * The per-component half of [collect].
     *
     * A component's cells are keyed by the `NetId` of the row that owns the slot rather than by
     * the slot index, which is what makes them comparable across two machines: a slot index is
     * relative to how many *earlier* rows carried the component, so one extra entity on one side
     * would re-key every cell after it and the report would name the wrong entity for every one.
     */
    private fun collectComponents(fields: WorldFieldStore, buffer: CellBuffer) {
        val registry = fields.registry
        for (component in 0 until registry.size) {
            val store = fields.storeAt(component)
            val used = fields.slotsUsedAt(component)
            val typeId = registry.schemaAt(component).typeId.raw
            buffer.add(DigestScope.ComponentType, NetId.NONE.raw, typeId, NO_FIELD, typeId.toLong())
            buffer.add(DigestScope.ComponentSlots, NetId.NONE.raw, typeId, NO_FIELD, used.toLong())

            val owners = slotOwners(fields, component, used)
            for (field in 0 until store.fieldCount) {
                for (slot in 0 until used) {
                    buffer.add(
                        DigestScope.Component, owners[slot], typeId, field,
                        store.hashableBits(slot, field, FieldComparison.Canonical),
                    )
                }
            }
        }
    }

    /**
     * `NetId.raw` per occupied slot of [component].
     *
     * Slots are claimed in row order, so this is one forward walk of the roster and never a
     * search. Reused across ticks; it is grown, never re-allocated per tick.
     */
    private fun slotOwners(fields: WorldFieldStore, component: Int, used: Int): IntArray {
        var owners = slotOwnerScratch
        if (owners.size < used) {
            owners = IntArray(maxOf(used, owners.size * 2))
            slotOwnerScratch = owners
        }
        var found = 0
        var row = 0
        while (found < used && row < fields.rowCount) {
            if (fields.isPresent(row, component)) {
                owners[fields.componentSlotAt(row, component)] = fields.netIdAt(row).raw
                found++
            }
            row++
        }
        check(found == used) {
            "component index $component reports $used occupied slot(s) but only $found row(s) " +
                "carry it; the presence bits and the slot count disagree"
        }
        return owners
    }

    private companion object {
        const val NO_TYPE: Int = ReplayDigestCells.NO_TYPE_ID
        const val NO_FIELD: Int = ReplayDigestCells.NO_FIELD

        /** A free-list entry folds an index and a generation, so it occupies two field slots. */
        const val HANDLE_FREE_STRIDE: Int = 2

        const val INITIAL_SLOT_OWNERS: Int = 64
    }
}

/**
 * A growable, primitive-backed run of cells for one tick.
 *
 * Parallel arrays rather than a list of objects: the writer refills this once per tick for the
 * whole fixture, and a per-cell object would make a 3600-tick run several hundred thousand
 * allocations for a file it is about to gzip anyway.
 */
private class CellBuffer {

    var size: Int = 0
        private set

    private var scopes = ByteArray(INITIAL)
    private var netIds = IntArray(INITIAL)
    private var typeIds = IntArray(INITIAL)
    private var fields = IntArray(INITIAL)
    private var values = LongArray(INITIAL)

    fun clear() {
        size = 0
    }

    fun add(scope: DigestScope, netId: Int, typeId: Int, field: Int, value: Long) {
        if (size == scopes.size) grow()
        scopes[size] = scope.ordinal.toByte()
        netIds[size] = netId
        typeIds[size] = typeId
        fields[size] = field
        values[size] = value
        size++
    }

    fun scopeAt(index: Int): Int = scopes[index].toInt()
    fun netIdAt(index: Int): Int = netIds[index]
    fun typeIdAt(index: Int): Int = typeIds[index]
    fun fieldAt(index: Int): Int = fields[index]
    fun valueAt(index: Int): Long = values[index]

    private fun grow() {
        val capacity = scopes.size * 2
        scopes = scopes.copyOf(capacity)
        netIds = netIds.copyOf(capacity)
        typeIds = typeIds.copyOf(capacity)
        fields = fields.copyOf(capacity)
        values = values.copyOf(capacity)
    }

    private companion object {
        const val INITIAL: Int = 1024
    }
}

/** Reading and writing `.udeaeq` files. */
public object ReplayDigestIo {

    /** A writer over [path], gzipped, with [header] already written. */
    public fun writer(path: Path, header: ReplayDigestHeader): ReplayDigestWriter {
        Files.createDirectories(path.toAbsolutePath().parent)
        val stream = DataOutputStream(
            BufferedOutputStream(GZIPOutputStream(Files.newOutputStream(path), GZIP_BUFFER)),
        )
        return ReplayDigestWriter(stream, header)
    }

    /** The component table for [registry], as a digest header carries it. */
    public fun componentsOf(registry: ComponentRegistry): List<DigestComponentInfo> =
        (0 until registry.size).map { index ->
            val type = registry.typeAt(index)
            val schema = type.schema
            DigestComponentInfo(
                typeId = schema.typeId,
                typeName = schema.typeName,
                componentFqn = type.componentClass.qualifiedName ?: schema.typeName,
                fieldNames = schema.fieldNames,
                fieldKinds = (0 until schema.fieldCount).map { schema.kindOf(it) },
            )
        }

    /**
     * Reads a whole `.udeaeq` file.
     *
     * Whole rather than streamed because a comparison walks both sides in step and then walks
     * one of them *backwards* for the five ticks of history a report prints, and a stream that
     * cannot go back would have to be read twice.
     */
    public fun read(path: Path): ReplayDigest =
        DataInputStream(BufferedInputStream(GZIPInputStream(Files.newInputStream(path), GZIP_BUFFER)))
            .use { read(it, path.fileName.toString()) }

    private fun read(input: DataInputStream, what: String): ReplayDigest {
        val magic = try {
            input.readLong()
        } catch (e: EOFException) {
            throw ReplayDigestFormatException("$what is empty: ${e.message}")
        }
        if (magic != ReplayDigestFormat.MAGIC) {
            throw ReplayDigestFormatException(
                "$what does not start with the .udeaeq magic (got 0x${magic.toULong().toString(HEX)})",
            )
        }
        val version = input.readInt()
        if (version != ReplayDigestFormat.VERSION) {
            throw ReplayDigestFormatException(
                "$what is format version $version; this build reads ${ReplayDigestFormat.VERSION}",
            )
        }
        val header = readHeader(input, what)

        val hashes = LongArray(header.tickCount)
        val offsets = IntArray(header.tickCount + 1)
        val cells = GrowingCells()
        for (index in 0 until header.tickCount) {
            hashes[index] = input.readLong()
            val count = input.readInt()
            if (count < 0 || count > ReplayDigestFormat.MAX_CELLS_PER_TICK) {
                throw ReplayDigestFormatException(
                    "$what claims $count cell(s) at tick index $index, over the " +
                        "${ReplayDigestFormat.MAX_CELLS_PER_TICK} cap",
                )
            }
            repeat(count) {
                cells.add(
                    input.readByte(),
                    input.readInt(),
                    input.readInt(),
                    input.readInt(),
                    input.readLong(),
                )
            }
            offsets[index + 1] = cells.size
        }
        return cells.toDigest(header, hashes, offsets)
    }

    private fun readHeader(input: DataInputStream, what: String): ReplayDigestHeader {
        val label = input.readUTF()
        val fixture = input.readUTF()
        val gameId = input.readUTF()
        val gameVersion = input.readUTF()
        val jvm = input.readUTF()
        val os = input.readUTF()
        val firstTick = Tick(input.readLong())
        val tickCount = input.readInt()
        if (tickCount < 0 || tickCount > ReplayDigestFormat.MAX_TICKS) {
            throw ReplayDigestFormatException(
                "$what claims $tickCount tick(s), over the ${ReplayDigestFormat.MAX_TICKS} cap",
            )
        }
        val componentCount = input.readInt()
        if (componentCount < 0 || componentCount > ReplayDigestFormat.MAX_COMPONENTS) {
            throw ReplayDigestFormatException(
                "$what claims $componentCount component(s), over the " +
                    "${ReplayDigestFormat.MAX_COMPONENTS} cap",
            )
        }
        val components = ArrayList<DigestComponentInfo>(componentCount)
        repeat(componentCount) {
            val typeId = input.readInt()
            val typeName = input.readUTF()
            val fqn = input.readUTF()
            val fieldCount = input.readInt()
            if (fieldCount < 0 || fieldCount > ReplayDigestFormat.MAX_COMPONENTS) {
                throw ReplayDigestFormatException("$what claims $fieldCount field(s) on $typeName")
            }
            val names = ArrayList<String>(fieldCount)
            val kinds = ArrayList<FieldKind>(fieldCount)
            repeat(fieldCount) {
                names += input.readUTF()
                val ordinal = input.readInt()
                if (ordinal !in FIELD_KINDS.indices) {
                    throw ReplayDigestFormatException(
                        "$what names field kind $ordinal on $typeName; this build knows " +
                            "${FIELD_KINDS.size}",
                    )
                }
                kinds += FIELD_KINDS[ordinal]
            }
            components += DigestComponentInfo(ComponentTypeId(typeId), typeName, fqn, names, kinds)
        }
        return ReplayDigestHeader(
            label = label,
            fixture = fixture,
            gameId = gameId,
            gameVersion = gameVersion,
            firstTick = firstTick,
            tickCount = tickCount,
            jvm = jvm,
            os = os,
            components = components,
        )
    }

    private val FIELD_KINDS: Array<FieldKind> = FieldKind.entries.toTypedArray()

    private const val GZIP_BUFFER: Int = 64 * 1024
    private const val HEX: Int = 16
}

/** The reader's accumulator: the same parallel-array layout [ReplayDigest] holds. */
private class GrowingCells {

    var size: Int = 0
        private set

    private var scopes = ByteArray(INITIAL)
    private var netIds = IntArray(INITIAL)
    private var typeIds = IntArray(INITIAL)
    private var fields = IntArray(INITIAL)
    private var values = LongArray(INITIAL)

    fun add(scope: Byte, netId: Int, typeId: Int, field: Int, value: Long) {
        if (size == scopes.size) grow()
        scopes[size] = scope
        netIds[size] = netId
        typeIds[size] = typeId
        fields[size] = field
        values[size] = value
        size++
    }

    fun toDigest(
        header: ReplayDigestHeader,
        hashes: LongArray,
        offsets: IntArray,
    ): ReplayDigest = ReplayDigest(
        header = header,
        hashes = hashes,
        offsets = offsets,
        scopes = scopes.copyOf(size),
        netIds = netIds.copyOf(size),
        typeIds = typeIds.copyOf(size),
        fields = fields.copyOf(size),
        values = values.copyOf(size),
    )

    private fun grow() {
        val capacity = scopes.size * 2
        scopes = scopes.copyOf(capacity)
        netIds = netIds.copyOf(capacity)
        typeIds = typeIds.copyOf(capacity)
        fields = fields.copyOf(capacity)
        values = values.copyOf(capacity)
    }

    private companion object {
        const val INITIAL: Int = 1 shl 16
    }
}
