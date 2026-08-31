package dev.wildware.moba.item

import com.github.quillraven.fleks.Family
import dev.wildware.moba.Player
import dev.wildware.moba.Position
import dev.wildware.moba.ability.Corpse
import dev.wildware.moba.lane.Wallet
import dev.wildware.moba.match.Respawn
import dev.wildware.udea.assets.AssetId
import dev.wildware.udea.core.ServiceKey
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.core.SimSystem
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.serviceKey

/**
 * What the shop did with one order.
 *
 * Sealed rather than a pair of nullable fields, so a caller's `when` is exhaustive and a
 * refusal cannot be mistaken for a purchase of zero gold.
 */
public sealed interface ShopOutcome {

    /** Which champion the order was for. */
    public val champion: NetId

    /** A purchase that happened. */
    public data class Bought(
        override val champion: NetId,
        /** What was bought. */
        public val item: AssetId,
        /** Gold actually taken: the shelf price minus the components traded in. */
        public val paid: Int,
        /** How many owned components the purchase consumed. */
        public val tradedIn: Int,
        /** The slot it went into. [Inventory.TRINKET] for a trinket. */
        public val slot: Int,
    ) : ShopOutcome

    /** A sale that happened. */
    public data class Sold(
        override val champion: NetId,
        /** What was sold. */
        public val item: AssetId,
        /** Gold returned. See [ShopRules.sellValue]. */
        public val refund: Int,
        /** The slot it came out of. */
        public val slot: Int,
    ) : ShopOutcome

    /** An order the shop would not carry out, and why. */
    public data class Refused(
        override val champion: NetId,
        /** The reason. Never a boolean; see [ShopRefusal]. */
        public val reason: ShopRefusal,
    ) : ShopOutcome
}

/**
 * One thing somebody asked the shop to do.
 *
 * A command object rather than a direct call, for the reason `SimBarrier` is one (standards
 * section 3, Command): a purchase is a mutation of two components at once, and queueing it means
 * it happens at a known point in a known tick rather than from whatever code happened to be
 * running. A bot placing an order mid-tick and a test placing one between ticks then reach the
 * simulation through the same door and produce the same result.
 */
public sealed interface ShopOrder {

    /** The champion the order is for, as a [NetId] - never a Fleks `Entity` (spec 5). */
    public val champion: NetId

    /** Buy [item], trading in any of its components the champion already carries. */
    public data class Buy(
        override val champion: NetId,
        public val item: AssetId,
    ) : ShopOrder

    /** Sell whatever is in [slot], at [ShopRules.SELL_PERCENT] of its shelf price. */
    public data class Sell(
        override val champion: NetId,
        public val slot: Int,
    ) : ShopOrder {
        init {
            require(slot in 0 until Inventory.CAPACITY) {
                "inventory slot $slot is outside 0 until ${Inventory.CAPACITY}"
            }
        }
    }
}

/**
 * The shop, as everything outside the simulation sees it: a place to put orders and a record of
 * what happened to the last tick's.
 *
 * ## Why the orders queue instead of being applied where they are made
 *
 * `MobaHostSession`'s KDoc has the general form of this argument: a client sends *input*, never a
 * component write. The shop is the same shape one level up. A caller - a bot system, a test, and
 * in a later ticket a client RPC - says what it wants; [ShopSystem] decides whether it may have
 * it, on its own tick, with the world in a state it can reason about. Applying a purchase from
 * the caller's stack would mean gold moving in the middle of another system's iteration.
 *
 * ## Why [outcomes] is only the last tick's
 *
 * It is a mirror, exactly as `LaneService` is: the authority is the [Wallet] and the [Inventory]
 * on the entity, because a component is the only kind of state a `time.rewind` restores. Keeping
 * every outcome ever would be a second, unrewound history of the match growing without bound. A
 * caller that wants to know what happened to its order reads this on the tick after it placed one.
 *
 * The list is retained and cleared rather than reallocated, so an idle shop allocates nothing.
 */
public class ShopService {

    private val queued = ArrayDeque<ShopOrder>()
    private val lastOutcomes = ArrayList<ShopOutcome>()

    /** Purchases carried out since this service was built. */
    public var purchases: Long = 0L
        private set

    /** Sales carried out since this service was built. */
    public var sales: Long = 0L
        private set

    /** Orders refused, for any reason. */
    public var refusals: Long = 0L
        private set

    /** What happened to the orders drained on the last tick. Empty on a tick with no orders. */
    public val outcomes: List<ShopOutcome> get() = lastOutcomes

