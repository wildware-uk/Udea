package dev.wildware.udea.assets

/**
 * One shop item: what it costs, what it upgrades from, and what carrying it does.
 *
 * ## Why the kind is here and not in `moba`
 *
 * The same argument [GameplayEffect] makes. Every field below is a number, a name or a [Ref] -
 * nothing on it is a result of interning, so it decodes out of a `.udeapak` with no running game.
 * A game's *own* kind is declared through `asset(kind, ...)` and comes back as an
 * `OpaqueAsset`, which loses the typed references: [components] would be a bare list of ids that
 * no build-time pass could check, so a recipe naming a component that does not exist would be a
 * null at shop time rather than `UDEA0004` with a file and a line. That check is half of what
 * issue #132 asks for, and it is only available to a published kind.
 *
 * ## [cost] is the price on the shelf, not the price at the counter
 *
 * A finished item's [cost] is what a champion with an empty inventory pays for it. A champion who
 * already owns one of its [components] pays [cost] minus that component's own [cost] - the
 * **recipe difference** - which is what makes a build path a saving rather than a tax.
 *
 * Deriving the counter price from one authored number is deliberate. The alternative is to author
 * a combine cost beside a total, which is two numbers that must agree and no compiler that checks
 * they do; a designer who retunes one and not the other has built an item whose displayed price
 * is not its price. Here there is one number and the arithmetic is the game's.
 *
 * The arithmetic only works while [cost] is at least the sum of its components' costs.
 * `ItemRecipeValidator` (`UDEA0037`) fails the build when it is not, because the alternative is a
 * shop that pays a champion to combine.
 *
 * ## The three fields the shop does not read
 *
 * [unique], [grantedAbility] and [passive] are the schema half of issue #166, which owns the
 * systems that act on them: unique-passive deduplication, granted item actives on a shared
 * item-cooldown slot, and stat modifiers applied as GAS effects. They are declared here, and
 * authored in `moba/assets/item`, because a schema that grows a field later is an asset tree that
 * has to be re-authored later - and because a reference is only checked at build time if
 * something declares it as a reference.
 *
 * [stats] is in the same position: the shop moves it into an inventory slot and reads none of it.
 */
public data class Item(
    override val id: AssetId,
    /**
     * Total gold. What a champion with none of its [components] pays.
     *
     * An `Int`, because gold is an `Int` everywhere in this engine's example game and a currency
     * held as a float is a currency that does not add up over a match.
     */
    public val cost: Int,
    /**
     * Flat attribute modifiers this item grants while carried, by authored attribute name.
     *
     * Names rather than interned `AttributeId`s, for [GameplayEffect]'s reason: an id is an index
     * into a table only a running game has. Empty for an item that is purely a component.
     */
    public val stats: Map<String, Float> = emptyMap(),
    /**
     * The items this one is built from, each consumed from the inventory when it is bought.
     *
     * A list and not a set: an item built from two of the same component lists it twice, and the
     * shop then requires two distinct inventory slots holding it.
     */
    public val components: List<Ref<Item>> = emptyList(),
    /**
     * The unique group this item's passive belongs to, or `null` when it does not have one.
     *
     * Two items sharing a [UniqueName] grant one instance of the effect between them. Nothing in
     * this ticket reads it; see the class KDoc.
     */
    public val unique: UniqueName? = null,
    /** The ability carrying this item grants, or `null`. An *active*. See the class KDoc. */
    public val grantedAbility: Ref<Ability>? = null,
    /** The effect carrying this item applies, or `null`. A *passive*. See the class KDoc. */
    public val passive: Ref<GameplayEffect>? = null,
    /**
     * Whether this item goes in the trinket slot rather than one of the six.
     *
     * A flag on the item and not a separate kind, because everything else about a trinket - a
     * cost, a recipe, a unique, an active - is an item's. It is what makes "six slots and a
     * trinket" a real shape rather than seven interchangeable slots with one oddly named.
     */
    public val trinket: Boolean = false,
) : AssetData {

    init {
        require(cost >= 0) { "item '$id' costs $cost gold; an item cannot be worth negative gold" }
        require(stats.values.all { it.isFinite() }) {
            "item '$id' grants a non-finite stat modifier: $stats"
        }
        // A trinket occupies a slot of its own, and a recipe is satisfied out of the carried
        // slots - so a trinket with components would be a recipe whose parts can never be found,
        // and every purchase of it would silently be at full price. Refused here rather than in
        // the shop, because it is a property of one item and needs no graph to see.
        require(!(trinket && components.isNotEmpty())) {
            "item '$id' is a trinket and has a recipe of ${components.size} component(s); a " +
                "recipe is satisfied out of the six carried slots, so a trinket's could never be"
        }
    }
}
