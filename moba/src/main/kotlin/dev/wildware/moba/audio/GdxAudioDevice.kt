package dev.wildware.moba.audio

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.audio.Sound
import dev.wildware.udea.audio.AudioDevice
import dev.wildware.udea.audio.AudioLoadException
import dev.wildware.udea.audio.SoundHandle

/**
 * The half of the audio path that names LibGDX, and the only one.
 *
 * ## Why this lives in the game and not in `udea-audio`
 *
 * `udea-audio` is a designated headless module (`ModuleGraphRules.HEADLESS_PROJECTS`), and
 * `UDEA-MG-002-BYTECODE` bans `com/badlogic/gdx/Gdx` there by exact name - it is the static handle
 * to the application, the window and the audio device, and a module that names it dies on a
 * machine with no display. That ban is right and this class is what it forces: the routing, the
 * attenuation, the voice cap and the drain are engine code with no backend in them, and the twenty
 * lines that turn a path into a noise are the game's.
 *
 * It is also the honest place for it *today*, because what a `Sound` is loaded **from** is a
 * `moba` question and not an engine one - see [handleFor].
 *
 * ## Where the bytes come from, stated plainly
 *
 * `.udeapak` does not carry them. `BundleWriter` has a `blobs` section and `Reachability` computes
 * the `ResPath` set for it, but `AssetPackCli` calls `BundleContent.reachable(assets, atlas)` with
 * no blobs and `BundleReader` exposes no way to read one back - so the packed bundle holds the
 * `SoundCue` *records* (which files, how loud) and none of the audio. Closing that is a change to
 * `udea-assets` and `udea-assets-compiler`; until it lands, this reads the files the way the rest
 * of the world does, through [Gdx.files]. `internal` covers both cases that matter: a file
 * relative to the working directory, which is what `:moba:runClient` has, and a classpath resource,
 * which is what a packaged run would have. A file in neither place is an [AudioLoadException] that
 * names the path - not a silent substitution, because "the game is quiet" and "the game's audio
 * moved" must not look the same (standards section 1).
 */
public class GdxAudioDevice(
    /**
     * Prefixed to every `ResPath` before it is handed to [Gdx.files].
     *
     * A `SoundCue` names `sounds/effects/melee_hit_1.ogg`, which is relative to the *asset root*;
     * the asset root is `moba/assets` and is not itself packaged, so the path needs the root's own
     * name in front of it to be found from the working directory. When blobs land in the bundle
     * this parameter is what disappears.
     */
    private val assetRoot: String = DEFAULT_ASSET_ROOT,
) : AudioDevice {

    private val sounds = ArrayList<Sound>()

    /** Paths already loaded, so two cues naming one file share a `Sound`. */
    private val bySlot = HashMap<String, Int>()

    /** How many distinct files are open. */
    public val loaded: Int get() = sounds.size

    override fun load(path: String): SoundHandle {
        bySlot[path]?.let { return SoundHandle(it) }
        val audio = checkNotNull(Gdx.audio) {
            "Gdx.audio is null, so no LibGDX application has been started. A process with no " +
                "audio output uses AudioDevice.Silent - see MobaAudio.forHost - rather than " +
                "constructing this and hoping."
        }
        val file = handleFor(path)
        if (!file.exists()) {
            throw AudioLoadException(
                path,
                "no sound file at '${file.path()}'. It was looked for relative to the working " +
                    "directory (${System.getProperty("user.dir")}) and on the classpath. The " +
                    "authored path comes from a `soundCue` in moba/assets/sounds; the files " +
                    "themselves are under moba/assets/$path and are not packed into " +
                    "assets.udeapak - see GdxAudioDevice for why.",
            )
        }
        val sound = try {
            audio.newSound(file)
        } catch (failure: RuntimeException) {
            throw AudioLoadException(path, "LibGDX could not decode '${file.path()}'", failure)
        }
        sounds += sound
        val slot = sounds.size - 1
        bySlot[path] = slot
        return SoundHandle(slot)
    }

    override fun play(sound: SoundHandle, volume: Float, pitch: Float, pan: Float) {
        // `setPan` on a fresh id rather than `play(volume)` then `setPan(id, pan, volume)`: the
        // three-argument overload is the one gdx implements atomically on every backend, and the
        // two-call form has an audible frame of centred playback on OpenAL.
        sounds[sound.slot].play(volume, pitch, pan)
    }

    override fun close() {
        sounds.forEach { it.dispose() }
        sounds.clear()
        bySlot.clear()
    }

    /** `assets/sounds/effects/melee_hit_1.ogg` for a `ResPath` of `sounds/effects/melee_hit_1.ogg`. */
    private fun handleFor(path: String) =
        Gdx.files.internal(if (assetRoot.isEmpty()) path else "$assetRoot/$path")

    override fun toString(): String = "GdxAudioDevice($loaded sound(s) from $assetRoot/)"

    public companion object {
        /**
         * The asset root's directory name, as `udea { assetRoots.from("assets") }` names it in
         * `moba/build.gradle.kts`. Written here because a `Gdx.files` lookup cannot read a Gradle
         * extension; `MobaAudioTest` asserts the two agree by finding the files through it.
         */
        public const val DEFAULT_ASSET_ROOT: String = "assets"
    }
}
