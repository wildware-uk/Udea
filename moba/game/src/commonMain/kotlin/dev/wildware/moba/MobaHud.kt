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
import dev.wildware.udea.render.RenderPhase
import dev.wildware.udea.render.RenderResources
import dev.wildware.udea.render.RenderSystem
import dev.wildware.udea.render.draw.BitmapFont2D
import dev.wildware.udea.render.draw.Rgba
import dev.wildware.udea.render.draw.SpriteBatch2D
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
 * The player's health, mana, ability slots and cooldowns, drawn in screen space.
 *
 * ## What was on screen before this
 *
 * Nothing. Two play agents graded the game "partly" and both said the same thing: a human cannot
 * see their own health, cannot tell which of twenty-seven sprites is theirs, has no idea what
 * abilities they have, and when they die the controls simply stop answering. The floating
 * `HealthbarRenderSystem` rails are stacked into unreadable stripes in the middle of an
 * eleven-unit melee and are the *world's* information, not the player's.
 *
 * ## This is an interim rendering, and the typeface is the visible part of that
 *
 * It was a scene2d `Stage` with one custom `Actor` in it, drawn with LibGDX's built-in 15px face.
 * Both left with LibGDX in issue #211, and issue #188 - "port `MobaHud`'s drawing half to
 * ComposeGL, leaving `HudState` untouched" - is the ticket that decides what the HUD finally looks
 * like. Until it lands the numbers are drawn with [BitmapFont2D], the 5x7 face `udea-render`
 * compiles in, through the same [SpriteBatch2D] every other renderer uses.
 *
 * What that costs is the typeface and nothing else: the same values, in the same places, in the
 * same colours. What it buys is that a capture still *has* a HUD, so the port's parity comparison
 * compares a heads-up display against a heads-up display rather than against a hole.
 *
 * [HudState] and [MobaHudModel] are untouched, which is issue #188's explicit requirement: what a
 * headless test asserts on did not move, and this class is only the half that paints it.
 *
 * ## Why it is one class and not a widget tree
 *
 * The scene2d version made the same argument and it survives the port: every number here changes
 * every frame, so a widget that re-lays-out on `setText` allocates per frame per label. This
 * formats into a reused [StringBuilder], measures with [BitmapFont2D.measure] - which is
 * arithmetic over a fixed advance, not a layout - and allocates nothing per frame.
 *
 * ## Where it draws, and what that means for a capture
 *
 * Into the **offscreen** target, at [RenderPhase.UI], which is before the capture point. That is
 * deliberate and unchanged: game UI is part of the game, so an agent asking for a screenshot to
 * check whether an ability is on cooldown gets the cooldown in the picture. The agent activity
 * overlay is the thing that must stay out of a capture, and it is a different type on a different
 * surface.
 */
