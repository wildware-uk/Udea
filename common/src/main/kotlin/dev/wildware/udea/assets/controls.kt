package dev.wildware

import com.badlogic.gdx.Gdx
import dev.wildware.udea.assets.Asset
import dev.wildware.udea.assets.AssetType

data class Control(
    val name: String
) {
    val id = ControlId++

    companion object : AssetType<Control>() {
        override val id: String = "control"
        private var ControlId = 0
    }
}

data class Binding(
    val control: Asset<Control>,
    val input: BindingInput
) {
    companion object : AssetType<Binding>() {
        override val id = "binding"
    }

    sealed interface BindingInput {
        fun pressed(): Boolean
        fun justPressed(): Boolean

        data class Key(val key: Int) : BindingInput {
            override fun pressed(): Boolean {
                return Gdx.input.isKeyPressed(key)
            }

            override fun justPressed(): Boolean {
                return Gdx.input.isKeyJustPressed(key)
            }
        }

        data class Mouse(val button: Int) : BindingInput {
            override fun pressed(): Boolean {
                return Gdx.input.isButtonPressed(button)
            }

            override fun justPressed(): Boolean {
                return Gdx.input.isButtonJustPressed(button)
            }
        }
    }
}
