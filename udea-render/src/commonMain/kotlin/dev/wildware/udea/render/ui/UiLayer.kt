package dev.wildware.udea.render.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import de.fabmax.kool.KoolContext
import dev.wildware.composegl.kool.ComposeGlScene
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.input.KeyEvent
import dev.wildware.composegl.ui.input.KeyEventType
import dev.wildware.composegl.ui.input.Modifiers
import dev.wildware.composegl.ui.input.TextEvent
import dev.wildware.composegl.ui.layout.ScalePolicy
import dev.wildware.udea.render.input.InputFrame
import dev.wildware.udea.render.input.KeyPhase
import dev.wildware.udea.render.input.KeyStroke
import dev.wildware.udea.render.input.PointerId
import dev.wildware.udea.render.input.PointerReportListener
import dev.wildware.udea.render.input.UiInput
import dev.wildware.udea.render.input.UiPointers
import dev.wildware.composegl.kool.KoolBackend as ComposeGlBackend

/**
 * The game's interface: one ComposeGL screen at a time, drawn over everything Kool drew.
 *
 * The `composegl-kool` host this module owns (spec section 4, issue #224). A game hands it a
 * [UiScreen] and never sees `composegl-kool`, Kool or a GL call - which is what `UDEA-MG-002`
 * requires and `RenderModuleGraphTest` checks.
 *
 * ```kotlin
 * val fonts = DesktopFonts()
 * val ui = UiLayer(fonts, Size(1280f, 720f))
 * backend.show(ui)          // the backend owns it from here, and closes it
 * ui.show(PauseMenu())      // and `ui.show(null)` takes it away again
 * ```
 *
 * ## Where it draws, and why a capture never sees it
 *
 * A `ComposeGlScene` is a Kool `Scene` that clears nothing, so it draws on top of the scenes added
 * before it. [attach] adds it after `KoolSurface`'s `udea-screen`, which means it lands in the
 * **window's** framebuffer, after the presented frame - and never inside the `OffscreenPass2d` that
 * every `RenderSystem` draws into and every capture reads. So "the menu is not in the agent's
 * screenshot" is a fact about two render targets rather than an ordering rule somebody maintains,
 * exactly as the agent overlay's exclusion is. `GlUiLayerTest` pins it in pixels.
 *
 * ## The render thread
 *
 * The toolkit is touched on one thread only. Everything that composes, lays out or draws happens
 * inside Kool's frame, on Kool's render thread; [attach] and [detachAndClose] are `internal` and run
 * there because `KoolBackend` submits them there. [show] is the exception and is deliberately not
 * one: it writes Compose snapshot state, which is safe from any thread, and the composition that
 * reads it still runs on the render thread.
 *
 * @param fonts where glyphs are measured and rasterised, and where the toolkit samples solid colour
 *   from - so a screen of nothing but coloured boxes needs one too. [DesktopFonts] is the desktop's.
 * @param design the size the screens are written at. The window is fitted to it by [policy], so a
 *   screen is laid out once and looks the same on every window.
 */
