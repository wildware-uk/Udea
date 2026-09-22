package dev.wildware.hollow.render

import com.github.quillraven.fleks.World
import dev.wildware.hollow.CombatRules
import dev.wildware.hollow.FoxWaves
import dev.wildware.hollow.HollowCombat
import dev.wildware.hollow.HollowControls
import dev.wildware.hollow.Player
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.gas.Attributes
import dev.wildware.udea.render.OffscreenTarget
import dev.wildware.udea.render.RenderResource
import dev.wildware.udea.render.RenderResources
import dev.wildware.udea.render.RenderSystem
import dev.wildware.udea.render.ui.UiFonts

/**
 * Whose HUD this is, and what the time is where it is decided (issue #252).
 *
 * Two answers a HUD cannot find in the world it draws. **Whose**: a client's world holds every
 * player, and only the connection knows which one is its own. **When**: a client's clock is its
 * own and is not the server's (`HollowGame`'s KDoc says why), but a cooldown's ready tick and the
 * wave schedule are both the server's ticks. `HollowClient` is one of these, with the character
 * the server gave it and the newest server tick it has been told of.
 */
public interface HollowHudSource {

    /** The player the HUD is about, or [NetId.NONE] while it is not known yet. */
    public val character: NetId

    /** The tick the world being drawn is as of, on the clock the server keeps. */
    public val now: Tick
}

/**
 * Everything the HUD shows, refilled in place once a frame (issue #252).
 *
 * A value object and not a widget, as `moba`'s `HudState` is and for its reason: the numbers a
 * player reads off the screen are the numbers a headless test asserts on, and the drawing is the only
 * part of the HUD that test does not reach.
 */
internal class HollowHudState {

    /** A player to show exists in the world. False before the server has named one. */
    var present: Boolean = false
        internal set

    /** That player's health has reached zero. */
    var dead: Boolean = false
        internal set

    /** Hit points, off the `hollow.health` attribute. */
    var health: Float = 0f
        internal set

    /** The ceiling [health] is clamped to. */
    var maxHealth: Float = 0f
        internal set

    /** How many waves of foxes have arrived. `0` before the first. */
    var wave: Long = 0L
        internal set

    /** The score. Always `0` in this ticket: scoring is issue #253's (H5). */
    var score: Int = 0
        internal set

    private val remaining = IntArray(CombatRules.PLAYER_SLOTS)

    /** Ticks until [slot] - `CombatRules.ATTACK_SLOT`, `DASH_SLOT` or `HEAL_SLOT` - is ready; `0` when it is. */
    fun remainingAt(slot: Int): Int = remaining[slot]

    internal fun setRemaining(slot: Int, ticks: Int) {
        remaining[slot] = ticks
    }

    override fun toString(): String =
        "HollowHudState(present=$present dead=$dead health=$health/$maxHealth wave=$wave score=$score " +
            "remaining=${remaining.toList()})"
}

/**
 * Reads the HUD's numbers out of a world, once a frame (issue #252).
 *
 * ## Nothing here is a second source of truth
 *
 * Health is the player's own replicated `Attributes`. A cooldown is `Player`'s ready tick for the
 * slot - the `@Net` copy of the cooldown effect, which is the only form of it a client is sent -
 * less the source's tick. The wave is `FoxWaves.arrivedBy` of that same tick. So a HUD on the server,
 * on a client and in a test reads the same numbers from the same state, and none of them keeps a
 * clock of its own that could keep running through a rewind.
 *
 * Presentation, and not a Fleks system: a plain object [HollowHudSystem] calls, never constructed
 * by a headless server.
 */
internal class HollowHudModel(
    /** The tables the player's `Attributes` index. The same object the world was built with. */
    private val combat: HollowCombat,
    /** The wave schedule the server plays, or null for a world that sends none. */
    private val waves: FoxWaves?,
) {

    /** Refilled by [sample]; never replaced, so a drawer can hold on to it. */
    val state: HollowHudState = HollowHudState()

    /** Refills [state] from [world] for the player [character], as of the server's tick [now]. */
    fun sample(world: World, netIds: NetIdIndex, character: NetId, now: Tick) {
        state.wave = waves?.arrivedBy(now) ?: 0L
        state.score = 0
        val entity = if (character == NetId.NONE) null else netIds.resolveOrNull(character)
        val player = entity?.let { with(world) { it.getOrNull(Player) } }
        if (entity == null || player == null) {
            state.present = false
            for (slot in 0 until CombatRules.PLAYER_SLOTS) state.setRemaining(slot, 0)
            return
        }
        state.present = true
        val attributes = with(world) { entity.getOrNull(Attributes) }
        state.health = attributes?.base(combat.health) ?: 0f
        state.maxHealth = attributes?.base(combat.maxHealth) ?: 0f
        state.dead = attributes != null && combat.isDead(attributes)
        state.setRemaining(CombatRules.ATTACK_SLOT, left(player.attackReady, now))
        state.setRemaining(CombatRules.DASH_SLOT, left(player.dashReady, now))
        state.setRemaining(CombatRules.HEAL_SLOT, left(player.healReady, now))
    }

    private fun left(ready: Tick, now: Tick): Int = ready.ticksSince(now).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

    override fun toString(): String = "HollowHudModel($state)"
}

/**
 * The HUD as one [RenderSystem]: [HollowHudModel] sampled once a frame and [HollowHudScreen] drawn
 * from it, into the **captured** frame through `udea-render`'s `CapturedUi` - so a screenshot holds
 * the health and the cooldowns a player sees (issue #252). `moba`'s `MobaHudSystem` is the same
 * shape, and says at length why a HUD is a `CapturedUi` and not a `UiLayer`.
 *
 * The fonts are handed in by the launcher, because a rasteriser is a platform's; they are owned from
 * here, registered with [RenderResources.own] before the interface that draws with them.
 */
internal class HollowHudSystem(
    resources: RenderResources,
    private val model: HollowHudModel,
    /** Whose HUD, as of when. Read every frame, because a launcher learns it after this is built. */
    private val source: () -> HollowHudSource?,
    /** Registered at [HOLLOW_HUD_FONT_SIZES] in the toolkit's default family. */
    fonts: UiFonts,
) : RenderSystem {

    private val screen = HollowHudScreen(model.state, keyLabels())

    private var world: World? = null
    private var netIds: NetIdIndex? = null

    init {
        resources.own(RenderResource { fonts.close() })
        resources.capturedUi(fonts).show(screen)
    }

    override fun onBind(world: World, ctx: GameContext) {
        this.world = world
        this.netIds = ctx[CoreModule.NET_IDS]
    }

    override fun render(target: OffscreenTarget, alpha: Float) {
        val world = world ?: return
        val netIds = netIds ?: return
        val from = source() ?: return
        // Sampled here, before Kool draws the pass the interface lands in, so the numbers describe
        // the tick the world under them is drawn at.
        model.sample(world, netIds, from.character, from.now)
        screen.refresh()
    }

    override fun toString(): String = "HollowHudSystem($model)"

    private companion object {
        /** The key printed on each slot, read back out of the bindings the game runs on. */
        fun keyLabels(): Array<String> = Array(CombatRules.PLAYER_SLOTS) { slot ->
            val action = when (slot) {
                CombatRules.ATTACK_SLOT -> HollowControls.ATTACK_ACTION
                CombatRules.DASH_SLOT -> HollowControls.DASH_ACTION
                else -> HollowControls.HEAL_ACTION
            }
            val keys = HollowControls.BINDINGS.binding(action).keys
            if (keys.isEmpty()) "-" else keys[0].name.uppercase()
        }
    }
}
