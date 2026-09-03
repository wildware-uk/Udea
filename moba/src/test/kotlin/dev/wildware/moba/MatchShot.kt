package dev.wildware.moba

import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.moba.ability.Combatant
import dev.wildware.moba.ability.UnitBlueprint
import dev.wildware.moba.entry.MobaEntry
import dev.wildware.moba.item.ShopService
import dev.wildware.moba.lane.Wallet
import dev.wildware.moba.match.MatchPhase
import dev.wildware.moba.match.MatchService
import dev.wildware.udea.assets.AssetId
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.gas.Abilities
import dev.wildware.udea.gas.GameplayEffects
import dev.wildware.udea.gas.GasServices
import dev.wildware.udea.render.capture.CaptureResult
import dev.wildware.udea.render.input.InjectedIntent
import dev.wildware.udea.render.input.IntentState
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlin.io.path.createParentDirectories
import kotlin.io.path.writeBytes
import kotlin.system.exitProcess

/**
 * Four captures of the shipping game, each taken on the tick its subject is actually on screen.
 *
 * ## Why this is a harness and not a test
 *
 * The same reason [VfxShot] is one, and stated there at length: it creates a real GL context, so
 * on a machine with no driver it is a failure rather than a skip, and a gate that turns into a
 * skip is the failure mode this repository has already shipped once. It lives in the **test**
 * source set so `check` compiles it and the shipped jar does not carry it, and it has no `@Test`,
 * so JUnit never runs it. It is launched by hand off the test runtime classpath.
 *
 * ## What it photographs, and why these four
 *
 * These are the four claims a play agent graded "partly" and could not verify from a screenshot:
 *
 * | file | the claim it is evidence for |
 * |---|---|
 * | `melee.png` | overlapping units can be told apart, and one of them is visibly *you* |
 * | `hud.png` | a human can read their own health, their cooldowns and the score |
 * | `spin.png` | the elite orc special has art of its own and it is on screen when it fires |
 * | `result.png` | a match ends and the game says who won |
 * | `item_bar.png` | two bought items put their actives on the bar, beside the champion's own |
 * | `item_fired.png` | firing one item active leaves the champion's own two ready |
 *
 * The last two are issue #166's, and they are here rather than in a harness of their own because
 * this one already boots the shipped game with a GL context, drives it through the bound keys and
 * writes a PNG per subject - which is the whole of what they need. The purchases go in through
 * [ShopService], which is the same door a bot and a test use; nothing writes an [Inventory] slot
 * directly.
 *
 * `melee.png` and `hud.png` are two moments of one claim rather than the same file twice: the HUD
 * is drawn at `RenderPhase.UI`, which is *before* the capture point on purpose, so every frame
 * here carries it and the second one is taken once a cooldown is visibly running.
 *
 * ## The input is injected, not simulated
 *
 * The spin is fired by pressing the key a human presses. [InjectedIntent] is an ordinary
 * `IntentSource`, so `PlayerControlSystem` cannot tell it from a keyboard - which is what makes
 * `spin.png` evidence that **the control works** rather than evidence that an ability can be
 * activated from outside the input path.
 */
public object MatchShot {

    /** Where the PNGs are written. One per subject. */
    public const val OUTPUT_PROPERTY: String = "udea.matchshot.dir"

    /** The tick the melee is photographed on. Far enough in that the lines have met. */
    public const val MELEE_TICK: Long = 420L

    /**
     * The tick the champion goes shopping on.
     *
     * Early, and for a reason: `ShopSystem` refuses an order from a champion outside its own
     * fountain, and the champion has not been knocked anywhere yet at tick 20. Late enough that
     * `ChampionSystem`, `InventoryGrantSystem` and `RespawnSystem` have all granted what a shopper
     * needs - the same warm-up `ShopHarness` waits out, with room to spare.
     */
    public const val SHOP_TICK: Long = 20L

    /** How much gold the champion is given, so no picture here is about affordability. */
    public const val SHOP_GOLD: Int = 20_000

    /** The tick the HUD is photographed on. Later, so a cooldown is running in a slot. */
    public const val HUD_TICK: Long = 560L

    /** Give up after this many ticks. Long enough for a match to resolve on the default layout. */
    public const val DEADLINE_TICKS: Long = 2_400L

