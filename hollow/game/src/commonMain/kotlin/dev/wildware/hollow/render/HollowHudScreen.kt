package dev.wildware.hollow.render

import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonSkippableComposable
import androidx.compose.runtime.mutableLongStateOf
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.layout.Alignment
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
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
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.text.TextStyle
import dev.wildware.composegl.ui.widget.Text
import dev.wildware.hollow.CombatRules
import dev.wildware.hollow.HollowMovement
import dev.wildware.udea.render.ui.UiScreen

/**
 * Hollow's HUD as a ComposeGL [UiScreen] (issue #252): health bottom-left with the three ability
 * slots under it, each with its key and, while it cools, the seconds left; the wave and the score
 * along the top; and a banner when the player is dead.
 *
 * Plain composables over [HollowHudState], for the reasons `moba`'s `MobaHudScreen` gives: a
 * ComposeGL `Hotbar` would count a cooldown down on the interface's own clock, and this one is read
 * off the simulation every frame. [refresh] is what tells the composition the state moved, since the
 * state is refilled in place and is not Compose state.
 *
 * The panels are opaque, so the text is readable over whatever is behind it, and so a harness can
 * tell the HUD reached a capture by reading one of their pixels ([HollowHudLook]).
 */
internal class HollowHudScreen(
    /** The model's state. Held, never copied: the model refills this instance. */
    private val state: HollowHudState,
    /** What to print on each slot: the key bound to it. */
    private val keyLabels: Array<String>,
) : UiScreen {

    /** Bumped by [refresh] and read by [content]. That it moved is what matters. */
    private val frame = mutableLongStateOf(0L)

    /** Tells the composition [state] may have changed. Once a frame, after the model has sampled. */
    fun refresh() {
        frame.longValue++
    }

    @Composable
    override fun content() {
        frame.longValue
        Box(Modifier.fillMaxSize()) {
            TopStrip(state.wave, state.score)
            if (state.present) {
                PlayerPanel(state, keyLabels)
                if (state.dead) DeathBanner()
            }
        }
    }

}


/**
 * Where the HUD's solid panels are and what colour they are, for a shot to check a capture by.
 *
 * Public because the reader is `:hollow:desktop`'s fight shot, which asks whether the HUD reached the
 * picture by reading a pixel of each panel: a HUD that drew into the window instead of the capture
 * fails it. The values are the ones [HollowHudScreen] lays out with, so they are typed once.
 */
public object HollowHudLook {

    /** From the capture's edges to the panels. */
    public const val MARGIN: Float = 18f

    /** Inside a panel, around what it holds. */
    public const val PADDING: Float = 10f

    /** The top strip's height: one line of body text with room either side. */
    public const val STRIP: Float = 34f

    /** The player panel and the top strip, as `0xRRGGBB`. */
    public const val PANEL_RGB: Int = 0x10141A

    /** The death banner. */
    public const val DEATH_RGB: Int = 0x4D0505
}

@Composable
private fun TopStrip(wave: Long, score: Int) {
    Box(
        Modifier.align(Alignment.TopStart)
            .offset(HollowHudLook.MARGIN, HollowHudLook.MARGIN)
            .height(HollowHudLook.STRIP)
            .background(PANEL)
            .padding(left = HollowHudLook.PADDING, top = 0f, right = HollowHudLook.PADDING, bottom = 0f),
        contentAlignment = Alignment.CentreStart,
    ) {
        Text("WAVE $wave      SCORE $score", textStyle = BODY, colour = Colour.White)
    }
}

/**
 * Health, its rail, and the slots. Never skipped: it is handed the same [state] instance every
 * frame with new numbers in it, which Compose would otherwise take as unchanged.
 */
@Composable
@NonSkippableComposable
private fun PlayerPanel(state: HollowHudState, keyLabels: Array<String>) {
    Column(
        Modifier.align(Alignment.BottomStart)
            .offset(HollowHudLook.MARGIN, -HollowHudLook.MARGIN)
            .background(PANEL)
            .padding(HollowHudLook.PADDING),
    ) {
        val health = kotlin.math.ceil(state.health).toInt()
        val max = kotlin.math.ceil(state.maxHealth).toInt()
        Text("HEALTH  $health / $max", textStyle = BODY, colour = Colour.White)
        Spacer(Modifier.height(GAP))
        Rail(if (state.maxHealth > 0f) (state.health / state.maxHealth).coerceIn(0f, 1f) else 0f)
        Spacer(Modifier.height(ROW_GAP))
        Row {
            for (slot in 0 until CombatRules.PLAYER_SLOTS) {
                if (slot > 0) Spacer(Modifier.width(GAP))
                Slot(
                    key = keyLabels[slot],
                    name = NAMES[slot],
                    total = TOTALS[slot],
                    remaining = state.remainingAt(slot),
                    blocked = state.dead,
                )
            }
        }
    }
}

