package dev.wildware.udea.render.ui

import androidx.compose.runtime.Composable

/**
 * One screen of user interface: a `@Composable` and a lifecycle, and nothing else.
 *
 * ## Why it is not a `Screen`, and why it has no `render`
 *
 * `UIScreen` in the old tree was a `KtxScreen` — it had its own `render(delta)`, read
 * `Gdx.graphics.deltaTime` for itself (`screen/UIScreen.kt:18`) and drew its own stage. That
 * made it a second game loop running beside the real one: two things deciding when a frame
 * happens, two readings of wall time per frame, and a menu that kept animating while the game
 * was paused because nobody had told it.
 *
 * Here a screen declares what it looks like and owns no timing at all. [UiLayer] composes it,
 * lays it out and draws it, once, inside the frame the pipeline is already running.
 *
 * ## Why [content] takes nothing
 *
 * The scene2d version was handed the `Stage`, because a scene2d screen has to place actors in
 * pixel coordinates and therefore needs to know how big the surface is. A composition does not:
 * the layer lays the tree out against the surface it is drawing into, and a screen says
 * `Modifier.fillMaxSize()` or `Modifier.align(Alignment.BottomStart)` and gets the same answer on
 * a 720p window, a 4K one and a 64x32 capture. A parameter here would be a size a screen could
 * cache and then be wrong about after a resize.
 *
 * Anything a screen does need — the world, the game's own state — arrives through its
 * constructor, from whichever [dev.wildware.udea.render.RenderSystem] wraps the layer and
 * therefore has [dev.wildware.udea.render.RenderSystem.onBind].
 */
public interface UiScreen {

    /**
     * This screen's interface, composed once when the screen is shown and recomposed by the
     * Compose runtime whenever state it read has changed.
     *
     * Read game state through `mutableStateOf` (or a `State` derived from one) rather than off a
     * plain field: a plain field changes without telling anybody, and the label showing it then
     * updates only when something *else* on the screen happens to recompose.
     */
    @Composable
    public fun content()

    /**
     * Called after the screen's nodes have been taken out of the tree.
     *
     * For anything the screen allocated that the toolkit does not own. The nodes are the
     * composition's; a texture the screen registered is not.
     */
    public fun dispose() {}
}
