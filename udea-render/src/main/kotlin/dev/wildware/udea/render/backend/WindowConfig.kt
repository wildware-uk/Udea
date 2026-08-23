package dev.wildware.udea.render.backend

/**
 * The window and framebuffer sizes a GL backend is asked for.
 *
 * ## Why the render size is not the window size
 *
 * [renderWidth]/[renderHeight] size the offscreen framebuffer the game is drawn into;
 * [windowWidth]/[windowHeight] size the window it is blitted to. Keeping them apart is what
 * makes the acceptance criterion "an `Offscreen` and a `Windowed` capture of the same seeded
 * scene at the same tick are byte-identical" achievable at all: a capture reads the framebuffer,
 * whose size is stated here and does not move when a human drags a window edge. If captures
 * were backbuffer-sized, every screenshot an agent diffed would carry the window manager's
 * opinion in it.
 */
public class WindowConfig(
    /** Shown in the title bar, and in the task switcher of a [Windowed] host. */
    public val title: String = DEFAULT_TITLE,
    /** Width of the window, in logical pixels. */
    public val windowWidth: Int = DEFAULT_WIDTH,
    /** Height of the window, in logical pixels. */
    public val windowHeight: Int = DEFAULT_HEIGHT,
    /** Width of the offscreen framebuffer, and so of every full-frame capture. */
    public val renderWidth: Int = DEFAULT_WIDTH,
    /** Height of the offscreen framebuffer, and so of every full-frame capture. */
    public val renderHeight: Int = DEFAULT_HEIGHT,
    /**
     * Frames per second the backend paces itself to.
     *
     * Applied to both the foreground and idle rates, because an `Offscreen` host's window is
     * never focused: leaving the idle rate at LibGDX's default would run the agent's host at
     * a rate nobody chose, and leaving it at zero would spin a core flat out drawing frames
     * no one will ever look at.
     */
    public val framesPerSecond: Int = DEFAULT_FPS,
    /**
     * Wait for the display's vertical blank.
     *
     * Off by default: it is meaningless for a hidden window and it makes an agent's capture
     * latency a property of the monitor. A [Windowed] host that wants a smooth picture turns
     * it on.
     */
    public val vsync: Boolean = false,
) {

    init {
        require(windowWidth > 0 && windowHeight > 0) {
            "window must have positive extent, was ${windowWidth}x$windowHeight"
        }
        require(renderWidth > 0 && renderHeight > 0) {
            "render target must have positive extent, was ${renderWidth}x$renderHeight"
        }
        require(framesPerSecond > 0) {
            "framesPerSecond must be positive, was $framesPerSecond; a backend that never " +
                "draws never serves a capture either"
        }
        require(title.isNotBlank()) { "window title must not be blank" }
    }

    override fun toString(): String = "WindowConfig('$title', window=${windowWidth}x$windowHeight, " +
        "render=${renderWidth}x$renderHeight, fps=$framesPerSecond, vsync=$vsync)"

    public companion object {
        private const val DEFAULT_TITLE: String = "Udea"
        private const val DEFAULT_WIDTH: Int = 1280
        private const val DEFAULT_HEIGHT: Int = 720

        /** The simulation's tick rate. Drawing faster than the sim ticks buys an agent nothing. */
        private const val DEFAULT_FPS: Int = 60
    }
}