public class UiLayer(
    private val fonts: UiFonts,
    private val design: Size,
    private val policy: ScalePolicy = ScalePolicy.Fit,
) : UiInput {

    /**
     * What is shown, as Compose state: assigning it recomposes, which is what makes [show] a plain
     * setter rather than a mount and unmount.
     */
    private var screen: UiScreen? by mutableStateOf(null)

    /** The toolkit's side, which exists only while attached. Render thread only. */
    private var mounted: Mounted? = null

    /** Where the scene's pointer verdicts go. Render thread only. */
    private var pointerReports: PointerReportListener? = null

    /** Shows [screen], or nothing when it is `null`. Safe from any thread. */
    public fun show(screen: UiScreen?) {
        this.screen = screen
    }

    /**
     * Offers [event] to the interface, and says whether it took it.
     *
     * The [UiInput] half of `KoolKeyboard`'s ordering: a text field with focus eats the letters, a
     * panel with an `onKeyEvent` eats Escape, and everything neither wanted comes back `false` and
     * becomes an intent. Nothing shown means nothing taken, which is what makes closing a menu put
     * every key back in the game's hands with no other state to unwind.
     *
     * **Render thread only.** It goes straight to the toolkit's `KeyRouter`, `KeyNavigator` and
     * `FocusManager`, none of which is thread-safe and none of which marshals. `KoolKeyboard` is the
     * only caller and Kool delivers to it while polling input, which on this engine's host is the
     * render thread - because `KoolThread` configures Kool with `asyncSceneUpdate = false`, so
     * `Input.poll`, the frame callbacks and the scene drawing are one thread rather than two.
     * `GlKoolInputTest` asserts that identity, and is what would go red if `asyncSceneUpdate` were
     * ever turned back on; the migration then is ComposeGL's own render-thread frame hook.
     */
    override fun onKey(event: KeyStroke): Boolean {
        val scene = mounted?.scene ?: return false
        return when (event.phase) {
            // Committed text, never a key: the toolkit refuses control characters outright, and a
            // backspace arriving as a character was a real bug in the frontend this replaces.
            KeyPhase.Character ->
                event.character.isPrintable() && scene.player.onText(TextEvent(event.character.toString()))

            KeyPhase.Down -> scene.player.onKey(toolkitEvent(event, KeyEventType.Down, repeat = false))
            KeyPhase.Repeat -> scene.player.onKey(toolkitEvent(event, KeyEventType.Down, repeat = true))
            KeyPhase.Up -> scene.player.onKey(toolkitEvent(event, KeyEventType.Up, repeat = false))
        }
    }

    /**
     * The interface's verdict on each pointer in each frame: the [UiPointers] half of `KoolPointer`'s
     * ordering, as [onKey] is the [UiInput] half of `KoolKeyboard`'s.
     *
     * It reports from [attach] to [detachAndClose]. While it does, a pointer waits for its verdict;
     * before the scene exists and after it has gone there is nothing to wait for. Showing no screen
     * does not stop it: the scene still reports, and reports nothing used. The verdicts come straight
     * from ComposeGL's `onPointerUsed`, which fires on the render thread at the start of the scene's
     * render, with the Kool frame the scene's own listener read the pointer in.
     */
    internal val pointers: UiPointers = object : UiPointers {
        override val isReporting: Boolean get() = mounted != null

        override fun reportTo(listener: PointerReportListener?) {
            pointerReports = listener
        }
    }

    /**
     * Builds the screen's scene and puts it on [ctx], over the scenes already there.
     *
     * Render thread only, and built here rather than in the constructor for that reason: a
     * `ComposeGlScene` joins Kool's input stack and makes a canvas as it is constructed.
     */
    internal fun attach(ctx: KoolContext) {
        check(mounted == null) { "$this is attached already; one layer serves one context" }
        val backend = ComposeGlBackend(fonts.atlas)
        val scene = ComposeGlScene(backend, design, policy)
        scene.setContent { screen?.content() }
        // Read at each report rather than captured here, so a listener set after attaching still hears.
        scene.onPointerUsed = { use ->
            pointerReports?.onReport(PointerId(use.pointer), InputFrame(use.frame), use.used)
        }
        ctx.addScene(scene.scene)
        mounted = Mounted(ctx, backend, scene)
    }

    /**
     * Takes the scene off the context and lets go of the composition and the canvas's GL objects.
     *
     * Render thread only. Does nothing when never attached, and nothing the second time: closing a
     * layer twice is what happens when a caller closes one the backend already owns.
     */
    internal fun detachAndClose() {
        val held = mounted ?: return
        mounted = null
        held.ctx.removeScene(held.scene.scene)
        held.scene.close()
        held.backend.close()
    }

    /** [event] as the toolkit's own key event, with the modifiers it was held with. */
    private fun toolkitEvent(event: KeyStroke, type: KeyEventType, repeat: Boolean) = KeyEvent(
        key = toolkitKey(event.key),
        type = type,
        modifiers = Modifiers(
            (if (event.shift) Modifiers.SHIFT else 0) or
                (if (event.control) Modifiers.CONTROL else 0) or
                (if (event.alt) Modifiers.ALT else 0) or
                (if (event.meta) Modifiers.META else 0),
        ),
        repeat = repeat,
    )

    /**
     * Whether this character is text rather than a control code.
     *
     * The toolkit's rule, stated on its own `TextEvent`: a backend must never send a control
     * character as text. Backspace arriving as a character inserted a square in the frontend this
     * one replaces, which is why it is checked here and not left to the router.
     */
    private fun Char.isPrintable(): Boolean = this >= ' ' && this != DELETE

    override fun toString(): String = "UiLayer(${design.width}x${design.height})"

    private companion object {

        /** The one control code above the space bar, so [isPrintable] names it rather than 0x7F. */
        const val DELETE: Char = '\u007F'
    }

    /** The three objects that exist only between [attach] and [detachAndClose]. */
    private class Mounted(
        val ctx: KoolContext,
        val backend: ComposeGlBackend,
        val scene: ComposeGlScene,
    )
}
