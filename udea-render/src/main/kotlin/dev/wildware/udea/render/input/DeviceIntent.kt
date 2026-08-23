package dev.wildware.udea.render.input

import kotlin.math.sqrt

/**
 * The [IntentSource] that reads a keyboard and a gamepad. **The only thing that reads a device.**
 *
 * It names no LibGDX type: it talks to [KeyboardState] and [GamepadState], and `GdxKeyboard` is
 * the one class in this repository that binds those to `Gdx.input`. That split is what lets the
 * whole of the input model - the edge counting, the vector accumulation, the deadzone, the
 * normalisation - be tested with no window, no context and no hardware, which is the half of the
 * old `ControllerSystem` that could never be tested at all.
 *
 * Runs on the simulation thread, once per tick, and allocates nothing.
 */
public class DeviceIntent(
    private val bindings: InputBindings,
    private val keyboard: KeyboardState = KeyboardState.NONE,
    private val gamepad: GamepadState = GamepadState.NONE,
) : IntentSource {

    override fun sample(into: Intent) {
        require(into.catalog === bindings.catalog) {
            "this source samples into an Intent built over its own bindings' catalog"
        }
        sampleActions(into)
        sampleAxes(into)
        // After every binding has read, never during: two actions on one key must both see the
        // press. Spending it inside the loop would give it to whichever id sorted first.
        keyboard.endSample()
        gamepad.endSample()
    }

    private fun sampleActions(into: Intent) {
        val actions = bindings.actionsById
        for (index in actions.indices) {
            val binding = actions[index]
            var held = false
            var presses = 0
            for (key in binding.keys) {
                if (keyboard.isKeyDown(key)) held = true
                presses += keyboard.pressesSince(key)
            }
            if (gamepad.isConnected) {
                for (button in binding.buttons) {
                    if (gamepad.isButtonDown(button)) held = true
                    presses += gamepad.pressesSince(button)
                }
            }
            val id = ActionId(index)
            into.setPressed(id, held)
            into.setPressCount(id, presses)
        }
    }

    private fun sampleAxes(into: Intent) {
        val axes = bindings.axesById
        for (index in axes.indices) {
            val binding = axes[index]
            var x = keyAxis(binding.negativeX, binding.positiveX)
            var y = keyAxis(binding.negativeY, binding.positiveY)

            if (gamepad.isConnected) {
                val rawX = if (binding.gamepadAxisX == Axis2DBinding.UNBOUND) {
                    0f
                } else {
                    gamepad.axis(binding.gamepadAxisX)
                }
                val rawYRead = if (binding.gamepadAxisY == Axis2DBinding.UNBOUND) {
                    0f
                } else {
                    gamepad.axis(binding.gamepadAxisY)
                }
                val rawY = if (binding.invertGamepadY) -rawYRead else rawYRead
                val magnitude = sqrt(rawX * rawX + rawY * rawY)
                if (magnitude > binding.deadzone && magnitude > 0f) {
                    // Rescale so the first millimetre outside the dead area is a *small* value
                    // rather than a jump to `deadzone`. Without this a stick snaps to a quarter
                    // speed the instant it leaves the centre, which reads as a sticky pad.
                    val scaled = ((magnitude - binding.deadzone) / (1f - binding.deadzone))
                        .coerceAtMost(1f)
                    x += rawX / magnitude * scaled
                    y += rawY / magnitude * scaled
                }
            }

            // Clamp, do not normalise. `ControllerSystem` called `nor()` unconditionally, which
            // turns a half-pushed stick into a sprint; clamping leaves a partial deflection
            // partial and still brings a keyboard diagonal to exactly 1.
            val length = sqrt(x * x + y * y)
            if (length > 1f) {
                x /= length
                y /= length
            }
            into.setAxis(AxisId(index), x, y)
        }
    }

    /** `-1`, `0` or `+1` from an opposing key pair. Both down cancels, which is what a player means. */
    private fun keyAxis(negative: Int, positive: Int): Float {
        var value = 0f
        if (negative != Axis2DBinding.UNBOUND && keyboard.isKeyDown(negative)) value -= 1f
        if (positive != Axis2DBinding.UNBOUND && keyboard.isKeyDown(positive)) value += 1f
        return value
    }

    override fun toString(): String = "DeviceIntent($bindings, gamepad=${gamepad.isConnected})"
}
