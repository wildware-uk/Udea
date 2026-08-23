package dev.wildware.udea.agent.host.overlay

import dev.wildware.udea.agent.AgentBridge
import dev.wildware.udea.agent.activity.AgentSessions
import dev.wildware.udea.core.host.RenderMode

/**
 * The agent activity overlay: a corner panel and world-space markers, drawn for the human and
 * never for the agent (spec 3.7).
 *
 * ## What this class is, and what it deliberately is not
 *
 * It is the layout, the ordering, the verbosity gate and the per-frame budget. It is **not** an
 * `OverlaySystem`: that interface lives in `udea-render`, which this module may not depend on -
 * `udea-agent-host` is in `ModuleGraphRules.HEADLESS_PROJECTS`, and `udeaVerifyHeadless` fails
 * the build if a class compiled here names a GL type.
 *
 * The consequence, stated plainly rather than left to be discovered: **the GL adapter does not
 * exist in `src/main` anywhere in this tree.** Something in `udea-render` must implement
 * [OverlayCanvas] and [WorldProjector] over a `Batch`, a `BitmapFont` and the camera, and wrap
 * this class in an `OverlaySystem` so that it is constructed from an `OverlayResources` and
 * therefore structurally cannot reach a capturable target. That is the same position
 * [dev.wildware.udea.agent.host.RenderControl] is in. What *is* proven here is that this code
 * cannot leak into a capture and that the port is sufficient for a real GL implementation:
 * `OverlayCaptureIsolationTest` boots a real LWJGL3 context, drives a real GL adapter over these
 * two ports, and asserts both halves of spec 3.7's guarantee.
 *
 * ## Windowed only
 *
 * [isEnabled] is false outside [RenderMode.Windowed] and no amount of wiring changes that.
 * `Headless` has no GL at all and `Offscreen` exists solely to produce captures - so in the two
 * modes an agent normally drives, the question of whether the overlay could reach a capture does
 * not arise. That is spec 3.7's first rule, and it is a constructor-time decision here rather
 * than a per-frame `if` somebody can invert.
 */
public class AgentOverlayView(
    bridge: AgentBridge,
    /** Labels for the session colours. The same table `AgentHost` interns into. */
    sessions: AgentSessions,
    /** What mode this process renders in. The overlay exists only in [RenderMode.Windowed]. */
    private val mode: RenderMode,
    keys: HardwareKeyState = HardwareKeyState.NEVER,
    /** Where the panel sits. */
    private val corner: OverlayCorner = OverlayCorner.TOP_LEFT,
    initialVerbosity: OverlayVerbosity = OverlayVerbosity.DEFAULT,
) {

    /** The pre-formatted panel. Public so a host can assert what a human would be reading. */
    public val model: AgentOverlayModel =
        AgentOverlayModel(bridge.activity, bridge.narration, sessions)

    /** The world-space markers. */
    public val markers: AgentMarkers = AgentMarkers(bridge.activity)

    /** The hotkey. Reads real hardware, upstream of anything the agent can inject (issue #161). */
    public val verbosityControl: OverlayVerbosityControl =
        OverlayVerbosityControl(keys, initialVerbosity)

    /** Whether this process draws the overlay at all. */
    public val isEnabled: Boolean = mode == RenderMode.Windowed

    /** Frames drawn. A health signal for the isolation test, not state. */
    public var frames: Long = 0L
        private set

    /**
     * Draws one frame of overlay.
     *
     * @param dtSeconds wall seconds since the previous frame. **Not** `alpha` and not a `Tick`:
     *   an overlay is not allowed to read simulation time, and every fade here is wall-timed so
     *   that it still runs on a paused game.
     * @param projector world to screen, for the markers.
     * @param locator where an anchored entity is now. [EntityLocator.NONE] draws no entity
     *   rings, which is the honest answer for a host with no world index wired.
     */
    public fun render(
        canvas: OverlayCanvas,
        dtSeconds: Float,
        projector: WorldProjector = OFFSCREEN,
        locator: EntityLocator = EntityLocator.NONE,
    ) {
        if (!isEnabled) return
        frames++

        val verbosity = verbosityControl.poll()
        // Ages advance even at OFF, so that turning the overlay back on does not reveal a set of
        // markers frozen at the age they had when it was turned off.
        markers.update(dtSeconds)
        if (verbosity == OverlayVerbosity.OFF) return

        model.refreshIfStale(verbosity)
        drawPanel(canvas)
        if (verbosity.showsMarkers) markers.draw(canvas, projector, locator)
    }

    /**
     * The panel: one background rectangle and one text draw per row.
     *
     * Allocation-free. The rows are already formatted (see [AgentOverlayModel]); this is
     * arithmetic and draw calls.
     */
    private fun drawPanel(canvas: OverlayCanvas) {
        val rows = model.rowCount
        if (rows == 0) return

        var widest = 0f
        for (index in 0 until rows) {
            val measured = canvas.measure(model.rowText(index))
            if (measured > widest) widest = measured
        }
        val panelWidth = widest + PADDING * 2f
        val panelHeight = canvas.lineHeight * rows + PADDING * 2f
        val left = when (corner) {
            OverlayCorner.TOP_LEFT, OverlayCorner.BOTTOM_LEFT -> MARGIN
            OverlayCorner.TOP_RIGHT, OverlayCorner.BOTTOM_RIGHT -> canvas.width - MARGIN - panelWidth
        }
        val bottom = when (corner) {
            OverlayCorner.TOP_LEFT, OverlayCorner.TOP_RIGHT -> canvas.height - MARGIN - panelHeight
            OverlayCorner.BOTTOM_LEFT, OverlayCorner.BOTTOM_RIGHT -> MARGIN
        }

        canvas.fill(left, bottom, panelWidth, panelHeight, OverlayPalette.PANEL)

        // Rows read downwards from the top of the panel, so the newest call is the top line: it
        // is the one a human glances at, and a panel that grew downwards would move it every
        // time a call was recorded.
        var baseline = bottom + panelHeight - PADDING - canvas.lineHeight * ASCENT_FRACTION
        for (index in 0 until rows) {
            canvas.text(left + PADDING, baseline, model.rowText(index), model.rowColour(index))
            baseline -= canvas.lineHeight
        }
    }

    override fun toString(): String =
        "AgentOverlayView($mode, ${verbosityControl.verbosity}, $frames frame(s))"

    public companion object {

        /** Pixels between the panel and the window edge. */
        public const val MARGIN: Float = 12f

        /** Pixels between the panel's edge and its text. */
        public const val PADDING: Float = 8f

        /**
         * Where a row's baseline sits within its line box, as a fraction of the line height.
         *
         * Text is drawn from its baseline, and a row box is a line height tall; without this the
         * first row's ascenders would be clipped by the panel's top edge.
         */
        public const val ASCENT_FRACTION: Float = 0.78f

        /**
         * A projector that puts nothing on screen.
         *
         * The default, so a host with no camera wired draws a panel and no markers rather than
         * markers at the origin. Named rather than a lambda at the call site so that "no camera"
         * is a value a test can assert against.
         */
        public val OFFSCREEN: WorldProjector = WorldProjector { _, _, _ -> false }
    }
}

/** Which corner of the window the panel sits in. */
public enum class OverlayCorner {
    /** The default: the corner least likely to hold a MOBA's minimap or ability bar. */
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
}
