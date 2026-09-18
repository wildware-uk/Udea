package dev.wildware.moba.item

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Issue #132's first acceptance criterion.
 *
 * > `RecipeTest`: buying a finished item consumes owned components, refunds the recipe
 * > difference correctly, and fails cleanly with insufficient gold.
 *
 * Every purchase below goes through [ShopService] and is carried out by [ShopSystem] on a real
 * tick of the shipped `MobaGame.definition()`. Nothing calls [ShopRules] directly: the arithmetic
 * being right is worth nothing if the system does not use it, and a test of the arithmetic alone
 * would have gone green against a shop that never charged anybody.
 *
 * ## The prices this file asserts, and where they come from
 *
 * They are read off the catalogue rather than written down here, and that is deliberate. A test
 * carrying `750` as a literal would have to be edited by every balance pass, and one that had not
 * been edited would fail for a reason that is not a defect. What is asserted is the *relationship*
 * - "paid is the shelf price minus what the parts are worth" - computed from the same catalogue
 * the shop reads. A balance change moves both sides and the test stays true; a change to the
 * arithmetic moves one, and it does not.
 */
class RecipeTest {

    /**
     * The headline: components already owned are consumed and only the difference is charged.
     *
     * Three purchases, and the third is the one under test. The first two are what put the parts
     * in the inventory, and they are made through the shop rather than written into slots so that
     * the state the recipe consumes is a state the shop itself can produce.
     */
    @Test
    fun `buying a finished item consumes its components and charges the difference`() {
        val game = ShopHarness.boot()
        val blade = game.item(Items.BLADE)
        val whetstone =
            game.item(Items.WHETSTONE)
        val greatsword =
            game.item(Items.GREATSWORD)

        val purse = greatsword.item.cost + blade.item.cost + whetstone.item.cost
        game.grant(purse)

        assertIs<ShopOutcome.Bought>(game.buy(Items.BLADE))
        assertIs<ShopOutcome.Bought>(game.buy(Items.WHETSTONE))
        assertEquals(
            setOf(Items.BLADE, Items.WHETSTONE),
            game.carried(),
            "the two components must be in the inventory before the recipe can consume them",
        )
        val beforeCombine = game.wallet().gold

        val combine = assertIs<ShopOutcome.Bought>(game.buy(Items.GREATSWORD))
        println(
            "[shop] greatsword shelf ${greatsword.item.cost}, parts " +
                "${blade.item.cost}+${whetstone.item.cost}, paid ${combine.paid}",
        )

        // The recipe difference, against the catalogue's own numbers.
        assertEquals(
            greatsword.item.cost - blade.item.cost - whetstone.item.cost,
            combine.paid,
            "a champion owning both components pays the shelf price minus both of them",
        )
        assertEquals(2, combine.tradedIn, "both components must have been consumed")
        assertEquals(
            beforeCombine - combine.paid,
            game.wallet().gold,
            "the purse must move by exactly what the shop said it charged",
        )

        // Consumed means gone, not merely paid for: the two parts are out of the inventory and
        // the finished item is in one slot. A shop that charged the difference and left the
        // components behind would be a champion carrying eighteen strength for the price of six.
        assertEquals(setOf(Items.GREATSWORD), game.carried())
        assertEquals(1, game.inventory().occupied)
    }

    /**
     * A component the champion does not own is paid for in full.
     *
     * The other side of the same subtraction, and the one that catches a shop that credits every
     * component of a recipe whether it was owned or not. Without it, "the difference is refunded"
     * would be satisfied by a shop that gave the discount away for nothing.
     */
    @Test
    fun `a component the champion does not own is not discounted`() {
        val game = ShopHarness.boot()
        val blade = game.item(Items.BLADE)
        val greatsword =
            game.item(Items.GREATSWORD)
        game.grant(greatsword.item.cost + blade.item.cost)

        assertIs<ShopOutcome.Bought>(game.buy(Items.BLADE))
        val combine = assertIs<ShopOutcome.Bought>(game.buy(Items.GREATSWORD))

        assertEquals(
            greatsword.item.cost - blade.item.cost,
            combine.paid,
            "only the blade was owned, so only the blade's cost comes off",
        )
        assertEquals(1, combine.tradedIn, "the whetstone was never owned and cannot be consumed")
        assertEquals(setOf(Items.GREATSWORD), game.carried())
    }

