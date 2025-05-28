package dev.wildware.screen

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.utils.viewport.ScreenViewport
import ktx.app.KtxScreen
import ktx.assets.disposeSafely

abstract class UIScreen : KtxScreen {

    val screenViewport = ScreenViewport()
    val stage = Stage(screenViewport)

    override fun render(delta: Float) {
        stage.act(Gdx.graphics.deltaTime.coerceAtMost(1 / 30f))
        stage.draw()
    }

    override fun show() {
        Gdx.input.inputProcessor = stage
    }

    override fun dispose() {
        stage.disposeSafely()
    }

    override fun resize(width: Int, height: Int) {
        screenViewport.update(width, height, true)
    }
}
