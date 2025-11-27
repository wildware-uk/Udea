package dev.wildware.udea.ecs.system

import com.badlogic.gdx.math.Vector2
import com.github.quillraven.fleks.IntervalSystem
import dev.wildware.udea.assets.*

class ControllerSystem : IntervalSystem() {
    private val controls = Assets.filterIsInstance<Control>()
    private val bindings = Assets.filterIsInstance<Binding>()

    private val inputPressed = Array(controls.size) { false }
    private val inputJustPressed = Array(controls.size) { false }

    private val axes = Assets.filterIsInstance<Axis2D>()
    private val axisBindings = Assets.filterIsInstance<Axis2DBinding>()

    private val axisValues = Array(axes.size) { Vector2() }

    override fun onTick() {
        inputPressed.fill(false)
        inputJustPressed.fill(false)

        bindings.forEach {
            val b = it
            val id = b.control.value.controlId
            inputPressed[id] = inputPressed[id] || b.input.pressed()
            inputJustPressed[id] = inputJustPressed[id] || b.input.justPressed()
        }

        axisValues.forEach { value -> value.setZero() }
        axisBindings.forEach { binding -> if (binding.input.pressed()) axisValues[binding.axis.value.id].add(binding.direction) }
        axisValues.forEach { it.nor() }
    }

    /**
     * Return true if the input is pressed.
     * */
    fun isInputPressed(control: Control) = inputPressed[control.controlId]

    /**
     * Return true if the input was just pressed.
     * */
    fun isInputJustPressed(control: Control) = inputJustPressed[control.controlId]

    /**
     * Gets the value of an axis.
     * */
    fun getAxisValue(axis: Axis2D) = axisValues[axis.id]
}
