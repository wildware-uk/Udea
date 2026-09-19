package dev.wildware.udea.editor

import dev.wildware.udea.render.view.EditorCamera
import dev.wildware.udea.render.view.WorldViewport

/**
 * The editor window's two looks at the world (issue #234): the Scene tab and the Game tab.
 *
 * A launcher opens both on its render backend - `KoolBackend.openSceneView` and `openGameView` - and
 * hands them here; the window shows one at a time and never draws into either. The Scene tab's
 * [EditorCamera] is the editor's own and presentation state only: moving it changes what the Scene
 * tab shows and nothing a simulation, a snapshot or a capture reads.
 *
 * @property scene seen through its own [EditorCamera]; its pointer is the editor's.
 * @property game the capturable frame, seen through the game's camera; its pointer is the game's.
 */
public class EditorViews(
    public val scene: WorldViewport,
    public val game: WorldViewport,
) {
    init {
        require(scene.camera != null) { "$scene has no editor camera, so it cannot be the Scene tab" }
        require(game.camera == null) { "$game has an editor camera, so it cannot be the Game tab" }
    }

    /** The Scene tab's camera. */
    public val camera: EditorCamera get() = checkNotNull(scene.camera)

    override fun toString(): String = "EditorViews(scene=$scene, game=$game)"

    public companion object {

        /**
         * Two views with no render context behind them ([WorldViewport.detached]): the tabs, the camera
         * and the pointer all work, and nothing is drawn. For an editor window with no GL - its
         * headless tests.
         */
        public fun detached(width: Int = DETACHED_WIDTH, height: Int = DETACHED_HEIGHT): EditorViews = EditorViews(
            scene = WorldViewport.detached(EditorCamera(), width, height),
            game = WorldViewport.detached(null, width, height),
        )

        private const val DETACHED_WIDTH = 640
        private const val DETACHED_HEIGHT = 360
    }
}
