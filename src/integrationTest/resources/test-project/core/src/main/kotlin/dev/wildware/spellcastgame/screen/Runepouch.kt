package dev.wildware.spellcastgame.screen

import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.ui.TextField
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.badlogic.gdx.scenes.scene2d.utils.DragAndDrop
import dev.wildware.udea.assets.Assets
import dev.wildware.network.cbor
import dev.wildware.screen.UIScreen
import dev.wildware.spellcastgame.SpellCastGame
import dev.wildware.spellcastgame.screen.widget.RuneHolder
import dev.wildware.spellcastgame.screen.widget.RuneWidget
import dev.wildware.spellcastgame.spell.RuneDescriptor
import dev.wildware.spellcastgame.spell.Spell
import dev.wildware.spellcastgame.spell.SpellLoader
import dev.wildware.spellcastgame.spell.modifiers.MaxChildren
import kotlinx.serialization.encodeToHexString
import ktx.scene2d.*

class Runepouch(
    val game: SpellCastGame
) : UIScreen() {

    val runes by lazy { Assets[RuneDescriptor] }

    val dragAndDrop = DragAndDrop()

    lateinit var runeHolder: RuneHolder
    val nameTextField = TextField("Spell Name", Scene2DSkin.defaultSkin)

    override fun show() {
        super.show()
        stage.actors {
            splitPane {
                setFillParent(true)

                setFirstWidget(table {
                    label("Runepouch")
                    row()
                    horizontalGroup {
                        it.fill().grow()
                        wrap(true)
                        runes.forEachIndexed { index, it ->
                            val runeActor = RuneWidget(it)
                            runeActor.width = 64f
                            addActor(runeActor)

                            dragAndDrop.addSource(object : DragAndDrop.Source(runeActor) {
                                override fun dragStart(
                                    event: InputEvent,
                                    x: Float,
                                    y: Float,
                                    pointer: Int
                                ): DragAndDrop.Payload? {
                                    return DragAndDrop.Payload().apply {
                                        val actor = RuneWidget(it)
                                        setObject(it)
                                        setDragActor(actor)
                                        stage.addActor(actor)
                                    }
                                }

                                override fun dragStop(
                                    event: InputEvent?,
                                    x: Float,
                                    y: Float,
                                    pointer: Int,
                                    payload: DragAndDrop.Payload,
                                    target: DragAndDrop.Target?
                                ) {
                                    payload.dragActor.remove()
                                }
                            })
                        }
                    }.fill()
                })

                setSecondWidget(table {
                    textButton("Save") {
                        addListener(object : ClickListener() {
                            override fun clicked(event: InputEvent, x: Float, y: Float) {
                                val spell = Spell(runeHolder.build(), nameTextField.text)
                                SpellLoader.saveSpell(spell)
                            }
                        })
                    }
                    row()

                    scrollPane {
                        it.grow()

                        table {
                            add(nameTextField).top()
                            runeHolder = RuneHolder(MaxChildren.Unlimited, dragAndDrop)
                            add(runeHolder).expand()
                        }
                    }
                })
            }
        }
    }
}
