package dev.wildware.udea.replay.equality

import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.replication.ComponentTypeId
import dev.wildware.udea.core.snapshot.FieldKind
import dev.wildware.udea.core.snapshot.WorldHasher

/**
 * Builds digest streams by hand, cell by cell, for the tests that need a world that cannot exist.
 *
 * A test that asks "what does the report say when the two runs differ **only** in the random
 * stream" cannot get there by simulating: an extra draw moves a field on the very next tick. So it
 * is assembled here instead, against the same [ReplayDigest] the reader produces - `internal`
 * rather than a parallel type, because a second in-memory representation of a digest is a second
 * thing that can disagree with the file.
 *
 * The hash of each tick is **folded from the cells**, exactly as [ReplayDigestWriter] requires of a
 * real one. That keeps the invariant these fixtures are used to test true of the fixtures
 * themselves: a hand-built stream whose hash did not match its cells would be testing the wrong
 * thing and would look like a pass.
 */
internal class DigestBuilder(
    private val label: String,
    private val components: List<DigestComponentInfo> = listOf(DRIFTER),
    private val fixture: String = "hand-built",
) {

    private val hashes = ArrayList<Long>()
    private val offsets = ArrayList<Int>().apply { add(0) }
    private val scopes = ArrayList<Byte>()
    private val netIds = ArrayList<Int>()
    private val typeIds = ArrayList<Int>()
    private val fields = ArrayList<Int>()
    private val values = ArrayList<Long>()

    /** Appends one tick made of [cells], hashing it by folding them. */
    fun tick(vararg cells: Cell): DigestBuilder {
        var hash = WorldHasher.OFFSET_BASIS
        for (cell in cells) {
            scopes += cell.scope.ordinal.toByte()
            netIds += cell.netIdRaw
            typeIds += cell.typeIdRaw
            fields += cell.field
            values += cell.value
            hash = WorldHasher.fold(hash, cell.value)
        }
        hashes += hash
        offsets += scopes.size
        return this
    }

    /**
     * Appends one tick whose recorded hash is [hash] regardless of what its cells fold to.
     *
     * The only way to reach the one state `ReplayEquality` refuses outright - hashes that differ
     * while every cell agrees - which no `ReplayDigestWriter` can produce, and which therefore has
     * to be forged to be tested at all.
     */
    fun corruptTick(hash: Long, vararg cells: Cell): DigestBuilder {
        tick(*cells)
        hashes[hashes.size - 1] = hash
        return this
    }

    fun build(): ReplayDigest = ReplayDigest(
        header = ReplayDigestHeader(
            label = label,
            fixture = fixture,
            gameId = "hand-built",
            gameVersion = "1",
            firstTick = Tick.ZERO,
            tickCount = hashes.size,
            jvm = "test-jvm",
            os = "test-os",
            components = components,
        ),
        hashes = hashes.toLongArray(),
        offsets = offsets.toIntArray(),
        scopes = scopes.toByteArray(),
        netIds = netIds.toIntArray(),
        typeIds = typeIds.toIntArray(),
        fields = fields.toIntArray(),
        values = values.toLongArray(),
    )

    /** One cell, as a test writes it. */
    data class Cell(
        val scope: DigestScope,
        val netIdRaw: Int,
        val typeIdRaw: Int,
        val field: Int,
        val value: Long,
    )

    companion object {

        /** A component table entry standing in for the fixture world's `Drifter`. */
        val DRIFTER: DigestComponentInfo = DigestComponentInfo(
            typeId = ComponentTypeId(1),
            typeName = "Drifter",
            componentFqn = "dev.wildware.udea.replay.equality.fixture.Drifter",
            fieldNames = listOf("x", "y"),
            fieldKinds = listOf(FieldKind.Float, FieldKind.Float),
        )

        private const val TYPE_ID: Int = 1

        /** `rowCount`. */
        fun rowCount(rows: Int): Cell = Cell(
            DigestScope.RowCount, NetId.NONE.raw, ReplayDigestCells.NO_TYPE_ID,
            ReplayDigestCells.NO_FIELD, rows.toLong(),
        )

        /** A roster row's own id. */
        fun rosterNetId(netId: NetId): Cell = Cell(
            DigestScope.Roster, netId.raw, ReplayDigestCells.NO_TYPE_ID,
            ReplayDigestCells.ROSTER_NET_ID, netId.raw.toLong(),
        )

        /** A roster row's presence word. */
        fun presence(netId: NetId, word: Int, bits: Long): Cell =
            Cell(DigestScope.Roster, netId.raw, ReplayDigestCells.NO_TYPE_ID, word, bits)

        /** A component type's id, folded before its slot count. */
        fun componentType(): Cell = Cell(
            DigestScope.ComponentType, NetId.NONE.raw, TYPE_ID,
            ReplayDigestCells.NO_FIELD, TYPE_ID.toLong(),
        )

        /** A component type's occupied slot count. */
        fun componentSlots(used: Int): Cell = Cell(
            DigestScope.ComponentSlots, NetId.NONE.raw, TYPE_ID,
            ReplayDigestCells.NO_FIELD, used.toLong(),
        )

        /** One float field of one entity. */
        fun float(netId: NetId, field: Int, value: Float): Cell =
            Cell(DigestScope.Component, netId.raw, TYPE_ID, field, value.toRawBits().toLong())

        /** `SimClock.tick`. */
        fun clock(tick: Long): Cell = Cell(
            DigestScope.Clock, NetId.NONE.raw, ReplayDigestCells.NO_TYPE_ID,
            ReplayDigestCells.NO_FIELD, tick,
        )

        /** One word of the random streams' saved state. */
        fun rng(word: Int, value: Long): Cell =
            Cell(DigestScope.Rng, NetId.NONE.raw, ReplayDigestCells.NO_TYPE_ID, word, value)

        /** One word of the id allocator's state. */
        fun handle(field: Int, value: Long): Cell =
            Cell(DigestScope.Handles, NetId.NONE.raw, ReplayDigestCells.NO_TYPE_ID, field, value)
    }
}