internal class HudPainter(
    private val model: MobaHudModel,
    private val font: BitmapFont2D,
    private val titleFont: BitmapFont2D,
    /** What to print in each slot's box: the key actually bound to it, out of the asset graph. */
    private val keyLabels: Array<String>,
) {

    /** Reused. See the class KDoc: no `String` is built per frame. */
    private val text = StringBuilder(48)

    /**
     * `ability/orc_elite_spin` shortened to `ORC_ELITE_SPIN`, memoised on the name it came from.
     *
     * `substringAfterLast` and `uppercase` each allocate a `String`, and a slot's ability changes
     * when a unit is granted a different one - which is to say almost never, and certainly not
     * sixty times a second. The key is compared by identity, because the model hands back the same
     * interned constant every frame.
     */
    private val shortNames = arrayOfNulls<String>(UnitBlueprint.ABILITY_SLOTS)

    private val shortNameKeys = arrayOfNulls<String>(UnitBlueprint.ABILITY_SLOTS)

    /** [HudState.unitName] uppercased, memoised for the same reason. */
    private var unitLabel: String = ""

    private var unitLabelKey: String = ""

    /** Draws this frame's HUD into [batch], which the caller has begun in pixel space. */
    fun draw(batch: SpriteBatch2D, width: Float, height: Float) {
        val state = model.state
        // Three states, not two. `alive` and `died` are both false in a world that has simply
        // never had a player in it - which is not hypothetical: `MobaShot` stands the whole roster
        // on the field and seeds no level, so an `else` here would stamp YOU DIED across the one
        // capture whose entire job is to show the characters.
        if (state.alive) {
            drawVitals(batch, state)
            drawSlots(batch, state)
        } else if (state.died) {
            drawDeath(batch, state, width, height)
        }
        // After the vitals and before nothing: the scoreboard is the only thing on this screen
        // that is true whether or not the player is alive, which is exactly why the game read as a
        // fight simulator without it. It is drawn last so its strip sits over the world rather
        // than under a health rail that happens to reach the top of the screen.
        if (state.hasMatch) drawMatch(batch, state, width, height)
    }

    /**
     * The score at the top, and the result across the middle once there is one.
     *
     * ## Why the counts are drawn even while nobody has won
     *
     * Because "who is winning" is the whole of the objective in a last-side-standing game, and the
     * alternative shipped: two play agents watched twenty-seven units fight for forty seconds with
     * no number anywhere on screen telling them what was being contested or how close it was.
     * Three living counts is the smallest thing that makes the fight legible.
     */
    private fun drawMatch(batch: SpriteBatch2D, state: HudState, width: Float, height: Float) {
        val top = height - MARGIN
        batch.fill(0f, top - SCORE_STRIP, width, SCORE_STRIP, BACKGROUND)
        text.setLength(0)
        text.append("MATCH ").append(state.matchNumber).append("    ")
        text.append("ORC ").append(state.orcAlive).append("   ")
        text.append("SOLDIER ").append(state.soldierAlive).append("   ")
        text.append("UNDEAD ").append(state.undeadAlive)
        label(batch, font, text, MARGIN, top - PADDING, Rgba.WHITE)
        if (!state.matchDecided) return
        // The result, in the middle, at title scale. `drawDeath` may already have put its own
        // banner there; a player who died on the last blow of a match sees both, which is the
        // truth about what happened and reads better than either one suppressing the other.
        val middle = height / 2f + BANNER_HEIGHT
        batch.fill(0f, middle - BANNER_HEIGHT / 2f, width, BANNER_HEIGHT, RESULT_BACKGROUND)
        text.setLength(0)
        if (state.winner == Team.NONE) text.append(DRAW_TITLE) else {
            text.append(teamLabel(state.winner)).append(" WINS")
        }
        // Centred by measuring, which for a fixed-advance face is one multiply and allocates
        // nothing - so the scene2d version's hand-measured half-width constants are gone, and with
        // them the way they went stale when the face changed.
        centred(batch, titleFont, text, width, middle, RESULT_COLOUR)
    }

    /** A `Team` constant as the word this HUD prints. Constants, so nothing is allocated. */
    private fun teamLabel(team: Int): String = when (team) {
        Team.ORC -> "ORC"
        Team.SOLDIER -> "SOLDIER"
        Team.UNDEAD -> "UNDEAD"
        else -> "NOBODY"
    }

    /**
     * The health rail, the mana rail under it when the unit has any, and the numbers over both.
     *
     * The text goes **above** the health rail rather than inside it: a label placed inside a
     * sixteen-pixel bar is legible right up until the moment the bar is half empty and the glyphs
     * are half on the fill and half on the backing.
     */
    private fun drawVitals(batch: SpriteBatch2D, state: HudState) {
        val healthBottom = MARGIN + SLOT_SIZE + ROW_GAP + BAR_HEIGHT + GAP
        rail(
            batch,
            healthBottom,
            HealthbarRenderSystem.fractionOf(state.health, state.maxHealth),
            HEALTH_COLOUR,
        )
        if (state.unitName !== unitLabelKey) {
            unitLabelKey = state.unitName
            unitLabel = state.unitName.uppercase()
        }
        text.setLength(0)
        text.append(unitLabel).append("   ")
        text.append(state.health.toInt()).append(" / ").append(state.maxHealth.toInt())
        label(batch, font, text, MARGIN, healthBottom + BAR_HEIGHT + LINE_HEIGHT + 2f, Rgba.WHITE)
        // Only a unit that has mana gets a rail for it, the same rule the floating bars follow: an
        // always-empty second rail teaches a player to ignore the place the priest's mana is.
        if (state.maxMana <= 0f) return
        rail(
            batch,
            healthBottom - GAP - BAR_HEIGHT,
            HealthbarRenderSystem.fractionOf(state.mana, state.maxMana),
            MANA_COLOUR,
        )
    }

    /**
     * One box per slot, with the key that fires it, plus the ability names in a column beside them.
     *
     * The name is **outside** the box: `ORC_ELITE_SPIN` is wider than a slot at any readable size,
     * so a name drawn inside would run across its neighbour and off the panel - which is how a HUD
     * ends up unreadable in exactly the situation it exists for.
     */
    private fun drawSlots(batch: SpriteBatch2D, state: HudState) {
        val namesLeft = MARGIN + UnitBlueprint.ABILITY_SLOTS * (SLOT_SIZE + GAP) + GAP * 2f
        // The names run *downward* from here, one per slot, so the anchor has to leave room for
        // every slot below the first or the last name is drawn off the bottom of the window.
        // Anchored off `ABILITY_SLOTS` rather than off `state.slotCount`, so the column does not
        // jump up the screen when a unit happens to have been granted fewer.
        val namesTop = MARGIN + LINE_HEIGHT * UnitBlueprint.ABILITY_SLOTS
        for (slot in 0 until state.slotCount) {
            val left = MARGIN + slot * (SLOT_SIZE + GAP)
            val name = state.nameAt(slot)
            val remaining = state.remainingAt(slot)
            val ready = remaining == 0
            val box = when {
                name.isEmpty() -> EMPTY_COLOUR
                ready -> READY_COLOUR
                else -> BACKGROUND
            }
            batch.fill(left, MARGIN, SLOT_SIZE, SLOT_SIZE, box)
            // The key goes on an empty box too, and that is a change issue #166 made after looking
            // at a capture: the item bar starts empty, so a box with nothing in it and no letter
            // on it is a slot a player has no way to discover. Dimmed, because an empty slot is
            // not something to press yet.
            val keyTint = when {
                name.isEmpty() -> EMPTY_TEXT
                ready -> Rgba.WHITE
                else -> COOLING_TEXT
            }
            label(batch, font, keyLabels[slot], left + PADDING, MARGIN + SLOT_SIZE - PADDING, keyTint)
            if (name.isEmpty()) continue
            // The sweep: a shutter over the fraction of the cooldown still to run, shrinking
            // downward. A box that is nearly clear is a box nearly ready, which is the reading a
            // player already has from every MOBA they have played.
            val total = state.totalAt(slot)
            if (!ready && total > 0) {
                val filled = (remaining.toFloat() / total).coerceIn(0f, 1f)
                batch.fill(left, MARGIN, SLOT_SIZE, SLOT_SIZE * filled, SWEEP_COLOUR)
            }
            if (!ready) {
                text.setLength(0)
                appendSeconds(remaining, state.tickRate)
                label(batch, font, text, left + PADDING, MARGIN + LINE_HEIGHT + PADDING, keyTint)
            }
            label(
                batch, font, shortNameOf(slot, name),
                namesLeft, namesTop - slot * LINE_HEIGHT, keyTint,
            )
        }
    }

    /**
     * What a dead player is told, because the alternative is what shipped: nothing at all.
     *
     * Both play agents reported the same thing - the controls stop answering, there is no message,
     * and a player cannot tell a death from a freeze.
     *
     * The countdown is `entity[Respawn].readyTick - now`, sampled by [MobaHudModel] and carried on
     * [HudState.respawnTicks]. A player who is told only that they died still cannot tell a death
     * from a freeze - the second line is what says the game is coming back, and when. Zero means no
     * respawn is scheduled, which is a unit this game does not revive, and the line is then omitted
     * rather than printed as `0.0s`.
     */
    private fun drawDeath(batch: SpriteBatch2D, state: HudState, width: Float, height: Float) {
        val middle = height / 2f
        batch.fill(0f, middle - BANNER_HEIGHT / 2f, width, BANNER_HEIGHT, DEATH_BACKGROUND)
        centred(batch, titleFont, DEAD_TITLE, width, middle, DEATH_COLOUR)
        if (state.respawnTicks > 0) {
            text.setLength(0)
            text.append(RESPAWN_PREFIX)
            appendSeconds(state.respawnTicks, state.tickRate)
            centred(batch, font, text, width, middle - BANNER_HEIGHT / 2f + PADDING, Rgba.WHITE)
        }
    }

    /** The dark backing, then [filled] of [BAR_WIDTH] in [colour]. */
    private fun rail(batch: SpriteBatch2D, bottom: Float, filled: Float, colour: Rgba) {
        batch.fill(MARGIN, bottom, BAR_WIDTH, BAR_HEIGHT, BACKGROUND)
        if (filled <= 0f) return
        batch.fill(MARGIN, bottom, BAR_WIDTH * filled, BAR_HEIGHT, colour)
    }

    /**
     * [text] with its left edge at [x] and the **top** of its line at [topY].
     *
     * The scene2d face placed a line by its top and [BitmapFont2D] places one by its baseline, so
     * the conversion lives here rather than at each of the nine call sites - which is what stops
     * one of them being converted differently from the rest.
     */
    private fun label(
        batch: SpriteBatch2D,
        face: BitmapFont2D,
        value: CharSequence,
        x: Float,
        topY: Float,
        tint: Rgba,
    ) {
        face.draw(batch, value, x, topY - face.lineHeight, tint)
    }

    /** [value] centred on [width], with the middle of its line at [middleY]. */
    private fun centred(
        batch: SpriteBatch2D,
        face: BitmapFont2D,
        value: CharSequence,
        width: Float,
        middleY: Float,
        tint: Rgba,
    ) {
        face.draw(batch, value, (width - face.measure(value)) / 2f, middleY - face.lineHeight / 2f, tint)
    }

    /** @see shortNames */
    private fun shortNameOf(slot: Int, name: String): String {
        if (shortNameKeys[slot] !== name) {
            shortNameKeys[slot] = name
            shortNames[slot] = name.substringAfterLast('/').uppercase()
        }
        return shortNames[slot] ?: ""
    }

    /**
     * Seconds to one decimal, into [text], in integer arithmetic.
     *
     * Not `String.format` and not `"%.1f"`: both allocate, per cooling slot per frame, on the
     * per-frame path. `(ticks * 10 + rate / 2) / rate` is a rounded tenth with no float in it.
     */
    private fun appendSeconds(ticks: Int, tickRate: Int) {
        if (tickRate <= 0) return
        val tenths = (ticks.toLong() * 10 + tickRate / 2) / tickRate
        text.append(tenths / 10).append('.').append(tenths % 10).append('s')
    }

    private companion object {

        const val MARGIN: Float = 18f
        const val GAP: Float = 6f
        const val PADDING: Float = 8f

        /** The gap between the ability row and the bars above it. */
        const val ROW_GAP: Float = 14f

        const val SLOT_SIZE: Float = 76f
        const val BAR_WIDTH: Float = 300f
        const val BAR_HEIGHT: Float = 16f

        /** One line of the body face at [MobaHudSystem.FONT_SCALE], with a little room. */
        const val LINE_HEIGHT: Float = 22f

        const val BANNER_HEIGHT: Float = 88f

        const val DEAD_TITLE: String = "YOU DIED"

        const val DRAW_TITLE: String = "DRAW"

        const val RESPAWN_PREFIX: String = "back in "

        /** The height of the score strip along the top. One line plus [PADDING] either side. */
        const val SCORE_STRIP: Float = 32f

        val BACKGROUND: Rgba = Rgba.of(0.08f, 0.06f, 0.06f, 0.82f)
        val HEALTH_COLOUR: Rgba = Rgba.of(0.85f, 0.22f, 0.20f, 1f)
        val MANA_COLOUR: Rgba = Rgba.of(0.30f, 0.36f, 0.90f, 1f)
        val READY_COLOUR: Rgba = Rgba.of(0.16f, 0.24f, 0.34f, 0.88f)
        val EMPTY_COLOUR: Rgba = Rgba.of(0.10f, 0.10f, 0.10f, 0.55f)
        val SWEEP_COLOUR: Rgba = Rgba.of(0.02f, 0.02f, 0.04f, 0.72f)
        val COOLING_TEXT: Rgba = Rgba.of(0.62f, 0.62f, 0.66f, 1f)

        /** The key on a slot holding nothing: legible, and clearly not something to press. */
        val EMPTY_TEXT: Rgba = Rgba.of(0.45f, 0.45f, 0.48f, 1f)
        val DEATH_BACKGROUND: Rgba = Rgba.of(0.30f, 0.02f, 0.02f, 0.72f)
        val DEATH_COLOUR: Rgba = Rgba.of(1f, 0.86f, 0.86f, 1f)
        val RESULT_BACKGROUND: Rgba = Rgba.of(0.06f, 0.10f, 0.18f, 0.80f)
        val RESULT_COLOUR: Rgba = Rgba.of(1f, 0.92f, 0.55f, 1f)
    }
}

