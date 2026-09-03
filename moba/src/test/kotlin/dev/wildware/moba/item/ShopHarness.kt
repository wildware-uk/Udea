package dev.wildware.moba.item

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.moba.MobaGame
import dev.wildware.moba.MobaModule
import dev.wildware.moba.ability.CharacterAttributes
import dev.wildware.moba.ability.Corpse
import dev.wildware.moba.ability.MobaTags
import dev.wildware.moba.MobaControls
import dev.wildware.moba.Player
import dev.wildware.moba.PlayerControlSystem
import dev.wildware.udea.gas.Abilities
import dev.wildware.udea.gas.ActivationResult
import dev.wildware.udea.gas.AttributeId
import dev.wildware.udea.gas.Attributes
import dev.wildware.udea.gas.GameplayEffects
import dev.wildware.udea.gas.GasServices
import dev.wildware.moba.Position
import dev.wildware.moba.entry.MobaEntry
import dev.wildware.moba.lane.Wallet
import dev.wildware.udea.assets.AssetId
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.render.input.ActionId
import dev.wildware.udea.render.input.InjectedIntent
import dev.wildware.udea.render.input.IntentState

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
    /** This world's tag vocabulary, for an assertion about *which* tag blocked an activation. */
    val tags: MobaTags,
) {

    /** The one activation path, the applier and the tables, off the built context. */
    val gas: GasServices = host.ctx[GasServices.KEY]

    /** Where [tap] writes. Installed as this host's intent source; see [tap]. */
    private val injected = InjectedIntent(MobaControls.BINDINGS.catalog).also {
        host.ctx[IntentState.KEY].source = it
    }

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

    /** Its stats. `current` is what an item bonus moves; `base` is what damage writes. */
    fun attributes(): Attributes = with(host.world) { champion()[Attributes] }

    /** Its ability bar: two of its kind's and `UnitBlueprint.ITEM_SLOTS` for item actives. */
    fun abilities(): Abilities = with(host.world) { champion()[Abilities] }

    /** Every effect applied to it right now. */
    fun effects(): GameplayEffects = with(host.world) { champion()[GameplayEffects] }

    /** The champion's `current` value of [id]. */
    fun stat(id: AttributeId): Float = attributes().current(id)

    /** How many applications of the effect named [name] the champion is carrying. */
    fun applied(name: String): Int {
        val defIndex = gas.effects.indexOf(name)
        val effects = effects()
        var count = 0
        for (slot in 0 until effects.count) if (effects.defIndexAt(slot) == defIndex) count++
        return count
    }

    /** Runs [ticks] ticks. */
    fun run(ticks: Int) {
        host.run(ticks)
    }

    /**
     * The ability name in [slot], or `-` when the slot holds nothing.
     *
     * A name and not an index, so a failure says `ability/orc_elite_spin` rather than `1`.
     */
    fun abilityIn(slot: Int): String {
        val instance = abilities().instanceAt(slot)
        return if (!instance.isGranted) "-" else gas.abilities.defAt(instance.abilityIndex).name
    }

    /** Ticks until [slot] comes off cooldown, `0` when it is ready. */
    fun cooldown(slot: Int): Int =
        gas.activation.cooldownRemaining(abilities(), effects(), slot, host.tick)

    /** Whether [slot] would activate right now, and if not, why. Mutates nothing. */
    fun canActivate(slot: Int): ActivationResult =
        gas.activation.canActivate(championId(), abilities(), attributes(), effects(), slot, host.tick)

    /** Activates [slot] through the one activation path this game has, and says what happened. */
    fun activate(slot: Int): ActivationResult =
        gas.activation.activate(championId(), abilities(), attributes(), effects(), slot, host.tick)

    /**
     * Presses [action] and runs the tick that samples it.
     *
     * [InjectedIntent] is what `input.tap` drives over HTTP and it is the *same* `IntentState` a
     * keyboard writes, so `PlayerControlSystem` cannot tell one from the other. A press here is a
     * press - which is what makes an assertion about it evidence that the **key** fires an item
     * active, rather than evidence that an ability can be activated by calling the activation
     * object directly.
     */
    fun tap(action: ActionId) {
        injected.tap(action)
        host.run(1)
    }

    /** The one `PlayerControlSystem` this host runs, for its counters. */
    fun control(): PlayerControlSystem = host.world.system<PlayerControlSystem>()

    /**
     * Kills the champion where it stands and runs the tick that retires it.
     *
     * Writes the `health` **base**, because damage is an instant effect and instant effects write
     * `base` - so this is the same number a killing blow would have reduced, and `DeathSystem`
     * sees the same zero it would have seen.
     */
    fun kill() {
        attributes().setBase(combat.health, 0f)
        host.run(1)
        check(with(host.world) { champion().getOrNull(Corpse) } != null) {
            "the champion still has no Corpse a tick after its health was zeroed; `DeathSystem` " +
                "is what retires it and it runs in SimPhase.Gameplay"
        }
    }

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
            val combat = definition.modules.filterIsInstance<MobaModule>().single().combat
            val host = GameHost(RenderMode.Headless, definition, null)
            MobaEntry.seed(host)
            host.run(WARMUP_TICKS)
            val harness = ShopHarness(host, catalog, combat.attributes, combat.tags)
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
    const val SENTINEL_GREAVES = "item/sentinel_greaves"
    const val BLOODLETTER = "item/bloodletter"
    const val ARCHMAGE_STAFF = "item/archmage_staff"
    const val AEGIS = "item/aegis"
    const val PHOENIX_CHARM = "item/phoenix_charm"
}
