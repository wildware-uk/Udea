package dev.wildware.udea.assets

import com.badlogic.gdx.Gdx
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.github.quillraven.fleks.UniqueId
import dev.wildware.udea.Vector2

class Control : Asset<Control>() {
    val controlId = ControlId++

    companion object {
        private var ControlId = 0
    }
}

data class Binding(
    val control: AssetReference<Control>,
    val input: BindingInput
) : Asset<Binding>() {
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

class Axis2D : Asset<Axis2D>() {
    val id: Int = nextId++

    companion object {
        private var nextId = 0
    }
}

class Axis2DBinding(
    val axis: AssetReference<Axis2D>,
    val input: Binding.BindingInput,
    val direction: Vector2
) : Asset<Axis2DBinding>()

/**
 * Creates a keyboard key binding.
 * */
fun key(key: Int) = Binding.BindingInput.Key(key)

/**
 * Creates a mouse button binding.
 * */
fun mouse(button: Int) = Binding.BindingInput.Mouse(button)
