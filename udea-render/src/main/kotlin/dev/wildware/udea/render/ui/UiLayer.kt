package dev.wildware.udea.render.ui

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.InputMultiplexer
import com.badlogic.gdx.InputProcessor
import com.badlogic.gdx.utils.Disposable
import com.github.quillraven.fleks.World
import dev.wildware.composegl.gdx.GdxBackend
import dev.wildware.composegl.gdx.GdxFonts
import dev.wildware.composegl.gdx.GdxKeyboardInput
import dev.wildware.composegl.gdx.GdxPointerInput
import dev.wildware.composegl.ui.backend.UiBackend
import dev.wildware.composegl.ui.focus.FocusManager
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.host.UiHost
import dev.wildware.composegl.ui.host.UiRenderer
import dev.wildware.composegl.ui.input.KeyRouter
import dev.wildware.composegl.ui.input.PointerRouter
import dev.wildware.composegl.ui.layout.Viewport
import dev.wildware.composegl.ui.widget.ProvideClipboard
import dev.wildware.composegl.ui.widget.ProvideFonts
import dev.wildware.composegl.ui.widget.ProvideHaptics
import dev.wildware.composegl.ui.widget.ProvideSoftKeyboard
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.render.FrameTime
import dev.wildware.udea.render.OffscreenTarget
import dev.wildware.udea.render.RenderPhase
import dev.wildware.udea.render.RenderPipeline
import dev.wildware.udea.render.RenderResources
import dev.wildware.udea.render.RenderSystem

/**
 * The ComposeGL layer: one `UiHost`, one composition, and the screen currently mounted in it.
 *
 * Runs at [RenderPhase.UI] — **before** the capture point, on purpose. Game UI is part of the
 * game: an agent asking for a screenshot to check whether an ability is on cooldown needs the
 * cooldown ring in the picture. The thing an agent must not see is the *agent activity
 * overlay*, which is an `OverlaySystem` in [RenderPhase.Overlay] and is a different type
 * drawing on a different surface (spec 3.7).
 *
 * ## What it replaces, and what stayed the same
 *
 * It was a scene2d `Stage` and a `ScreenViewport` until issue #187. Four behaviours came across
 * unchanged, because they were the reasons the class existed rather than facts about scene2d:
 * one advance per frame, a delta clamped tighter than the pipeline's own, a single mounted screen
 * whose lifecycle the layer owns, and the interface getting first refusal on input. The scene2d
 * version is [dev.wildware.udea.render.ui.scene2d.Scene2dUiLayer], which exists only until #188
 * ports `MobaHud` off it.
 *
 * What is genuinely different is that a composition decides for itself whether anything changed.
 * `UiHost.frame` returns false for ever on a screen nobody has touched, so a menu sitting still
 * costs a function call rather than a layout pass — and the layer still draws every frame, because
 * the surface it draws into is shared with the world and last frame's pixels are gone.
 *
 * ## The clock is accumulated here, and that is not the drift `AGENTS.md` bans
 *
 * A `UiHost` wants the frame's *absolute* time in nanoseconds; a [FrameTime] hands out a
 * per-frame *delta* in seconds. So the layer keeps its own reading and adds each clamped delta to
 * it. `SimClock.time` is derived rather than accumulated precisely to stop drift accumulating in
 * simulated time — and this is not simulated time. It is the clock a fade and a spinner run on,
 * it never enters a snapshot, and a replay does not reproduce it. An accumulated presentation
 * clock that is a few microseconds behind the wall is exactly as correct as one that is not.
 *
 * ## The delta is clamped, and not to the same figure as everything else
 *
 * [MAX_UI_SECONDS] is a thirtieth of a second, tighter than the pipeline's own
 * [RenderPipeline.MAX_FRAME_SECONDS]. A single long frame — a breakpoint, a shader compile, a GC
 * pause — would otherwise hand a one-second tween forty seconds and run it straight to its end
 * state, which looks like the animation never played. A quarter-second step through a 200ms fade
 * is still the whole fade, which is why the pipeline's figure is not tight enough here.
 */
