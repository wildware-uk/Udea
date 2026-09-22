package dev.wildware.udea.render.gl

import dev.wildware.udea.core.host.RenderMode
import kotlin.test.Test

/**
 * `Offscreen` - the agent's mode, and what `moba.agent` and every shot main run in - left driving
 * for [SOAK_SECONDS] of real frames. See [soakDrive] for what this proves and why it is timed.
 */
class GlDriveSoakTest {

    @Test
    fun `an offscreen game keeps drawing for sixteen seconds of real frames`() {
        soakDrive(RenderMode.Offscreen)
    }
}
