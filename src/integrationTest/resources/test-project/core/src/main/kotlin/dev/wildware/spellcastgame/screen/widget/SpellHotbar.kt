package dev.wildware.spellcastgame.screen.widget

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.HorizontalGroup
import com.badlogic.gdx.scenes.scene2d.ui.Label
import dev.wildware.spellcastgame.spell.Spell
import ktx.scene2d.Scene2DSkin

class SpellHotbar : HorizontalGroup() {

    var selectedSpell: Int = 0
        set(value) {
            field = value
            draw()
        }

    var spells: List<Spell> = emptyList()
        set(value) {
            field = value
            draw()
        }

    init {
        space(10F)
    }

    fun draw() {
        clearChildren()

        spells.forEach {
            addActor(Label(it.name, Scene2DSkin.defaultSkin).apply {
                color = if (spells.indexOf(it) == selectedSpell) {
                    Color.GREEN
                } else {
                    Color.WHITE
                }
            })
        }
    }
}
