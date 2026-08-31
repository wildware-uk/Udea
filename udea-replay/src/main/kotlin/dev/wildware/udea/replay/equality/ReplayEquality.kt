package dev.wildware.udea.replay.equality

import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.snapshot.DivergenceReport

/** What one side held for a cell at one earlier tick, for the history a report prints. */
public class CellHistoryEntry(
    /** The tick. */
    public val tick: Tick,
    /** The value the first stream held, or `null` when the cell did not exist there. */
    public val expected: String?,
    /** The value the second stream held, or `null` when the cell did not exist there. */
    public val actual: String?,
) {

    /** True when both sides held the same thing at this tick. */
    public val agreed: Boolean get() = expected == actual
}

/**
 * One cell two machines disagreed about, with enough context to act on it.
 *
 * The shape is deliberately the same as `FieldDivergence`'s - entity, component, field, both
 * values - because an agent or a person who can read one should not have to learn a second
 * format. What it adds is [history], which the within-JVM report has no need of: a difference
 * found by rewinding a snapshot ring already has the whole world to hand, and a difference found
 * between two files does not.
 */
public class CrossRunCellDivergence(
    /** Which cell, as both streams name it. */
    public val key: DigestCellKey,
    /** The entity, or [NetId.NONE] for state that belongs to no entity. */
    public val netId: NetId,
    /**
     * The component's fully qualified name, or a bracketed pseudo-component.
     *
     * `<roster>`, `<clock>`, `<rng>` and `<handles>` are the four pseudo-components, and they are
     * why a divergence report is never empty: `WorldHasher` folds the clock, the random streams
     * and the id allocator, and none of the three is a field of anything.
     */
    public val componentName: String,
    /** The lowered field's name, or the pseudo-component's own. */
    public val fieldName: String,
    /** The first stream's value, rendered through the field's kind. */
    public val expected: String,
    /** The second stream's value, rendered through the field's kind. */
    public val actual: String,
    /** The same cell over the ticks leading up to the divergence, oldest first. */
    public val history: List<CellHistoryEntry>,
) {

    /** `netId component.field` — the first line of this divergence in a report. */
    public val label: String
        get() = if (netId.isNone) "$componentName.$fieldName" else "$netId $componentName.$fieldName"

    override fun toString(): String = "$label: expected $expected, got $actual"
}

/**
 * The verdict of comparing two platforms' digest streams.
 *
 * ## Why there is always a cell
 *
 * [ReplayDigestWriter] refuses to write a tick whose cells do not refold to the world hash, so
 * the cells at a tick *are* the hash's inputs. Two streams that disagree therefore disagree about
 * at least one cell, and [describe] can always name one. A bare "hash mismatch at tick N" is not
 * a sentence this class can produce - the one path that could have produced it, cells equal and
 * hashes different, means a stream that no `ReplayDigestWriter` wrote, and [ReplayEquality] fails
 * that as a corrupt input rather than reporting it as a divergence.
 */
