package dev.wildware.udea.render.ui

import dev.wildware.composegl.ui.widget.SceneDrawScope
import dev.wildware.udea.render.kool.PassBlit

/**
 * The world, as something a ComposeGL `SceneView` can show (issue #194).
 *
 * ```kotlin
 * SceneView(state, Modifier.fillMaxSize()) {
 *     clear(Colour.Black)
 *     world.drawInto(this)
 * }
 * ```
 *
 * The door an editor, or any screen with a viewport in it, goes through to put the world inside a
 * panel without naming Kool: a `SceneView`'s `raw` block hands over a Kool frame, and the world is
 * Kool's to draw, so both halves stay in this module as spec section 3 requires. The widget is
 * ComposeGL's and not Udea's - the issue is explicit that the viewport is `SceneView`.
 *
 * ## What it shows
 *
 * The capturable frame: the offscreen pass every `RenderSystem` draws into and every capture reads,
 * copied into the `SceneView`'s picture at the picture's size, keeping its shape - letterboxed, the
 * way the window presents it. So the viewport shows exactly the pixels an agent's screenshot holds,
 * and the panels around it, which are drawn into the window, are in no capture.
 *
 * ## When it is drawn
 *
 * Only when the `SceneView` renders, which is when its state is invalidated. The world is redrawn
 * into the pass every frame whether or not anything looks at it; calling `invalidate()` is how a
 * screen says the picture it holds is out of date. A paused editor invalidates when something
 * changed, a running game every frame.
 *
 * One `WorldView` serves any number of `SceneView`s: it holds no picture of its own, only the
 * render-thread object that copies the pass into whichever picture is bound.
 */
public class WorldView internal constructor(private val blit: PassBlit) {

    /**
     * Copies the world into the picture [scene] is drawing, letterboxed. Call it inside a
     * `SceneView`'s draw block; the bars are whatever the block cleared the picture to first.
     */
    public fun drawInto(scene: SceneDrawScope) {
        val width = scene.width
        val height = scene.height
        scene.raw { blit.into(width, height) }
    }

    override fun toString(): String = "WorldView($blit)"
}
