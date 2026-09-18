package dev.wildware.udea.render.audio

import dev.wildware.udea.assets.AssetId
import dev.wildware.udea.assets.ResPath
import dev.wildware.udea.assets.SoundCue
import dev.wildware.udea.audio.AudioBindings
import dev.wildware.udea.audio.AudioDevice
import dev.wildware.udea.audio.AudioLoadException
import dev.wildware.udea.audio.CueAudio
import dev.wildware.udea.audio.CueSound
import dev.wildware.udea.core.Cue
import dev.wildware.udea.core.CueId
import dev.wildware.udea.core.CueQueue
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.identity.NetId
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.sound.sampled.LineEvent
import javax.sound.sampled.LineUnavailableException
import kotlin.math.log10
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The Kool-backed [AudioDevice], driven through Kool 0.19.0's real desktop `AudioClip` down to the
 * Java Sound line, with [CaptureMixer] standing where the sound card would.
 *
 * Every assertion about playback reads one of two things: Kool's own `AudioClip` state
 * (`isEnded`, `currentTime`, `duration`) or what arrived at the line Kool opened (decoded frames,
 * the gain Kool set, the `start()` it called). Nothing reads a flag this module sets about itself,
 * so swapping [AudioDevice.Silent] in, or making `play` a no-op, turns these red - BRIEF-221 carries
 * both runs.
 *
 * The fixture is `audio/tone_440hz_500ms.ogg`, generated for this test rather than taken from
 * `moba` (whose recordings have no recorded provenance, `docs/art-assets.md`):
 *
 *     ffmpeg -f lavfi -i "sine=frequency=440:sample_rate=44100:duration=0.5" -ac 1 \
 *       -c:a libvorbis -q:a 2 -fflags +bitexact -map_metadata -1 tone_440hz_500ms.ogg
 */
class KoolAudioDeviceTest {

    private val tone = "audio/tone_440hz_500ms.ogg"
    private val toneSeconds = 0.5F
    private val toneFrames = 22_050

    /** The directory `audio/` sits in: this test source set's resource root. */
    private val resourceRoot: Path =
        Paths.get(checkNotNull(javaClass.getResource("/$tone")) { "fixture $tone is missing" }.toURI())
            .parent.parent

    private lateinit var device: KoolAudioDevice

    @BeforeTest
    fun selectCaptureMixer() {
        CaptureMixerProvider.select()
        device = assertIs<KoolAudioDevice>(koolAudioDevice(resourceRoot))
    }

    @AfterTest
    fun release() {
        device.close()
    }

    @Test
    fun `a drained cue reaches a line Kool opened and Kool reports it playing and then ended`() {
        val hit = CueId(7)
        val bindings = AudioBindings.of(
            listOf(CueSound.load(hit, SoundCue(AssetId("tone"), listOf(ResPath(tone)), volume = 0.5F), device)),
        )
        val clip = device.clipAt(bindings[hit]!!.handleAt(0))
        transcript("loaded '$tone': Kool AudioClip duration=${clip.duration}s isEnded=${clip.isEnded}")
        assertEquals(toneSeconds, clip.duration, 0.01F, "Kool's clip is the decoded tone's length")

        val line = CaptureMixer.lines.single()
        transcript("line Kool opened: format=${line.format} frames=${line.frameLength} peak=${line.peak}")
        assertEquals(toneFrames, line.frameLength, "Kool decoded the whole file onto the line")
        // ffmpeg's `sine` source is an eighth of full scale, so 4096 give or take the codec.
        assertTrue(line.peak in 3_500..5_000, "Kool opened the line on the tone, not on silence: peak ${line.peak}")
        assertEquals(0, line.starts, "loading opens a line; it does not start one")

        val queue = CueQueue()
        queue.emit(Cue(hit, Tick(120L), NetId.NONE))
        val audio = CueAudio(device, bindings, seed = 1L)
        assertEquals(1, audio.drain(queue))
        assertEquals(1L, audio.played)

        transcript(
            "after drain: Kool isEnded=${clip.isEnded} currentTime=${clip.currentTime}s " +
                "line starts=${line.starts} gain=${line.gain.value}dB",
        )
        assertEquals(1, line.starts, "the drained cue started Kool's line")
        assertFalse(clip.isEnded, "Kool reports the clip playing")
        assertEquals(decibels(0.5F), line.gain.value, 0.01F, "Kool set the cue's volume on the line")

        Thread.sleep(150L)
        val advanced = clip.currentTime
        transcript("150ms later: Kool isEnded=${clip.isEnded} currentTime=${advanced}s")
        assertTrue(advanced > 0.05F, "Kool's playback position advanced: $advanced s")

        awaitEnded(clip)
        transcript("finished: Kool isEnded=${clip.isEnded} currentTime=${clip.currentTime}s line events=${line.events}")
        assertEquals(listOf(LineEvent.Type.OPEN, LineEvent.Type.START, LineEvent.Type.STOP), line.events.toList())
    }

