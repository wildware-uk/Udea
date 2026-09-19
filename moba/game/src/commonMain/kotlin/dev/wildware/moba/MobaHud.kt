package dev.wildware.moba

import com.github.quillraven.fleks.Family
import com.github.quillraven.fleks.World
import dev.wildware.moba.ability.CharacterAttributes
import dev.wildware.moba.ability.Corpse
import dev.wildware.moba.ability.UnitBlueprint
import dev.wildware.moba.level.GameUnit
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.core.SimClock
import dev.wildware.udea.gas.Abilities
import dev.wildware.udea.gas.AbilityActivation
import dev.wildware.udea.gas.AbilityTable
import dev.wildware.udea.gas.Attributes
import dev.wildware.udea.gas.GameplayEffects
import dev.wildware.udea.render.OffscreenTarget
import dev.wildware.udea.render.RenderResource
import dev.wildware.udea.render.RenderResources
import dev.wildware.udea.render.RenderSystem
import dev.wildware.udea.render.ui.UiFonts
import dev.wildware.moba.level.Team
import dev.wildware.moba.match.MatchPhase
import dev.wildware.moba.match.MatchService
import dev.wildware.moba.match.Respawn

/**
 * Everything the player's own HUD shows, refilled in place once per frame.
 *
 * A value object and not a widget, so the numbers a human reads off the screen are the numbers a
 * headless test can assert on. `MobaHudTest` drives a real `GameHost` with no GL context in the
 * process and reads this; the drawing code below is then the only untested part of the HUD, and
 * it draws nothing it was not handed.
 *
 * Every array is sized once at [UnitBlueprint.ABILITY_SLOTS] and written by index, because
 * [MobaHudModel.sample] runs per frame.
 */
public class HudState {

    /** A [Player] entity exists in the world right now. `false` once the player has been killed. */
    public var alive: Boolean = false
        internal set

    /** A [Player] existed at some point and does not now. The one thing a corpse needs told. */
    public var died: Boolean = false
        internal set

    /** The `character` asset name of the unit being driven: `soldier`. Empty when [alive] is false. */
    public var unitName: String = ""
        internal set

    /** Current `health`, straight off the attribute `ability/damage` subtracts from. */
    public var health: Float = 0f
        internal set

    /** `maxHealth`. Zero would make every bar full, so [MobaHudScreen] treats it as "no bar". */
    public var maxHealth: Float = 0f
        internal set

    /** Current `mana`. Zero for a soldier, which has none. */
    public var mana: Float = 0f
        internal set

    /** `maxMana`. The mana rail is drawn only when this is positive - see `HealthbarRenderSystem`. */
    public var maxMana: Float = 0f
        internal set

    /** Ticks per second, so a cooldown in ticks can be shown to a human in seconds. */
    public var tickRate: Int = SimClock.DEFAULT_TICK_RATE
        internal set

    /** How many of the [UnitBlueprint.ABILITY_SLOTS] slots this unit was actually granted. */
    public var slotCount: Int = 0
        internal set

    /**
     * Ticks until a dead player stands up, or `0` when no respawn is pending.
     *
     * Read off `Respawn.readyTick` rather than counted here, for the reason every other number
     * on this screen is: a HUD that kept its own clock would keep counting through a
     * `time.rewind` and disagree with the system that actually stands the unit up.
     */
    public var respawnTicks: Int = 0
        internal set

    /** Whether a match exists at all. `false` in a world assembled without `MatchModule`. */
    public var hasMatch: Boolean = false
        internal set

    /** Which match of this session. One-based. */
    public var matchNumber: Int = 0
        internal set

    /** Whether a result is standing, as opposed to the fight still being on. */
    public var matchDecided: Boolean = false
        internal set

    /** The winning `Team` constant once [matchDecided], else `Team.NONE`. */
    public var winner: Int = Team.NONE
        internal set

    /** Living orcs, as of the last tick the simulation published. */
    public var orcAlive: Int = 0
        internal set

    /** @see orcAlive */
    public var soldierAlive: Int = 0
        internal set

    /** @see orcAlive */
    public var undeadAlive: Int = 0
        internal set

    private val names = Array(UnitBlueprint.ABILITY_SLOTS) { "" }

