package dev.wildware.moba

import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonSkippableComposable
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.HorizontalAlignment
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.layout.Spacer
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.align
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.height
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.text.TextStyle
import dev.wildware.composegl.ui.widget.Text
import dev.wildware.moba.ability.UnitBlueprint
import dev.wildware.moba.level.Team
import dev.wildware.udea.render.ui.UiScreen

/**
 * The player's health, mana, ability and item slots, death banner and score, as a ComposeGL
 * [UiScreen]: the drawing half of the HUD, over the [HudState] that [MobaHudModel] fills.
 *
 * ## What it replaced
 *
 * A painter that drew every string with `udea-render`'s 5x7 `BitmapFont2D` and every box with the
 * sprite batch, placing each by hand (issue #212's stand-in for the LibGDX widget HUD, which left with
 * LibGDX). The words, the colours and where each thing sits came across; the arithmetic that placed
 * them did not, because a composition lays itself out. The typeface is the visible difference: real
 * glyphs from a `.ttf`, which is the whole of what the owner's parity rule for this port allows to
 * differ - every number [HudState] carries is still on screen.
 *
 * ## Every widget is a plain composable
 *
 * ComposeGL has game widgets - a `Hotbar` with a `RadialCooldown`, a `Bar` - and none is used, for
 * reasons of correctness rather than looks. A `Hotbar`'s cooldown is a timer the *interface* owns and
 * counts down on its own clock; this HUD's cooldown is [HudState.remainingAt], read off the effect
 * actually on the unit, so it cannot keep counting through a `time.rewind` - a second clock is the
 * thing [MobaHudModel] exists to avoid. A `Hotbar` is also clickable and answers the number keys,
 * where input reaches the simulation through `IntentState` and nothing else. And both live in
 * `composegl-game`, a dependency this module would take for a rail that is two boxes.
 *
 * ## The panels are opaque
 *
 * The stand-in drew its backings at about 80% opacity over the world. These are solid: text is
 * readable over a solid panel whatever melee is behind it, which is what the HUD is for, and a solid
 * panel is a colour a capture can be checked for exactly - `MatchShot` does, and that check is what
 * would notice a HUD that stopped reaching the capture.
 *
 * ## Why [refresh] exists
 *
 * [HudState] is refilled in place and is not Compose state, on purpose: it is the value object
 * `MobaHudTest` asserts on with no toolkit anywhere, and it does not change for this. So nothing tells
 * the composition a field moved. [refresh] bumps one counter that [content] reads, once a frame after
 * [MobaHudModel.sample]; [content] re-runs, reads the fields, and hands them down as plain values, so
 * each piece below recomposes only when a value it shows has changed.
 *
 * ## Allocation
 *
 * The stand-in allocated nothing per frame and this does: a label string whenever the number in it
 * changes, which for a cooling slot is ten times a second. Labels are built from whole numbers -
 * tenths of a second, whole hit points - so a frame on which nothing a player can read has changed
 * builds none.
 */
internal class MobaHudScreen(
    /** The model's state. Held and never copied: [MobaHudModel.sample] refills this instance. */
    private val state: HudState,
    /** What to print in each slot's box: the key actually bound to it, out of the asset graph. */
    private val keyLabels: Array<String>,
) : UiScreen {

    /** Bumped by [refresh] and read by [content]. Its value means nothing; that it moved does. */
    private val frame = mutableLongStateOf(0L)

    /** Tells the composition [state] may have changed. Once a frame, after the model has sampled. */
    fun refresh() {
        frame.longValue++
    }

    @Composable
    override fun content() {
        // Read for its effect: this read is what re-runs `content` after `refresh`.
        frame.longValue
        Box(Modifier.fillMaxSize()) {
            // Three states, not two. `alive` and `died` are both false in a world that has never had
            // a player in it - `MobaShot` stands the roster up and seeds no level - so an `else` here
            // would stamp YOU DIED across the roster picture.
            if (state.alive) {
                PlayerPanel(state, keyLabels)
            } else if (state.died) {
                DeathBanner(tenthsOf(state.respawnTicks, state.tickRate))
            }
            // The one thing that is true whether or not the player is alive, drawn last so it sits
            // over the rest.
            if (state.hasMatch) {
                ScoreStrip(state.matchNumber, state.orcAlive, state.soldierAlive, state.undeadAlive)
                if (state.matchDecided) ResultBanner(state.winner)
            }
        }
    }

    /** What `MobaHudScreenTest` measures the drawing against. */
    internal companion object {

        /** The length of the health and mana rails. */
        const val BAR_WIDTH: Float = 300f

        /** The side of one slot's box. */
        const val SLOT_SIZE: Float = 76f

        val HEALTH_FILL: Colour = Colour.rgb(0xD93833)
        val MANA_FILL: Colour = Colour.rgb(0x4D5CE6)

        /** The shutter over the part of a cooldown still to run. */
        val SWEEP: Colour = Colour.rgb(0x08080C)
    }
}