    @Test
    fun `the same sound twice at once takes a second line rather than restarting the first`() {
        val sound = device.load(tone)
        val clip = device.clipAt(sound)
        device.play(sound, volume = 0.5F, pitch = 1F, pan = 0F)
        device.play(sound, volume = 0.25F, pitch = 1F, pan = 0F)

        val lines = CaptureMixer.lines.toList()
        assertEquals(2, lines.size, "Kool pooled a second line for the overlapping play")
        assertEquals(listOf(1, 1), lines.map { it.starts }, "each line started once; neither was restarted")
        assertEquals(decibels(0.5F), lines[0].gain.value, 0.01F, "the first play kept its own volume")
        assertEquals(decibels(0.25F), lines[1].gain.value, 0.01F, "the second play got its own volume")
        assertFalse(clip.isEnded)
    }

    @Test
    fun `loading one path twice shares the clip`() {
        assertEquals(device.load(tone), device.load(tone))
        assertEquals(1, CaptureMixer.lines.size, "one file, one Kool clip, one line")
    }

    @Test
    fun `volume zero is the quietest gain the line has, not an error`() {
        val sound = device.load(tone)
        device.play(sound, volume = 0F, pitch = 1F, pan = 0F)
        val line = CaptureMixer.lines.single()
        assertEquals(1, line.starts)
        assertTrue(line.gain.value <= -79F, "gain at volume 0 was ${line.gain.value}dB")
    }

    @Test
    fun `pitch and pan at their extremes still play, at the volume asked for`() {
        val sound = device.load(tone)
        device.play(sound, volume = 1F, pitch = CueAudio.MAX_PITCH, pan = -1F)
        device.play(sound, volume = 1F, pitch = CueAudio.MIN_PITCH, pan = 1F)
        val lines = CaptureMixer.lines.toList()
        assertEquals(listOf(1, 1), lines.map { it.starts })
        lines.forEach { assertEquals(0F, it.gain.value, 0.01F) }
    }

    @Test
    fun `a file that is not there is an AudioLoadException naming the path`() {
        val failure = assertFailsWith<AudioLoadException> { device.load("audio/nope.ogg") }
        assertEquals("audio/nope.ogg", failure.path)
        assertContains(failure.message.orEmpty(), resourceRoot.resolve("audio/nope.ogg").toString())
        assertTrue(CaptureMixer.lines.isEmpty(), "nothing was opened for a file that does not exist")
    }

    @Test
    fun `a file Kool cannot decode is an AudioLoadException, whichever decoder refused it`() {
        val dir = Files.createTempDirectory("udea-audio")
        Files.write(dir.resolve("noise.ogg"), ByteArray(64) { it.toByte() })
        Files.write(dir.resolve("noise.wav"), ByteArray(64) { it.toByte() })
        val broken = koolAudioDevice(dir)
        try {
            for (path in listOf("noise.ogg", "noise.wav")) {
                val failure = assertFailsWith<AudioLoadException> { broken.load(path) }
                assertEquals(path, failure.path)
                assertContains(failure.message.orEmpty(), "could not decode")
                assertIs<IllegalStateException>(failure.cause, "Kool's own decode failure is kept as the cause")
            }
        } finally {
            broken.close()
        }
    }

    @Test
    fun `a line the system will not open is an AudioLoadException that says there is no output`() {
        CaptureMixer.refuseOpen = "Device or resource busy"
        val failure = assertFailsWith<AudioLoadException> { device.load(tone) }
        assertEquals(tone, failure.path)
        assertContains(failure.message.orEmpty(), "no audio output")
        assertIs<LineUnavailableException>(failure.cause, "Java Sound's refusal is kept as the cause")
    }

    @Test
    fun `close stops a sound that is playing and refuses to play afterwards`() {
        val sound = device.load(tone)
        val clip = device.clipAt(sound)
        device.play(sound, volume = 1F, pitch = 1F, pan = 0F)
        val line = CaptureMixer.lines.single()
        assertTrue(line.isRunning)

        device.close()
        assertFalse(line.isRunning, "close stopped the line Kool was playing on")
        assertTrue(clip.isEnded, "Kool saw the stop")
        assertFailsWith<IllegalStateException> { device.play(sound, volume = 1F, pitch = 1F, pan = 0F) }
        assertEquals(1, line.starts, "nothing was started after close")
    }

    private fun decibels(volume: Float): Float = 20F * log10(volume)

    private fun awaitEnded(clip: de.fabmax.kool.modules.audio.AudioClip) {
        val deadline = System.nanoTime() + 3_000_000_000L
        while (!clip.isEnded) {
            check(System.nanoTime() < deadline) { "Kool never reported the clip ended; at ${clip.currentTime}s" }
            Thread.sleep(20L)
        }
    }

    /** One line of the desktop-run transcript BRIEF-221 splices from this test's report. */
    private fun transcript(line: String) = println("[kool-audio] $line")
}
