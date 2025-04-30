package dev.wildware.ecs.system

import com.badlogic.gdx.Gdx
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IteratingSystem
import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.*
import dev.wildware.Binding.BindingInput.Key
import dev.wildware.Binding.BindingInput.Mouse
import dev.wildware.ecs.component.Controller
import dev.wildware.ecs.component.Networkable
import dev.wildware.udea.assets.Assets

class ControllerSystem : IteratingSystem(
    family { all(Controller, Networkable) }
) {
    private val controls = Assets[Control].toList()
    private val bindings = Assets[Binding].toList()

    private val inputPressed = Array(controls.size) { false }
    private val inputJustPressed = Array(controls.size) { false }

    override fun onTick() {
        inputPressed.fill(false)
        inputJustPressed.fill(false)

        bindings.forEach {
            val b = it()
            val id = b.control().id
            inputPressed[id] = inputPressed[id] || b.input.pressed()
            inputJustPressed[id] = inputJustPressed[id] || b.input.justPressed()
        }

        super.onTick()
    }

    override fun onTickEntity(entity: Entity) {
        if (!world.hasAuthority(entity)) return

        val controller = entity[Controller]
        inputPressed.copyInto(controller.bindingPressed)
        inputJustPressed.copyInto(controller.bindingJustPressed)
    }
}