/**
 * Where the HUD's solid panels are and what colour each is, for a harness that checks a capture.
 *
 * Public because the reader is `:moba:desktop`'s `MatchShot`, which is another module: it asks whether
 * the HUD reached the picture by reading one pixel of each panel that should be there, and a HUD that
 * drew into the window instead - or drew its panels translucent, as the stand-in did - fails it. The
 * values are the ones [MobaHudScreen] lays out with, so they cannot be typed twice and drift.
 */
public object MobaHudLook {

    /** From the capture's edges to the player panel and the score strip. */
    public const val MARGIN: Float = 18f

    /** Inside the player panel, around what it holds. */
    public const val PANEL_PADDING: Float = 10f

    /** The height of the death and result banners. */
    public const val BANNER: Float = 88f

    /** The strip along the top: one line of body text with room either side. */
    public const val SCORE_STRIP: Float = 32f

    /** The player panel and the score strip, as `0xRRGGBB`. */
    public const val PANEL_RGB: Int = 0x140F0F

    /** The death banner. */
    public const val DEATH_RGB: Int = 0x4D0505

    /** The result banner. */
    public const val RESULT_RGB: Int = 0x0F1A2E
}

/**
 * The bottom-left panel: the unit and its hit points, the rails, then the slots and their names.
 *
 * The label goes above the health rail rather than inside it: text inside a sixteen-pixel bar is half
 * on the fill and half on the backing the moment the bar is half empty.
 *
 * ## Never skipped
 *
 * It is handed [state], and [state] is the same instance every frame with different numbers in it.
 * Compose compares an argument like that by identity, so without [NonSkippableComposable] it would
 * decide nothing had changed and keep drawing the first frame's health for ever. What it hands *down*
 * is plain numbers, so the pieces below it still skip when theirs are unchanged.
 */
@Composable
@NonSkippableComposable
private fun PlayerPanel(state: HudState, keyLabels: Array<String>) {
    Column(
        Modifier.testTag(MobaHudTags.PANEL)
            .align(Alignment.BottomStart)
            .offset(MobaHudLook.MARGIN, -MobaHudLook.MARGIN)
            .background(PANEL)
            .padding(MobaHudLook.PANEL_PADDING),
    ) {
        Vitals(state.unitName, state.health.toInt(), state.maxHealth.toInt())
        Spacer(Modifier.height(GAP))
        Rail(MobaHudTags.HEALTH, HealthbarRenderSystem.fractionOf(state.health, state.maxHealth), MobaHudScreen.HEALTH_FILL)
        // Only a unit that has mana gets a rail for it, the rule the floating bars follow: an
        // always-empty second rail teaches a player to ignore the place the priest's mana is.
        if (state.maxMana > 0f) {
            Spacer(Modifier.height(GAP))
            Rail(MobaHudTags.MANA, HealthbarRenderSystem.fractionOf(state.mana, state.maxMana), MobaHudScreen.MANA_FILL)
        }
        Spacer(Modifier.height(ROW_GAP))
        Row {
            for (slot in 0 until state.slotCount) {
                if (slot > 0) Spacer(Modifier.width(GAP))
                val remaining = state.remainingAt(slot)
                val total = state.totalAt(slot)
                Slot(
                    slot = slot,
                    key = keyLabels[slot],
                    granted = state.nameAt(slot).isNotEmpty(),
                    shutter = if (remaining > 0 && total > 0) (remaining.toFloat() / total).coerceIn(0f, 1f) else 0f,
                    tenths = if (remaining > 0) tenthsOf(remaining, state.tickRate) else NO_TIME,
                )
            }
            SlotNames(state)
        }
    }
}

@Composable
private fun Vitals(unitName: String, health: Int, maxHealth: Int) {
    val label = remember(unitName) { unitName.uppercase() }
    Text("$label   $health / $maxHealth", Modifier.testTag(MobaHudTags.VITALS), textStyle = BODY, colour = Colour.White)
}

/** A backing [MobaHudScreen.BAR_WIDTH] long with [filled] of it in [colour]. */
@Composable
private fun Rail(tag: String, filled: Float, colour: Colour) {
    Box(Modifier.testTag(tag).size(MobaHudScreen.BAR_WIDTH, BAR_HEIGHT).background(TRACK)) {
        if (filled > 0f) {
            Box(Modifier.size(MobaHudScreen.BAR_WIDTH * filled, BAR_HEIGHT).background(colour))
        }
    }
}