    /** Queues [order] for the next tick. */
    public fun submit(order: ShopOrder) {
        queued.addLast(order)
    }

    /** Queues a purchase of [item] for [champion]. */
    public fun buy(champion: NetId, item: AssetId): Unit = submit(ShopOrder.Buy(champion, item))

    /** Queues a sale of [slot] for [champion]. */
    public fun sell(champion: NetId, slot: Int): Unit = submit(ShopOrder.Sell(champion, slot))

    /** How many orders are waiting. */
    public val pending: Int get() = queued.size

    /** Called by [ShopSystem] at the top of its tick. Not part of the public shop. */
    internal fun beginTick() {
        lastOutcomes.clear()
    }

    /** Called by [ShopSystem]; null when the queue is empty. */
    internal fun next(): ShopOrder? = queued.removeFirstOrNull()

    /** Called by [ShopSystem] once per drained order. */
    internal fun record(outcome: ShopOutcome) {
        lastOutcomes += outcome
        when (outcome) {
            is ShopOutcome.Bought -> purchases++
            is ShopOutcome.Sold -> sales++
            is ShopOutcome.Refused -> refusals++
        }
    }

    override fun toString(): String =
        "ShopService(bought=$purchases, sold=$sales, refused=$refusals, pending=${queued.size})"

    public companion object {

        /** How a bot, a test or a HUD reaches the shop. */
        public val KEY: ServiceKey<ShopService> = serviceKey("moba.shop")
    }
}

/**
 * Gives every champion an [Inventory].
 *
 * The same shape and the same argument as `ChampionSystem` granting a `Wallet`: the champion is
 * an authored level entity dressed by a `SpawnOverrides` in a file this ticket does not own, and
 * granting here means an agent that spawns a second champion with `world.spawn_blueprint` gets a
 * working inventory without knowing this package exists. A match restart destroys the entity, so
 * the next match starts empty-handed - which is what a MOBA match does.
 *
 * One `configure` per champion per match; after the first tick the `getOrNull` finds it and
 * nothing is created.
 */
public class InventoryGrantSystem : SimSystem() {

    private val champions: Family = world.family { all(Player, Position) }

    /** Inventories granted since the process started. One per champion per match. */
    public var granted: Long = 0L
        private set

    override fun onTick() {
        val entities = champions.entities
        // Backwards, for `ChampionSystem`'s reason: `configure` changes an archetype, which
        // compacts the family bag, so walking forwards skips whichever entity moved into the
        // slot just vacated.
        var index = entities.size - 1
        while (index >= 0) {
            val entity = entities[index]
            index--
            if (entity.getOrNull(Inventory) != null) continue
            with(world) { entity.configure { it += Inventory() } }
            granted++
        }
    }

    override fun toString(): String = "InventoryGrantSystem(granted=$granted)"
}

/**
 * Buying, selling and combining.
 *
 * ## The three rules the issue asks for, and where each one is
 *
 * - **Only while alive.** A champion that has died has lost its `Combatant` and gained a
 *   `Corpse` (`DeathSystem`), and that is the check: `Corpse in entity` is [ShopRefusal.Dead].
 * - **Only in the fountain.** [ShopRules.inFountain], against the champion's own
 *   `Respawn.spawnX`/`spawnY`. See [ShopRules.FOUNTAIN_RADIUS] for why the spawn point is the
 *   fountain rather than a rectangle authored somewhere else.
 * - **Recipe combine on purchase.** [ShopRules.componentSlots] finds the parts already carried,
 *   [ShopRules.priceFor] charges the difference, and the parts are cleared in the same
 *   `configure`-free write. No structural change: an inventory slot is a field, so combining is
 *   arithmetic and not an archetype move.
 *
 * ## Why the whole order is decided before anything is written
 *
 * Every refusal is computed first and the mutation happens last, so a refused order leaves the
 * world byte-identical. A shop that deducted gold and then discovered there was no room would be
 * a shop that can charge for nothing - and it would be discovered by a player, once, in a match.
 *
 * ## Ordering
 *
 * `SimPhase.Gameplay`, `after(ChampionSystem)` and `after(InventoryGrantSystem)`, because a
 * shopper needs the wallet one grants and the inventory the other does. The `ChampionSystem` edge
 * is why [ItemModule] must come after `LaneModule` in a definition's module list: `SimRegistry`
 * resolves an edge only against a system some module has already contributed. `LaneModule`
 * declares the same kind of edge against `MatchModule`'s `RespawnSystem` for the same reason.
 *
 * There is deliberately no edge against `RespawnSystem`, which grants the `Respawn` this reads
 * for the fountain. It is in `SimPhase.Cleanup` and this is in `Gameplay`, so a champion's first
 * tick has no spawn point yet and an order placed on it is [ShopRefusal.NoSuchChampion]. That is
 * one tick at the start of a match, and asserting a cross-phase ordering the registry does not
 * promise would be worse than saying so here.
 */
