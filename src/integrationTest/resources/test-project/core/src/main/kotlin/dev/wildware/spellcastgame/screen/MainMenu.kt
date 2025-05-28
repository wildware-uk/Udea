package dev.wildware.spellcastgame.screen

import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import dev.wildware.screen.UIScreen
import dev.wildware.spellcastgame.SpellCastGame
import ktx.assets.disposeSafely
import ktx.assets.toInternalFile
import ktx.scene2d.actors
import ktx.scene2d.image
import ktx.scene2d.table
import ktx.scene2d.textButton

class MainMenu(
    val game: SpellCastGame
) : UIScreen() {

    val logo = Texture("logo.png".toInternalFile())

    init {
        stage.actors {
            table {
                setFillParent(true)
                image(logo)
                row()
                textButton("Play") {
                    addListener(object : ClickListener() {
                        override fun clicked(event: InputEvent?, x: Float, y: Float) {
                            game.setScreen<PlayScreen>()
                        }
                    })
                }
                row()
                textButton("Runepouch") {
                    addListener(object : ClickListener() {
                        override fun clicked(event: InputEvent?, x: Float, y: Float) {
                            game.setScreen<Runepouch>()
                        }
                    })
                }
                row()
                textButton("Settings")
                row()
                textButton("Exit")
            }
        }
    }

    override fun dispose() {
        logo.disposeSafely()
    }
}
