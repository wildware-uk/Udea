package dev.wildware.udea.render.backend

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * How a launch check asks a running game for a frame: three environment variables, read once.
 *
 * No context here. What the probe *does* with a context is `GlLaunchProbeTest`'s and
 * `GlLaunchProbeStopFileTest`'s, each in a JVM of its own.
 */
class LaunchProbeTest {

    @Test
    fun `no probe is asked for when the frame file is not named`() {
        assertNull(LaunchProbe.fromEnvironment(env()))
        assertNull(LaunchProbe.fromEnvironment(env(LaunchProbe.FRAMES_VARIABLE to "5")))
        assertNull(LaunchProbe.fromEnvironment(env(LaunchProbe.PNG_VARIABLE to "  ")))
    }

    @Test
    fun `a named frame file asks for the default frame count and closes without waiting`() {
        val probe = LaunchProbe.fromEnvironment(env(LaunchProbe.PNG_VARIABLE to "out/frame.png"))
            ?: error("a probe was asked for")
        assertEquals(Path.of("out/frame.png"), probe.png)
        assertEquals(LaunchProbe.DEFAULT_FRAMES, probe.frames)
        assertNull(probe.stopFile)
    }

    @Test
    fun `the frame count and the stop file are read when given`() {
        val probe = LaunchProbe.fromEnvironment(
            env(
                LaunchProbe.PNG_VARIABLE to "frame.png",
                LaunchProbe.FRAMES_VARIABLE to " 7 ",
                LaunchProbe.STOP_VARIABLE to "stop.flag",
            ),
        ) ?: error("a probe was asked for")
        assertEquals(7, probe.frames)
        assertEquals(Path.of("stop.flag"), probe.stopFile)
    }

    @Test
    fun `a frame count that is not a positive whole number is refused by name`() {
        for (bad in listOf("abc", "0", "-3", "1.5")) {
            val refused = assertFailsWith<IllegalArgumentException>(bad) {
                LaunchProbe.fromEnvironment(
                    env(LaunchProbe.PNG_VARIABLE to "frame.png", LaunchProbe.FRAMES_VARIABLE to bad),
                )
            }
            assertTrue(
                refused.message.orEmpty().contains(LaunchProbe.FRAMES_VARIABLE),
                "the refusal of '$bad' did not name the variable: ${refused.message}",
            )
        }
    }

    private fun env(vararg pairs: Pair<String, String>): (String) -> String? {
        val values = mapOf(*pairs)
        return { values[it] }
    }
}