    private class Shot(
        val name: String,
        val future: CompletableFuture<CaptureResult>,
        val note: String,
    )

    @JvmStatic
    @Suppress("LongMethod", "NestedBlockDepth", "CyclomaticComplexMethod")
    public fun main(args: Array<String>) {
        val dir = Path.of(System.getProperty(OUTPUT_PROPERTY) ?: "build/reports/udea/match")
        val mode = MobaEntry.modeFromProperties(fallback = RenderMode.Offscreen)
        val pending = ArrayList<Shot>()
        val written = HashSet<String>()
        val log = StringBuilder()
        MobaEntry.runWithGl(mode) { host, rendering ->
            val player = MobaEntry.seed(host)
            val injected = InjectedIntent(MobaControls.BINDINGS.catalog)
            host.ctx[IntentState.KEY].source = injected
            MobaEntry.follow(rendering, player)
            val match = host.ctx[MatchService.KEY]
            val netIds = host.ctx[CoreModule.NET_IDS]
            val shop = host.ctx[ShopService.KEY]
            var spinAskedAt = -1L
            var resultAsked = false
            var shopped = false
            var itemBarAsked = false
            var itemFiredAt = -1L
            MobaEntry.Attachment(
                frame = { delta ->
                    host.frame(delta)
                    val now = host.ctx.clock.tick.value
                    if (now < MELEE_TICK && pending.none { it.name == "melee" } && "melee" !in written) {
                        pending += Shot(
                            "melee",
                            rendering.presentation().capture(afterTick = MELEE_TICK),
                            "the melee, camera on the player",
                        )
                    }
                    if (now < HUD_TICK && pending.none { it.name == "hud" } && "hud" !in written) {
                        pending += Shot(
                            "hud",
                            rendering.presentation().capture(afterTick = HUD_TICK),
                            "the HUD with a cooldown running",
                        )
                    }
                    // Issue #166. Buy two items with actives, photograph the bar they land on, then
                    // fire the first with the key bound to it and photograph what that costs.
                    if (!shopped && now >= SHOP_TICK) {
                        shopped = true
                        grantGold(host, netIds, player, SHOP_GOLD)
                        shop.buy(player, AssetId(WARHAMMER))
                        shop.buy(player, AssetId(AEGIS))
                    }
                    if (shopped && !itemBarAsked && itemActives(host, netIds, player) == 2) {
                        itemBarAsked = true
                        pending += Shot(
                            "item_bar",
                            rendering.presentation().capture(afterTick = now + 2),
                            "two item actives on the bar, granted at tick " + now,
                        )
                    }
                    // Fired only once the bar has been photographed empty of cooldowns, so the two
                    // pictures are the same bar before and after rather than two arbitrary frames.
                    if (itemFiredAt < 0 && "item_bar" in written) {
                        if (itemCooldown(host, netIds, player) > 0) {
                            itemFiredAt = now
                            pending += Shot(
                                "item_fired",
                                rendering.presentation().capture(afterTick = now + 2),
                                "the item bar cooling down, fired from " + MobaControls.ITEM_1 +
                                    " at tick " + now,
                            )
                        } else {
                            injected.tap(MobaControls.ITEM_1_ACTION)
                        }
                    }
                    // The spin: press Q, then photograph the tick after the activation lands. The
                    // press is repeated until the slot reports itself active, because the ability
                    // refuses while the unit is mid-cast or the slot is cooling, and a single tap
                    // that happened to land in that window would photograph nothing.
                    if (spinAskedAt < 0 && now > MELEE_TICK) {
                        if (spinIsActive(host, netIds, player)) {
                            spinAskedAt = now
                            pending += Shot(
                                "spin",
                                rendering.presentation().capture(afterTick = now + 2),
                                "the spin, fired from the bound key, activated at tick " + now,
                            )
                        } else {
                            injected.tap(MobaControls.ATTACK_2_ACTION)
                        }
                    }
                    // The result banner, thirty ticks after the match is decided, so the HUD has
                    // certainly sampled it.
                    if (!resultAsked && match.hasMatch && match.phase != MatchPhase.Fighting) {
                        resultAsked = true
                        pending += Shot(
                            "result",
                            rendering.presentation().capture(afterTick = now + 30),
                            "match " + match.matchNumber + " won by team " + match.winner +
                                " decided on tick " + match.endedTick,
                        )
                    }
                    val done = pending.filter { it.future.isDone }
                    for (shot in done) {
                        val result = shot.future.get()
                        val out = dir.resolve(shot.name + ".png")
                        out.createParentDirectories()
                        out.writeBytes(result.bytes)
                        written += shot.name
                        pending.remove(shot)
                        log.append("[match.shot] wrote ").append(out.toAbsolutePath())
                            .append(" ").append(result.width).append("x").append(result.height)
                            .append(" at tick ").append(result.tick.value)
                            .append(" - ").append(shot.note)
                            .append(" | alive=").append(alive(host))
                            .append(" score orc=").append(match.orcAlive)
                            .append(" soldier=").append(match.soldierAlive)
                            .append(" undead=").append(match.undeadAlive)
                            .append("\n")
                    }
                    if (written.size == SUBJECTS.size || now > DEADLINE_TICKS) {
                        rendering.requestExit()
                    }
                },
            )
        }
        print(log)
        val missing = SUBJECTS - written
        if (missing.isNotEmpty()) {
            System.err.println("[match.shot] never captured: " + missing)
            exitProcess(1)
        }
    }

