package dev.wildware.udea.screen

import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.Sprite
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.kotcrab.vis.ui.VisUI
import dev.wildware.udea.AssetLoaderTask
import dev.wildware.udea.UdeaGame
import dev.wildware.udea.gameManager
import ktx.assets.toInternalFile
import ktx.scene2d.actors
import ktx.scene2d.image
import ktx.scene2d.label
import ktx.scene2d.progressBar
import ktx.scene2d.table

class LoadingScreen(
    val assetLoaderTask: AssetLoaderTask,
    val game: UdeaGame
) : UIScreen() {

    val loadingImage = Sprite(Texture("images/logo_square.png".toInternalFile()))

    lateinit var progressBar: com.badlogic.gdx.scenes.scene2d.ui.ProgressBar
    private lateinit var statusText: Label

    init {
        useSkin(VisUI.getSkin()) {
            stage.actors {
                table {
                    setFillParent(true)
                    center()
                    defaults().pad(10f)

                    image(loadingImage) {
                        it.size(128F)
                    }
                    row()
                    progressBar = progressBar {
                        it.size(1024F, 20F)
                    }
                    row()
                    statusText = label("Loading...")
                }
            }
        }
    }

    override fun render(delta: Float) {
        super.render(delta)

        if(!assetLoaderTask.finished) {
            assetLoaderTask.load()
            statusText.setText(assetLoaderTask.status)
            progressBar.value = assetLoaderTask.progress.toFloat() / assetLoaderTask.total.toFloat()

            if(assetLoaderTask.finished) {
                game.onReady(gameManager)
            }
        }
    }
}
