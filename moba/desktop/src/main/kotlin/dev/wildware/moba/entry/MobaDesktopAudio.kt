package dev.wildware.moba.entry

import dev.wildware.moba.audio.MobaAudio
import dev.wildware.udea.audio.AudioDevice
import dev.wildware.udea.audio.AudioLoadException
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.render.audio.koolAudioDevice
import java.nio.file.Path

/**
 * The sound a desktop `moba` process makes, and the one place that decides it will make none.
 *
 * ## Why this is the launcher's and not the game's
 *
 * The device that makes a noise is `udea-render`'s `koolAudioDevice` (issue #221), which is
 * `jvmMain`: it reads the `.ogg` files off disk under the asset root, because the packed bundle
 * carries the cue records but not the audio bytes. `:moba:game` compiles for Android as well and
 * cannot name it, so the choice lives here, next to the other things only a desktop process has.
 *
 * ## Why silence is chosen here, by name
 *
 * `koolAudioDevice` never falls back on its own: when there is no output, or a file is missing or
 * will not decode, `load` throws [AudioLoadException], and issue #221's ruling is that picking
 * [AudioDevice.Silent] instead belongs to the game's root. This is that root. The fallback is
 * reported once on [log] with the device's own message, so a machine with no output (this
 * repository's build box is one) and a sound file that went missing both say so rather than
 * playing nothing without a word. The cue queue is drained either way, which is the part that
 * must never stop.
 */
public object MobaDesktopAudio {

    /**
     * Audio for [host]: silent in [RenderMode.Headless], otherwise Kool's device over [assetRoot],
     * falling back to silent - and saying why - when that device cannot load the game's sounds.
     *
     * Call it on the render thread, as every entry point does: a Kool clip opens its output line
     * as it is built.
     *
     * @param device builds the real device. A parameter so a test can stand one in; a windowed
     *   process always gets `koolAudioDevice`.
     */
    public fun forHost(
        host: GameHost,
        assetRoot: Path? = System.getProperty(MobaLaunch.ASSET_ROOT_PROPERTY)?.let { Path.of(it) },
        device: (Path) -> AudioDevice = ::koolAudioDevice,
        log: (String) -> Unit = { System.err.println(it) },
    ): MobaAudio {
        if (host.mode == RenderMode.Headless) return MobaAudio.silent(host)
        if (assetRoot == null) {
            log("[moba.audio] silent: -D${MobaLaunch.ASSET_ROOT_PROPERTY} is not set, so there is no directory to read sounds from")
            return MobaAudio.silent(host)
        }
        val real = device(assetRoot)
        return try {
            MobaAudio.of(host, real)
        } catch (failed: AudioLoadException) {
            real.close()
            log(
                "[moba.audio] silent: '${failed.path}' would not load (${failed.message}" +
                    (failed.cause?.let { "; ${it::class.simpleName}: ${it.message}" } ?: "") +
                    "), so this process plays through AudioDevice.Silent",
            )
            MobaAudio.silent(host)
        }
    }
}
