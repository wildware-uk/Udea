package dev.wildware.hollow.render

import dev.wildware.hollow.HollowControls
import dev.wildware.udea.render.camera.ThirdPersonRig
import dev.wildware.udea.render.input.Intent
import dev.wildware.udea.render.input.IntentSource

/**
 * WASD read relative to where the camera is looking (issue #250).
 *
 * W walks away from the camera, A walks to the camera's left, and turning the view with the mouse
 * turns which way W means. Without this the keys would mean fixed compass directions, which in a
 * third-person game reads as the controls being wrong rather than as a design.
 *
 * ## Why the *intent* carries the facing and not the simulation
 *
 * A camera is presentation: `ThirdPersonRig` reads the world and writes nothing into it, a server
 * has no camera at all, and a replay has no camera either. So the turn from screen axes to world
 * axes happens **here**, where input is sampled, and what is written into the `Intent` - and
 * therefore what is recorded, replayed and sent to a server - is already a world-space direction.
 * `ThirdPersonRig`'s own KDoc asks for exactly this and says what reading the camera from a
 * simulation system would cost.
 *
 * ## Threads
 *
 * [sample] runs on the simulation thread, at `SimPhase.Intent`. On every host this engine ships
 * that is also the thread the rig's frame ran on (`KoolThread`), which is what makes the four
 * floats readable without a lock.
 */
internal class CameraRelativeIntent(
    /** Where the keys come from: a `DeviceIntent` over the window's keyboard. */
    private val device: IntentSource,
    /**
     * The camera the axis is read relative to, or `null` while there is none.
     *
     * A function and not a reference, because this source is built when the game's definition is -
     * before the backend exists, and therefore before any `RenderSystem` does. While it answers
     * null the keys mean fixed compass directions.
     */
    private val rig: () -> ThirdPersonRig?,
) : IntentSource {

    override fun sample(into: Intent) {
        device.sample(into)
        val turning = rig() ?: return
        val screenX = into.axisX(HollowControls.MOVE_AXIS)
        val screenY = into.axisY(HollowControls.MOVE_AXIS)
        // Nothing held: leave the axis at the zero the device wrote, rather than writing a zero
        // built out of the camera's facing, which for a rig that has never run would be a guess.
        if (screenX == 0f && screenY == 0f) return
        into.setAxis(
            HollowControls.MOVE_AXIS,
            turning.forwardX * screenY + turning.rightX * screenX,
            turning.forwardY * screenY + turning.rightY * screenX,
        )
    }

    override fun toString(): String = "CameraRelativeIntent($device)"
}
