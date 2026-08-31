package dev.wildware.moba.item

import dev.wildware.moba.ability.Combatant
import dev.wildware.moba.ability.Corpse
import dev.wildware.moba.match.Respawn
import dev.wildware.udea.assets.AssetId
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.gas.Attributes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The rest of issue #132's shop scope: selling, the fountain, being dead, and a full inventory.
 *
 * `RecipeTest` is the named acceptance criterion and covers buying. This is the other four bullets
 * of the Scope section - *"buy, sell at reduced value, recipe combine on purchase, shop only
 * usable in the fountain radius and only while alive"* - plus the two boundaries the design is
 * built around: six full slots, and the trinket that does not count against them.
 *
 * Every case runs on the shipped `MobaGame.definition()` through [ShopService]. See [ShopHarness]
 * for the two things a test here is allowed to write, and why.
 */
class ShopProofTest {

    /**
     * A sale returns [ShopRules.SELL_PERCENT] of the shelf price and frees the slot.
     *
     * Asserted as "less than what it cost and more than nothing", *and* as the exact number the
     * rule computes. The exact assertion catches an arithmetic change; the inequality is what
     * says the number means something - a sale that returned the full price would make buying and
     * selling a free action, and one that returned nothing would make a misclick permanent.
     */
    @Test
    fun `selling returns a reduced price and empties the slot`() {
        val game = ShopHarness.boot()
        val blade = game.item(Items.BLADE)
        game.grant(blade.item.cost)
        val bought = assertIs<ShopOutcome.Bought>(game.buy(Items.BLADE))
        assertEquals(0, game.wallet().gold)

        val sold = assertIs<ShopOutcome.Sold>(game.sell(bought.slot))
        println("[shop] blade cost ${blade.item.cost}, sold for ${sold.refund}")

        assertEquals(ShopRules.sellValue(blade.item.cost), sold.refund)
        assertTrue(
            sold.refund < blade.item.cost,
            "a sale at the full price makes buying free: ${sold.refund} of ${blade.item.cost}",
        )
        assertTrue(sold.refund > 0, "a sale that returns nothing makes a misclick permanent")
        assertEquals(sold.refund, game.wallet().gold)
        assertEquals(0, game.inventory().occupied, "the slot must be free again")
    }

    /** Selling a slot with nothing in it is refused rather than paying out for air. */
    @Test
    fun `selling an empty slot is refused`() {
        val game = ShopHarness.boot()
        game.grant(500)
        val outcome = assertIs<ShopOutcome.Refused>(game.sell(0))
        assertEquals(ShopRefusal.EmptySlot, outcome.reason)
        assertEquals(500, game.wallet().gold)
    }

    /**
     * The shop is only usable inside the fountain.
     *
     * The champion starts standing on its own spawn point, so the *positive* case is the world's
     * default and the negative case is what has to be arranged. The distance moved is read off
     * [ShopRules.FOUNTAIN_RADIUS] rather than written as a literal, so a balance change to the
     * radius moves the test with it instead of leaving it asserting an old geometry.
     *
     * Both directions are here on purpose. A test that only walked away would pass against a shop
     * that refused everybody.
     */
    @Test
    fun `the shop refuses a champion who has walked out of the fountain`() {
        val game = ShopHarness.boot()
        val blade = game.item(Items.BLADE)
        game.grant(blade.item.cost * 2)
        val spawn = with(game.host.world) { game.champion()[Respawn] }

        // Just outside: one world unit past the radius, on the x axis.
        game.moveChampion(spawn.spawnX + ShopRules.FOUNTAIN_RADIUS + 1f, spawn.spawnY)
        val away = assertIs<ShopOutcome.Refused>(game.buy(Items.BLADE))
        assertEquals(ShopRefusal.OutsideFountain, away.reason)
        assertEquals(0, game.inventory().occupied)

        // Just inside: one world unit short of it. The control.
        game.moveChampion(spawn.spawnX + ShopRules.FOUNTAIN_RADIUS - 1f, spawn.spawnY)
        assertIs<ShopOutcome.Bought>(game.buy(Items.BLADE))
        assertEquals(1, game.inventory().occupied)
    }