/** A backing [BAR_WIDTH] long, [filled] of it red. */
@Composable
private fun Rail(filled: Float) {
    Box(Modifier.size(BAR_WIDTH, BAR_HEIGHT).background(TRACK)) {
        if (filled > 0f) Box(Modifier.size(BAR_WIDTH * filled, BAR_HEIGHT).background(HEALTH_FILL))
    }
}

/**
 * One slot: its key and name, and while it cools a shutter over the part still to run and the
 * seconds left, rounded up - a slot never says `0.0s` while it is still cooling.
 */
@Composable
private fun Slot(key: String, name: String, total: Int, remaining: Int, blocked: Boolean) {
    val cooling = remaining > 0
    Box(Modifier.size(SLOT_WIDTH, SLOT_HEIGHT).background(if (cooling || blocked) SLOT_COOLING else SLOT_READY)) {
        if (cooling && total > 0) {
            Box(
                Modifier.align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(SLOT_HEIGHT * (remaining.toFloat() / total).coerceIn(0f, 1f))
                    .background(SWEEP),
            )
        }
        val tint = if (cooling || blocked) COOLING_TEXT else Colour.White
        Text(
            "$key  $name",
            Modifier.align(Alignment.TopStart).padding(left = GAP, top = GAP / 2f, right = 0f, bottom = 0f),
            textStyle = SMALL,
            colour = tint,
        )
        Text(
            if (cooling) secondsLeft(remaining) else "READY",
            Modifier.align(Alignment.BottomStart).padding(left = GAP, top = 0f, right = 0f, bottom = GAP / 2f),
            textStyle = BODY,
            colour = tint,
        )
    }
}

@Composable
private fun DeathBanner() {
    Box(
        Modifier.align(Alignment.Centre)
            .fillMaxWidth()
            .height(BANNER)
            .background(Colour.rgb(HollowHudLook.DEATH_RGB.toLong())),
        contentAlignment = Alignment.Centre,
    ) {
        Text("YOU DIED", textStyle = TITLE, colour = DEATH_TEXT)
    }
}

/**
 * [ticks] as a player reads them, `1.3s`: tenths of a second rounded **up**, in integer arithmetic,
 * so a slot with a tick left says `0.1s` rather than `0.0s`.
 */
private fun secondsLeft(ticks: Int): String {
    val rate = HollowMovement.TICK_RATE
    val tenths = (ticks.toLong() * 10 + rate - 1) / rate
    return "${tenths / 10}.${tenths % 10}s"
}

/** What each slot is called on screen. */
private val NAMES: Array<String> = Array(CombatRules.PLAYER_SLOTS) { slot ->
    when (slot) {
        CombatRules.ATTACK_SLOT -> "ATTACK"
        CombatRules.DASH_SLOT -> "DASH"
        else -> "HEAL"
    }
}

/** Each slot's whole cooldown, for the shutter's fraction. */
private val TOTALS: IntArray = IntArray(CombatRules.PLAYER_SLOTS) { slot ->
    when (slot) {
        CombatRules.ATTACK_SLOT -> CombatRules.ATTACK_COOLDOWN.count.toInt()
        CombatRules.DASH_SLOT -> CombatRules.DASH_COOLDOWN.count.toInt()
        else -> CombatRules.HEAL_COOLDOWN.count.toInt()
    }
}

private const val GAP: Float = 6f
private const val ROW_GAP: Float = 12f
private const val BAR_WIDTH: Float = 330f
private const val BAR_HEIGHT: Float = 16f
private const val SLOT_WIDTH: Float = 106f
private const val SLOT_HEIGHT: Float = 62f
private const val BANNER: Float = 88f

private val SMALL: TextStyle = TextStyle(size = 16f)
private val BODY: TextStyle = TextStyle(size = 20f)
private val TITLE: TextStyle = TextStyle(size = 44f)

private val PANEL: Colour = Colour.rgb(HollowHudLook.PANEL_RGB.toLong())
private val TRACK: Colour = Colour.rgb(0x2A2E36)
private val HEALTH_FILL: Colour = Colour.rgb(0xD93833)
private val SLOT_READY: Colour = Colour.rgb(0x2D5A3A)
private val SLOT_COOLING: Colour = Colour.rgb(0x2A2E36)
private val SWEEP: Colour = Colour.rgb(0x08080C)
private val COOLING_TEXT: Colour = Colour.rgb(0x9098A4)
private val DEATH_TEXT: Colour = Colour.rgb(0xFFD6D6)

/**
 * The whole pixel sizes the HUD sets text in, for a launcher to rasterise.
 *
 * Derived from the styles rather than listed again beside them: a `UiFonts` rasterises only the
 * sizes it is given, and a size the HUD sets text in but nobody registered draws nothing at all.
 * Public and top-level, as `moba`'s `HUD_FONT_SIZES` is, because the reader is a launcher and
 * [HollowHudScreen] itself is not part of any game's API.
 */
public val HOLLOW_HUD_FONT_SIZES: List<Int> = listOf(SMALL, BODY, TITLE).map { it.size.toInt() }
