package dev.wildware.udea.core.identity

import com.github.quillraven.fleks.Entity

/**
 * The one place a [NetId] becomes a Fleks `Entity` and back.
 *
 * This is the boundary translator, and the only type in the engine that is allowed to hold
 * both halves. Everything upstream of it — packets, snapshots, agent tool calls — speaks
 * [NetId]; everything downstream speaks `Entity`.
 *
 * ## Why it exists
 *
 * `common/utils.kt:35-36` resolved an inbound network entity with a linear `find` over the
 * `Networkable` family, once per packet. That is `O(entities x packets)` every tick, and it
 * got worse exactly when the game got busy. Here both directions are array reads:
 *
 * - forward, [resolveOrNull]: three array reads at `netId.index`;
 * - reverse, [netIdOf]: one array read at `entity.id` plus one verification.
 *
 * Neither allocates, and neither cost depends on how many ids are live.
 *
 * ## Recycling and staleness
 *
 * Freed indices go on a **FIFO** queue, so the index freed longest ago is the next one
 * handed out. Combined with the 8-bit generation this means a stale [NetId] can only alias
 * a live entity after its index has been through 256 full allocate/free cycles, and the
 * FIFO ordering pushes that as far out as the population allows. Until then a stale id
 * resolves to `null`, which is a detectable error rather than a silent hit on the wrong
 * entity.
 *
 * Fresh indices are only minted once the free queue is empty, which keeps the index space
 * dense — that density is what lets the backing arrays be flat and the ids be 16 bits.
 *
 * ## Capacity
 *
 * [capacity] live ids, 65 536 by default, preallocated. Preallocation is not premature: it
 * is what makes [allocate] and [free] allocation-free at steady state, which the snapshot
 * ring's per-tick budget depends on. Exceeding it raises [NetIdExhaustedException] rather
 * than crashing on an array bound.
 *
 * Not thread-safe: like the rest of the kernel it belongs to one simulation on one thread.
 */
