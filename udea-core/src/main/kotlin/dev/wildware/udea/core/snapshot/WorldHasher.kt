package dev.wildware.udea.core.snapshot

/**
 * The determinism gate: one `Long` that summarises a whole simulated world.
 *
 * Spec 7 is blunt that the ASM determinism scanner "produces false confidence — it catches
 * direct calls but not nondeterminism laundered through Fleks internals, LibGDX math, or
 * cross-JVM float differences", and names **this** as the actual gate from Phase 0. A hash
 * stream catches nondeterminism regardless of where it came from, and it works long before
 * input replay exists.
 *
 * It hashes the captured [WorldFieldStore] and never the live Fleks world. That is the whole
 * trick: a Fleks world iterates in whatever order its archetypes ended up in, so hashing it
 * would measure Fleks' internals, whereas a captured store is in ascending [dev.wildware.udea
 * .core.identity.NetId] order by construction. The hash is therefore independent of the order
 * entities were spawned in, which is precisely the class of difference that is *not* a
 * divergence.
 *
 * ## Canonical order — do not change it
 *
 * `TimeControl`'s rewind test, the snapshot-equivalence gate and, later, Phase 7's cross-OS
 * replay equality all assert against this. The order is:
 *
 * 1. the entity roster: every row's `NetId`, then its component-presence words;
 * 2. then, per component type in **ascending `ComponentTypeId`**:
 *    a. the type id;
 *    b. per lowered field in ascending field index;
 *    c. per occupied slot in ascending slot index — which is ascending `NetId`, because slots
 *       are claimed in row order.
 *
 * Presence is folded because it is otherwise invisible: two worlds where the *other* entity
 * carries the component hold identical column data and an identical roster, and only the
 * presence bits tell them apart.
 *
 * ## Floats are canonicalised, and comparison is not
 *
 * [FieldComparison.Canonical]: `-0.0f` folds to `0.0f` and every `NaN` payload folds to one.
 * A delta encoder must not do this — see [FieldComparison] — but a determinism gate must, or
 * two JVMs that computed the same number report a divergence over a sign bit nobody can act
 * on, and the real defect is buried under it.
 *
 * ## FNV-1a
 *
 * Chosen because it is eight lines with no table, no allocation and no platform-dependent
 * intrinsic, which is what a determinism gate has to be: a hash whose own behaviour varied
 * across JVMs would be worse than no hash at all. It is not cryptographic and does not need
 * to be — nothing here is adversarial, and a collision costs one missed divergence report,
 * not a security property.
 */
public object WorldHasher {

    /** FNV-1a 64-bit offset basis. */
    private const val OFFSET_BASIS: Long = -0x340d631b7bdddcdbL // 0xcbf29ce484222325

    /** FNV-1a 64-bit prime. */
    private const val PRIME: Long = 0x100000001b3L

    /** The hash of a world with no entities at all. */
    public val EMPTY: Long = OFFSET_BASIS

    /**
     * Hashes one captured world in canonical order. Allocation-free.
     *
     * This is the per-tick hash the equivalence gate records: run the simulation, capture,
     * hash, keep the number.
     */
    public fun hash(fields: WorldFieldStore): Long {
        var hash = OFFSET_BASIS
        hash = fold(hash, fields.rowCount.toLong())

        for (row in 0 until fields.rowCount) {
            hash = fold(hash, fields.netIdAt(row).raw.toLong())
            for (word in 0 until fields.presenceWordCount) {
                hash = fold(hash, fields.presenceWordAt(row, word))
            }
        }

        val registry = fields.registry
        for (component in 0 until registry.size) {
            val store = fields.storeAt(component)
            val used = fields.slotsUsedAt(component)
            hash = fold(hash, registry.schemaAt(component).typeId.raw.toLong())
            hash = fold(hash, used.toLong())
            for (field in 0 until store.fieldCount) {
                for (slot in 0 until used) {
                    hash = fold(hash, store.hashableBits(slot, field, FieldComparison.Canonical))
                }
            }
        }
        return hash
    }

    /**
     * Hashes a whole snapshot: its fields, plus the state a re-run needs to reproduce them.
     *
     * The random streams and the id allocator are not fields, and they are exactly what makes
     * the *next* tick reproducible. A rewind test that only compared [hash] would pass with a
     * restored world whose next roll came out different.
     */
    public fun hash(snapshot: WorldSnapshot): Long {
        var hash = hash(snapshot.fields)
        hash = fold(hash, snapshot.tick.value)
        for (word in snapshot.rng) hash = fold(hash, word)
        val handles = snapshot.handles
        hash = fold(hash, handles.nextFresh.toLong())
        hash = fold(hash, handles.highWater.toLong())
        hash = fold(hash, handles.freeCount.toLong())
        for (position in 0 until handles.freeCount) {
            hash = fold(hash, handles.freeIndexAt(position).toLong())
            hash = fold(hash, handles.freeGenerationAt(position).toLong())
        }
        return hash
    }

    /** One 64-bit value, byte by byte, least significant first. */
    private fun fold(hash: Long, value: Long): Long {
        var result = hash
        var remaining = value
        repeat(Long.SIZE_BYTES) {
            result = (result xor (remaining and 0xFFL)) * PRIME
            remaining = remaining ushr 8
        }
        return result
    }
}