    private val remaining = IntArray(UnitBlueprint.ABILITY_SLOTS)

    private val total = IntArray(UnitBlueprint.ABILITY_SLOTS)

    /** The ability in [slot], as `AbilityDef.name` - `ability/melee`. Empty when ungranted. */
    public fun nameAt(slot: Int): String = names[slot]

    /** Ticks until [slot] comes off cooldown, `0` when it is ready. */
    public fun remainingAt(slot: Int): Int = remaining[slot]

    /** [slot]'s full cooldown after reduction, for the fraction a sweep fills. `0` for none. */
    public fun totalAt(slot: Int): Int = total[slot]

    internal fun setSlot(slot: Int, name: String, remainingTicks: Int, totalTicks: Int) {
        names[slot] = name
        remaining[slot] = remainingTicks
        total[slot] = totalTicks
    }

    internal fun clearSlots() {
        for (slot in names.indices) setSlot(slot, "", 0, 0)
        slotCount = 0
    }
}

/**
 * Reads the player's health, mana and cooldowns out of the live world, once per frame.
 *
 * ## Why the reading is split from the drawing
 *
 * Two reasons, and neither is taste. A `BitmapFont` needs a GL context, so a test that asserted
 * on pixels would need a window; this half needs none, and `MobaHudTest` runs it over
 * `RenderMode.Headless` against the real level and the real ability table. And the numbers are
 * the claim - "a human can see their cooldown" is false if the number is wrong, however good the
 * box around it looks.
 *
 * ## Nothing here is a second source of truth
 *
 * Health and mana come from the unit's own [Attributes], the same component
 * `HealthbarRenderSystem` draws the floating bars from and the same one `ability/damage` writes.
 * The cooldown comes from [AbilityActivation.cooldownRemaining], which resolves the instance's
 * `cooldownHandle` through the applied-effect list - so it is the *effect that is actually on the
 * unit*, not a timer this file keeps, and it survives a rewind for the same reason the fight
 * does. A HUD that counted down its own clock would keep counting through a `time.rewind` and
 * disagree with the ability it claims to describe.
 *
 * ## It is presentation, and not a Fleks system
 *
 * Spec 3.3, and `RenderRegistry.build` enforces it. This is a plain object a [RenderSystem]
 * calls; a headless server never constructs one, and a rewind does not re-run it.
 */
