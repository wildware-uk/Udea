package dev.wildware.udea.assets

import dev.wildware.udea.diagnostics.DidYouMean

/**
 * One packed asset graph, and the only way to turn a [Ref] into an [AssetData].
 *
 * ## An instance, not a global
 *
 * `object Assets` (`common/.../assets.kt:90-126`) was a mutable global map: any code anywhere
 * could write into it, two tests in one JVM fought over it, `clear()` existed because they had to,
 * and a miss `error(...)`d mid-frame with the entire map interpolated into the message. This is a
 * constructor-injected instance (standards section 1). Two registries can be alive in one JVM,
 * queried independently, with no shared state at all - which is what makes an agent harness able
 * to hold a scenario's assets beside the game's.
 *
 * ## Resolution
 *
 * `registry[ref]` is an array read once the reference has interned its slot, and a single hash
 * lookup plus a type check the first time. The type check is the point of [Ref.expected]: a
 * reference pointed at the wrong kind of asset fails here, naming the id and both kinds, instead
 * of becoming a `ClassCastException` in unrelated code later.
 *
 * ## Hot reload
 *
 * [applyDelta] swaps values *at the same slots*, so nothing holding an [AssetIndex] - a component
 * field, a `Ref`, a snapshot captured before the reload - has to be rewritten or invalidated. A
 * delta that would change the graph's shape is refused whole ([classify]); the host answers that
 * by building a new registry, which is a new [AssetLayout] and therefore a clean break.
 *
 * Not thread-safe for mutation: [applyDelta] runs on the `SimBarrier` between ticks (spec 3.4).
 * Reads are safe from any thread, including while another thread has resolved references (see
 * [Ref]).
 */