public class ShopSystem(
    /** Every item this build ships. Read once at load; see [ItemCatalog]. */
    private val catalog: ItemCatalog,
    /** The queue orders arrive on and the mirror outcomes are published to. */
    private val service: ShopService,
) : SimSystem() {

    /** The one identity a tool call, a packet and a snapshot all agree on (spec 5). */
    private val netIds: NetIdIndex = ctx[CoreModule.NET_IDS]

    override fun onTick() {
        service.beginTick()
        while (true) {
            val order = service.next() ?: return
            service.record(carryOut(order))
        }
    }

    private fun carryOut(order: ShopOrder): ShopOutcome {
        val refused = ShopOutcome.Refused(order.champion, ShopRefusal.NoSuchChampion)
        val entity = netIds.resolveOrNull(order.champion) ?: return refused
        with(world) {
            // Every one of the five is granted within a champion's first ticks, and an order
            // that arrives before they are is refused rather than half-applied. See the class
            // KDoc on why there is no ordering edge that would make that impossible.
            if (entity.getOrNull(Player) == null) return refused
            val position = entity.getOrNull(Position) ?: return refused
            val wallet = entity.getOrNull(Wallet) ?: return refused
            val inventory = entity.getOrNull(Inventory) ?: return refused
            val spawn = entity.getOrNull(Respawn) ?: return refused

            if (Corpse in entity) return ShopOutcome.Refused(order.champion, ShopRefusal.Dead)
            if (!ShopRules.inFountain(position.x, position.y, spawn.spawnX, spawn.spawnY)) {
                return ShopOutcome.Refused(order.champion, ShopRefusal.OutsideFountain)
            }
            return when (order) {
                is ShopOrder.Buy -> buy(order, wallet, inventory)
                is ShopOrder.Sell -> sell(order, wallet, inventory)
            }
        }
    }

    private fun buy(order: ShopOrder.Buy, wallet: Wallet, inventory: Inventory): ShopOutcome {
        val entry = catalog.find(order.item)
            ?: return ShopOutcome.Refused(order.champion, ShopRefusal.NoSuchItem)
        val consumed = ShopRules.componentSlots(inventory, catalog, entry)
        if (!ShopRules.hasRoomFor(inventory, entry, consumed)) {
            return ShopOutcome.Refused(order.champion, ShopRefusal.NoRoom)
        }
        val price = ShopRules.priceFor(inventory, catalog, entry, consumed)
        if (wallet.gold < price) {
            return ShopOutcome.Refused(order.champion, ShopRefusal.InsufficientGold)
        }

        var tradedIn = 0
        for (slot in 0 until Inventory.CARRIED) {
            if (consumed and (1 shl slot) == 0) continue
            inventory.place(slot, null)
            tradedIn++
        }
        val destination = if (entry.item.trinket) Inventory.TRINKET else inventory.firstFreeCarried()
        // `hasRoomFor` has already answered this, so a miss here is a disagreement between the
        // two - which is a defect in this file rather than a state a player can reach, and it
        // fails loudly rather than dropping the item into slot -1.
        check(destination != Inventory.NO_SLOT) {
            "no room for ${entry.item.id} after freeing $tradedIn slot(s), but ShopRules said there was"
        }
        inventory.place(destination, entry.index)
        wallet.gold -= price
        return ShopOutcome.Bought(order.champion, entry.item.id, price, tradedIn, destination)
    }

    private fun sell(order: ShopOrder.Sell, wallet: Wallet, inventory: Inventory): ShopOutcome {
        val entry = catalog.at(inventory, order.slot)
            ?: return ShopOutcome.Refused(order.champion, ShopRefusal.EmptySlot)
        val refund = ShopRules.sellValue(entry.item.cost)
        inventory.place(order.slot, null)
        wallet.gold += refund
        return ShopOutcome.Sold(order.champion, entry.item.id, refund, order.slot)
    }

    override fun toString(): String = "ShopSystem($service)"
}
