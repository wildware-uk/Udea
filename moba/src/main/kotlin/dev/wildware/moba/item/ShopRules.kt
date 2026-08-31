package dev.wildware.moba.item

/**
 * Why the shop said no.
 *
 * An enum and not a boolean, because "you cannot buy that" has seven different causes and a bot
 * that cannot tell them apart is a bot that retries the one it cannot fix. `when` over this is
 * exhaustive, so a cause added later fails to compile at every site that has to explain one.
 */
public enum class ShopRefusal {

    /** The order named an id that is not an item in this build's asset graph. */
    NoSuchItem,

    /**
     * The order named an entity that cannot shop.
     *
     * Not only "no such champion": also a champion that has not yet been given its wallet, its
     * inventory or its spawn point. All three are granted on a champion's first ticks, so this
     * is what an order placed on tick one of a match gets, and the first tick of a match is not
     * a shopping trip.
     */
    NoSuchChampion,

    /** The champion is a corpse. A MOBA is not shopped from the grave. */
    Dead,

    /** The champion is not standing in its own fountain. See [ShopRules.FOUNTAIN_RADIUS]. */
    OutsideFountain,

    /** The champion cannot afford the counter price. See [ShopRules.priceFor]. */
    InsufficientGold,

    /** Every slot the item could go in is full, even after its components are consumed. */
    NoRoom,

    /** A sale named a slot with nothing in it. */
    EmptySlot,
}

/**
 * The prices, the distances and the arithmetic of buying and selling.
 *
 * Everything here is a pure function of an [Inventory], an [ItemCatalog] and a few numbers. That
 * is deliberate: it means the whole of "what does this purchase cost and is it legal" can be
 * tested without a world, a tick or an entity, and [ShopSystem] is then the small part that moves
 * gold and writes slots.
 *
 * ## Integers, everywhere
 *
 * Gold is an `Int` (`Wallet.gold`) and every price here is integer arithmetic. A sell value is
 * `cost * SELL_PERCENT / 100` and not `cost * 0.7f`, because a currency computed in floats does
 * not add up over a match and because the truncation has to be the same on a server and on a
 * client and in a replay. Integer division truncates toward zero for the non-negative values a
 * cost can take, which is the rounding the shop wants anyway: the house keeps the remainder.
 */
public object ShopRules {

    /**
     * How close to its own spawn point a champion must stand to shop, in world units.
     *
     * The fountain is a champion's own `Respawn.spawnX`/`spawnY` - the place the level put it and
     * the place it stands back up - rather than a rectangle authored somewhere else. That is what
     * a fountain *is* in this genre, it is already per-team without a second table, and it is
     * already restored by a rewind because `Respawn` is in the snapshot registry.
     *
     * 260 is larger than the 220 of `LaneGeometry.XP_RADIUS`, so a champion cannot both shop and
     * soak a creep wave's experience from the same spot in a lane that starts at
     * `LaneGeometry.LANE_Y` - the shop is a place you go back to, which is the whole reason the
     * rule exists.
     */
    public const val FOUNTAIN_RADIUS: Float = 260f

    /** What a sale returns, as a percentage of the item's shelf price. */
    public const val SELL_PERCENT: Int = 70

    /** A slot mask with nothing in it: no component of the order was found in the inventory. */
    private const val NO_SLOTS: Int = 0

    /** Gold returned for selling an item that cost [cost]. */
    public fun sellValue(cost: Int): Int = cost * SELL_PERCENT / 100

    /** Whether a champion at ([x], [y]) is inside the fountain at ([spawnX], [spawnY]). */
    public fun inFountain(x: Float, y: Float, spawnX: Float, spawnY: Float): Boolean {
        val dx = x - spawnX
        val dy = y - spawnY
        // Squared, so there is no square root on a comparison that only needs an ordering.
        return dx * dx + dy * dy <= FOUNTAIN_RADIUS * FOUNTAIN_RADIUS
    }

    /**
     * The carried slots of [inventory] holding direct components of [entry], as a bit per slot.
     *
     * One slot per component and never two components matched to one slot, which is what makes an
     * item built from two of the same part require two of them. The search is greedy from slot
     * zero and therefore deterministic: two inventories holding the same items in the same slots
     * always consume the same slots, on a server and in a replay.
     *
     * Only the six carried slots are searched. The trinket slot is a slot and not a stash, and an
     * `Item` with `trinket = true` may not have a recipe at all - `Item`'s own `init` refuses one
     * - so there is nothing a recipe could legitimately find in there.
     */
    public fun componentSlots(inventory: Inventory, catalog: ItemCatalog, entry: ItemEntry): Int {
        var claimed = NO_SLOTS
        for (component in entry.componentIndices) {
            for (slot in 0 until Inventory.CARRIED) {
                val bit = 1 shl slot
                if (claimed and bit != 0) continue
                if (inventory.rawAt(slot) != component) continue
                claimed = claimed or bit
                break
            }
        }
        return claimed
    }

    /**
     * What [entry] costs a champion who is trading in the slots of [consumed].
     *
     * The **recipe difference**: the shelf price minus what the parts already owned are worth.
     * Never negative, because `ItemRecipeValidator` (`UDEA0037`) fails the build for an item that
     * costs less than the components it is built from - so this subtraction cannot go the wrong
     * way for any item that is in a bundle at all.
     */
    public fun priceFor(
        inventory: Inventory,
        catalog: ItemCatalog,
        entry: ItemEntry,
        consumed: Int,
    ): Int {
        var traded = 0
        for (slot in 0 until Inventory.CARRIED) {
            if (consumed and (1 shl slot) == 0) continue
            traded += catalog.at(inventory, slot)?.item?.cost ?: 0
        }
        return entry.item.cost - traded
    }

    /**
     * Whether [entry] fits in [inventory] once the slots in [consumed] are freed.
     *
     * A trinket needs the trinket slot and nothing else; a carried item needs one of the six,
     * which the components it is trading in may themselves be about to free. That second half is
     * the case a naive "is there a free slot" check gets wrong: a champion with six full slots
     * holding two components of the item it is buying has room, and refusing it would make a
     * full inventory a dead end.
     */
    public fun hasRoomFor(inventory: Inventory, entry: ItemEntry, consumed: Int): Boolean {
        if (entry.item.trinket) return inventory.isEmpty(Inventory.TRINKET)
        var free = 0
        for (slot in 0 until Inventory.CARRIED) {
            if (inventory.isEmpty(slot) || consumed and (1 shl slot) != 0) free++
        }
        return free > 0
    }
}
