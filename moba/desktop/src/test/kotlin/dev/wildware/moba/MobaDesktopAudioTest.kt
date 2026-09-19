package dev.wildware.moba

import dev.wildware.moba.entry.MobaDesktopAudio
import dev.wildware.udea.audio.AudioDevice
import dev.wildware.udea.audio.AudioLoadException
import dev.wildware.udea.audio.SoundHandle
import dev.wildware.udea.core.host.RenderMode
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Which device a windowed `moba` process plays through, and what it does when there is none.
 *
 * `koolAudioDevice` never picks silence for itself: with no audio output it throws
 * [AudioLoadException] from `load`, and choosing [AudioDevice.Silent] is the game root's call
 * (issue #221's ruling). This box has no usable output, so an unguarded windowed client would die
 * on its first frame. These drive [MobaDesktopAudio] with a stand-in device, because a real one
 * behaves differently on every machine.
 */
class MobaDesktopAudioTest {

    /** A device that answers every load the way `koolAudioDevice` does on a machine with no output. */
    private class NoOutput : AudioDevice {
        var loads = 0
        var closed = false

        override fun load(path: String): SoundHandle {
            loads++
            throw AudioLoadException(path, "no audio output")
        }

        override fun play(sound: SoundHandle, volume: Float, pitch: Float, pan: Float) = Unit

        override fun close() {
            closed = true
        }
    }

    /** A device that loads everything, and counts what it was asked for. */
    private class Loads : AudioDevice {
        var loads = 0

        override fun load(path: String): SoundHandle {
            loads++
            return SoundHandle(loads)
        }

        override fun play(sound: SoundHandle, volume: Float, pitch: Float, pan: Float) = Unit

        override fun close() = Unit
    }

    @Test
    fun `with no audio output a windowed process falls back to silence and says why`() {
        val host = MobaGame.host(RenderMode.Windowed)
        val device = NoOutput()
        val said = mutableListOf<String>()

        val audio = MobaDesktopAudio.forHost(host, ROOT, device = { device }, log = { said += it })

        assertTrue(device.loads > 0, "the real device was never tried, so this proves nothing about the fallback")
        assertTrue(device.closed, "the device that failed was left open")
        assertEquals(1, said.size, "expected one line saying why the process is silent, got $said")
        assertTrue("no audio output" in said.single(), "the log line does not carry the cause: ${said.single()}")
        // Still a working drain over Silent: the routing table was built, over a device that loads.
        assertTrue(audio.sounds.bindings.size > 0, "the silent fallback routed nothing")
        audio.close()
    }

    @Test
    fun `a windowed process with an audio output plays through the device it was given`() {
        val host = MobaGame.host(RenderMode.Windowed)
        val device = Loads()
        val said = mutableListOf<String>()

        val audio = MobaDesktopAudio.forHost(host, ROOT, device = { device }, log = { said += it })

        assertTrue(device.loads > 0, "the device was built and then not used")
        assertEquals(emptyList(), said, "a device that loaded fine should not be reported as a fallback")
        audio.close()
    }

    @Test
    fun `a headless process never opens a device`() {
        val host = MobaGame.host(RenderMode.Headless)
        var built = false

        val audio = MobaDesktopAudio.forHost(host, ROOT, device = { built = true; Loads() }, log = {})

        assertEquals(false, built, "a headless process built an audio device")
        audio.close()
    }

    private companion object {
        val ROOT: Path = Path.of("unused-by-stand-in-devices")
    }
}
