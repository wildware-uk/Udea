package dev.wildware.moba.item

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.moba.MobaGame
import dev.wildware.moba.MobaModule
import dev.wildware.moba.ability.CharacterAttributes
import dev.wildware.moba.Player
import dev.wildware.moba.Position
import dev.wildware.moba.entry.MobaEntry
import dev.wildware.moba.lane.Wallet
import dev.wildware.udea.assets.AssetId
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.module.CoreModule

/**
 * A booted headless `moba`, with the champion standing in its own fountain.
 *
 * ## Nothing here is a stand-in for the game
 *
 * `MobaGame.definition()` and nothing else: the real level, the real twenty-seven units, the real
 * lane and the real [ItemModule] appended to the same module list `MobaClient.main` assembles.
 * Orders go in through [ShopService], which is the same door a bot in issue #133 will use, and
 * every purchase is carried out by [ShopSystem] on a tick.
 *
 * The two things a test is allowed to do to the world are stated rather than buried, and they are
 * the two `LaneProofTest` allows itself for the same reason: it **writes the champion's gold**,
 * because gold is earned from last hits and a purchase test that first had to farm a wave would
 * be a test of `BountySystem`; and it **writes the champion's `Position`**, which is what
 * `world.set_component_field` does over HTTP. Neither touches the rule under test.
 */
internal class ShopHarness private constructor(
    val host: GameHost,
    val catalog: ItemCatalog,
    /**
     * This world's own attribute ids, off the table its units were dressed with.
     *
     * An `AttributeId` is an index into one `AttributeTable`, so a second table built here would
     * read a unit's armour and call it health - the mistake `MobaGame` threads its table through
     * five call sites to prevent, and the one `LaneProofTest` takes the same precaution against.
     */
    val combat: CharacterAttributes,
) {

    /** Where orders go in and outcomes come out. Off the definition's own module. */
    val shop: ShopService = host.ctx[ShopService.KEY]

    private val netIds = host.ctx[CoreModule.NET_IDS]

    /** The champion the level authored. */
    fun champion(): Entity = host.world.family { all(Player) }.entities[0]

    /** Its net id, which is how an order names it. */
    fun championId(): NetId = netIds.netIdOf(champion())

    /** Its purse. */
    fun wallet(): Wallet = with(host.world) { champion()[Wallet] }

    /** Its six slots and its trinket. */
    fun inventory(): Inventory = with(host.world) { champion()[Inventory] }

    /**
     * The catalogue entry for [id], failing loudly rather than returning null.
     *
     * Prices are read off this rather than written into a test as literals: an assertion carrying
     * `750` would have to be edited by every balance pass, and one that had not been would fail
     * for a reason that is not a defect. What the tests assert is the *relationship* between the
     * numbers, computed from the same catalogue the shop reads.
     */
    fun item(id: String): ItemEntry = requireNotNull(catalog.find(AssetId(id))) {
        "no item called '$id' in a catalogue of ${catalog.size}: " +
            catalog.entries.joinToString { it.item.id.value }
    }

    /** Sets the purse to [gold]. See the class KDoc for why a test is allowed to. */
    fun grant(gold: Int) {
        wallet().gold = gold
    }

    /** Moves the champion to ([x], [y]). What `world.set_component_field` does over HTTP. */
    fun moveChampion(x: Float, y: Float) {
        with(host.world) {
            val position = champion()[Position]
            position.x = x
            position.y = y
        }
    }

    /** Queues a purchase of [id], runs the tick that carries it out, and returns the outcome. */
    fun buy(id: String): ShopOutcome {
        shop.buy(championId(), AssetId(id))
        host.run(1)
        return shop.outcomes.single()
    }

    /** Queues a sale of [slot], runs the tick that carries it out, and returns the outcome. */
    fun sell(slot: Int): ShopOutcome {
        shop.sell(championId(), slot)
        host.run(1)
        return shop.outcomes.single()
    }

    /**
     * What the champion is carrying, as asset ids in slot order, with `-` for an empty slot.
     *
     * A `List<String>` rather than a set, so a failure names *which slot* moved. The shop places
     * an item in the lowest free carried slot, which is deterministic, so slot order is a real
     * assertion and not an artefact.
     */
    fun contents(): List<String> = (0 until Inventory.CAPACITY).map { slot ->
        catalog.at(inventory(), slot)?.item?.id?.value ?: "-"
    }

    /** The items being carried, ignoring where. For an assertion that does not care about order. */
    fun carried(): Set<String> = contents().filterNot { it == "-" }.toSet()

    companion object {

        /**
         * Boots the shipped definition and ticks until the champion can shop.
         *
         * The wallet, the inventory and the spawn point are granted by three systems in two
         * phases over the first ticks of a match, so a test that placed an order on tick one
         * would be testing [ShopRefusal.NoSuchChampion]. `WARMUP_TICKS` is generous and the
         * assertion below is what makes it honest: if the champion is not a shopper afterwards,
         * the harness says so rather than every test failing on a refusal.
         */
        fun boot(): ShopHarness {
            loadPhysicsNatives()
            val definition = MobaGame.definition()
            val catalog = definition.modules.filterIsInstance<ItemModule>().single().catalog
            val attributes = definition.modules.filterIsInstance<MobaModule>().single()
                .combat.attributes
            val host = GameHost(RenderMode.Headless, definition, null)
            MobaEntry.seed(host)
            host.run(WARMUP_TICKS)
            val harness = ShopHarness(host, catalog, attributes)
            with(host.world) {
                val champion = harness.champion()
                checkNotNull(champion.getOrNull(Wallet)) { "no wallet after $WARMUP_TICKS ticks" }
                checkNotNull(champion.getOrNull(Inventory)) { "no inventory after $WARMUP_TICKS ticks" }
                checkNotNull(champion.getOrNull(dev.wildware.moba.match.Respawn)) {
                    "no spawn point after $WARMUP_TICKS ticks, so the fountain is unknown"
                }
            }
            return harness
        }

        /** Ticks a fresh match runs before a champion has everything a shopper needs. */
        const val WARMUP_TICKS: Int = 5

        /**
         * Box2D's natives, which a headless test JVM does not have loaded.
         *
         * Copied from `LaneProofTest.loadPhysicsNatives`, whose KDoc explains the gap at length
         * and says the two lines belong to whoever owns `MobaPhysicsModule`. Duplicated rather
         * than shared because moving it would edit a file this ticket does not own, and it is
         * two idempotent calls; when the natives are loaded where they belong, both copies go.
         */
        private fun loadPhysicsNatives() {
            com.badlogic.gdx.utils.GdxNativesLoader.load()
            com.badlogic.gdx.physics.box2d.Box2D.init()
        }
    }
}

/** Every item id this game ships, spelled once so a rename fails to compile rather than to run. */
internal object Items {
    const val BLADE = "item/blade"
    const val WHETSTONE = "item/whetstone"
    const val CLOAK = "item/cloak"
    const val PLATE = "item/plate"
    const val VIAL = "item/vial"
    const val BAND = "item/band"
    const val GEM = "item/gem"
    const val BOOTS = "item/boots"
    const val GREATSWORD = "item/greatsword"
    const val TWIN_BLADES = "item/twin_blades"
    const val BULWARK = "item/bulwark"
    const val LIFESTONE = "item/lifestone"
    const val WARHAMMER = "item/warhammer"
    const val SCOUTING_TOTEM = "item/scouting_totem"
    const val WARDING_LENS = "item/warding_lens"
}
