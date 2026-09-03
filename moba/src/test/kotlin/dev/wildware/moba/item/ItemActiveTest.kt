package dev.wildware.moba.item

import dev.wildware.moba.ability.MobaAbilities
import dev.wildware.moba.ability.MobaTags
import dev.wildware.moba.ability.UnitBlueprint
import dev.wildware.udea.gas.ActivationResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * An item's active, on the ability bar, on a cooldown of its own.
 *
 * ## What issue #166 asks this file to say
 *
 * - **An item active is castable.** Buying `item/warhammer` puts `ability/orc_elite_spin` into a
 *   slot above the champion's own two, and activating that slot fires it.
 * - **Its cooldown is independent of a champion's ability cooldowns.** Firing the item does not
 *   put slot 0 or slot 1 on cooldown, and firing slot 0 does not put the item on one.
 * - **It is blocked while dead.** A corpse keeps its `Abilities`, its `Attributes` and its
 *   `GameplayEffects`, which is `AbilitySystem`'s whole family, so this is a real refusal and not
 *   a family that happens to exclude the entity.
 *
 * The shared half of "shared item-cooldown slot" is
 * [firing one item active puts the other item slot on the same cooldown], which is the property
 * that makes the two slots one bar rather than two.
 *
 * ## The activation path is the game's
 *
 * [ShopHarness.activate] calls the `AbilityActivation` off the built context - the same object
 * `PlayerControlSystem` calls on a key press, `UnitBrain` calls for an AI unit and
 * `MobaHostSession` calls for an `activateAbility` packet. There is no second door.
 */
class ItemActiveTest {

    @Test
    fun `buying an item with an active grants it into an item slot`() {
        val shop = shopper()
        assertEquals("-", shop.abilityIn(FIRST), "the item bar starts empty")

        assertTrue(shop.buy(Items.WARHAMMER) is ShopOutcome.Bought, "the warhammer was not bought")
        shop.run(2)

        assertEquals(
            MobaAbilities.ORC_SPIN,
            shop.abilityIn(FIRST),
            "`item/warhammer` grants `ability/orc_elite_spin`, and it goes on the item bar",
        )
    }

    @Test
    fun `an item active fires from the ability bar`() {
        val shop = carrying(Items.WARHAMMER)

        assertEquals(
            ActivationResult.Activated,
            shop.activate(FIRST),
            "the item slot is granted and off cooldown, so it should have fired",
        )
        assertTrue(shop.cooldown(FIRST) > 0, "firing it should have started its cooldown")
    }

    /**
     * The criterion, in the two directions it can be broken.
     *
     * A shared-cooldown implementation that put the group on *every* slot would fail the first
     * assertion; one that let a champion ability start the item cooldown would fail the second.
     */
    @Test
    fun `an item active's cooldown is independent of a champion's ability cooldowns`() {
        val shop = carrying(Items.WARHAMMER)

        assertEquals(ActivationResult.Activated, shop.activate(FIRST), "the item should have fired")
        assertTrue(shop.cooldown(FIRST) > 0, "the item is cooling down")
        assertEquals(
            0,
            shop.cooldown(PRIMARY),
            "the champion's own slot 0 (${shop.abilityIn(PRIMARY)}) is not part of the item bar",
        )
        assertEquals(
            0,
            shop.cooldown(SECONDARY),
            "nor is its slot 1 (${shop.abilityIn(SECONDARY)})",
        )

        assertEquals(
            ActivationResult.Activated,
            shop.activate(PRIMARY),
            "a champion ability is not blocked by the item cooldown",
        )
        val itemRemaining = shop.cooldown(FIRST)
        assertTrue(itemRemaining > 0, "the item is still cooling down after the champion swung")
        assertTrue(shop.cooldown(PRIMARY) > 0, "and slot 0 has started its own, separate cooldown")
    }