    /**
     * A recipe that names one component twice needs two of it.
     *
     * `item/twin_blades` is two blades. A champion carrying one gets one blade's worth off and a
     * champion carrying two gets both - which is why `Item.components` is a `List` and not a
     * `Set`, and why the slot search claims a slot before moving to the next component.
     *
     * This is the case a shop that matched components against a *set* of carried ids would get
     * wrong twice over: it would discount both blades for a champion carrying one, and it would
     * consume only one of the two carried by a champion carrying both.
     */
    @Test
    fun `a recipe naming one component twice needs two of it`() {
        val one = ShopHarness.boot()
        val blade = one.item(Items.BLADE)
        val twin = one.item(Items.TWIN_BLADES)
        one.grant(twin.item.cost + blade.item.cost)
        assertIs<ShopOutcome.Bought>(one.buy(Items.BLADE))
        val withOne = assertIs<ShopOutcome.Bought>(one.buy(Items.TWIN_BLADES))
        assertEquals(twin.item.cost - blade.item.cost, withOne.paid)
        assertEquals(1, withOne.tradedIn)

        val two = ShopHarness.boot()
        two.grant(twin.item.cost + blade.item.cost * 2)
        assertIs<ShopOutcome.Bought>(two.buy(Items.BLADE))
        assertIs<ShopOutcome.Bought>(two.buy(Items.BLADE))
        assertEquals(2, two.inventory().occupied, "two blades must occupy two distinct slots")
        val withTwo = assertIs<ShopOutcome.Bought>(two.buy(Items.TWIN_BLADES))
        assertEquals(twin.item.cost - blade.item.cost * 2, withTwo.paid)
        assertEquals(2, withTwo.tradedIn)
        assertEquals(setOf(Items.TWIN_BLADES), two.carried())

        println("[shop] twin_blades with one blade ${withOne.paid}, with two ${withTwo.paid}")
        assertTrue(
            withTwo.paid < withOne.paid,
            "carrying the second blade must be worth something: ${withTwo.paid} vs ${withOne.paid}",
        )
    }

    /**
     * A recipe consumes the components it names, not their components.
     *
     * `item/warhammer` is a greatsword plus a blade, and a greatsword is itself a blade plus a
     * whetstone. Buying the warhammer trades in the greatsword - the thing that is in the
     * inventory - and not the parts that were consumed to make it, which are not there any more.
     *
     * This pins the "direct components only" rule that `ItemRecipeValidator` relies on to be able
     * to sum a recipe without recursing.
     */
    @Test
    fun `a recipe trades in a finished component, not the parts inside it`() {
        val game = ShopHarness.boot()
        val blade = game.item(Items.BLADE)
        val whetstone = game.item(Items.WHETSTONE)
        val greatsword = game.item(Items.GREATSWORD)
        val warhammer = game.item(Items.WARHAMMER)

        game.grant(warhammer.item.cost + greatsword.item.cost + blade.item.cost + whetstone.item.cost)
        assertIs<ShopOutcome.Bought>(game.buy(Items.BLADE))
        assertIs<ShopOutcome.Bought>(game.buy(Items.WHETSTONE))
        assertIs<ShopOutcome.Bought>(game.buy(Items.GREATSWORD))
        assertIs<ShopOutcome.Bought>(game.buy(Items.BLADE))
        assertEquals(setOf(Items.GREATSWORD, Items.BLADE), game.carried())

        val combine = assertIs<ShopOutcome.Bought>(game.buy(Items.WARHAMMER))
        assertEquals(
            warhammer.item.cost - greatsword.item.cost - blade.item.cost,
            combine.paid,
            "the warhammer trades in the greatsword at its own price, not at its parts' prices",
        )
        assertEquals(2, combine.tradedIn)
        assertEquals(setOf(Items.WARHAMMER), game.carried())
    }

