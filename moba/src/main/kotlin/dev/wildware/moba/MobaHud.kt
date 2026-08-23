package dev.wildware.moba

import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Stage
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
import dev.wildware.udea.render.FrameTime
import dev.wildware.udea.render.OffscreenTarget
import dev.wildware.udea.render.RenderResources
import dev.wildware.udea.render.RenderSystem
import dev.wildware.udea.render.ui.UiLayer
import dev.wildware.udea.render.ui.UiScreen
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
 * ## On the scene2d layer, at [dev.wildware.udea.render.RenderPhase.UI]
 *
 * Which is before the capture point, on purpose - `UiLayer`'s own KDoc makes the argument: game
 * UI is part of the game, so an agent asking for a screenshot to check whether an ability is on
 * cooldown gets the cooldown in the picture. The agent activity **overlay** is the thing that
 * must stay out of a capture, and it is a different type on a different surface.
 *
 * The stage is never installed as an input processor. That is deliberate and it is the coordination
 * point named in this wave's brief: input reaches the simulation through `IntentState` and nothing
 * else, and a stage that swallowed keys would put a second, untested reader in front of the one
 * path a human and an agent share. Nothing here is clickable.
 */
internal class MobaHudScreen(
    private val model: MobaHudModel,
    private val font: BitmapFont,
    private val pixel: TextureRegion,
    /** What to print in each slot's box: the key actually bound to it, out of the asset graph. */
    private val keyLabels: Array<String>,
) : UiScreen {

    override fun build(stage: Stage): Actor = HudActor(model, font, pixel, keyLabels)
}

/**
 * The one actor the HUD is made of.
 *
 * A single custom [Actor] rather than a `Table` of `Label`s, for one reason worth stating: every
 * number here changes every frame, and a scene2d `Label` re-lays-out and allocates a
 * `GlyphLayout` on every `setText` with new content. This draws straight into the batch the stage
 * already opened, formats into a reused [StringBuilder], and allocates nothing per frame - the
 * same argument `DebugOverlayRenderSystem` makes for the same reason.
 */