public class ReplayEqualityResult(
    /** The stream named first in the comparison. */
    public val expected: ReplayDigestHeader,
    /** The stream named second. */
    public val actual: ReplayDigestHeader,
    /** How many ticks were compared before the answer was known. */
    public val ticksCompared: Int,
    /** The first tick the two disagreed at, or `null` when they agreed throughout. */
    public val tick: Tick?,
    /** [expected]'s hash at [tick]. Meaningless when there is none. */
    public val expectedHash: Long,
    /** [actual]'s hash at [tick]. */
    public val actualHash: Long,
    /** How many cells differ at [tick] in total, before the report's cap. */
    public val divergingCells: Int,
    /** The differing cells, capped at [DivergenceReport.MAX_REPORTED]. */
    public val divergences: List<CrossRunCellDivergence>,
) {

    /** True when every compared tick agreed, cell for cell. The claim this exists to make. */
    public val isEqual: Boolean get() = tick == null

    /** How many ticks matched before the first divergence, or all of them. */
    public val matchingTicks: Int
        get() = tick?.let { (it.value - expected.firstTick.value).toInt() } ?: ticksCompared

    /**
     * The whole verdict, as CI prints it.
     *
     * Capped at [DivergenceReport.MAX_REPORTED] cells for the reason that class gives: a world
     * that has diverged usually diverges everywhere, and one screenful of the first few beats ten
     * thousand lines of the rest.
     */
    public fun describe(): String {
        val builder = StringBuilder()
        if (isEqual) {
            builder.append("replay equality holds: ").append(ticksCompared)
                .append(" tick(s) of '").append(expected.fixture)
                .append("' are cell-for-cell identical")
            appendLegs(builder)
            return builder.toString()
        }
        builder.append("replay equality FAILED at ").append(tick)
            .append(" (").append(matchingTicks).append(" tick(s) matched first)")
        appendLegs(builder)
        builder.append("\n  world hash: ").append(expectedHash).append(" against ").append(actualHash)
        check(divergences.isNotEmpty()) {
            "a divergence at $tick named no cell. That is unreachable by construction - " +
                "ReplayDigestWriter refolds its cells into the world hash before writing them - " +
                "and reporting it as a hash mismatch would be exactly the bare hash this gate " +
                "exists to replace."
        }
        builder.append("\n  ").append(divergingCells).append(" differing cell(s):")
        for (divergence in divergences) appendDivergence(builder, divergence)
        if (divergingCells > divergences.size) {
            builder.append("\n    ... and ").append(divergingCells - divergences.size).append(" more")
        }
        return builder.toString()
    }

    private fun appendLegs(builder: StringBuilder) {
        builder.append("\n  fixture ").append(expected.fixture)
        builder.append("\n  A = '").append(expected.label).append("'  [").append(expected.os)
            .append("; ").append(expected.jvm).append(']')
        builder.append("\n  B = '").append(actual.label).append("'  [").append(actual.os)
            .append("; ").append(actual.jvm).append(']')
    }

    private fun appendDivergence(builder: StringBuilder, divergence: CrossRunCellDivergence) {
        builder.append("\n    ").append(divergence.label)
        builder.append("\n      A = ").append(divergence.expected)
        builder.append("\n      B = ").append(divergence.actual)
        if (divergence.history.isEmpty()) return
        builder.append("\n      the preceding ").append(divergence.history.size)
            .append(" tick(s) of this cell:")
        for (entry in divergence.history) {
            builder.append("\n        ").append(entry.tick)
                .append(if (entry.agreed) "  agreed  " else "  DIFFER  ")
                .append("A = ").append(entry.expected ?: ABSENT)
                .append(", B = ").append(entry.actual ?: ABSENT)
        }
    }

    override fun toString(): String = describe()

    private companion object {
        const val ABSENT: String = "<no such cell>"
    }
}

/** Two digest streams describe different things and cannot be compared. */
public class IncomparableDigestsException(
    /** Every reason, one per line in the message. */
    public val reasons: List<String>,
    message: String,
) : IllegalArgumentException(message)

/**
 * `replayEquals`: did two machines produce the same simulation from the same recording?
 *
 * This is the Phase 7 gate. `udeaVerifyDeterminism` is a bytecode reference scan and
 * `determinism-audit.md` §1 is the written list of what it structurally cannot see - float
 * differences across JVMs are the row that says "The cross-OS `replay-equality` CI job. Nothing
 * else." This is that job's arithmetic.
 */
public object ReplayEquality {

    /** How many earlier ticks of a differing cell a report prints. Spec 7 asks for five. */
    public const val HISTORY_TICKS: Int = 5

    /**
     * Compares [expected] against [actual], tick by tick and cell by cell.
     *
     * Cells rather than hashes, and that is not belt-and-braces. A hash is a 64-bit summary and
     * two different worlds can in principle share one; the cells *are* the state, so comparing
     * them removes the collision from the gate rather than arguing it is unlikely. The hash is
     * still recorded in the report, because it is the number every other part of this engine
     * talks about.
     *
     * @throws IncomparableDigestsException when the two streams describe different fixtures,
     *   games, tick ranges or component sets. Comparing those would match each side's cells
     *   against cells that mean something else and name fields that never diverged.
     * @throws IllegalStateException when a tick's hashes differ and none of its cells do, which
     *   means at least one stream was not written by [ReplayDigestWriter].
     */
    public fun replayEquals(expected: ReplayDigest, actual: ReplayDigest): ReplayEqualityResult {
        val reasons = expected.header.incomparabilitiesAgainst(actual.header)
        if (reasons.isNotEmpty()) {
            throw IncomparableDigestsException(
                reasons,
                "'${expected.header.label}' and '${actual.header.label}' cannot be compared:" +
                    reasons.joinToString("") { "\n  $it" },
            )
        }

        for (index in 0 until expected.tickCount) {
            if (ticksAgree(expected, actual, index)) continue
            return diverged(expected, actual, index)
        }
        return ReplayEqualityResult(
            expected = expected.header,
            actual = actual.header,
            ticksCompared = expected.tickCount,
            tick = null,
            expectedHash = 0L,
            actualHash = 0L,
            divergingCells = 0,
            divergences = emptyList(),
        )
    }