    /** The other half: the two item slots are one bar, so one fired means both are waiting. */
    @Test
    fun `firing one item active puts the other item slot on the same cooldown`() {
        val shop = carrying(Items.WARHAMMER, Items.AEGIS)
        assertEquals(MobaAbilities.PRIEST_HEAL, shop.abilityIn(SECOND), "the aegis is on the bar")

        assertEquals(ActivationResult.Activated, shop.activate(FIRST), "the warhammer should fire")

        assertEquals(
            shop.cooldown(FIRST),
            shop.cooldown(SECOND),
            "both item slots wait out one cooldown; that is what makes it a shared item bar",
        )
        assertIs<ActivationResult.OnCooldown>(
            shop.canActivate(SECOND),
            "the second item active is refused while the shared cooldown runs",
        )
    }

    /**
     * An active bought while the shared cooldown is running adopts it.
     *
     * Without this, "sell nothing and buy a second active" is a free reset of a cooldown a player
     * is meant to be waiting out - the hole `AbilityActivation.grant` exists to close.
     */
    @Test
    fun `an active granted while the item bar is cooling adopts the cooldown`() {
        val shop = carrying(Items.WARHAMMER)
        assertEquals(ActivationResult.Activated, shop.activate(FIRST), "the warhammer should fire")
        val remaining = shop.cooldown(FIRST)
        assertTrue(remaining > 0, "the item bar is cooling down")

        shop.buy(Items.AEGIS)
        shop.run(2)

        assertEquals(MobaAbilities.PRIEST_HEAL, shop.abilityIn(SECOND), "the aegis is on the bar")
        assertIs<ActivationResult.OnCooldown>(
            shop.canActivate(SECOND),
            "an active bought into a cooling item bar waits the rest of it out",
        )
    }

    @Test
    fun `an item active is blocked while dead`() {
        val shop = carrying(Items.WARHAMMER)
        assertEquals(
            ActivationResult.Activated,
            shop.canActivate(FIRST),
            "the item active is ready while the champion is alive",
        )

        shop.kill()

        val refusal = shop.canActivate(FIRST)
        assertIs<ActivationResult.BlockedByTag>(
            refusal,
            "a corpse keeps its Abilities, Attributes and GameplayEffects, so nothing about " +
                "`AbilitySystem`'s family stops a dead champion casting - the tag does. The " +
                "refusal was $refusal",
        )
        assertEquals(
            shop.tags.dead,
            refusal.tag,
            "the refusal names ${MobaTags.DEAD} and not some other blocking tag",
        )
        assertEquals(
            MobaAbilities.ORC_SPIN,
            shop.abilityIn(FIRST),
            "the active is still granted; it is blocked, not revoked, so the shared cooldown " +
                "the corpse was waiting out is still on its slot when it stands back up",
        )
    }

    /** Selling the item takes its active off the bar. The way back out. */
    @Test
    fun `selling an item revokes its active`() {
        val shop = carrying(Items.WARHAMMER)
        val slot = shop.contents().indexOf(Items.WARHAMMER)
        assertTrue(slot >= 0, "the warhammer is not in the inventory: ${shop.contents()}")

        assertTrue(shop.sell(slot) is ShopOutcome.Sold, "the warhammer did not sell")
        shop.run(2)

        assertEquals("-", shop.abilityIn(FIRST), "nothing is carried, so nothing is granted")
        assertEquals(
            ActivationResult.NotGranted,
            shop.canActivate(FIRST),
            "an empty item slot is not castable",
        )
    }

    /** A booted game whose champion is in its fountain, rich, and carrying [items]. */
    private fun carrying(vararg items: String): ShopHarness {
        val shop = shopper()
        for (id in items) {
            assertTrue(shop.buy(id) is ShopOutcome.Bought, "$id was not bought")
        }
        shop.run(2)
        return shop
    }

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

        const val PRIMARY: Int = 0
        const val SECONDARY: Int = 1

        /** The first item-active slot. */
        val FIRST: Int = UnitBlueprint.ITEM_SLOT_FIRST

        /** The second. */
        val SECOND: Int = UnitBlueprint.ITEM_SLOT_FIRST + 1

        const val GOLD: Int = 20_000
    }
}
