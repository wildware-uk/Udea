package dev.wildware.udea.agent.host.render

import dev.wildware.udea.agent.host.CaptureFrame
import dev.wildware.udea.agent.host.EditorView
import dev.wildware.udea.agent.host.EditorViewControl
import dev.wildware.udea.render.view.WorldViewport
import java.util.concurrent.Future

/**
 * [EditorViewControl] over an editor window's two `udea-render` views: what `editor.screenshot` reads
 * (issue #234). Each capture is of the view's own pass, gizmos included - never the capturable frame.
 *
 * @param scene the Scene tab's view, seen through its editor camera.
 * @param game the Game tab's view.
 */
public class WorldViewportControl(
    private val scene: WorldViewport,
    private val game: WorldViewport,
) : EditorViewControl {

    init {
        require(scene.camera != null) { "$scene has no editor camera, so it cannot be the Scene tab" }
        require(game.camera == null) { "$game has an editor camera, so it cannot be the Game tab" }
    }

    override fun capture(view: EditorView): Future<CaptureFrame> = when (view) {
        EditorView.Scene -> scene.capture()
        EditorView.Game -> game.capture()
    }.asCaptureFrame()

    override fun toString(): String = "WorldViewportControl($scene, $game)"
}