public class NetIdIndex(
    public val capacity: Int = NetId.MAX_INDICES,
    entityCapacity: Int = capacity,
) {
    init {
        require(capacity in 1..NetId.MAX_INDICES) {
            "capacity must be in 1..${NetId.MAX_INDICES}, was $capacity"
        }
        require(entityCapacity >= 1) { "entityCapacity must be positive, was $entityCapacity" }
    }

    /** Live entity per index; `null` where the index is free. Holds the caller's instance. */
    private val entities = arrayOfNulls<Entity>(capacity)

    /** Current generation per index. Bumped on [free], wrapping at [NetId.GENERATION_MODULUS]. */
    private val generations = IntArray(capacity)

    /** Occupancy per index, kept separate so a null entity can never be mistaken for live. */
    private val liveFlags = BooleanArray(capacity)

    /** FIFO ring of free indices. */
    private val freeRing = IntArray(capacity)
    private var freeHead = 0
    private var freeTail = 0
    private var freeSize = 0

    /** Lowest index never yet handed out. */
    private var nextFresh = 0

    /** One past the highest index ever handed out; bounds [forEachLive]. */
    private var highWater = 0

    /** `entity.id -> NetId.raw`. Grows only when a larger Fleks entity id is first seen. */
    private var reverse = IntArray(entityCapacity) { NONE_RAW }

    /** How many ids are currently live. Includes ids [reserve]d but not yet [attach]ed. */
    public var liveCount: Int = 0
        private set

    /**
     * Ids taken by [reserve] that no entity has been [attach]ed to yet.
     *
     * Non-zero only between a `BlueprintSpawner.spawn` and the barrier drain that applies it.
     * A number that stays non-zero across ticks means a spawn was submitted and its action
     * never ran — the id is live, resolves to nothing, and will never be handed out again.
     */
    public var reservedCount: Int = 0
        private set

    /**
     * Assigns the next id to [entity].
     *
     * @throws NetIdExhaustedException when [capacity] ids are already live.
     * @throws IllegalArgumentException when [entity] already holds an id.
     */
    public fun allocate(entity: Entity): NetId {
        require(netIdOf(entity).isNone) { "Entity $entity already has a NetId" }

        val index = takeIndex()

        entities[index] = entity
        liveFlags[index] = true
        liveCount++
        if (index >= highWater) highWater = index + 1

        val id = NetId.of(index, generations[index])
        ensureReverseCapacity(entity.id)
        reverse[entity.id] = id.raw
        return id
    }

    /**
     * Takes the next id with no entity behind it yet, to be completed by [attach].
     *
     * The half of [allocate] a between-tick mutation needs. `BlueprintSpawner.spawn` has to
     * hand its caller a [NetId] *at submit time* — an agent that spawns a blueprint and names
     * it in the next tool call cannot wait for a barrier drain to learn what it spawned — but
     * it must not create the entity until that drain, or the world would be mutated in the
     * middle of a tick, which is the one thing `SimBarrier` exists to prevent.
     *
     * Between the two calls the index is **live**: [allocate] will never hand it out again and
     * neither will [reserve]. It resolves to `null`, exactly as a stale id does, and
     * [forEachLive] skips it — so a snapshot taken while a spawn is queued simply does not
     * contain the entity that does not exist yet, rather than containing a row with no
     * component data. Being in none of the places a restore rebuilds from is exactly why
     * [saveInto] has to record it explicitly; see there.
     *
     * @throws NetIdExhaustedException when [capacity] ids are already live.
     */
    public fun reserve(): NetId {
        val index = takeIndex()

        entities[index] = null
        liveFlags[index] = true
        liveCount++
        reservedCount++
        if (index >= highWater) highWater = index + 1

        return NetId.of(index, generations[index])
    }

    /**
     * Completes a [reserve] by putting [entity] behind [netId].
     *
     * Distinct from [bind], which reinstates an id a snapshot recorded and therefore requires
     * the index to be *free*. This requires it to be an outstanding reservation, which is what
     * makes "the id I was handed" and "the id this entity got" the same id by construction
     * rather than by the caller remembering to pass the right one.
     *
     * @throws IllegalStateException if [netId] is not an outstanding reservation — already
     *   attached, freed, stale, or never reserved. Loud, because the alternative is a spawned
     *   entity with no identity and an id an agent is holding that will never resolve.
     * @throws IllegalArgumentException if [entity] already holds an id.
     */
    public fun attach(entity: Entity, netId: NetId) {
        require(!netId.isNone) { "NetId.NONE names no reservation and cannot be attached to" }
        val index = netId.index
        require(index < capacity) { "NetId index $index exceeds this index's capacity $capacity" }
        check(isOutstandingReservation(netId)) {
            "$netId is not an outstanding reservation; reserve() it before attaching an entity"
        }
        require(netIdOf(entity).isNone) { "Entity $entity already has a NetId" }

        entities[index] = entity
        reservedCount--
        ensureReverseCapacity(entity.id)
        reverse[entity.id] = netId.raw
    }

    /**
     * True if [netId] is a [reserve]d index that no entity has been [attach]ed to yet.
     *
     * The predicate form of what [attach] checks, so a caller holding a reservation from an
     * earlier tick can ask whether it survived — a rewind past the submission unwinds it — and
     * skip the work instead of creating an entity and discovering during [attach] that it has
     * no identity to give it. `BlueprintSpawner`'s queued spawn is the caller this exists for.
     */
    public fun isOutstandingReservation(netId: NetId): Boolean {
        if (netId.isNone) return false
        val index = netId.index
        if (index >= capacity) return false
        return liveFlags[index] && generations[index] == netId.generation && entities[index] == null
    }

    /**
     * The next index to hand out: recycled if one is waiting, otherwise fresh.
     *
     * Shared by [allocate] and [reserve] so the two can never disagree about the FIFO
     * recycling order — which is the property the generation counter's staleness guarantee is
     * measured against.
     */
    private fun takeIndex(): Int = when {
        freeSize > 0 -> {
            val recycled = freeRing[freeHead]
            freeHead++
            if (freeHead == capacity) freeHead = 0
            freeSize--
            recycled
        }

        nextFresh < capacity -> nextFresh++
        else -> throw NetIdExhaustedException(capacity)
    }

    /**
     * Releases [netId], bumping its index's generation so the id becomes stale.
     *
     * @return true if the id was live and has been released; false if it was already stale,
     *   already free, or [NetId.NONE]. Never throws: a double free is a caller mistake worth
     *   reporting, not worth crashing a tick over.
     */
    public fun free(netId: NetId): Boolean {
        val index = netId.index
        if (netId.isNone || index >= capacity) return false
        if (!liveFlags[index]) return false
        if (generations[index] != netId.generation) return false

        val entity = entities[index]
        if (entity != null) {
            val entityId = entity.id
            if (entityId >= 0 && entityId < reverse.size && reverse[entityId] == netId.raw) {
                reverse[entityId] = NONE_RAW
            }
        } else {
            // A live index with no entity is an outstanding `reserve`. Freeing one is how a
            // spawn that will never be applied gives its index back; the counter has to follow
            // or `reservedCount` would report a queued spawn forever.
            reservedCount--
        }

        entities[index] = null
        liveFlags[index] = false
        liveCount--
        generations[index] = (generations[index] + 1) and NetId.GENERATION_MASK

        freeRing[freeTail] = index
        freeTail++
        if (freeTail == capacity) freeTail = 0
        freeSize++
        return true
    }

    /**
     * Resolves [netId] in O(1), or `null` if it is stale, free or [NetId.NONE].
     *
     * A `null` here means "that entity is gone", and the caller must handle it. It is never
     * the wrong entity: that is the whole point of the generation counter.
     */
    public fun resolveOrNull(netId: NetId): Entity? {
        if (netId.isNone) return null
        val index = netId.index
        if (index >= capacity) return null
        if (!liveFlags[index]) return null
        if (generations[index] != netId.generation) return null
        return entities[index]
    }

    /** True if [netId] currently resolves to a live entity. */
    public operator fun contains(netId: NetId): Boolean = resolveOrNull(netId) != null

    /**
     * The id assigned to [entity], or [NetId.NONE] if it has none.
     *
     * The reverse array is keyed by Fleks entity id, which Fleks itself recycles, so the
     * candidate is verified against the forward table before it is returned. Without that,
     * a recycled Fleks slot would inherit the previous occupant's [NetId].
     */
    public fun netIdOf(entity: Entity): NetId {
        val entityId = entity.id
        if (entityId < 0 || entityId >= reverse.size) return NetId.NONE
        val raw = reverse[entityId]
        if (raw == NONE_RAW) return NetId.NONE
        val candidate = NetId.ofRaw(raw)
        return if (resolveOrNull(candidate) == entity) candidate else NetId.NONE
    }

    /**
     * Visits every live id in ascending [NetId] order — the capture order (spec 5).
     *
     * Ascending index, not insertion order, so two processes holding the same live set
     * capture in the same sequence no matter how their free lists churned. The scan is
     * bounded by the high-water mark rather than [capacity], so a simulation with 50 live
     * entities does not walk 65 536 slots.
     *
     * [NetIdVisitor] is a `fun interface` rather than a function type so the [NetId] passed
     * to it is not boxed.
     */
    public fun forEachLive(visitor: NetIdVisitor) {
        for (index in 0 until highWater) {
            if (!liveFlags[index]) continue
            val entity = entities[index] ?: continue
            visitor.visit(NetId.of(index, generations[index]), entity)
        }
    }

    /** Drops every id. Generations are preserved, so ids handed out before still read stale. */
    public fun clear() {
        for (index in 0 until highWater) {
            if (liveFlags[index]) free(NetId.of(index, generations[index]))
        }
    }

    /**
     * Drops every id **and** the recycling history, so the next [allocate] returns index zero.
     *
     * Stronger than [clear], and only correct where the whole population is being replaced: a
     * scene swap. After [clear] the free queue still holds the ids the previous population
     * used, in the order they were released — so loading the same scene twice in one process
     * lays its entities out at different indices depending on what ran in between, and two
     * clients that took different routes to the same level disagree about which index is which.
     * Resetting the queue makes a scene load a pure function of the scene.
     *
     * Generations are **not** reset. An id captured before the reset must still read stale, or
     * a reference held across a scene swap would silently resolve to whatever occupies its
     * index in the new scene — which is the aliasing bug the generation counter exists for.
     */
    public fun reset() {
        clear()
        freeHead = 0
        freeTail = 0
        freeSize = 0
        nextFresh = 0
        highWater = 0
    }

    // --- snapshot restore ------------------------------------------------------------------
    // Three members, and nothing else, so that a rewind hands out the same ids the original
    // run did. Without them a restored world would re-allocate different ids for everything
    // spawned after the snapshot, and the snapshot-equivalence gate would fail on entity
    // identity rather than on gameplay. See [HandleState] for what is recorded and why the
    // generations of live indices are deliberately not.

    /**
     * Records the allocator state — the free queue, its generations, and the two watermarks —
     * into [state], which is cleared first.
     *
     * A live index's generation is already carried by its [NetId] in the snapshot's rows, and
     * an index at or above `nextFresh` has never been handed out, so its generation is zero by
     * construction. Everything else — every index the restored world will not account for —
     * has to be here, or it is lost.
     *
     * ## Outstanding reservations are recorded as free
     *
     * A [reserve]d index is live-but-empty, so [forEachLive] skips it and the snapshot's roster
     * has no row for it. It is also not in [freeRing] and is below [nextFresh]. If only the
     * free ring were written here, [restoreFrom] would leave that index neither free, nor
     * fresh, nor in the roster: [takeIndex] could never hand it out again, and every rewind
     * across a queued spawn — the normal case, because `BlueprintSpawner.spawn` reserves at
     * submit time and the barrier applies it a tick later — would leak one index permanently
     * until the id space ran out.
     *
     * So each one is appended to the free queue, with its generation bumped exactly as [free]
     * bumps it. The bump is not cosmetic: without it the index would be re-minted at the
     * generation the in-flight spawn is still holding, and that stale id would alias the new
     * occupant — the one thing the generation counter exists to prevent. With it, the reserved
     * id reads stale from the restore onwards and [attach] refuses it loudly.
     *
     * They go **after** the ring's own entries, which is the FIFO order [free] would have
     * produced had the reservations been abandoned at the moment of capture.
     */
    public fun saveInto(state: HandleState) {
        state.reset()
        state.nextFresh = nextFresh
        state.highWater = highWater
        var position = freeHead
        repeat(freeSize) {
            val index = freeRing[position]
            state.addFree(index, generations[index])
            position++
            if (position == capacity) position = 0
        }
        if (reservedCount == 0) return
        for (index in 0 until highWater) {
            if (!liveFlags[index] || entities[index] != null) continue
            state.addFree(index, (generations[index] + 1) and NetId.GENERATION_MASK)
        }
    }

    /**
     * Resets this index to [state]: no live ids, the recorded free queue, the recorded
     * watermarks.
     *
     * Every live id is dropped **without** bumping its generation, unlike [free] — a restore
     * is not a destruction, and bumping here would make every id in the snapshot stale before
     * [bind] could reinstate it. The caller re-binds the snapshot's roster afterwards.
     */
    public fun restoreFrom(state: HandleState) {
        require(state.nextFresh in 0..capacity) {
            "HandleState.nextFresh ${state.nextFresh} is outside 0..$capacity"
        }
        require(state.highWater in 0..capacity) {
            "HandleState.highWater ${state.highWater} is outside 0..$capacity"
        }
        require(state.freeCount <= capacity) {
            "HandleState carries ${state.freeCount} free indices, more than the $capacity available"
        }

        for (index in 0 until highWater) {
            entities[index] = null
            liveFlags[index] = false
            generations[index] = 0
        }
        reverse.fill(NONE_RAW)
        liveCount = 0
        // A restore replaces the whole population, and a reservation names an entity in a
        // future that has just been unwound. `saveInto` recorded every reservation outstanding
        // at capture time into the free queue with a bumped generation, so those indices come
        // back below as free and recyclable, and the id an in-flight spawn is holding reads
        // stale from here on — which is what `attach` refuses loudly rather than binding into
        // a world that never asked for it. A reservation taken *after* the capture needs no
        // record: the index it drew is either still in the snapshot's free queue or still
        // above the snapshot's `nextFresh`, so restoring the two watermarks hands it back.
        reservedCount = 0

        freeHead = 0
        freeTail = state.freeCount % capacity
        freeSize = state.freeCount
        for (position in 0 until state.freeCount) {
            val index = state.freeIndexAt(position)
            require(index in 0 until capacity) {
                "HandleState free index $index is outside 0 until $capacity"
            }
            freeRing[position] = index
            generations[index] = state.freeGenerationAt(position)
        }

        nextFresh = state.nextFresh
        highWater = state.highWater
    }

    /**
     * Reinstates [netId] on [entity] exactly as captured, generation included.
     *
     * For snapshot restore, and for nothing else: allocation goes through [allocate], which
     * is the only thing that may choose an id. This exists because a rewind must give a
     * re-created entity the *same* id it had — a new one would make every reference held by
     * another restored component point at nothing.
     *
     * @throws IllegalArgumentException if the index is already live. Immediately after
     *   [restoreFrom] no index is, and the free queue provably excludes every index in the
     *   snapshot's roster (see [HandleState]), so binding a whole roster in ascending order
     *   always succeeds.
     */
    public fun bind(entity: Entity, netId: NetId) {
        require(!netId.isNone) { "NetId.NONE names no entity and cannot be bound" }
        val index = netId.index
        require(index < capacity) { "NetId index $index exceeds this index's capacity $capacity" }
        require(!liveFlags[index]) {
            "index $index is already live as ${NetId.of(index, generations[index])}"
        }

        entities[index] = entity
        liveFlags[index] = true
        generations[index] = netId.generation
        liveCount++
        if (index >= highWater) highWater = index + 1
        if (index >= nextFresh) nextFresh = index + 1

        ensureReverseCapacity(entity.id)
        reverse[entity.id] = netId.raw
    }

    private fun ensureReverseCapacity(entityId: Int) {
        require(entityId >= 0) { "Fleks entity id must not be negative, was $entityId" }
        if (entityId < reverse.size) return
        var size = reverse.size
        while (size <= entityId) size *= 2
        val grown = IntArray(size) { NONE_RAW }
        reverse.copyInto(grown)
        reverse = grown
    }

    private companion object {
        const val NONE_RAW: Int = -1
    }
}

/** Callback for [NetIdIndex.forEachLive]. A `fun interface` so [NetId] stays unboxed. */
public fun interface NetIdVisitor {
    public fun visit(netId: NetId, entity: Entity)
}

/**
 * Raised when a [NetIdIndex] has no index left to hand out.
 *
 * 65 536 live ids is comfortable for a 5v5 MOBA with creeps and projectiles, but wave
 * spawning is exactly the kind of code that finds a ceiling. A typed failure names the
 * limit; an unchecked array write would have named a line number in an array copy.
 */
public class NetIdExhaustedException(
    public val capacity: Int,
) : IllegalStateException("NetIdIndex exhausted: all $capacity ids are live")