public class AssetRegistry(
    data: Array<AssetData>,
    contentHash: ByteArray,
    /**
     * Where reloads are recorded. Defaulted so a registry built for a test or for a build-time-only
     * game needs no ceremony, and injectable so that a host swapping a registry for a
     * shape-changing reload keeps one continuous version history across the swap - without which a
     * fresh registry would report version 0 and a rewind into the old graph would look safe
     * (issue #64).
     */
    private val log: AssetGraphLog = AssetGraphLog(),
) : AssetGraphVersions by log {

    /**
     * Defensive copy: the caller's array is theirs, and a registry whose contents could be
     * rewritten from outside would be the `object Assets` global again with extra steps.
     */
    private val values: Array<AssetData> = data.copyOf()

    private val layout: AssetLayout = AssetLayout(values)

    private val storedContentHash: ByteArray = contentHash.copyOf()

    private val listeners = ArrayList<AssetGraphListener>()

    /**
     * The hash of the pack these assets were read from. A fresh copy per call, because a
     * `ByteArray` field would otherwise be a mutable public global by another name; callers hold
     * it briefly to compare, so the copy is not on any hot path.
     */
    public val contentHash: ByteArray get() = storedContentHash.copyOf()

    /** How many assets the graph holds. */
    public val size: Int get() = values.size

    /** Every id, in slot order. The order the pack assigned, so it is stable across runs. */
    public val ids: List<AssetId> get() = layout.ids

    /**
     * The asset [ref] names.
     *
     * @throws UnknownAssetException if no asset in this graph has that id.
     * @throws AssetTypeMismatchException if the asset is not the kind [ref] expects.
     */
    public operator fun <T : AssetData> get(ref: Ref<T>): T {
        val binding = ref.binding
        if (binding != null && binding.layout === layout) {
            @Suppress("UNCHECKED_CAST")
            return values[binding.index] as T
        }
        return resolve(ref)
    }

    /** The slot [id] occupies, for a caller that stores an index rather than a reference. */
    public fun indexOf(id: AssetId): AssetIndex {
        val slot = layout.slotOf(id)
        if (slot == AssetLayout.NO_SLOT) throw unknownAsset(id)
        return AssetIndex(slot)
    }

    /**
     * The asset at [index].
     *
     * The read a `@Net`/`@Sim` field does after a snapshot restore: the field stored an int, the
     * int is still valid after a value-only reload, and this is where it becomes data again.
     */
    public fun at(index: AssetIndex): AssetData {
        require(index.value < values.size) {
            "asset slot $index is outside a graph of ${values.size} assets"
        }
        return values[index.value]
    }

    /** The asset called [id], or `null`. The lookup that is allowed to miss. */
    public fun find(id: AssetId): AssetData? {
        val slot = layout.slotOf(id)
        return if (slot == AssetLayout.NO_SLOT) null else values[slot]
    }

    /** Whether this graph holds an asset called [id]. */
    public operator fun contains(id: AssetId): Boolean = layout.slotOf(id) != AssetLayout.NO_SLOT

    /**
     * Whether [delta] can be swapped into this graph in place.
     *
     * A property of the delta *and of this registry*: adding `character/orc_shaman` is a shape
     * change for a graph that does not have it and an ordinary value change for one that does, so
     * this cannot be decided by the compiler alone.
     */
    public fun classify(delta: GraphDelta): DeltaClassification {
        val changes = ArrayList<ShapeChange>()
        for (change in delta.changed) {
            val slot = layout.slotOf(change.id)
            val replacement = change.data
            when {
                replacement == null && slot == AssetLayout.NO_SLOT -> Unit // Already gone: no-op.
                replacement == null -> changes += ShapeChange(change.id, RestartReason.AssetRemoved)
                slot == AssetLayout.NO_SLOT -> changes += ShapeChange(change.id, RestartReason.AssetAdded)
                replacement::class != values[slot]::class ->
                    changes += ShapeChange(change.id, RestartReason.KindChanged)
            }
        }
        return if (changes.isEmpty()) {
            DeltaClassification.HotSwappable
        } else {
            DeltaClassification.RequiresRestart(changes)
        }
    }

    /**
     * Swaps [delta]'s values into their existing slots, or refuses the whole delta.
     *
     * Whole-delta on purpose: applying the hot-swappable half of a mixed delta would leave the
     * running graph in a state no build ever produced, and an agent comparing screenshots would be
     * looking at a graph that exists nowhere else. On refusal the registry is untouched, no version
     * is burned and no listener is called.
     *
     * A no-op change - the same value swapped in again - still bumps the version and still
     * notifies. "Nothing observable changed" is a judgement about equality that this class is not
     * in a position to make for a game's own asset kinds, and a reload that silently reported
     * nothing would be worse than one that reported a change nobody could see.
     */
    public fun applyDelta(delta: GraphDelta): DeltaResult {
        val classification = classify(delta)
        if (classification is DeltaClassification.RequiresRestart) return DeltaResult.Refused(classification)

        val changedIds = LinkedHashSet<AssetId>(delta.changed.size)
        for (change in delta.changed) {
            // Null here is the one hot-swappable removal: an asset this graph never had. There is
            // no slot to clear and nothing observable happened, so it is not reported as a change.
            val replacement = change.data ?: continue
            // The slot exists and the kind matches: classify() returned HotSwappable, which is
            // exactly that claim about every remaining entry.
            values[layout.slotOf(change.id)] = replacement
            changedIds += change.id
        }
        if (changedIds.isEmpty()) return DeltaResult.Applied(log.current(), emptySet())

        val version = log.record(changedIds)
        val changeSet = AssetChangeSet(changedIds, requiresRestart = false)
        // A copy: a listener that removes itself while being notified is a listener that would
        // otherwise mutate the list being iterated. Reloads are rare; the allocation is not.
        listeners.toList().forEach { it.onAssetsChanged(changeSet) }
        return DeltaResult.Applied(version, changedIds)
    }

    /**
     * Registers [listener], to be called after each applied delta.
     *
     * For state derived from asset values that cannot be recomputed on read - a texture atlas
     * binding, a cached ability table. Anything that can simply read through the registry should,
     * because then a reload needs no listener at all.
     */
    public fun addListener(listener: AssetGraphListener) {
        require(listener !in listeners) { "$listener is already registered on this graph" }
        listeners += listener
    }

    /** Removes [listener]. Returns whether it was registered. */
    public fun removeListener(listener: AssetGraphListener): Boolean = listeners.remove(listener)

    override fun toString(): String = "AssetRegistry(size=$size, version=${current()})"

    private fun <T : AssetData> resolve(ref: Ref<T>): T {
        val slot = layout.slotOf(ref.id)
        if (slot == AssetLayout.NO_SLOT) throw unknownAsset(ref.id)
        val value = values[slot]
        if (!ref.expected.isInstance(value)) {
            throw AssetTypeMismatchException(
                id = ref.id,
                expected = ref.expected.simpleName ?: ref.expected.toString(),
                actual = value::class.simpleName ?: value::class.toString(),
            )
        }
        ref.binding = RefBinding(layout, slot)
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    /**
     * The miss, with a suggestion attached.
     *
     * `DidYouMean` from `udea-diagnostics` rather than a second Levenshtein here: spec 5 requires
     * the runtime miss and the build-time `udea:unresolved-ref` diagnostic to suggest the same
     * thing, and two implementations of an edit-distance threshold is how they come to disagree.
     */
    /**
     * Binds every reference the bundle reader decoded to the slot the packer patched into it
     * (issue #89, Trello #32).
     *
     * This is the step that makes `registry[ref]` an array index for the *whole* graph rather
     * than for whatever happened to be resolved first. Without it every `Ref` off a bundle
     * would take the [resolve] path once - a `HashMap<String, Int>` lookup and a `KClass`
     * instance check - and the id strings would have to stay reachable to make it possible,
     * which is most of what a packed graph exists to avoid.
     *
     * The type check happens here, once, rather than lazily on first use: a bundle whose
     * `spriteAnimation.sheet` points at a `SoundCue` is a broken *artifact*, and finding that
     * out during the load screen is strictly better than finding it out on the frame the
     * animation first plays.
     */
    internal fun bindPacked(binder: dev.wildware.udea.assets.pack.RefBinder) {
        binder.bindAll { ref, index ->
            if (index !in values.indices) {
                throw PackedRefOutOfRangeException(ref.id, index, values.size)
            }
            val value = values[index]
            if (value.id != ref.id) {
                throw PackedRefMismatchException(ref.id, index, value.id)
            }
            if (!ref.expected.isInstance(value)) {
                throw AssetTypeMismatchException(
                    id = ref.id,
                    expected = ref.expected.simpleName ?: ref.expected.toString(),
                    actual = value::class.simpleName ?: value::class.toString(),
                )
            }
            ref.binding = RefBinding(layout, index)
        }
    }

    private fun unknownAsset(id: AssetId): UnknownAssetException = UnknownAssetException(
        id = id,
        suggestion = DidYouMean.suggest(id.value, layout.ids.map { it.value })?.let(::AssetId),
        graphSize = size,
    )
}

/**
 * Told when the graph's values change under it.
 *
 * A `fun interface` so a caller writes `registry.addListener { world.rebuildAtlas() }`. The change
 * set is the same one `changesSince` would report for the delta, so a listener never has to ask
 * the registry what just happened.
 */
public fun interface AssetGraphListener {

    /** Called after [AssetRegistry.applyDelta] has swapped the values in. */
    public fun onAssetsChanged(change: AssetChangeSet)
}
