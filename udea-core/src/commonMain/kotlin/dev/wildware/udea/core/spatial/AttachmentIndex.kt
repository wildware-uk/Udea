package dev.wildware.udea.core.spatial

import dev.wildware.udea.core.identity.NetId

/**
 * What is mounted **on** an entity: the other way round from [AttachedTo] (issue #270).
 *
 * [AttachedTo] points a part at its parent, and a game constantly needs the reverse - swap the
 * part in this socket, blow the parts off a wreck, count what a unit is carrying, draw a
 * loadout in the HUD. Without this, answering any of those means walking every entity in the
 * world that carries an [AttachedTo] and comparing parents, once per question:
 *
 * ```
 * // what the engine used to make a game write
 * family { all(AttachedTo) }.forEach { part -> if (part[AttachedTo].parent == parent) found += part }
 * ```
 *
 * With this, the same question is two array reads:
 *
 * ```
 * val attachments = ctx[CoreModule.ATTACHMENTS]
 * attachments.forEachChild(chassis) { part, node -> blowOff(part) }   // everything mounted on it
 * val turret = attachments.childOf(chassis, Chassis.Nodes.socket_roof) // the part in one socket
 * ```
 *
 * ## It is derived, and holds nothing of its own
 *
 * [AttachmentSystem] rebuilds it from scratch every tick, in `PostPhysics`, out of the very
 * [AttachedTo] components it is already reading to place each part. Nothing here is state: a
 * rewind, a snapshot restore, a level load or a replay re-derives the whole index on the next
 * tick from components the snapshot already carries, so there is no per-entity routing state
 * outside the components for a restore to leave stale. `udea-nav` (issue #264) is built on the
 * same rule for the same reason.
 *
 * That has one consequence a caller has to know: **the answers are the mounts as of the last
 * tick [AttachmentSystem] ran.** A system in `Gameplay` - after `PostPhysics` - sees this
 * tick's; one in `PreSimulation` sees last tick's; a part mounted between ticks through the
 * barrier appears on the tick after the barrier drains it. That is the same rule the placed
 * transforms already follow.
 *
 * ## Order
 *
 * A parent's children come back in ascending [NetId] order, never in family order. Fleks
 * iterates a family in entity-id order, and a Fleks entity id is *not* stable across a
 * snapshot restore - the ids are re-minted as the roster is rebuilt, while [NetId]s are rebound
 * exactly as captured. Sorting here is what makes "the first part on this chassis" mean the same
 * thing before and after a rewind (spec 5's entity identity contract).
 *
 * ## What is in it
 *
 * Every mount whose parent resolves to a live entity and whose part has a [NetId] of its own.
 * A part whose parent has been destroyed is not indexed, and nothing is lost by that: the only
 * id that could name that parent is stale, so no query can reach it. A stale id asked for here
 * answers empty rather than answering with whatever now occupies its index - the generation is
 * compared, not just the index.
 *
 * ## Cost
 *
 * The rebuild is `O(mounts)` per tick with no allocation at steady state; a question is array
 * reads and does not depend on how big the world is. Not thread-safe: like the rest of the
 * kernel it belongs to one simulation on one thread.
 */
public class AttachmentIndex internal constructor() {

    /**
     * The rebuild in progress. A `Long` so it cannot wrap: at 60Hz it would take longer than
     * the age of the universe, which removes a branch that could only ever be reasoned about
     * rather than tested.
     */
    private var epoch: Long = 0L

    /** The last **completed** rebuild. Queries compare against this, so a half-built index reads empty. */
    private var readable: Long = NEVER

    /**
     * Per parent index: the epoch its bucket below was written in, or **zero** for an index no
     * rebuild has ever touched - which is every index until one is, because the arrays are
     * zero-filled and [beginRebuild] increments before the first rebuild. Not [NEVER]; see there.
     */
    private var parentEpoch = LongArray(INITIAL_PARENTS)

    /** Per parent index: the parent's whole [NetId.raw], so a stale generation answers nothing. */
    private var parentRaw = IntArray(INITIAL_PARENTS)

    /** Per parent index: where its children start in [childRaw]. */
    private var bucketStart = IntArray(INITIAL_PARENTS)

    /** Per parent index: how many children it has. */
    private var bucketCount = IntArray(INITIAL_PARENTS)

    /** Per parent index: the write cursor [endRebuild] fills its run with. */
    private var bucketFill = IntArray(INITIAL_PARENTS)

    /** The parent indices written this rebuild, so no array is cleared per tick. */
    private var touched = IntArray(INITIAL_PARENTS)
    private var touchedCount = 0

    /** One entry per mount, in the order [add] received them. */
    private var entryParent = IntArray(INITIAL_MOUNTS)
    private var entryChild = IntArray(INITIAL_MOUNTS)
    private var entryNode = IntArray(INITIAL_MOUNTS)
    private var entryCount = 0

    /** The children, one run per parent, each run ascending by [NetId]. */
    private var childRaw = IntArray(INITIAL_MOUNTS)
    private var childNode = IntArray(INITIAL_MOUNTS)