/**
 * One slot: the key that fires it and, while it cools, a shutter over the fraction still to run and
 * the seconds left.
 *
 * The key goes on an empty box too, dimmed: the item bar starts empty, and a box with nothing in it
 * and no letter on it is a slot a player has no way to discover (issue #166).
 *
 * @param shutter the fraction of the cooldown still to run, `0` when ready.
 * @param tenths the time left in tenths of a second, or [NO_TIME] when ready.
 */
@Composable
private fun Slot(slot: Int, key: String, granted: Boolean, shutter: Float, tenths: Long) {
    val cooling = granted && tenths >= 0L
    val box = when {
        !granted -> SLOT_EMPTY
        cooling -> SLOT_COOLING
        else -> SLOT_READY
    }
    val tint = when {
        !granted -> EMPTY_TEXT
        cooling -> COOLING_TEXT
        else -> Colour.White
    }
    Box(Modifier.testTag(MobaHudTags.slot(slot)).size(MobaHudScreen.SLOT_SIZE).background(box)) {
        // A shutter shrinking downward: a box nearly clear is a box nearly ready, which is the
        // reading a player already has from every MOBA they have played. Under the text, so the
        // seconds stay readable on it.
        if (granted && shutter > 0f) {
            Box(
                Modifier.align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(MobaHudScreen.SLOT_SIZE * shutter)
                    .background(MobaHudScreen.SWEEP),
            )
        }
        Text(
            key,
            Modifier.align(Alignment.TopStart).padding(left = PADDING, top = PADDING / 2f, right = 0f, bottom = 0f),
            textStyle = SMALL,
            colour = tint,
        )
        if (cooling) {
            Text(
                secondsLabel(tenths),
                Modifier.align(Alignment.BottomStart).padding(left = PADDING, top = 0f, right = 0f, bottom = PADDING / 2f),
                textStyle = BODY,
                colour = tint,
            )
        }
    }
}

/**
 * The names beside the boxes, one row per slot, rather than inside them: `ORC_ELITE_SPIN` is wider
 * than a box, and a name drawn inside one runs across its neighbour.
 *
 * Laid out against [UnitBlueprint.ABILITY_SLOTS] rather than [HudState.slotCount], so the column does
 * not move when a unit has been granted fewer. Never skipped, for [PlayerPanel]'s reason.
 */
@Composable
@NonSkippableComposable
private fun SlotNames(state: HudState) {
    Column(Modifier.testTag(MobaHudTags.NAMES).padding(left = GAP * 2f, top = 0f, right = 0f, bottom = 0f)) {
        for (slot in 0 until UnitBlueprint.ABILITY_SLOTS) {
            Box(Modifier.height(MobaHudScreen.SLOT_SIZE / UnitBlueprint.ABILITY_SLOTS)) {
                val name = if (slot < state.slotCount) state.nameAt(slot) else ""
                if (name.isNotEmpty()) SlotName(name, ready = state.remainingAt(slot) == 0)
            }
        }
    }
}

/** `ability/orc_elite_spin` as `ORC_ELITE_SPIN`, worked out once per name rather than per frame. */
@Composable
private fun SlotName(name: String, ready: Boolean) {
    val short = remember(name) { name.substringAfterLast('/').uppercase() }
    Text(short, textStyle = SMALL, colour = if (ready) Colour.White else COOLING_TEXT)
}

/**
 * What a dead player is told: that they died, and when they are back.
 *
 * The second line is what tells a death from a freeze. It is left out rather than printed as `0.0s`
 * when no respawn is scheduled, which is a unit this game does not revive.
 */
@Composable
private fun DeathBanner(tenths: Long) {
    Box(
        Modifier.testTag(MobaHudTags.DEATH)
            .align(Alignment.Centre)
            .fillMaxWidth()
            .height(MobaHudLook.BANNER)
            .background(Colour.rgb(MobaHudLook.DEATH_RGB.toLong())),
        contentAlignment = Alignment.Centre,
    ) {
        Column(horizontalAlignment = HorizontalAlignment.Centre) {
            Text(DEAD_TITLE, textStyle = TITLE, colour = DEATH_TEXT)
            if (tenths > 0L) Text("back in " + secondsLabel(tenths), textStyle = BODY, colour = DEATH_TEXT)
        }
    }
}

