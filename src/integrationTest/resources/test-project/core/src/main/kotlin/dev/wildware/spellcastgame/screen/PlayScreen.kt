package dev.wildware.spellcastgame.screen

import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import dev.wildware.WorldSource
import dev.wildware.WorldSource.Host
import dev.wildware.screen.UIScreen
import dev.wildware.spellcastgame.MainGame
import dev.wildware.spellcastgame.SpellCastGame
import dev.wildware.spellcastgame.gameScreen
import ktx.scene2d.actors
import ktx.scene2d.table
import ktx.scene2d.textButton

class PlayScreen(
    val game: SpellCastGame
) : UIScreen() {

    init {
        stage.actors {
            table {
                setFillParent(true)

                textButton("Host") {
                    it.pad(10F)

                    addListener(object : ClickListener() {
                        override fun clicked(event: InputEvent?, x: Float, y: Float) {
                            gameScreen = MainGame(Host())
                            game.addScreen(gameScreen)
                            game.setScreen<MainGame>()
                        }
                    })
                }
                row()
                textButton("Join") {
                    it.pad(10F)

                    addListener(object : ClickListener() {
                        override fun clicked(event: InputEvent?, x: Float, y: Float) {
                            gameScreen = MainGame(WorldSource.Connect("137.220.118.234"))
                            game.addScreen(gameScreen)
                            game.setScreen<MainGame>()
                        }
                    })
                }
                row()
                textButton("Back") {
                    it.pad(40F)
                }
            }
        }
    }
}
