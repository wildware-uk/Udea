package dev.wildware.udea.render.ui

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Stage

/**
 * One screen of user interface: a scene2d root actor and a lifecycle, and nothing else.
 *
 * ## Why it is not a `Screen`
 *
 * `UIScreen` in the old tree was a `KtxScreen` — it had its own `render(delta)`, read
 * `Gdx.graphics.deltaTime` for itself (`screen/UIScreen.kt:18`) and drew its own stage. That
 * made it a second game loop running beside the real one: two things deciding when a frame
 * happens, two readings of wall time per frame, and a menu that kept animating while the game
 * was paused because nobody had told it.
 *
 * Here a screen owns actors and no timing at all. [UiLayer] draws it, once, inside the frame
 * the pipeline is already running.
 */
public interface UiScreen {

    /**
     * Builds this screen's root actor.
     *
     * Called once when the screen is shown, with the [Stage] it will live in — a screen that
     * needs the stage's dimensions to lay itself out has them, and one that does not can
     * ignore the parameter.
     */
    public fun build(stage: Stage): Actor

    /**
     * Called after the root actor has been removed from the stage.
     *
     * For anything the screen allocated that the stage does not own. Actors are the stage's;
     * a texture the screen loaded is not.
     */
    public fun dispose() {}
}
