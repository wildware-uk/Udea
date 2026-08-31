package dev.wildware.moba.item

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType
import dev.wildware.udea.annotations.Net
import dev.wildware.udea.annotations.Replicated
import dev.wildware.udea.annotations.Visibility
import dev.wildware.udea.assets.AssetIndex

/**
 * What a champion is carrying: six slots and a trinket.
 *
 * ## Why seven `Int` fields and not an array
 *
 * `udea-codegen` lowers one property at a time and `FieldLowering` accepts `Boolean`, `Int`,
 * `Long`, `Float`, `NetId`, `Tick` and enums. An `IntArray` is none of those, and a component
 * whose state is an array has to carry a hand-written `Replicator` - which is what
 * `udea-gas`'s `Attributes`, `Abilities` and `GameplayEffects` do, at ids above
 * `net-components.lock`'s space. `moba` may not: `ReplicatorApiShapeTest` fails a hand-written
 * replicator in game code, because a hand-written one stores a `FieldMask` in the game and a
 * widening of that type would then be a breaking change to every game.
 *
 * So an inventory of a fixed size is a fixed number of fields, and the size being fixed is the
 * game design rather than a workaround: a MOBA inventory is six slots and a trinket, and a
 * variable-length one would be a different game.
 *
 * ## Why a slot holds an `Int` and not an `AssetIndex`
 *
 * The same reason `Tower.targetRaw` holds an `Int` and not a `NetId`: the generated replicator
 * needs a kind `FieldLowering` knows, and `AssetIndex` is a value class over a `val`, which
 * lowering refuses because `Replicator.apply` could not restore it. The typed form is what
 * [itemAt] and [place] take, so nothing outside this class does the unwrapping.
 *
 * An `AssetIndex` is also the *only* asset identity a snapshot may hold - `Ref.slot`'s own KDoc
 * says so - because it is stable across a hot reload. An inventory holding an asset id string
 * would be a rewind that restored an item by a name the reloaded graph had moved.
 *
 * ## `visibility = OwnerOnly`, and what it does today
 *
 * Issue #132 specifies it, and it is declared here as the statement of intent it is. **Nothing
 * enforces it today.** `Visibility` has been in `udea-annotations` since Phase 0 and no consumer
 * reads it: `udea-codegen`'s `ComponentModelBuilder` never looks at the argument and `udea-net`'s
 * `SnapshotWriter` has no per-recipient mask stripping, so every client holding this entity is
 * sent every field of this component. That is the same state `lifetime` was in before issue #114
 * turned it into bytes not sent, and issue #167 is the ticket that does the same for this one.
 * Until it lands, this is a declaration and not a guarantee, and this paragraph is here so that
 * nobody reads the annotation as one.
 */
@Replicated
public class Inventory(
    /**
     * The first of the six carried slots: an item's [AssetIndex] value, or [EMPTY].
     *
     * The six are separate fields rather than an array for the reason in the class KDoc, and
     * they are read and written through [itemAt] and [place] rather than by name, so that the
     * "which slot" arithmetic exists in exactly one place.
     */
    @Net(visibility = Visibility.OwnerOnly) public var slot0: Int = EMPTY,
    /** @see slot0 */
    @Net(visibility = Visibility.OwnerOnly) public var slot1: Int = EMPTY,
    /** @see slot0 */
    @Net(visibility = Visibility.OwnerOnly) public var slot2: Int = EMPTY,
    /** @see slot0 */
    @Net(visibility = Visibility.OwnerOnly) public var slot3: Int = EMPTY,
    /** @see slot0 */
    @Net(visibility = Visibility.OwnerOnly) public var slot4: Int = EMPTY,
    /** @see slot0 */
    @Net(visibility = Visibility.OwnerOnly) public var slot5: Int = EMPTY,
    /**
     * The trinket slot, which only an `Item` with `trinket = true` may occupy.
     *
     * A slot of its own and not a seventh carried slot, so that a champion with six full slots
     * can still buy a trinket - which is the whole reason the genre gives it a slot of its own.
     */
    @Net(visibility = Visibility.OwnerOnly) public var trinket: Int = EMPTY,
) : Component<Inventory> {

    /**
     * The item in [slot], or [AssetIndex.NONE]-shaped emptiness as [EMPTY].
     *
     * @param slot `0 until` [CAPACITY]; [TRINKET] is the trinket.
     */
    public fun rawAt(slot: Int): Int {
        require(slot in 0 until CAPACITY) { "inventory slot $slot is outside 0 until $CAPACITY" }
        return when (slot) {
            0 -> slot0
            1 -> slot1
            2 -> slot2
            3 -> slot3
            4 -> slot4
            5 -> slot5
            else -> trinket
        }
    }

    /** The item in [slot] as an asset index, or null when the slot is empty. */
    public fun itemAt(slot: Int): AssetIndex? =
        rawAt(slot).let { if (it == EMPTY) null else AssetIndex(it) }

    /** Whether [slot] holds nothing. */
    public fun isEmpty(slot: Int): Boolean = rawAt(slot) == EMPTY

    /** Puts [item] in [slot], or empties it when [item] is null. */
    public fun place(slot: Int, item: AssetIndex?) {
        require(slot in 0 until CAPACITY) { "inventory slot $slot is outside 0 until $CAPACITY" }
        val raw = item?.value ?: EMPTY
        when (slot) {
            0 -> slot0 = raw
            1 -> slot1 = raw
            2 -> slot2 = raw
            3 -> slot3 = raw
            4 -> slot4 = raw
            5 -> slot5 = raw
            else -> trinket = raw
        }
    }

    /** The lowest empty carried slot, or [NO_SLOT] when all six are full. */
    public fun firstFreeCarried(): Int {
        for (slot in 0 until CARRIED) if (isEmpty(slot)) return slot
        return NO_SLOT
    }

    /** How many of the seven slots hold something. */
    public val occupied: Int
        get() {
            var count = 0
            for (slot in 0 until CAPACITY) if (!isEmpty(slot)) count++
            return count
        }

    override fun type(): ComponentType<Inventory> = Inventory

    override fun toString(): String = buildString {
        append("Inventory(")
        for (slot in 0 until CAPACITY) {
            if (slot > 0) append(", ")
            append(if (slot == TRINKET) "trinket=" else "")
            append(if (isEmpty(slot)) "-" else rawAt(slot).toString())
        }
        append(")")
    }

    /** Fleks' handle for this component. */
    public companion object : ComponentType<Inventory>() {

        /**
         * A slot holding nothing.
         *
         * `-1` and not `0`, because slot zero of an asset graph is a real asset: a sentinel a
         * pack can legitimately hand out is an inventory that starts full of whatever sorted
         * first. It is the same value `AssetLayout.NO_SLOT` uses for the same reason.
         */
        public const val EMPTY: Int = -1

        /** How many slots a champion carries, not counting the trinket. */
        public const val CARRIED: Int = 6

        /** The trinket's slot index. */
        public const val TRINKET: Int = CARRIED

        /** Slots in all: the six carried and the trinket. */
        public const val CAPACITY: Int = CARRIED + 1

        /** What [firstFreeCarried] answers when every carried slot is full. */
        public const val NO_SLOT: Int = -1
    }
}
