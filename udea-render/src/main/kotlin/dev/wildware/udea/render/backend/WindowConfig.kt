package dev.wildware.udea.render.backend

/**
 * The window and framebuffer sizes a GL backend is asked for.
 *
 * ## Why the render size is not the window size
 *
 * [renderWidth]/[renderHeight] size the offscreen framebuffer the game is drawn into;
 * [windowWidth]/[windowHeight] size the window it is blitted to. A capture reads the
 * framebuffer, whose size is stated here and does not move when a human drags a window edge,
 * so **a capture's dimensions and framing are a property of this config and of nothing else**
 * — identical between `Offscreen` and `Windowed`, and unchanged by a resize. If captures were
 * backbuffer-sized, every screenshot an agent diffed would carry the window manager's opinion
 * in it. `GlCaptureTest` pins the size; `RenderPipeline.resize` deliberately leaves the
 * offscreen target alone.
 *
 * ## What this does *not* buy, and must not be read as buying
 *
 * It is a necessary condition for the epic's acceptance criterion "an `Offscreen` and a
 * `Windowed` capture of the same seeded scene at the same tick are byte-identical". It is not
 * a sufficient one, and as of this wave **that criterion does not hold**. Four wall-clock
 * inputs decide what a frame looks like, none of them a function of the tick:
 *
 * - the frame that serves a request is drawn at `GameLoop.alpha`, which is whatever residue
 *   the accumulator held when the frame came round;
 * - `AnimationRenderSystem` advances a playhead by wall seconds, and the playhead is the sum of
 *   every frame drawn so far;
 * - `ParticleRenderSystem` advances emitters by wall seconds, likewise cumulative;
 * - `CameraRig` smooths toward its target in wall seconds and carries the result between
 *   frames.
 *
 * Two runs of `host.run(200)` then `capture(afterTick = Tick(200))` therefore differ in sprite
 * positions, playheads, particles and camera. Serving a due request at `alpha = 0f` with
 * `frameSeconds = 0f` would fix the first input and only the first: the other three are
 * accumulated across every frame *before* the capture, so making them reproducible means
 * denominating presentation state in ticks, which is a larger change than a capture mode and
 * is not attempted here. This paragraph exists instead of a claim, because a documented
 * property nothing delivers is worse than a gap somebody can see.
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