/**
 * The HUD as one [RenderSystem]: [MobaHudModel] sampled, then [HudPainter] over the frame's batch.
 *
 * ## What the layer used to be
 *
 * A `Scene2dUiLayer` with a `Stage` in it, and a `Scene2dUiScreen` mounted in that. Both left with
 * LibGDX in issue #211, and the stage bought this HUD nothing it is losing: it was never installed
 * as an input processor - deliberately, because input reaches the simulation through `IntentState`
 * and nothing else - and the one actor in it drew straight into the batch the stage had opened.
 * So the layer is gone and the painter draws into the frame's own batch, which is one object fewer
 * between a number and a pixel.
 *
 * The fonts go through [RenderResources.own], so the pipeline releases them in reverse
 * construction order and nothing here has a `dispose` a caller has to remember.
 */
public class MobaHudSystem(
    resources: RenderResources,
    attributeIds: CharacterAttributes,
    abilityTable: AbilityTable,
    activation: AbilityActivation,
) : RenderSystem {

    private val batch = resources.batch

    private val model = MobaHudModel(attributeIds, abilityTable, activation)

    /**
     * The body face: `udea-render`'s compiled-in 5x7, at [FONT_SCALE].
     *
     * A face with no asset behind it, which is the property the scene2d version needed too - it
     * used LibGDX's built-in for the same reason, because this tree has no skin and no font asset
     * and inventing one to draw eight strings would be a bigger unshared thing than the HUD it
     * served. Issue #188's ComposeGL host is what replaces this with real glyphs.
     */
    private val font: BitmapFont2D = resources.own(BitmapFont2D.builtIn(FONT_SCALE))

    /** The same face at [TITLE_SCALE], for the two banners. One sheet each; they are 1KB. */
    private val titleFont: BitmapFont2D = resources.own(BitmapFont2D.builtIn(TITLE_SCALE))

    private val painter = HudPainter(model, font, titleFont, keyLabels())

    override fun onBind(world: World, ctx: GameContext) {
        model.bind(world, ctx)
    }

    override fun render(target: OffscreenTarget, alpha: Float) {
        // Sampled before anything draws, so the box and the number in it describe the same tick.
        model.sample()
        batch.beginPixels()
        try {
            painter.draw(batch, target.width.toFloat(), target.height.toFloat())
        } finally {
            // In a `finally` because a batch left begun poisons every later pass in the frame with
            // a failure that names the wrong system.
            batch.end()
        }
    }

    public companion object {

        /**
         * Texels per glyph texel for the body face: a 5x7 glyph drawn ten pixels wide.
         *
         * Two rather than three, and the number is decided by the widest line this HUD draws
         * rather than by taste: the vitals line is about twenty-one characters and the health rail
         * it sits over is [HudPainter] `BAR_WIDTH` = 300 pixels, so at a six-texel advance a
         * scale of 2 fits it and a scale of 3 overruns it by a third.
         */
        public const val FONT_SCALE: Int = 2

        /** The banner face. Twice the body, which is close to the 2.2 the scene2d titles used. */
        public const val TITLE_SCALE: Int = 4

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
