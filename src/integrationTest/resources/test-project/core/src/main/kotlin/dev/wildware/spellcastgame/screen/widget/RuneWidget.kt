package dev.wildware.spellcastgame.screen.widget

import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Stack
import com.badlogic.gdx.scenes.scene2d.ui.TextTooltip
import com.badlogic.gdx.scenes.scene2d.ui.Tooltip
import dev.wildware.udea.assets.Asset
import dev.wildware.spellcastgame.spell.RuneDescriptor
import ktx.assets.toInternalFile
import ktx.scene2d.Scene2DSkin

class RuneWidget(
    runeAsset: Asset<RuneDescriptor>? = null
) : Stack() {
    val runeSlot = Texture("ui/rune_slot.png".toInternalFile())
    val runeTexture = Texture("ui/rune.png".toInternalFile())
    val scatter = Texture("ui/runes/scatter.png".toInternalFile())

    init {
        add(Image(runeSlot))

        if (runeAsset != null) {
            val tooltip = TextTooltip(runeAsset().description, Scene2DSkin.defaultSkin)
            tooltip.setInstant(true)

            add(Image(runeTexture))
            add(Image(scatter))
            add(Label(runeAsset().name, Scene2DSkin.defaultSkin).apply {
                addListener(tooltip)
            })
        }
    }
}