    /** Every subject this harness photographs. */
    private val SUBJECTS = listOf("melee", "hud", "spin", "result", "item_bar", "item_fired")

    /** `item/warhammer`, which grants `ability/orc_elite_spin` as an active. */
    private const val WARHAMMER = "item/warhammer"

    /** `item/aegis`, which grants `ability/priest_heal`. Bought second, so it takes the second slot. */
    private const val AEGIS = "item/aegis"

    /**
     * Fills the champion's purse.
     *
     * The one thing this harness writes directly, and the same licence `ShopHarness` takes for the
     * same reason: gold is earned from last hits, and a capture that first had to farm a wave
     * would be a photograph of `BountySystem`. The purchase itself still goes through
     * [ShopService] and is still refused if the champion is dead or out of its fountain.
     */
    private fun grantGold(host: GameHost, netIds: NetIdIndex, player: NetId, gold: Int) {
        val entity = netIds.resolveOrNull(player) ?: return
        with(host.world) { entity.getOrNull(Wallet)?.gold = gold }
    }

    /** How many item slots hold a granted active right now. */
    private fun itemActives(host: GameHost, netIds: NetIdIndex, player: NetId): Int {
        val entity = netIds.resolveOrNull(player) ?: return 0
        return with(host.world) {
            val abilities = entity.getOrNull(Abilities) ?: return@with 0
            var granted = 0
            for (slot in UnitBlueprint.ITEM_SLOT_FIRST until UnitBlueprint.ABILITY_SLOTS) {
                if (slot < abilities.slotCount && abilities.instanceAt(slot).isGranted) granted++
            }
            granted
        }
    }

    /** Ticks left on the shared item cooldown, or `0`. */
    private fun itemCooldown(host: GameHost, netIds: NetIdIndex, player: NetId): Int {
        val entity = netIds.resolveOrNull(player) ?: return 0
        val gas = host.ctx[GasServices.KEY]
        return with(host.world) {
            val abilities = entity.getOrNull(Abilities) ?: return@with 0
            val effects = entity.getOrNull(GameplayEffects) ?: return@with 0
            gas.activation.cooldownRemaining(
                abilities,
                effects,
                UnitBlueprint.ITEM_SLOT_FIRST,
                host.ctx.clock.tick,
            )
        }
    }

    /** Living units, by the definition the match counts them with. */
    private fun alive(host: GameHost): Int =
        host.world.family { all(Combatant) }.entities.size

    /** Whether the player secondary slot is mid-cast right now. */
    private fun spinIsActive(host: GameHost, netIds: NetIdIndex, player: NetId): Boolean {
        val entity = netIds.resolveOrNull(player) ?: return false
        return with(host.world) {
            val abilities = entity.getOrNull(Abilities) ?: return@with false
            if (abilities.slotCount <= PlayerControlSystem.SLOT_SECONDARY) return@with false
            abilities.instanceAt(PlayerControlSystem.SLOT_SECONDARY).isActive
        }
    }
}