@Suppress("MagicNumber")
private class HudActor(
    private val model: MobaHudModel,
    private val font: BitmapFont,
    private val pixel: TextureRegion,
    private val keyLabels: Array<String>,
) : Actor() {

    /** Reused. See the class KDoc: no `String` is built per frame. */
    private val text = StringBuilder(48)

    /**
     * `ability/orc_elite_spin` shortened to `ORC_ELITE_SPIN`, memoised on the name it came from.
     *
     * `substringAfterLast` and `uppercase` each allocate a `String`, and a slot's ability changes
     * when a unit is granted a different one - which is to say almost never, and certainly not
     * sixty times a second. The key is compared by identity **and** equality: the model hands back
     * the same interned constant every frame, so the identity check is the one that fires.
     */
    private val shortNames = arrayOfNulls<String>(UnitBlueprint.ABILITY_SLOTS)

    private val shortNameKeys = arrayOfNulls<String>(UnitBlueprint.ABILITY_SLOTS)

    /** [HudState.unitName] uppercased, memoised for the same reason. */
    private var unitLabel: String = ""

    private var unitLabelKey: String = ""

    override fun draw(batch: Batch, parentAlpha: Float) {
        val state = model.state
        val stage = stage ?: return
        // Three states, not two. `alive` and `died` are both false in a world that has simply
        // never had a player in it - which is not hypothetical: `MobaShot` stands the whole
        // roster on the field and seeds no level, so an `else` here would stamp YOU DIED across
        // the one capture whose entire job is to show the characters.
        if (state.alive) {
            drawVitals(batch, state)
            drawSlots(batch, state)
        } else if (state.died) {
            drawDeath(batch, state, stage.width, stage.height)
        }
        // After the vitals and before nothing: the scoreboard is the only thing on this screen
        // that is true whether or not the player is alive, which is exactly why the game read as
        // a fight simulator without it. It is drawn last so its strip sits over the world rather
        // than under a health rail that happens to reach the top of the screen.
        if (state.hasMatch) drawMatch(batch, state, stage.width, stage.height)
        batch.color = Color.WHITE
        font.color = Color.WHITE
    }

    /**
     * The score at the top, and the result across the middle once there is one.
     *
     * ## Why the counts are drawn even while nobody has won
     *
     * Because "who is winning" is the whole of the objective in a last-side-standing game, and
     * the alternative shipped: two play agents watched twenty-seven units fight for forty
     * seconds with no number anywhere on screen telling them what was being contested or how
     * close it was. Three living counts is the smallest thing that makes the fight legible.
     *
     * ## Allocation
     *
     * None. Every label is a constant, the numbers go through the reused [text], and
     * [teamLabel] is a `when` over `Team`'s constants rather than an `uppercase()` per frame.
     */
    private fun drawMatch(batch: Batch, state: HudState, width: Float, height: Float) {
        val top = height - MARGIN
        batch.color = BACKGROUND
        batch.draw(pixel, 0f, top - SCORE_STRIP, width, SCORE_STRIP)
        batch.color = Color.WHITE
        text.setLength(0)
        text.append("MATCH ").append(state.matchNumber).append("    ")
        text.append("ORC ").append(state.orcAlive).append("   ")
        text.append("SOLDIER ").append(state.soldierAlive).append("   ")
        text.append("UNDEAD ").append(state.undeadAlive)
        font.color = Color.WHITE
        font.draw(batch, text, MARGIN, top - PADDING)
        if (!state.matchDecided) return
        // The result, in the middle, at title scale. `drawDeath` may already have put its own
        // banner there; a player who died on the last blow of a match sees both, which is the
        // truth about what happened and reads better than either one suppressing the other.
        val middle = height / 2f + BANNER_HEIGHT
        batch.color = RESULT_BACKGROUND
        batch.draw(pixel, 0f, middle - BANNER_HEIGHT / 2f, width, BANNER_HEIGHT)
        batch.color = Color.WHITE
        val scale = font.data.scaleX
        font.color = RESULT_COLOUR
        font.data.setScale(scale * TITLE_SCALE)
        text.setLength(0)
        if (state.winner == Team.NONE) text.append(DRAW_TITLE) else {
            text.append(teamLabel(state.winner)).append(" WINS")
        }
        // Centred by the character count rather than by a `GlyphLayout`: laying out a string to
        // centre it allocates a layout per frame, and this face is fixed-scale here.
        font.draw(batch, text, width / 2f - text.length * TITLE_HALF_CHAR, middle + TITLE_HALF_HEIGHT)
        font.data.setScale(scale)
        font.color = Color.WHITE
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
     * The text goes **above** the health rail rather than inside it. `BitmapFont.draw` takes the
     * top of the line, not the baseline, so a label placed inside a sixteen-pixel bar descends
     * through it and through the mana rail underneath - which is legible right up until the
     * moment a bar is half empty and the glyphs are half on the fill and half on the backing.
     */
    private fun drawVitals(batch: Batch, state: HudState) {
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
        font.color = Color.WHITE
        font.draw(batch, text, MARGIN, healthBottom + BAR_HEIGHT + LINE_HEIGHT + 2f)
        // Only a unit that has mana gets a rail for it, the same rule the floating bars follow:
        // an always-empty second rail teaches a player to ignore the place the priest's mana is.
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
     * The name is **outside** the box. `ORC_ELITE_SPIN` at this font scale is about a hundred and
     * fifty pixels wide and the box is seventy-six, so a name drawn inside would run across its
     * neighbour and off the panel - which is how a HUD ends up unreadable in exactly the situation
     * it exists for.
     */
    private fun drawSlots(batch: Batch, state: HudState) {
        val namesLeft = MARGIN + UnitBlueprint.ABILITY_SLOTS * (SLOT_SIZE + GAP) + GAP * 2f
        for (slot in 0 until state.slotCount) {
            val left = MARGIN + slot * (SLOT_SIZE + GAP)
            val name = state.nameAt(slot)
            val remaining = state.remainingAt(slot)
            val ready = remaining == 0
            batch.color = when {
                name.isEmpty() -> EMPTY_COLOUR
                ready -> READY_COLOUR
                else -> BACKGROUND
            }
            batch.draw(pixel, left, MARGIN, SLOT_SIZE, SLOT_SIZE)
            if (name.isEmpty()) continue
            // The sweep: a shutter over the fraction of the cooldown still to run, shrinking
            // downward. A box that is nearly clear is a box nearly ready, which is the reading a
            // player already has from every MOBA they have played.
            val total = state.totalAt(slot)
            if (!ready && total > 0) {
                val filled = (remaining.toFloat() / total).coerceIn(0f, 1f)
                batch.color = SWEEP_COLOUR
                batch.draw(pixel, left, MARGIN, SLOT_SIZE, SLOT_SIZE * filled)
            }
            batch.color = Color.WHITE
            font.color = if (ready) Color.WHITE else COOLING_TEXT
            font.draw(batch, keyLabels[slot], left + PADDING, MARGIN + SLOT_SIZE - PADDING)
            if (!ready) {
                text.setLength(0)
                appendSeconds(remaining, state.tickRate)
                font.draw(batch, text, left + PADDING, MARGIN + LINE_HEIGHT + PADDING)
            }
            font.draw(
                batch,
                shortNameOf(slot, name),
                namesLeft,
                MARGIN + SLOT_SIZE - PADDING - slot * LINE_HEIGHT,
            )
        }
        font.color = Color.WHITE
    }

    /**
     * What a dead player is told, because the alternative is what shipped: nothing at all.
     *
     * Both play agents reported the same thing - the controls stop answering, there is no message,
     * and a player cannot tell a death from a freeze.
     *
     * ## Why there is no second line telling them what to do
     *
     * There was one, and it read "no respawn yet - close the window and run it again". That was
     * true when it was written and stopped being true in the same wave: `moba.match.RespawnSystem`
     * stands a dead unit back up after `MatchRules.RESPAWN_TICKS`. A banner that told a player to
     * restart the process three seconds before the game revived them would be worse than the
     * silence it replaced, so the banner says only the part that is true either way.
     *
     * ## The countdown is here now
     *
     * It is `entity[Respawn].readyTick - now`, sampled by [MobaHudModel] and carried on
     * [HudState.respawnTicks]. A player who is told only that they died still cannot tell a
     * death from a freeze - the second line is what says the game is coming back, and when.
     * Zero means no respawn is scheduled, which is a unit this game does not revive, and the
     * line is then omitted rather than printed as `0.0s`.
     */
    private fun drawDeath(batch: Batch, state: HudState, width: Float, height: Float) {
        val middle = height / 2f
        batch.color = DEATH_BACKGROUND
        batch.draw(pixel, 0f, middle - BANNER_HEIGHT / 2f, width, BANNER_HEIGHT)
        batch.color = Color.WHITE
        val scale = font.data.scaleX
        font.color = DEATH_COLOUR
        font.data.setScale(scale * TITLE_SCALE)
        font.draw(batch, DEAD_TITLE, width / 2f - TITLE_HALF_WIDTH, middle + TITLE_HALF_HEIGHT)
        // Restored before the hint, and before anything else in the frame draws with this font:
        // the scale lives on the shared `BitmapFontData`, so leaving it doubled would make every
        // later label twice the size it asked for, one frame at a time.
        font.data.setScale(scale)
        if (state.respawnTicks > 0) {
            text.setLength(0)
            text.append(RESPAWN_PREFIX)
            appendSeconds(state.respawnTicks, state.tickRate)
            font.draw(batch, text, width / 2f - RESPAWN_HALF_WIDTH, middle - TITLE_HALF_HEIGHT)
        }
        font.color = Color.WHITE
    }

    /** The dark backing, then [filled] of [BAR_WIDTH] in [colour]. */
    private fun rail(batch: Batch, bottom: Float, filled: Float, colour: Color) {
        batch.color = BACKGROUND
        batch.draw(pixel, MARGIN, bottom, BAR_WIDTH, BAR_HEIGHT)
        if (filled <= 0f) return
        batch.color = colour
        batch.draw(pixel, MARGIN, bottom, BAR_WIDTH * filled, BAR_HEIGHT)
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

        /** One line of the 15px face at `MobaHudSystem.FONT_SCALE`, with a little room. */
        const val LINE_HEIGHT: Float = 22f

        const val BANNER_HEIGHT: Float = 88f
        const val TITLE_SCALE: Float = 2.2f

        /** Half a line of [DEAD_TITLE] at [TITLE_SCALE], so the banner centres on the screen. */
        const val TITLE_HALF_HEIGHT: Float = 22f

        /**
         * Half the rendered width of [DEAD_TITLE], for centring it.
         *
         * A measured constant rather than a `GlyphLayout`: laying out one fixed string to centre
         * it would allocate a layout per frame for text that never changes, and the face and the
         * scale are both fixed here. If either changes, this is what is wrong.
         */
        const val TITLE_HALF_WIDTH: Float = 110f

        const val DEAD_TITLE: String = "YOU DIED"

        const val DRAW_TITLE: String = "DRAW"

        const val RESPAWN_PREFIX: String = "back in "

        /** Half the rendered width of the longest respawn line, for centring it. Measured. */
        const val RESPAWN_HALF_WIDTH: Float = 52f

        /** The height of the score strip along the top. One line plus [PADDING] either side. */
        const val SCORE_STRIP: Float = 32f

        /**
         * Half the width of one character of the title face at [TITLE_SCALE].
         *
         * The result banner's text is not a fixed string - it is one of four team names - so the
         * single measured constant [TITLE_HALF_WIDTH] cannot centre it, and a `GlyphLayout` per
         * frame is what this class exists to avoid. Arial at this scale averages close enough to
         * this that a title is centred to within a character.
         */
        const val TITLE_HALF_CHAR: Float = 13f

        val BACKGROUND: Color = Color(0.08f, 0.06f, 0.06f, 0.82f)
        val HEALTH_COLOUR: Color = Color(0.85f, 0.22f, 0.20f, 1f)
        val MANA_COLOUR: Color = Color(0.30f, 0.36f, 0.90f, 1f)
        val READY_COLOUR: Color = Color(0.16f, 0.24f, 0.34f, 0.88f)
        val EMPTY_COLOUR: Color = Color(0.10f, 0.10f, 0.10f, 0.55f)
        val SWEEP_COLOUR: Color = Color(0.02f, 0.02f, 0.04f, 0.72f)
        val COOLING_TEXT: Color = Color(0.62f, 0.62f, 0.66f, 1f)
        val DEATH_BACKGROUND: Color = Color(0.30f, 0.02f, 0.02f, 0.72f)
        val DEATH_COLOUR: Color = Color(1f, 0.86f, 0.86f, 1f)
        val RESULT_BACKGROUND: Color = Color(0.06f, 0.10f, 0.18f, 0.80f)
        val RESULT_COLOUR: Color = Color(1f, 0.92f, 0.55f, 1f)
    }
}

/**
 * The HUD as one [RenderSystem]: a [UiLayer] with [MobaHudScreen] mounted in it.
 *
 * Wrapping the layer rather than registering it directly is what gives the HUD the world. A
 * [UiScreen] is handed a `Stage` and nothing else - by design, since a menu has no business
 * touching entities - so the binding arrives here, at the one type in the chain that has
 * [RenderSystem.onBind].
 *
 * The font, the white pixel and the layer's stage all go through [RenderResources.own], so the
 * pipeline disposes them in reverse construction order and nothing here has a `dispose` a caller
 * has to remember.
 */
public class MobaHudSystem(
    resources: RenderResources,
    frameTime: FrameTime,
    attributeIds: CharacterAttributes,
    abilityTable: AbilityTable,
    activation: AbilityActivation,
) : RenderSystem {

    private val model = MobaHudModel(attributeIds, abilityTable, activation)

    /**
     * LibGDX's built-in 15px face, scaled.
     *
     * A `Skin` would be the scene2d answer and this tree has none - no skin asset, no
     * `AssetKind` for one, and inventing a JSON skin to draw eight strings would be a bigger
     * unshared thing than the HUD it served. Stated rather than hidden: the day this game gets a
     * skin, this line is what changes.
     */
    private val font: BitmapFont = resources.own(BitmapFont())
        .apply { data.setScale(FONT_SCALE) }

    /** One white pixel, stretched into every bar and box. The same trick `Healthbar.kt` uses. */
    private val pixel: TextureRegion = TextureRegion(
        resources.own(
            Texture(
                Pixmap(1, 1, Pixmap.Format.RGBA8888).apply {
                    setColor(Color.WHITE)
                    fill()
                },
            ).also { it.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest) },
        ),
    )

    private val ui = UiLayer(resources, frameTime)

    init {
        ui.show(MobaHudScreen(model, font, pixel, keyLabels()))
    }

    override fun onBind(world: World, ctx: GameContext) {
        model.bind(world, ctx)
        ui.onBind(world, ctx)
    }

    override fun render(target: OffscreenTarget, alpha: Float) {
        // Sampled before the stage draws, so the box and the number in it describe the same tick.
        model.sample()
        ui.render(target, alpha)
    }

    public companion object {

        /** 15px Arial on a 720p window is small. 1.4 is readable without turning to mush. */
        public const val FONT_SCALE: Float = 1.4f

        /**
         * The key printed in each slot's box, read back out of the bindings the game runs on.
         *
         * Not the literals `"SPACE"` and `"Q"`. The bindings come from
         * `moba/assets/control/controls.udea.kts` and rebinding attack to `E` is an asset edit
         * with no Kotlin recompiled - a HUD that hard-coded the letter would be the one thing in
         * the input path that did not follow the asset, and it would be wrong silently.
         *
         * An action with no key bound prints `-`: a control the graph declares and binds nothing
         * to is legitimately present and unpressable, which is exactly what an empty box says.
         */
        public fun keyLabels(): Array<String> = arrayOf(
            labelFor(MobaControls.ATTACK_ACTION),
            labelFor(MobaControls.ATTACK_2_ACTION),
        )

        private fun labelFor(action: dev.wildware.udea.render.input.ActionId): String {
            val keys = MobaControls.BINDINGS.binding(action).keys
            return if (keys.isEmpty()) "-" else Input.Keys.toString(keys[0]).uppercase()
        }
    }
}
