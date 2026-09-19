package dev.wildware.udea.render.ui

import androidx.compose.runtime.Composable

/**
 * One screen of interface: a menu, a HUD, a death panel. **What a game writes.**
 *
 * A `@Composable` and not a class of draw calls, because the interface is ComposeGL (spec D5). A
 * game implements this with the toolkit's own widgets and never names a renderer: `composegl-ui` is
 * the toolkit with no backend in it and is the one ComposeGL artefact `UDEA-MG-002` lets a game
 * depend on. The frontend that turns this tree into triangles - `composegl-kool` - is `udea-render`'s
 * alone, and [UiLayer] is the whole of the door between them.
 *
 * ## It is not a `RenderSystem`
 *
 * A `RenderSystem` is handed a batch and draws into the frame an agent captures. A screen is
 * composed, laid out and drawn by the toolkit, over everything the game drew. The two are different
 * enough that sharing an interface would only hide it. Where a screen lands is decided by what shows
 * it: a [UiLayer] draws into the window and is absent from every capture (`GlUiLayerTest`), and a
 * [CapturedUi] draws into the capturable frame and is in every one (`GlCapturedUiTest`).
 */
public interface UiScreen {

    /** The screen's content, recomposed by the toolkit whenever what it reads changes. */
    @Composable
    public fun content()
}