public class MobaHudModel(
    /** Which slots in an [Attributes] hold health and mana. See `HealthbarRenderSystem`. */
    private val attributeIds: CharacterAttributes,
    /** Names and base cooldowns. The **same** table the world's units hold indices into. */
    private val abilityTable: AbilityTable,
    /**
     * The one activation object in this game, borrowed for its two read-only queries.
     *
     * [AbilityActivation.cooldownRemaining] and [AbilityActivation.effectiveCooldownTicks] mutate
     * nothing and touch none of its scratch state. Reimplementing them here would be the third
     * §8 rejection - copy-pasted logic differing only in a constant - and would drift the moment
     * cooldown reduction changed shape.
     */
    private val activation: AbilityActivation,
) {

    private var world: World? = null

    private var players: Family? = null

    private var clock: SimClock? = null

    /**
     * The scoreboard mirror, or `null` in a world assembled without `MatchModule`.
     *
     * Nullable and looked up with `getOrNull`, because one world in this tree genuinely has no
     * match in it: `MobaShot` stands the roster on the field to photograph it and seeds no level.
     * A HUD that required the service would turn that capture into a crash.
     */
    private var match: MatchService? = null

    /** Refilled by [sample]; never replaced, so a drawer can hold on to it. */
    public val state: HudState = HudState()

    /** Whether a [Player] has ever been in this world. See [sample]'s empty-family branch. */
    private var seenPlayer: Boolean = false

    /** Binds to the world being drawn. Called by [MobaHudSystem.onBind]. */
    public fun bind(world: World, ctx: GameContext) {
        this.world = world
        this.clock = ctx.clock
        this.players = world.family { all(Player) }
        this.match = ctx.getOrNull(MatchService.KEY)
    }

    /**
     * Refills [state] from the world.
     *
     * ## Death is a [Corpse], not a missing entity
     *
     * The obvious test - "the `Player` family is empty" - is wrong here, and writing it that way
     * first is how this was found. `DeathSystem.retire` does not remove a dead unit: it strips the
     * `Combatant`, zeroes the `Motion` and adds a [Corpse], and the entity lies on the field for
     * `DeathSystem.CORPSE_TICKS` - five seconds - before `clearOldBodies` takes it. It keeps its
     * `Player` component throughout. A HUD that waited for the entity to disappear would therefore
     * go on cheerfully drawing a corpse's health and cooldowns for five seconds after the player
     * died, and only then say anything, which is worse than saying nothing.
     *
     * The empty-family branch is still here for the five seconds after that, and [HudState.died]
     * is remembered rather than derived, because by then there is nothing left in the world to ask.
     */
    public fun sample() {
        val world = this.world ?: return
        val players = this.players ?: return
        val clock = this.clock ?: return
        state.tickRate = clock.tickRate
        sampleMatch()
        val entities = players.entities
        if (entities.size == 0) {
            state.alive = false
            state.respawnTicks = 0
            // Only once one has been seen. Sampling before the level's scene swap has drained
            // would otherwise report a death that has not happened to a player who does not exist.
            state.died = seenPlayer
            state.clearSlots()
            return
        }
        seenPlayer = true
        with(world) {
            val entity = entities[0]
            if (entity.getOrNull(Corpse) != null) {
                state.alive = false
                state.died = true
                // The one number a dead player actually wants. `readyTick - now` and not a timer
                // of this HUD's own: `RespawnSystem` owns the schedule, and a second clock here
                // would drift the moment a rewind moved the first one.
                val respawn = entity.getOrNull(Respawn)
                state.respawnTicks = if (respawn != null && respawn.isScheduled) {
                    (respawn.readyTick - clock.tick.value).coerceAtLeast(0L).toInt()
                } else {
                    0
                }
                state.clearSlots()
                return@with
            }
            state.alive = true
            state.died = false
            state.respawnTicks = 0
            state.unitName = entity.getOrNull(GameUnit)?.unitKind?.character ?: ""
            val attributes = entity.getOrNull(Attributes)
            if (attributes == null) {
                state.maxHealth = 0f
                state.maxMana = 0f
            } else {
                state.health = attributes.current(attributeIds.health)
                state.maxHealth = attributes.current(attributeIds.maxHealth)
                state.mana = attributes.current(attributeIds.mana)
                state.maxMana = attributes.current(attributeIds.maxMana)
            }
            val abilities = entity.getOrNull(Abilities)
            val effects = entity.getOrNull(GameplayEffects)
            if (abilities == null || effects == null || attributes == null) {
                state.clearSlots()
                return@with
            }
            val slots = minOf(abilities.slotCount, UnitBlueprint.ABILITY_SLOTS)
            state.slotCount = slots
            for (slot in 0 until slots) {
                val instance = abilities.instanceAt(slot)
                if (!instance.isGranted) {
                    state.setSlot(slot, "", 0, 0)
                    continue
                }
                val def = abilityTable.defAt(instance.abilityIndex)
                state.setSlot(
                    slot = slot,
                    name = def.name,
                    remainingTicks = activation.cooldownRemaining(
                        abilities,
                        effects,
                        slot,
                        clock.tick,
                    ),
                    totalTicks = activation.effectiveCooldownTicks(def, attributes),
                )
            }
            for (slot in slots until UnitBlueprint.ABILITY_SLOTS) state.setSlot(slot, "", 0, 0)
        }
    }

    /**
     * Copies the scoreboard out of [MatchService].
     *
     * The mirror and not the [dev.wildware.moba.match.MatchState] component, deliberately: the
     * component is the truth and the mirror is a copy of it written once per tick by the system
     * that owns it, so reading the mirror costs no family walk on a per-frame path and cannot be
     * more than one tick behind the thing it copies. A HUD that walked the world for a singleton
     * every frame would be paying a lookup sixty times a second to be no more correct.
     */
    private fun sampleMatch() {
        val match = this.match
        if (match == null || !match.hasMatch) {
            state.hasMatch = false
            return
        }
        state.hasMatch = true
        state.matchNumber = match.matchNumber
        state.matchDecided = match.phase != MatchPhase.Fighting
        state.winner = match.winner
        state.orcAlive = match.orcAlive
        state.soldierAlive = match.soldierAlive
        state.undeadAlive = match.undeadAlive
    }
}