    /** How many mounts the last completed rebuild indexed. */
    public val mountCount: Int get() = if (readable == epoch) entryCount else 0

    /**
     * How many parts are mounted on [parent], directly. A part mounted on one of those parts is
     * that part's child, not this one's.
     */
    public fun childCount(parent: NetId): Int {
        val bucket = bucketOf(parent)
        return if (bucket < 0) 0 else bucketCount[bucket]
    }

    /**
     * The [position]th part mounted on [parent], in ascending [NetId] order.
     *
     * @throws IndexOutOfBoundsException if [position] is not `0 until childCount(parent)`.
     */
    public fun childAt(parent: NetId, position: Int): NetId =
        NetId.ofRaw(childRaw[slotOf(parent, position)])

    /**
     * The node the [position]th part is mounted on: a [ModelNode.index], or [ModelNode.NONE].
     *
     * @throws IndexOutOfBoundsException if [position] is not `0 until childCount(parent)`.
     */
    public fun nodeAt(parent: NetId, position: Int): Int = childNode[slotOf(parent, position)]

    /** Where [parent]'s [position]th child sits in [childRaw] and [childNode]. */
    private fun slotOf(parent: NetId, position: Int): Int {
        val bucket = bucketOf(parent)
        val count = if (bucket < 0) 0 else bucketCount[bucket]
        if (position !in 0 until count) {
            throw IndexOutOfBoundsException(
                "$parent has $count part(s) mounted on it; there is none at position $position",
            )
        }
        return bucketStart[bucket] + position
    }

    /**
     * The part in [parent]'s [node] socket, or [NetId.NONE] when nothing is in it: the swap case.
     *
     * Nothing stops a game mounting two parts on one socket, so when several are there this is
     * the lowest-[NetId] one - the same order [childAt] walks. Use [forEachChild] to see all of
     * them.
     */
    public fun childOf(parent: NetId, node: Int): NetId {
        val bucket = bucketOf(parent)
        if (bucket < 0) return NetId.NONE
        val start = bucketStart[bucket]
        for (offset in 0 until bucketCount[bucket]) {
            if (childNode[start + offset] == node) return NetId.ofRaw(childRaw[start + offset])
        }
        return NetId.NONE
    }

    /** [childOf] by the node a part was mounted with: `childOf(chassis, Chassis.Nodes.socket_roof)`. */
    public fun childOf(parent: NetId, node: ModelNode): NetId = childOf(parent, node.index)

    /**
     * Visits every part mounted on [parent], in ascending [NetId] order, with the node each one
     * is on.
     *
     * [AttachmentVisitor] is a `fun interface` so the [NetId] handed to it is not boxed - the
     * same reason `NetIdVisitor` is one.
     */
    public fun forEachChild(parent: NetId, visitor: AttachmentVisitor) {
        val bucket = bucketOf(parent)
        if (bucket < 0) return
        val start = bucketStart[bucket]
        for (offset in 0 until bucketCount[bucket]) {
            visitor.visit(NetId.ofRaw(childRaw[start + offset]), childNode[start + offset])
        }
    }

    // --- the rebuild -------------------------------------------------------------------------
    // Three members, called by AttachmentSystem and by nothing else. They are `internal` because
    // an index a game could write would stop being derived, which is the property the whole
    // class rests on.

    /**
     * Starts a rebuild.
     *
     * Between this and [endRebuild] every query answers **empty**, not the previous rebuild: the
     * epoch has moved on and nothing has been published at it yet, while [add] is already
     * overwriting the previous rebuild's counts. Nothing observes that window - both calls happen
     * inside one `AttachmentSystem.onTick`, before any other system runs - and answering empty is
     * the safe half of the choice, because a half-built index that answered would answer wrongly.
     */
    internal fun beginRebuild() {
        epoch++
        touchedCount = 0
        entryCount = 0
    }

    /**
     * Records that [child] is mounted on [parent]'s [node].
     *
     * @throws IllegalStateException if two mounts in one rebuild name the same [NetId] index at
     *   different generations, which would mean a destroyed parent's children were being indexed
     *   beside a live one's. [AttachmentSystem] only offers mounts whose parent resolves, so
     *   reaching this is a defect in the caller rather than in a game's data.
     */
    internal fun add(child: NetId, parent: NetId, node: Int) {
        if (child.isNone || parent.isNone) return
        val index = parent.index
        ensureParents(index + 1)
        if (parentEpoch[index] != epoch) {
            parentEpoch[index] = epoch
            parentRaw[index] = parent.raw
            bucketCount[index] = 0
            ensureTouched(touchedCount + 1)
            touched[touchedCount++] = index
        } else {
            check(parentRaw[index] == parent.raw) {
                "NetId index $index was offered as both ${NetId.ofRaw(parentRaw[index])} and " +
                    "$parent in one rebuild; only a live parent may be indexed"
            }
        }
        bucketCount[index]++
        ensureEntries(entryCount + 1)
        entryParent[entryCount] = index
        entryChild[entryCount] = child.raw
        entryNode[entryCount] = node
        entryCount++
    }

