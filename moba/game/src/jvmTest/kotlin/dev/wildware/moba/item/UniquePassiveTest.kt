package dev.wildware.moba.item

import dev.wildware.moba.ability.MobaEffects
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What carrying an item does to a champion's stats, and what carrying two of one unique does not.
 *
 * ## The two things issue #166 asks this file to say
 *
 * - **Two copies of the same unique grant exactly one effect instance.** `item/bulwark` and
 *   `item/sentinel_greaves` are both `unique/fortified` and both name `item/passive_fortified`;
 *   a champion carrying both carries one application of it.
 * - **Selling one leaves the other active.** Sell the bulwark and the greaves take over the
 *   group, so the count stays one rather than falling to zero.
 *
 * The second assertion is the one that could pass for the wrong reason - a system that applied a
 * passive once and never removed anything would satisfy it - so [selling every member of a group
 * takes its passive away] runs the case that separates the two, and
 * [a second unique group grants a second instance] is the known negative: a system that simply
 * refused to apply more than one passive would fail it.
 *
 * ## Nothing here is a stand-in
 *
 * [ShopHarness] boots `MobaGame.definition()`: the real level, the real units, the real shop and
 * the real `ItemPassiveSystem` on the same module list `MobaClient.main` assembles. Purchases go
 * in through [ShopService], which is the door a bot and a tool call use.
 */
class UniquePassiveTest {

    /**
     * A carried item's `stats` reach the champion's attributes.
     *
     * The foundation the rest of this file stands on, and the case that was silently broken until
     * the item assets stopped saying `health`: `health` is declared `max = value(maxHealth)`, so
     * an additive modifier on it is clamped away on a champion at full health. `item/bulwark`
     * raises `armour`, which has no ceiling, and `maxHealth`, which is the ceiling.
     */
    @Test
    fun `a carried item's stats are applied as effects`() {
        val shop = shopper()
        val armourBefore = shop.stat(shop.combat.armour)
        val maxHealthBefore = shop.stat(shop.combat.maxHealth)

        assertTrue(shop.buy(Items.BULWARK) is ShopOutcome.Bought, "the bulwark was not bought")
        shop.run(2)

        val item = shop.item(Items.BULWARK).item
        // Armour moves twice: the item's own `stats` block, and `item/passive_fortified`, which
        // is the `unique/fortified` passive and also targets armour. The numbers are read from
        // the catalogue and from `MobaEffects.ITEM_PASSIVES` rather than written here, so a
        // balance pass retunes one place and this still asserts the relationship.
        val fortified = MobaEffects.ITEM_PASSIVES.single { it.effect == FORTIFIED }.amount
        assertEquals(
            armourBefore + item.stats.getValue("armour") + fortified,
            shop.stat(shop.combat.armour),
            "carrying ${item.id} did not move armour",
        )
        assertEquals(
            maxHealthBefore + item.stats.getValue("maxHealth"),
            shop.stat(shop.combat.maxHealth),
            "carrying ${item.id} did not move maxHealth",
        )
    }

    @Test
    fun `two copies of the same unique grant exactly one effect instance`() {
        val shop = shopper()
        assertTrue(shop.buy(Items.BULWARK) is ShopOutcome.Bought, "the bulwark was not bought")
        shop.run(2)
        assertEquals(1, shop.applied(FORTIFIED), "one `unique/fortified` item, one passive")

        assertTrue(
            shop.buy(Items.SENTINEL_GREAVES) is ShopOutcome.Bought,
            "the greaves were not bought",
        )
        shop.run(2)

        assertEquals(
            1,
            shop.applied(FORTIFIED),
            "`item/bulwark` and `item/sentinel_greaves` are both `unique/fortified`, so the " +
                "champion carrying both holds one `$FORTIFIED` and not two",
        )
    }

    /**
     * Their stat blocks still stack, which is what makes the deduplication *unique-passive*
     * deduplication rather than "the second item does nothing".
     *
     * A test that only counted the passive would pass for a system that ignored the second item
     * outright, which is a different game.
     */
    @Test
    fun `a second item in the same unique group still contributes its stats`() {
        val shop = shopper()
        shop.buy(Items.BULWARK)
        shop.run(2)
        val armourWithOne = shop.stat(shop.combat.armour)

        shop.buy(Items.SENTINEL_GREAVES)
        shop.run(2)

        assertEquals(
            armourWithOne + shop.item(Items.SENTINEL_GREAVES).item.stats.getValue("armour"),
            shop.stat(shop.combat.armour),
            "the greaves' own armour is a stat, not the unique passive, so it stacks",
        )
    }