public class UiLayer internal constructor(
    /**
     * The pipeline's batch and disposal list.
     *
     * The layer puts itself in that list in `init`, so the composition and whatever the backend
     * holds are released by the pipeline's reverse-order disposal and no caller has to remember.
     * The batch is what the shipped `GdxBackend` hands to `UiCanvas.raw`, so a screen that wants
     * to draw a sprite of its own draws it into the frame's one batch rather than a second.
     */
    resources: RenderResources,
    /** Wall seconds per frame. A composition's animations are wall-timed and never simulated. */
    private val frameTime: FrameTime,
    /**
     * Where the toolkit gets its canvas, its fonts, its clipboard and its cursor.
     *
     * A parameter rather than something built here, for the reason ComposeGL's own `GdxBackend`
     * gives for taking its fonts the same way: registering a typeface needs a `.ttf` this engine
     * knows nothing about. The public constructor below builds the shipped one from a font file;
     * a test hands in ComposeGL's `HeadlessBackend` and drives every behaviour in this class with
     * no driver at all.
     *
     * Disposed with the pipeline when it is `Disposable`, which the gdx one is: it owns the
     * canvas and the glyph atlas, and both are GL resources. The frame's batch is *not* the
     * backend's and is not disposed with it.
     */
    private val backend: UiBackend,
    /**
     * Framebuffer pixels per logical window unit, which is what turns a pointer position into a
     * position in the tree.
     *
     * Defaulted to what LibGDX reports, which is 1 on an ordinary display and 2 on a Retina one.
     * Injected because it is also the one number that is wrong when the surface being drawn into
     * is not the window: a `WindowConfig` with a `renderWidth` smaller than its `windowWidth`
     * scales the picture on the way out, and a caller that does that has to say so here. Nothing
     * in this engine can work it out, because a [RenderSystem] is deliberately never handed the
     * window.
     */
    private val hdpiScale: () -> Float = { Gdx.graphics?.backBufferScale ?: 1f },
) : RenderSystem, Disposable {

    /**
     * The shipped layer: a ComposeGL interface drawn through LibGDX, with [font] as its typeface.
     *
     * This is the constructor a game uses, and the reason the primary one is `internal`: a
     * `UiBackend` is ComposeGL's own type and which backend an Udea game draws its interface
     * through is not a decision a game gets to make -- there is one renderer module and it has
     * one GL binding. What a game does have to supply is the typeface, because a toolkit cannot
     * invent one and nothing in this engine knows which `.ttf` a particular game ships.
     *
     * ```
     * UiLayer(resources, frameTime, Gdx.files.internal("fonts/Inter.ttf"))
     * ```
     *
     * On the render thread, and after the context exists: registering a typeface rasterises its
     * Latin glyphs through FreeType, which needs the `gdx-freetype` natives this module brings.
     *
     * @param font a `.ttf` or `.otf`. Registered under the family name ComposeGL's default skin
     *   asks for, so an unstyled `Text` finds it.
     * @param fontSizes the whole sizes to rasterise. A size that was never registered is an error
     *   naming the ones that were, rather than text that silently does not draw -- so this is the
     *   list to extend when a screen wants a bigger heading. Defaulted to the three the default
     *   skin uses.
     */
    public constructor(
        resources: RenderResources,
        frameTime: FrameTime,
        font: FileHandle,
        fontSizes: List<Int> = DEFAULT_FONT_SIZES,
    ) : this(resources, frameTime, gdxBackend(resources, font, fontSizes))

    /** The composition. Internal: a `UiHost` is ComposeGL's, and only this module drives one. */
    internal val host: UiHost = UiHost()

    /**
     * Where focus is, refreshed by [renderer] once a frame after the layout.
     *
     * After, not before: taking focus tells every ancestor to reveal the newly focused node, and
     * the rectangle a scrolling list is handed there is whatever the layout wrote.
     */
    internal val focus: FocusManager = FocusManager(host.root)

    private val renderer: UiRenderer = UiRenderer(host, backend.canvas).also { it.focus = focus }

    /**
     * The viewport the tree is laid out, drawn and pointed at in.
     *
     * One design unit per pixel of the surface being drawn into — the same relationship the
     * scene2d `ScreenViewport` had — so a screen lays out in the pixels of the frame a capture
     * reads rather than in the pixels of the window. Rebuilt only when that surface changes size,
     * because a `Viewport` computes its scale and origin in its constructor and a fresh one per
     * frame is an allocation on the drawing path.
     */
    internal var viewport: Viewport = Viewport.oneToOne(Size(1f, 1f))
        private set

    /**
     * The interface's input, as one `InputProcessor`, for the front of the window's chain.
     *
     * ```
     * GdxKeyboard.install(uiLayer.input, keyboard)
     * ```
     *
     * Two processors behind one, because ComposeGL translates the pointer and the keyboard
     * separately and a game should not have to know that. Each returns whether the toolkit used
     * the event, so a `GdxKeyboard` behind this one never sees a click that landed on a button or
     * a key a text field took — which is the contract
     * [dev.wildware.udea.render.input.GdxKeyboard.install] states and `UiInputOrderTest` holds it
     * to, in both directions.
     *
     * The routers behind it are ComposeGL's own: [PointerRouter] works out what the pointer is
     * over and what counts as a click, and [KeyRouter] sends a key to the focused node and then
     * outwards through its parents.
     *
     * **There is deliberately no navigator here.** A `KeyNavigator` would make the arrow keys move
     * focus and Escape mean "back" for as long as a layer is mounted, which is a screen's policy
     * and not an engine default: a game whose HUD is always up would find that its player could no
     * longer walk with the arrow keys. What the interface consumes is exactly what some widget in
     * it asked for. A pad is the same question and is not wired either; `GdxGamepadInput` is what
     * would wire it, against the same routers.
     */
    public val input: InputProcessor = InputMultiplexer(
        GdxPointerInput(PointerRouter(host.root, focus, backend.cursor), { viewport }, hdpiScale = hdpiScale),
        GdxKeyboardInput(KeyRouter(focus, host.root)),
    )

    /** Frames the composition has been advanced by. A health signal, and what `UiLayerTest` counts. */
    internal var frameCount: Long = 0L
        private set

    /**
     * The toolkit's own clock, in nanoseconds, advanced by each frame's clamped delta.
     *
     * Exposed so a test can assert the clamp as a number as well as through an animation. The
     * epoch is the layer's construction and only differences mean anything.
     */
    internal var clockNanos: Long = 0L
        private set

    private var mounted: UiScreen? = null

    init {
        // The pipeline disposes what it owns, in reverse construction order. Registered here
        // rather than left to the caller because a composition nobody disposes is a Recomposer
        // whose coroutine outlives the window, and a `GdxBackend` nobody disposes is a glyph
        // atlas and a set of GL programs leaked per pipeline.
        resources.own(this)
        (backend as? Disposable)?.let(resources::own)
    }

    override fun onBind(world: World, ctx: GameContext): Unit = Unit

    override fun render(target: OffscreenTarget, alpha: Float) {
        fitTo(target)
        clockNanos += clampedFrameNanos()
        frameCount++
        // One call: settle the composition, lay it out for the viewport, refresh focus, then open
        // the canvas's frame, draw and close it. The canvas draws into whatever framebuffer is
        // bound, which inside a `RenderPipeline` frame is the offscreen target every capture is
        // read from, and it clears nothing -- the world is already there.
        renderer.render(viewport, clockNanos)
    }

    /**
     * Replaces the mounted screen, disposing the one that was there.
     *
     * One screen at a time, and swapping is a single call: two half-mounted screens is how the
     * old tree ended up with a loading screen's actors still receiving clicks behind a menu.
     *
     * The backend's fonts, clipboard, soft keyboard and haptics are provided around the screen's
     * content, so a `Text` measures against the game's typeface and a `TextField` can raise a
     * keyboard without the screen being handed any of them.
     */
    public fun show(screen: UiScreen) {
        hide()
        mounted = screen
        host.setContent {
            ProvideFonts(backend.fonts) {
                ProvideClipboard(backend.clipboard) {
                    ProvideSoftKeyboard(backend.softKeyboard) {
                        ProvideHaptics(backend.haptics) {
                            screen.content()
                        }
                    }
                }
            }
        }
    }

    /**
     * Unmounts the mounted screen, if any.
     *
     * An empty composition rather than a disposed host: a layer is mounted for the life of the
     * pipeline and `show` has to work again afterwards, and a `Composition` that has been disposed
     * cannot be set again.
     */
    public fun hide() {
        val current = mounted ?: return
        mounted = null
        host.setContent {}
        current.dispose()
    }

    /**
     * Disposes the composition and whatever screen is mounted.
     *
     * Registered through [RenderResources.own] in this class's `init`, so the pipeline's
     * reverse-order disposal covers it and no caller has to remember.
     *
     * Idempotent, because a double shutdown path is likelier than a real defect: `UiHost.dispose`
     * returns early when it has already run, and [hide] on nothing is a no-op.
     */
    override fun dispose() {
        hide()
        host.dispose()
    }

    /**
     * This frame's delta in nanoseconds, clamped to [MAX_UI_SECONDS].
     *
     * The pipeline has already clamped [FrameTime.frameSeconds] to its own, looser figure; this
     * is the second, tighter one. See the class KDoc for why there are two.
     */
    private fun clampedFrameNanos(): Long =
        (frameTime.frameSeconds.coerceAtMost(MAX_UI_SECONDS) * NANOS_PER_SECOND).toLong()

    /**
     * Sizes the UI viewport to the surface being drawn into.
     *
     * `oneToOne`, which is `ScalePolicy.Stretch` over a design size equal to the physical one: no
     * scale, no letterbox, pixel `(0, 0)` at the top-left corner of the drawable area. A game that
     * wants a fixed design resolution instead — layouts written once for 1280x720 and letterboxed
     * on to whatever the player has — is asking for a different `Viewport` here, and that is a
     * decision a game makes rather than a default an engine should pick.
     */
    private fun fitTo(target: OffscreenTarget) {
        val width = target.width.toFloat()
        val height = target.height.toFloat()
        if (viewport.physical.width == width && viewport.physical.height == height) return
        viewport = Viewport.oneToOne(Size(width, height))
    }

    internal companion object {

        /**
         * Longest delta the composition is advanced by in one frame, in seconds.
         *
         * Tighter than the pipeline's frame clamp because interface animations are short: a third
         * of a second through a 200ms fade is the whole fade, and anything longer is a stall being
         * mistaken for elapsed time.
         */
        const val MAX_UI_SECONDS: Float = 1f / 30f

        private const val NANOS_PER_SECOND: Float = 1_000_000_000f

        /**
         * The sizes ComposeGL's default skin asks for: body text, a heading, and the small
         * print. The same three its own documented first screen registers.
         */
        val DEFAULT_FONT_SIZES: List<Int> = listOf(13, 16, 20)

        /** The family name the default skin resolves an unstyled `Text` to. */
        private const val DEFAULT_FONT_FAMILY: String = "default"

        /**
         * The gdx backend, with [font] registered and the frame's batch behind `UiCanvas.raw`.
         *
         * Not registered for disposal here: the layer's `init` does that for whatever backend it
         * is given, so there is one place that decides who releases the glyph atlas rather than
         * two that might both think they do.
         */
        private fun gdxBackend(
            resources: RenderResources,
            font: FileHandle,
            fontSizes: List<Int>,
        ): UiBackend {
            val fonts = GdxFonts()
            fonts.registerTrueType(DEFAULT_FONT_FAMILY, font, fontSizes)
            return GdxBackend(fonts, resources.batch)
        }
    }
}