    /**
     * Whether the two streams hold identical cells at [index].
     *
     * An index-wise walk, because two agreeing runs emit their cells in the same canonical order
     * and the common case is the whole fixture agreeing. A key difference at the same index -
     * one side holding an entity the other does not - falls out of the same comparison and sends
     * the caller to the slower, keyed path that can say which entity.
     */
    private fun ticksAgree(expected: ReplayDigest, actual: ReplayDigest, index: Int): Boolean {
        // The hash first, and not as a shortcut - the cells are compared either way. Two streams
        // whose cells match while their recorded hashes do not is the one state no
        // `ReplayDigestWriter` can produce, so meeting it means a file has been truncated, edited
        // or written by something else, and the caller is told that rather than handed a "they
        // agree" that is built on a stream nobody should trust.
        if (expected.hashAt(index) != actual.hashAt(index)) return false
        val mine = expected.cellsOf(index)
        val theirs = actual.cellsOf(index)
        if (mine.last - mine.first != theirs.last - theirs.first) return false
        var a = mine.first
        var b = theirs.first
        while (a <= mine.last) {
            if (expected.valueAt(a) != actual.valueAt(b)) return false
            if (expected.scopeAt(a) != actual.scopeAt(b)) return false
            if (expected.netIdAt(a) != actual.netIdAt(b)) return false
            if (expected.typeIdAt(a) != actual.typeIdAt(b)) return false
            if (expected.fieldAt(a) != actual.fieldAt(b)) return false
            a++
            b++
        }
        return true
    }

    private fun diverged(
        expected: ReplayDigest,
        actual: ReplayDigest,
        index: Int,
    ): ReplayEqualityResult {
        val tick = expected.tickAt(index)
        val mine = cellMap(expected, index)
        val theirs = cellMap(actual, index)

        val keys = ArrayList<DigestCellKey>()
        for (cell in expected.cellsOf(index)) {
            val key = expected.keyAt(cell)
            if (theirs[key] != expected.valueAt(cell)) keys += key
        }
        for (cell in actual.cellsOf(index)) {
            val key = actual.keyAt(cell)
            if (!mine.containsKey(key)) keys += key
        }

        check(keys.isNotEmpty()) {
            "'${expected.header.label}' and '${actual.header.label}' disagree at $tick - hashes " +
                "${expected.hashAt(index)} and ${actual.hashAt(index)} - yet every cell matches. " +
                "ReplayDigestWriter refolds its cells into the world hash before writing them, so " +
                "at least one of these streams was not written by it and is corrupt. Re-run the " +
                "digest step rather than trusting either file."
        }

        val reported = keys.take(DivergenceReport.MAX_REPORTED).map { key ->
            describe(expected, actual, index, key, mine[key], theirs[key])
        }
        return ReplayEqualityResult(
            expected = expected.header,
            actual = actual.header,
            ticksCompared = index + 1,
            tick = tick,
            expectedHash = expected.hashAt(index),
            actualHash = actual.hashAt(index),
            divergingCells = keys.size,
            divergences = reported,
        )
    }

    private fun describe(
        expected: ReplayDigest,
        actual: ReplayDigest,
        index: Int,
        key: DigestCellKey,
        mine: Long?,
        theirs: Long?,
    ): CrossRunCellDivergence {
        val component = expected.componentOf(key.typeIdRaw) ?: actual.componentOf(key.typeIdRaw)
        val history = ArrayList<CellHistoryEntry>(ReplayEquality.HISTORY_TICKS)
        val from = maxOf(0, index - HISTORY_TICKS)
        for (earlier in from until index) {
            history += CellHistoryEntry(
                tick = expected.tickAt(earlier),
                expected = valueOf(expected, earlier, key, component),
                actual = valueOf(actual, earlier, key, component),
            )
        }
        return CrossRunCellDivergence(
            key = key,
            netId = key.netId,
            componentName = componentNameOf(key, component),
            fieldName = fieldNameOf(key, component),
            expected = render(mine, key, component),
            actual = render(theirs, key, component),
            history = history,
        )
    }

