package dev.wildware.udea.ecs.system

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IteratingSystem
import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.udea.assets.Assets
import dev.wildware.udea.assets.Binding
import dev.wildware.udea.assets.Control
import dev.wildware.udea.ecs.component.base.Networkable
import dev.wildware.udea.ecs.component.control.Controller
import dev.wildware.udea.hasAuthority

class ControllerSystem : IteratingSystem(
    family { all(Controller, Networkable) }
) {
    private val controls = Assets.filterIsInstance<Control>()
    private val bindings = Assets.filterIsInstance<Binding>()

    private val inputPressed = Array(controls.size) { false }
    private val inputJustPressed = Array(controls.size) { false }

    override fun onTick() {
        inputPressed.fill(false)
        inputJustPressed.fill(false)

        bindings.forEach {
            val b = it
            val id = b.control.value.controlId
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