    /**
     * The shop is only usable while alive.
     *
     * The champion is killed the way the game kills it - `DeathSystem` takes the `Combatant` away
     * and adds a `Corpse` - by writing its health to zero and running the tick that notices. That
     * is the same state a player reaches by losing a fight, so a shop that checked something else
     * would fail here rather than pass by coincidence.
     */
    @Test
    fun `the shop refuses a corpse`() {
        val game = ShopHarness.boot()
        val blade = game.item(Items.BLADE)
        game.grant(blade.item.cost)

        with(game.host.world) {
            val champion = game.champion()
            assertNotNull(champion.getOrNull(Combatant), "the champion starts alive")
            champion[Attributes].setBase(game.combat.health, 0f)
        }
        // A death is noticed by `DeathSystem` on a tick, not by the write.
        game.host.run(2)
        with(game.host.world) {
            assertTrue(Corpse in game.champion(), "the champion must actually be dead")
        }

        val outcome = assertIs<ShopOutcome.Refused>(game.buy(Items.BLADE))
        assertEquals(ShopRefusal.Dead, outcome.reason)
        assertEquals(blade.item.cost, game.wallet().gold, "a corpse must not be charged")
        assertEquals(0, game.inventory().occupied)
    }

    /**
     * Six carried slots is the limit, and a seventh purchase is refused rather than dropped.
     *
     * The full case. A shop that wrote past the sixth slot would corrupt the trinket; one that
     * silently discarded the purchase would take the gold. Both are excluded by asserting the
     * refusal, the purse and the contents together.
     */
    @Test
    fun `a seventh carried item is refused with the inventory untouched`() {
        val game = ShopHarness.boot()
        game.grant(10_000)
        val basics = listOf(
            Items.BLADE,
            Items.WHETSTONE,
            Items.CLOAK,
            Items.PLATE,
            Items.VIAL,
            Items.BAND,
        )
        assertEquals(
            Inventory.CARRIED,
            basics.size,
            "this test fills every carried slot, so the list must be exactly that long",
        )
        for (id in basics) assertIs<ShopOutcome.Bought>(game.buy(id))
        assertEquals(Inventory.CARRIED, game.inventory().occupied)
        val full = game.contents()
        val purse = game.wallet().gold

        val outcome = assertIs<ShopOutcome.Refused>(game.buy(Items.GEM))
        assertEquals(ShopRefusal.NoRoom, outcome.reason)
        assertEquals(full, game.contents(), "a refused purchase must not move a single slot")
        assertEquals(purse, game.wallet().gold)
    }

    /**
     * A full inventory can still buy a recipe made of what is in it.
     *
     * The case a naive "is there a free slot" check gets wrong, and the reason
     * [ShopRules.hasRoomFor] counts the slots the purchase is about to free. Without it a champion
     * with six items could never finish a build, which turns a full inventory into a dead end.
     */
    @Test
    fun `a full inventory can still combine two of its own slots`() {
        val game = ShopHarness.boot()
        game.grant(10_000)
        for (id in listOf(
            Items.BLADE,
            Items.WHETSTONE,
            Items.CLOAK,
            Items.PLATE,
            Items.VIAL,
            Items.BAND,
        )) {
            assertIs<ShopOutcome.Bought>(game.buy(id))
        }
        assertEquals(Inventory.CARRIED, game.inventory().occupied, "every carried slot is full")

        val combine = assertIs<ShopOutcome.Bought>(game.buy(Items.GREATSWORD))
        assertEquals(2, combine.tradedIn)
        assertTrue(Items.GREATSWORD in game.carried())
        assertTrue(Items.BLADE !in game.carried() && Items.WHETSTONE !in game.carried())
        // Two slots freed, one filled: five in use out of six.
        assertEquals(Inventory.CARRIED - 1, game.inventory().occupied)
    }

