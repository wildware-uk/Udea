package dev.wildware.spellcastgame.screen.widget

import com.badlogic.gdx.scenes.scene2d.ui.HorizontalGroup
import com.badlogic.gdx.scenes.scene2d.utils.DragAndDrop
import com.badlogic.gdx.utils.Align
import dev.wildware.spellcastgame.spell.modifiers.MaxChildren
import dev.wildware.spellcastgame.spell.modifiers.Rune

class RuneHolder(
    val maxChildren: MaxChildren,
    val dragAndDrop: DragAndDrop
) : HorizontalGroup() {

    val children = mutableListOf<RuneBuilder>()

    init {
        rowAlign(Align.topLeft)
    }

    fun add(runeBuilder: RuneBuilder) {
        children.add(runeBuilder)
        addActor(runeBuilder)
    }

    fun addUnlimitedRune() {
        val runeBuilder = RuneBuilder(dragAndDrop)
        add(runeBuilder)
        runeBuilder.onDrop {
            if (children.all { it.runeAsset != null }) {
                addUnlimitedRune()
            }
        }
    }

    init {
        when (maxChildren) {
            is MaxChildren.Value -> {
                repeat(maxChildren.max) {
                    add(RuneBuilder(dragAndDrop))
                }
            }

            MaxChildren.Unlimited -> {
                addUnlimitedRune()
            }

            else -> {}
        }
    }

    fun build(): List<Rune> {
        return children.mapNotNull { it.build() }
    }
}