    /**
     * Insufficient gold is refused, and refused *cleanly*.
     *
     * "Cleanly" is the load-bearing word and it is three assertions, not one. The order comes
     * back as a typed [ShopRefusal.InsufficientGold] rather than a silent no-op; the purse is
     * untouched; and the inventory is untouched. A shop that deducted what it could and left the
     * champion holding nothing would satisfy "the purchase did not happen" and would still have
     * taken the money.
     */
    @Test
    fun `a purchase with too little gold is refused and moves nothing`() {
        val game = ShopHarness.boot()
        val blade = game.item(Items.BLADE)
        val short = blade.item.cost - 1
        game.grant(short)

        val outcome = assertIs<ShopOutcome.Refused>(game.buy(Items.BLADE))
        assertEquals(ShopRefusal.InsufficientGold, outcome.reason)
        assertEquals(short, game.wallet().gold, "a refused purchase must not take any gold")
        assertEquals(0, game.inventory().occupied, "a refused purchase must not deliver anything")
        assertEquals(1L, game.shop.refusals)
        assertEquals(0L, game.shop.purchases)
    }

    /**
     * Exactly enough gold buys it. The boundary the test above sits one gold below.
     *
     * Without this, "refused when short" would be satisfied by a shop that refused everything.
     */
    @Test
    fun `a purchase with exactly enough gold succeeds and leaves nothing`() {
        val game = ShopHarness.boot()
        val blade = game.item(Items.BLADE)
        game.grant(blade.item.cost)

        val outcome = assertIs<ShopOutcome.Bought>(game.buy(Items.BLADE))
        assertEquals(blade.item.cost, outcome.paid)
        assertEquals(0, game.wallet().gold, "the purse must be exactly empty, not merely smaller")
        assertEquals(setOf(Items.BLADE), game.carried())
    }

    /**
     * The recipe difference is affordable when the shelf price is not.
     *
     * The reason a build path exists at all, and the case that makes the discount observable
     * rather than merely arithmetically correct: a champion holding both components and less than
     * the shelf price still walks out with the finished item.
     */
    @Test
    fun `a champion who cannot afford the shelf price can still afford the combine`() {
        val game = ShopHarness.boot()
        val blade = game.item(Items.BLADE)
        val whetstone = game.item(Items.WHETSTONE)
        val greatsword = game.item(Items.GREATSWORD)
        val difference = greatsword.item.cost - blade.item.cost - whetstone.item.cost

        game.grant(blade.item.cost + whetstone.item.cost + difference)
        assertIs<ShopOutcome.Bought>(game.buy(Items.BLADE))
        assertIs<ShopOutcome.Bought>(game.buy(Items.WHETSTONE))

        assertTrue(
            game.wallet().gold < greatsword.item.cost,
            "the point of the case is that the shelf price is now out of reach: " +
                "${game.wallet().gold} against ${greatsword.item.cost}",
        )
        assertIs<ShopOutcome.Bought>(game.buy(Items.GREATSWORD))
        assertEquals(0, game.wallet().gold)
        assertEquals(setOf(Items.GREATSWORD), game.carried())
    }

    /**
     * An item nothing declares is refused by name rather than crashing.
     *
     * A bot in issue #133 will name items from a build order, and a build order naming an item a
     * balance pass deleted must not take the match down with it.
     */
    @Test
    fun `an unknown item is refused rather than thrown`() {
        val game = ShopHarness.boot()
        game.grant(10_000)
        val outcome = assertIs<ShopOutcome.Refused>(game.buy("item/sword_of_nothing"))
        assertEquals(ShopRefusal.NoSuchItem, outcome.reason)
        assertEquals(10_000, game.wallet().gold)
    }
}
