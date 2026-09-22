package dev.wildware.udea.render.gl

import dev.wildware.udea.core.host.RenderMode
import kotlin.test.Test

/**
 * `Windowed` - the mode a person plays in, and the one #275's games closed themselves in - left
 * driving for [SOAK_SECONDS] of real frames. A class of its own because Kool allows one context
 * per JVM and `udeaGlTest` forks one JVM per class, so this mode cannot share with
 * `GlDriveSoakTest`.
 */
class GlWindowedDriveSoakTest {

    @Test
    fun `a windowed game keeps drawing for sixteen seconds of real frames`() {
        soakDrive(RenderMode.Windowed)
    }
}
