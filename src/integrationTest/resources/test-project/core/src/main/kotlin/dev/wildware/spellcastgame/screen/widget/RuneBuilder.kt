package dev.wildware.spellcastgame.screen.widget

import com.badlogic.gdx.Input
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox
import com.badlogic.gdx.scenes.scene2d.ui.Skin
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextField
import com.badlogic.gdx.scenes.scene2d.ui.VerticalGroup
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.badlogic.gdx.scenes.scene2d.utils.DragAndDrop
import com.badlogic.gdx.utils.Align
import dev.wildware.udea.assets.Asset
import dev.wildware.udea.assets.Assets
import dev.wildware.events.Event
import dev.wildware.events.EventListener
import dev.wildware.spellcastgame.spell.RuneDescriptor
import dev.wildware.spellcastgame.spell.RuneParameter
import dev.wildware.spellcastgame.spell.SpellElement
import dev.wildware.spellcastgame.spell.modifiers.Rune
import ktx.scene2d.Scene2DSkin

class RuneBuilder(
    val dragAndDrop: DragAndDrop,
    runeAsset: Asset<RuneDescriptor>? = null,
    skin: Skin = Scene2DSkin.defaultSkin
) : VerticalGroup() {
    var runeAsset: Asset<RuneDescriptor>? = null
        set(value) {
            updateRuneAsset(value)
            field = value
        }

    var runeHolder: RuneHolder? = null

    init {
        debug()
        this.runeAsset = runeAsset
        columnAlign(Align.topLeft)
    }

    private fun updateRuneAsset(runeAsset: Asset<RuneDescriptor>?) {
        clearChildren()

        if (runeAsset == null) {
            val slot = RuneWidget(runeAsset)
            addActor(slot)
            dragAndDrop.addTarget(object : DragAndDrop.Target(slot) {
                override fun drag(
                    source: DragAndDrop.Source, payload: DragAndDrop.Payload, x: Float, y: Float, pointer: Int
                ): Boolean {
                    return (payload.`object` as? Asset<*>)?.id != null
                }

                override fun drop(
                    source: DragAndDrop.Source?,
                    payload: DragAndDrop.Payload?,
                    x: Float,
                    y: Float,
                    pointer: Int
                ) {
                    this@RuneBuilder.runeAsset = payload?.`object` as? Asset<RuneDescriptor>
                    onDrop?.invoke()
                }
            })
        } else {
            val runeDescriptor = runeAsset()
            val runeWidget = RuneWidget(runeAsset)
            addActor(runeWidget)

            runeHolder = RuneHolder(runeDescriptor.spellModifier.maxChildren, dragAndDrop)
            addActor(runeHolder)

            runeWidget.addListener(object : ClickListener() {
                override fun clicked(event: InputEvent, x: Float, y: Float) {
                    if(event.button == Input.Buttons.RIGHT) {
                        this@RuneBuilder.runeAsset = null
                    }
                }
            })

            dragAndDrop.addTarget(object : DragAndDrop.Target(runeWidget) {
                override fun drag(
                    source: DragAndDrop.Source, payload: DragAndDrop.Payload, x: Float, y: Float, pointer: Int
                ): Boolean {
                    return (payload.`object` as? Asset<RuneDescriptor>)?.id != null
                }

                override fun drop(
                    source: DragAndDrop.Source?,
                    payload: DragAndDrop.Payload?,
                    x: Float,
                    y: Float,
                    pointer: Int
                ) {
                    this@RuneBuilder.runeAsset = payload?.`object` as? Asset<RuneDescriptor>
                    onDrop?.invoke()
                }
            })
        }
    }

    private var onDrop: (() -> Unit)? = null

    fun onDrop(onDrop: () -> Unit) {
        this.onDrop = onDrop
    }

    fun build(): Rune? {
        val runeAsset = runeAsset ?: return null
        val rune = runeAsset()
        return Rune(
            rune.spellModifier,
            children = runeHolder?.build() ?: emptyList(),
        )
    }
}