/** The living count per side along the top: "who is winning" is the whole objective here. */
@Composable
private fun ScoreStrip(matchNumber: Int, orc: Int, soldier: Int, undead: Int) {
    Box(
        Modifier.testTag(MobaHudTags.SCORE)
            .align(Alignment.TopStart)
            // An offset and not top padding: padding here would be taken out of the strip's own
            // height and leave the text hanging below a band half as tall as the line.
            .offset(0f, MobaHudLook.MARGIN)
            .fillMaxWidth()
            .height(MobaHudLook.SCORE_STRIP)
            .background(PANEL),
        contentAlignment = Alignment.CentreStart,
    ) {
        Text(
            "MATCH $matchNumber    ORC $orc   SOLDIER $soldier   UNDEAD $undead",
            Modifier.padding(left = MobaHudLook.MARGIN, top = 0f, right = 0f, bottom = 0f),
            textStyle = BODY,
            colour = Colour.White,
        )
    }
}

/**
 * Who won, a banner's height above the middle, so a player who died on the last blow of a match sees
 * both banners - which is the truth about what happened.
 */
@Composable
private fun ResultBanner(winner: Int) {
    Box(
        Modifier.testTag(MobaHudTags.RESULT)
            .align(Alignment.Centre)
            .offset(0f, -MobaHudLook.BANNER)
            .fillMaxWidth()
            .height(MobaHudLook.BANNER)
            .background(Colour.rgb(MobaHudLook.RESULT_RGB.toLong())),
        contentAlignment = Alignment.Centre,
    ) {
        Text(resultTitle(winner), textStyle = TITLE, colour = RESULT_TEXT)
    }
}

/** A `Team` constant as the title the result banner prints. */
private fun resultTitle(winner: Int): String = when (winner) {
    Team.NONE -> "DRAW"
    Team.ORC -> "ORC WINS"
    Team.SOLDIER -> "SOLDIER WINS"
    Team.UNDEAD -> "UNDEAD WINS"
    else -> "NOBODY WINS"
}

/**
 * [ticks] at [tickRate] as rounded tenths of a second, in integer arithmetic:
 * `(ticks * 10 + rate / 2) / rate`. [NO_TIME] for a clock with no rate.
 */
private fun tenthsOf(ticks: Int, tickRate: Int): Long =
    if (tickRate <= 0) NO_TIME else (ticks.toLong() * 10 + tickRate / 2) / tickRate

/** Tenths of a second as a player reads them: `12.7s`. */
private fun secondsLabel(tenths: Long): String = "${tenths / 10}.${tenths % 10}s"

/**
 * The `Modifier.testTag` values the HUD hangs on the things a player reads.
 *
 * Shared with `MobaHudScreenTest` rather than typed twice, so a renamed tag is a compile error instead
 * of a test that silently stops finding the node it asserted about.
 */
internal object MobaHudTags {
    const val PANEL: String = "moba-hud-panel"
    const val VITALS: String = "moba-hud-vitals"
    const val HEALTH: String = "moba-hud-health"
    const val MANA: String = "moba-hud-mana"
    const val NAMES: String = "moba-hud-names"
    const val DEATH: String = "moba-hud-death"
    const val SCORE: String = "moba-hud-score"
    const val RESULT: String = "moba-hud-result"

    fun slot(index: Int): String = "moba-hud-slot-$index"
}

/** [tenthsOf] when there is no time to show. */
private const val NO_TIME: Long = -1L

private const val GAP: Float = 6f
private const val PADDING: Float = 6f

/** The gap between the rails and the slot row under them. */
private const val ROW_GAP: Float = 12f
private const val BAR_HEIGHT: Float = 16f

private const val DEAD_TITLE: String = "YOU DIED"

private val SMALL: TextStyle = TextStyle(size = 16f)
private val BODY: TextStyle = TextStyle(size = 20f)
private val TITLE: TextStyle = TextStyle(size = 44f)

/**
 * The whole pixel sizes the HUD's text is set in, for a launcher to rasterise.
 *
 * Derived from the styles rather than listed again beside them: a `UiFonts` rasterises only the sizes
 * it is given, and a size it was not given is an error at draw time naming the ones it was.
 */
public val HUD_FONT_SIZES: List<Int> = listOf(SMALL, BODY, TITLE).map { it.size.toInt() }

private val PANEL: Colour = Colour.rgb(MobaHudLook.PANEL_RGB.toLong())

/** The empty part of a rail: lighter than the panel it sits on, so an empty rail still reads as one. */
private val TRACK: Colour = Colour.rgb(0x2A2222)

private val SLOT_READY: Colour = Colour.rgb(0x293D57)
private val SLOT_COOLING: Colour = Colour.rgb(0x241C1C)
private val SLOT_EMPTY: Colour = Colour.rgb(0x1E1A1A)

private val COOLING_TEXT: Colour = Colour.rgb(0x9E9EA8)
private val EMPTY_TEXT: Colour = Colour.rgb(0x73737A)
private val DEATH_TEXT: Colour = Colour.rgb(0xFFDBDB)
private val RESULT_TEXT: Colour = Colour.rgb(0xFFEB8C)
