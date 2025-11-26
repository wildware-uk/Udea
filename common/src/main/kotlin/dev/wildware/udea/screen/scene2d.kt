package dev.wildware.udea.screen

import com.badlogic.gdx.scenes.scene2d.ui.Skin
import ktx.scene2d.Scene2DSkin

fun useSkin(skin: Skin, block: () -> Unit) {
    val oldSkin = try {
        Scene2DSkin.defaultSkin
    } catch (e: Exception) {
        null
    }

    Scene2DSkin.defaultSkin = skin
    block()

    if (oldSkin != null) {
        Scene2DSkin.defaultSkin = oldSkin
    }
}