/**
 * The HUD as one [RenderSystem]: [MobaHudModel] sampled once a frame, and [MobaHudScreen] drawn from
 * what it sampled, into the capturable frame through `udea-render`'s `CapturedUi`.
 *
 * ## What was on screen before this
 *
 * Nothing, originally. Two play agents graded the game "partly" and both said the same thing: a human
 * cannot see their own health, cannot tell which of twenty-seven sprites is theirs, has no idea what
 * abilities they have, and when they die the controls simply stop answering. The floating
 * `HealthbarRenderSystem` rails are stacked into unreadable stripes in the middle of an eleven-unit
 * melee and are the *world's* information, not the player's.
 *
 * Then a scene2d `Stage`, then - once LibGDX left in issue #211 - a painter drawing `BitmapFont2D`
 * glyphs through the sprite batch as a stand-in. Issue #188 is this: the drawing half is a ComposeGL
 * screen, and [HudState] and [MobaHudModel] did not change, so what `MobaHudTest` asserts on is still
 * exactly what a player reads.
 *
 * ## Where it draws, and why a capture has it
 *
 * Into the **capturable** frame, on top of everything every `RenderSystem` drew. Game UI is part of
 * the game: an agent asking for a screenshot to check whether an ability is on cooldown gets the
 * cooldown in the picture. That is why this is a `CapturedUi` and not a `UiLayer` - a `UiLayer` draws
 * into the window after the presented frame, which is exactly where menus and the editor belong and
 * exactly where a HUD must not be. The agent activity overlay is the other thing that stays out of a
 * capture, and it is a different type on a different surface.
 *
 * The registration's `RenderPhase.UI` still orders [render] after the world passes; the drawing
 * itself is after all of them by construction, because the captured interface is a view drawn after
 * the batch rather than a point in it.
 *
 * ## The fonts
 *
 * Handed in by the launcher, because a `UiFonts` is a rasteriser and each platform has its own - the
 * desktop's is `DesktopFonts`, over stb_truetype, and this module is common code that cannot name it.
 * The pipeline owns them from here: they are registered with [RenderResources.own] before the
 * interface that draws with them, so the reverse-order release closes them after it.
 */
public class MobaHudSystem(
    resources: RenderResources,
    attributeIds: CharacterAttributes,
    abilityTable: AbilityTable,
    activation: AbilityActivation,
    /** Registered at [HUD_FONT_SIZES] in the toolkit's default family. Owned from here on. */
    fonts: UiFonts,
) : RenderSystem {

    private val model = MobaHudModel(attributeIds, abilityTable, activation)

    private val screen = MobaHudScreen(model.state, keyLabels())

    init {
        resources.own(RenderResource { fonts.close() })
        resources.capturedUi(fonts).show(screen)
    }

    override fun onBind(world: World, ctx: GameContext) {
        model.bind(world, ctx)
    }

    override fun render(target: OffscreenTarget, alpha: Float) {
        // Sampled here, before Kool draws the pass the interface lands in, so the box and the number
        // in it describe the same tick as the world under them.
        model.sample()
        screen.refresh()
    }

    public companion object {

        /**
         * The key printed in each slot's box, read back out of the bindings the game runs on.
         *
         * Not the literals `"SPACE"` and `"Q"`. The bindings come from
         * `moba/game/assets/control/controls.udea.kts` and rebinding attack to `E` is an asset
         * edit with no Kotlin recompiled - a HUD that hard-coded the letter would be the one thing
         * in the input path that did not follow the asset, and it would be wrong silently.
         *
         * An action with no key bound prints `-`: a control the graph declares and binds nothing
         * to is legitimately present and unpressable, which is exactly what an empty box says.
         */
        public fun keyLabels(): Array<String> =
            Array(UnitBlueprint.ABILITY_SLOTS) { labelFor(MobaControls.SLOT_ACTIONS[it]) }

        private fun labelFor(action: dev.wildware.udea.render.input.ActionId): String {
            val keys = MobaControls.BINDINGS.binding(action).keys
            return if (keys.isEmpty()) "-" else MobaControls.keyName(keys[0])
        }
    }
}