    @Test
    fun `selling one of a unique pair leaves the other's passive active`() {
        val shop = shopper()
        shop.buy(Items.BULWARK)
        shop.buy(Items.SENTINEL_GREAVES)
        shop.run(2)
        val bulwarkSlot = shop.contents().indexOf(Items.BULWARK)
        assertTrue(bulwarkSlot >= 0, "the bulwark is not in the inventory: ${shop.contents()}")

        assertTrue(shop.sell(bulwarkSlot) is ShopOutcome.Sold, "the bulwark did not sell")
        shop.run(2)

        assertEquals(
            listOf(Items.SENTINEL_GREAVES),
            shop.carried().toList(),
            "only the greaves should be left",
        )
        assertEquals(
            1,
            shop.applied(FORTIFIED),
            "the greaves are still `unique/fortified`, so the group is still granted once",
        )
    }

    /**
     * The case that stops the previous test passing for a system that never removes anything.
     *
     * Selling both members of the group takes the passive away. Without this, "still one after
     * selling one" is satisfied by an application nothing ever revokes.
     */
    @Test
    fun `selling every member of a unique group takes its passive away`() {
        val shop = shopper()
        shop.buy(Items.BULWARK)
        shop.buy(Items.SENTINEL_GREAVES)
        shop.run(2)
        assertEquals(1, shop.applied(FORTIFIED), "the pair grants one passive to start with")

        shop.sell(shop.contents().indexOf(Items.BULWARK))
        shop.run(2)
        shop.sell(shop.contents().indexOf(Items.SENTINEL_GREAVES))
        shop.run(2)

        assertEquals(emptyList(), shop.carried().toList(), "the inventory should be empty")
        assertEquals(0, shop.applied(FORTIFIED), "nothing fortified is carried, so nothing grants it")
    }

    /**
     * The known negative: a *different* unique group is a second instance.
     *
     * A system that had simply capped item passives at one - or that keyed them on nothing at
     * all - would pass every assertion above and fail this one.
     */
    @Test
    fun `a second unique group grants a second instance`() {
        val shop = shopper()
        shop.buy(Items.BULWARK)
        shop.buy(Items.LIFESTONE)
        shop.run(2)

        assertEquals(1, shop.applied(FORTIFIED), "`unique/fortified`, from the bulwark")
        assertEquals(1, shop.applied(VITALITY), "`unique/vitality`, from the lifestone")
    }

    /**
     * A `passive` with no `unique` is not deduplicated, because there is no group to deduplicate
     * it by.
     *
     * `item/bloodletter` and `item/archmage_staff` both name `item/passive_vigour` and neither
     * declares a unique, so a champion carrying both carries two of it.
     *
     * This is what pins the deduplication to the **unique id** rather than to the effect: a
     * system that had keyed it on "one application per passive effect" would hold one here.
     */
    @Test
    fun `a passive outside a unique group stacks per copy`() {
        val shop = shopper()
        assertEquals(0, shop.applied(VIGOUR), "nothing is carried yet")

        shop.buy(Items.BLOODLETTER)
        shop.run(2)
        assertEquals(1, shop.applied(VIGOUR), "the bloodletter's passive")

        shop.buy(Items.ARCHMAGE_STAFF)
        shop.run(2)
        assertEquals(
            2,
            shop.applied(VIGOUR),
            "neither item declares a `unique`, so there is nothing to deduplicate them by",
        )
    }

    /** A booted game with a champion in its fountain and enough gold for anything in the shop. */
    private fun shopper(): ShopHarness {
        val shop = ShopHarness.boot()
        with(shop.host.world) {
            val spawn = shop.champion()[dev.wildware.moba.match.Respawn]
            shop.moveChampion(spawn.spawnX, spawn.spawnY)
        }
        shop.grant(GOLD)
        return shop
    }

    private companion object {

        /** The `unique/fortified` passive: `item/bulwark`, `item/sentinel_greaves`, `item/aegis`. */
        const val FORTIFIED: String = "item/passive_fortified"

        /** The `unique/vitality` passive: `item/lifestone`, `item/phoenix_charm`. */
        const val VITALITY: String = "item/passive_vitality"

        /** The passive that belongs to no group: `item/bloodletter`, `item/archmage_staff`. */
        const val VIGOUR: String = "item/passive_vigour"

        /** More than the most expensive thing in the tree, so no test is about affordability. */
        const val GOLD: Int = 20_000
    }
}
