package dev.wildware.udea.assets

import com.badlogic.gdx.Gdx
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

class Control : Asset() {
    val controlId = ControlId++

    companion object {
        private var ControlId = 0
    }
}

data class Binding(
    val control: AssetRefence<Control>,
    val input: BindingInput
) : Asset() {
    @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS) // Use fully qualified class name for type info)
    @JsonSubTypes(
        JsonSubTypes.Type(value = BindingInput.Key::class),
        JsonSubTypes.Type(value = BindingInput.Mouse::class)
    )
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
