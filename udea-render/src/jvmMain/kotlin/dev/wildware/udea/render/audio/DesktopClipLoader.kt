package dev.wildware.udea.render.audio

import de.fabmax.kool.modules.audio.AudioClip
import de.fabmax.kool.modules.audio.AudioClipImpl
import dev.wildware.udea.audio.AudioDevice
import dev.wildware.udea.audio.AudioLoadException
import java.nio.file.Files
import java.nio.file.Path
import javax.sound.sampled.LineUnavailableException

/**
 * The desktop's audio device: Kool clips, loaded from files under [assetRoot].
 *
 * A `SoundCue` names a path relative to the asset root (`sounds/effects/melee_hit_1.ogg`), and the
 * packed bundle carries the cue records but not the audio bytes (`BundleReader` exposes no blobs),
 * so the files are read from the root directory itself. When blobs land in the bundle, this is the
 * parameter that changes.
 *
 * Every failure is an [AudioLoadException] from `load`, never a silent substitution: a process that
 * wants silence asks for `AudioDevice.Silent` by name.
 */
public fun koolAudioDevice(assetRoot: Path): AudioDevice = KoolAudioDevice(DesktopClipLoader(assetRoot))

/**
 * Kool 0.19.0's desktop clip: `AudioClipImpl(bytes, format)`, which decodes with Java Sound and
 * opens a `javax.sound.sampled.Clip` in its constructor. An `.ogg` is decoded by [OggToWav] first,
 * because Kool's own Vorbis decode frees memory it does not own and takes the JVM down.
 *
 * That constructor is where a machine with no audio output fails: `AudioSystem.getClip()` throws
 * `IllegalArgumentException` ("No line matching interface Clip ... is supported") when Java Sound
 * can see no mixer, and `Clip.open` throws `LineUnavailableException` when the device is held by
 * something else. Both become an [AudioLoadException] that says there is no output. A decode
 * failure - Kool's `IllegalStateException` for a format Java Sound cannot read, or [OggToWav]'s for
 * a stream stb cannot - becomes one that says so.
 */
internal class DesktopClipLoader(private val assetRoot: Path) : KoolClipLoader {

    override fun load(path: String): AudioClip {
        val file = assetRoot.resolve(path)
        if (!Files.isRegularFile(file)) {
            throw AudioLoadException(path, "no sound file at '$file' (the asset root is '$assetRoot')")
        }
        val format = path.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return try {
            // Kool's own Vorbis path crashes the JVM (see OggToWav), so an `.ogg` reaches Kool as
            // the WAV it decodes to, and Kool's clip opens that through Java Sound.
            val bytes = Files.readAllBytes(file)
            if (format == OGG) AudioClipImpl(OggToWav.convert(bytes), WAV) else AudioClipImpl(bytes, format)
        } catch (noLine: LineUnavailableException) {
            throw noOutput(path, noLine)
        } catch (noLine: IllegalArgumentException) {
            throw noOutput(path, noLine)
        } catch (undecodable: IllegalStateException) {
            throw AudioLoadException(path, "could not decode '$file' as '$format'", undecodable)
        }
    }

    private fun noOutput(path: String, cause: Exception) = AudioLoadException(
        path,
        "no audio output: Java Sound gave Kool no line to play '$path' on (${cause.message}). A " +
            "process that should be silent uses AudioDevice.Silent.",
        cause,
    )

    override fun toString(): String = "DesktopClipLoader($assetRoot)"

    private companion object {
        const val OGG = "ogg"
        const val WAV = "wav"
    }
}