    /** Every cell of tick [index], by key. Built for the one tick a divergence was found at. */
    private fun cellMap(digest: ReplayDigest, index: Int): Map<DigestCellKey, Long> {
        val map = HashMap<DigestCellKey, Long>()
        for (cell in digest.cellsOf(index)) map[digest.keyAt(cell)] = digest.valueAt(cell)
        return map
    }

    /**
     * One earlier tick's value for [key], or `null` when the cell did not exist then.
     *
     * A linear walk of that tick's cells rather than an index, and deliberately so: it runs at most
     * `MAX_REPORTED * HISTORY_TICKS` times, only once a divergence has already been found, and only
     * in a process whose entire job is rendering one failure. Building a map per history tick would
     * cost more than the scan it replaced.
     */
    private fun valueOf(
        digest: ReplayDigest,
        index: Int,
        key: DigestCellKey,
        component: DigestComponentInfo?,
    ): String? {
        for (cell in digest.cellsOf(index)) {
            if (digest.keyAt(cell) == key) return render(digest.valueAt(cell), key, component)
        }
        return null
    }

    private fun render(
        value: Long?,
        key: DigestCellKey,
        component: DigestComponentInfo?,
    ): String {
        if (value == null) return "<no such cell>"
        return when (key.scope) {
            DigestScope.Component -> component?.render(key.field, value) ?: value.toString()
            DigestScope.Roster ->
                if (key.field == ReplayDigestCells.ROSTER_NET_ID) {
                    NetId.ofRaw(value.toInt()).toString()
                } else {
                    "0x" + value.toULong().toString(HEX)
                }

            else -> value.toString()
        }
    }

    private fun componentNameOf(key: DigestCellKey, component: DigestComponentInfo?): String =
        when (key.scope) {
            DigestScope.Component, DigestScope.ComponentType, DigestScope.ComponentSlots ->
                component?.componentFqn ?: "<component ${key.typeIdRaw}>"

            DigestScope.RowCount, DigestScope.Roster -> ROSTER
            DigestScope.Clock -> CLOCK
            DigestScope.Rng -> RNG
            DigestScope.Handles -> HANDLES
        }

    private fun fieldNameOf(key: DigestCellKey, component: DigestComponentInfo?): String =
        when (key.scope) {
            DigestScope.Component -> component?.nameOf(key.field) ?: "<field ${key.field}>"
            DigestScope.ComponentType -> "<typeId>"
            DigestScope.ComponentSlots -> "<slotsUsed>"
            DigestScope.RowCount -> "rowCount"
            DigestScope.Roster ->
                if (key.field == ReplayDigestCells.ROSTER_NET_ID) "netId" else "presence[${key.field}]"

            DigestScope.Clock -> "tick"
            DigestScope.Rng -> "word[${key.field}]"
            DigestScope.Handles -> handleFieldName(key.field)
        }

    /**
     * The name of one word of the id allocator's state.
     *
     * The free list is `(index, generation)` pairs from [ReplayDigestCells.HANDLE_FREE_BASE], so
     * a divergence in it reads as `free[3].generation` rather than as a word number nobody can
     * map back to `HandleState`.
     */
    private fun handleFieldName(field: Int): String = when (field) {
        ReplayDigestCells.HANDLE_NEXT_FRESH -> "nextFresh"
        ReplayDigestCells.HANDLE_HIGH_WATER -> "highWater"
        ReplayDigestCells.HANDLE_FREE_COUNT -> "freeCount"
        else -> {
            val offset = field - ReplayDigestCells.HANDLE_FREE_BASE
            val position = offset / HANDLE_FREE_STRIDE
            if (offset % HANDLE_FREE_STRIDE == 0) "free[$position].index" else "free[$position].generation"
        }
    }

    private const val HANDLE_FREE_STRIDE: Int = 2
    private const val HEX: Int = 16
    private const val ROSTER: String = "<roster>"
    private const val CLOCK: String = "<clock>"
    private const val RNG: String = "<rng>"
    private const val HANDLES: String = "<handles>"
}