    /**
     * A trinket goes in the trinket slot, even when all six carried slots are full.
     *
     * The whole reason the seventh slot exists rather than being a sixth-plus-one.
     */
    @Test
    fun `a trinket fits when the six carried slots do not`() {
        val game = ShopHarness.boot()
        game.grant(10_000)
        for (id in listOf(
            Items.BLADE,
            Items.WHETSTONE,
            Items.CLOAK,
            Items.PLATE,
            Items.VIAL,
            Items.BAND,
        )) {
            assertIs<ShopOutcome.Bought>(game.buy(id))
        }
        assertIs<ShopOutcome.Refused>(game.buy(Items.GEM))

        val trinket = assertIs<ShopOutcome.Bought>(game.buy(Items.SCOUTING_TOTEM))
        assertEquals(Inventory.TRINKET, trinket.slot)
        assertEquals(Inventory.CAPACITY, game.inventory().occupied)
        assertEquals(Items.SCOUTING_TOTEM, game.contents()[Inventory.TRINKET])

        // And a second trinket has nowhere to go, because there is one trinket slot.
        val second = assertIs<ShopOutcome.Refused>(game.buy(Items.WARDING_LENS))
        assertEquals(ShopRefusal.NoRoom, second.reason)
        assertEquals(Items.SCOUTING_TOTEM, game.contents()[Inventory.TRINKET])
    }

    /**
     * A free trinket still goes through every check.
     *
     * `item/scouting_totem` costs nothing, which is the case where "can you afford it" is
     * trivially yes and every other rule is the only thing standing between a corpse and a
     * purchase. A shop that short-circuited a zero price would let a dead champion shop.
     */
    @Test
    fun `a free item is still refused outside the fountain`() {
        val game = ShopHarness.boot()
        assertEquals(0, game.item(Items.SCOUTING_TOTEM).item.cost, "this case needs a free item")
        val spawn = with(game.host.world) { game.champion()[Respawn] }
        game.moveChampion(spawn.spawnX + ShopRules.FOUNTAIN_RADIUS + 1f, spawn.spawnY)

        val outcome = assertIs<ShopOutcome.Refused>(game.buy(Items.SCOUTING_TOTEM))
        assertEquals(ShopRefusal.OutsideFountain, outcome.reason)
        assertEquals(0, game.inventory().occupied)
    }

    /**
     * An order for an entity that is not a champion is refused, not applied to somebody else.
     *
     * A creep has a `Position` and a net id and nothing a shopper needs. The failure this
     * excludes is a shop that resolved a net id, found no wallet, and wrote into the first
     * champion it could see instead.
     */
    @Test
    fun `an order naming something that is not a champion is refused`() {
        val game = ShopHarness.boot()
        game.grant(1000)
        val outcome = assertIs<ShopOutcome.Refused>(
            run {
                game.shop.buy(NetId.of(index = 4000, generation = 0), AssetId(Items.BLADE))
                game.host.run(1)
                game.shop.outcomes.single()
            },
        )
        assertEquals(ShopRefusal.NoSuchChampion, outcome.reason)
        assertEquals(1000, game.wallet().gold, "the champion's purse must not have been touched")
        assertEquals(0, game.inventory().occupied)
    }

    /**
     * The whole item tree in the shipped bundle is priced consistently.
     *
     * The runtime half of issue #132's fourth criterion: `udeaValidateAssets` checks this at build
     * time under `UDEA0037`, and this checks the bundle the game actually opens. Two passes over
     * one property, at two different times, because the build-time one runs against a source tree
     * and this one runs against the bytes that shipped.
     */
    @Test
    fun `every finished item in the bundle costs at least its parts`() {
        val game = ShopHarness.boot()
        assertTrue(game.catalog.size >= 12, "the shop needs a tree to sell: ${game.catalog.size}")
        val recipes = game.catalog.entries.filter { it.componentCount > 0 }
        assertTrue(recipes.size >= 8, "issue #132 asks for real build paths: ${recipes.size}")

        for (entry in recipes) {
            val parts = entry.item.components.sumOf { component ->
                game.item(component.id.value).item.cost
            }
            assertTrue(
                entry.item.cost >= parts,
                "${entry.item.id} costs ${entry.item.cost} and is built from parts worth $parts",
            )
        }
        println(
            "[shop] ${game.catalog.size} items, ${recipes.size} with build paths, " +
                "${game.catalog.entries.count { it.item.trinket }} trinkets",
        )
    }
}