    /** Publishes the rebuild: lays the children out parent by parent, each run sorted. */
    internal fun endRebuild() {
        ensureChildren(entryCount)
        var start = 0
        for (position in 0 until touchedCount) {
            val parent = touched[position]
            bucketStart[parent] = start
            bucketFill[parent] = 0
            start += bucketCount[parent]
        }
        for (entry in 0 until entryCount) {
            val parent = entryParent[entry]
            val at = bucketStart[parent] + bucketFill[parent]
            bucketFill[parent]++
            childRaw[at] = entryChild[entry]
            childNode[at] = entryNode[entry]
        }
        for (position in 0 until touchedCount) {
            val parent = touched[position]
            sortRun(bucketStart[parent], bucketCount[parent])
        }
        readable = epoch
    }

    /**
     * Sorts one parent's run ascending by [NetId], carrying each child's node with it.
     *
     * Insertion sort because a run is a unit's parts - a handful, never a population - and an
     * insertion sort over a handful beats anything that allocates a comparator to do better.
     */
    private fun sortRun(start: Int, count: Int) {
        for (position in 1 until count) {
            val at = start + position
            val raw = childRaw[at]
            val node = childNode[at]
            val key = order(raw)
            var hole = at
            while (hole > start && order(childRaw[hole - 1]) > key) {
                childRaw[hole] = childRaw[hole - 1]
                childNode[hole] = childNode[hole - 1]
                hole--
            }
            childRaw[hole] = raw
            childNode[hole] = node
        }
    }

    /**
     * The sort key of a raw [NetId] word: index first, generation second, which is [NetId]'s own
     * order.
     *
     * Not the raw word itself. A raw word carries the generation in the bits *above* the index,
     * so sorting by it would sort by generation first - an order that is still deterministic and
     * still wrong, because it would put a recycled index ahead of a lower one.
     */
    private fun order(raw: Int): Int =
        ((raw and NetId.INDEX_MASK) shl NetId.GENERATION_BITS) or (raw ushr NetId.INDEX_BITS)

    /** The slot holding [parent]'s children in the last completed rebuild, or -1 for none. */
    private fun bucketOf(parent: NetId): Int {
        if (parent.isNone || readable != epoch) return -1
        val index = parent.index
        if (index >= parentEpoch.size) return -1
        if (parentEpoch[index] != readable) return -1
        if (parentRaw[index] != parent.raw) return -1
        return index
    }

    private fun ensureParents(size: Int) {
        if (size <= parentEpoch.size) return
        var grown = parentEpoch.size
        while (grown < size) grown *= 2
        // `copyOf` zero-fills the new tail, and zero is no rebuild's epoch - `beginRebuild`
        // increments before the first one - so a parent index never written to reads as belonging
        // to no rebuild, which is what `bucketOf` needs it to say. See NEVER for why `readable`
        // starts at a different number.
        parentEpoch = parentEpoch.copyOf(grown)
        parentRaw = parentRaw.copyOf(grown)
        bucketStart = bucketStart.copyOf(grown)
        bucketCount = bucketCount.copyOf(grown)
        bucketFill = bucketFill.copyOf(grown)
    }

    private fun ensureTouched(size: Int) {
        if (size <= touched.size) return
        var grown = touched.size
        while (grown < size) grown *= 2
        touched = touched.copyOf(grown)
    }

    private fun ensureEntries(size: Int) {
        if (size <= entryParent.size) return
        var grown = entryParent.size
        while (grown < size) grown *= 2
        entryParent = entryParent.copyOf(grown)
        entryChild = entryChild.copyOf(grown)
        entryNode = entryNode.copyOf(grown)
    }

    private fun ensureChildren(size: Int) {
        if (size <= childRaw.size) return
        var grown = childRaw.size
        while (grown < size) grown *= 2
        childRaw = childRaw.copyOf(grown)
        childNode = childNode.copyOf(grown)
    }

    override fun toString(): String = "AttachmentIndex($mountCount mount(s) on $touchedCount parent(s))"

    private companion object {
        /**
         * An epoch no rebuild has, and deliberately **not** zero.
         *
         * Two different "never"s meet here and they must not be the same number. [parentEpoch] is
         * grown with `copyOf`, which zero-fills, so *zero* has to mean "this parent index has never
         * been written" - and it does, because [beginRebuild] increments before the first rebuild,
         * so no rebuild is ever epoch zero. [readable] needs a different one: it starts before any
         * rebuild has been published, and if it started at the same zero [epoch] does, the two
         * would compare equal and every query before the first tick would read the never-written
         * arrays instead of answering empty.
         */
        const val NEVER: Long = -1L

        /** Room for this many parent indices and mounts before the arrays grow. */
        const val INITIAL_PARENTS = 64
        const val INITIAL_MOUNTS = 64
    }
}

/**
 * Callback for [AttachmentIndex.forEachChild]: one mounted part and the node it is on.
 *
 * A `fun interface` so the [NetId] stays unboxed, exactly as `NetIdVisitor` is one.
 */
public fun interface AttachmentVisitor {
    /**
     * @param child the mounted part.
     * @param node the parent node it is mounted on: a [ModelNode.index], or [ModelNode.NONE].
     */
    public fun visit(child: NetId, node: Int)
}
