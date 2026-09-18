package dev.wildware.udea.render.audio

import de.fabmax.kool.modules.audio.AudioClip
import dev.wildware.udea.audio.AudioDevice
import dev.wildware.udea.audio.AudioLoadException
import dev.wildware.udea.audio.SoundHandle

/**
 * The [AudioDevice] that makes a noise: each loaded file is a Kool `AudioClip` (issue #221).
 *
 * ## Why it is here and not in `udea-audio`
 *
 * Spec section 3 keeps Kool in `udea-render`, and `UDEA-MG-002` bans `de.fabmax.kool:*` from
 * `udea-audio` because that module is designated headless. So `udea-audio` owns the drain, the
 * routing and the SPI, and this class is the only thing that turns a [SoundHandle] into a Kool
 * call. `AudioDevice.Silent` is still what `RenderMode.Headless` gets.
 *
 * ## What is common and what is a platform's
 *
 * This class is `commonMain`, and so is `AudioClip`. What differs per platform is how a clip is
 * *made*, which Kool 0.19.0 does with a different constructor on each: bytes and a format on the
 * desktop (`javax.sound.sampled.Clip` underneath), an `android.net.Uri` and a `Context` on Android
 * (`MediaPlayer`), a URL in a browser. That one step is [KoolClipLoader]; a target gains audio by
 * adding a loader and a factory, and nothing here changes. The desktop's is `DesktopClipLoader`.
 *
 * ## Volume, pitch and pan
 *
 * Kool 0.19.0's `AudioClip` has a volume and nothing else: no playback rate and no pan, on any of
 * its three backends. So [play] honours `volume` and cannot honour `pitch` or `pan`. It does not
 * approximate them - a pan faked as a volume drop is a quieter sound in the wrong place, not a
 * sound on the left. `CueAudio` still computes both, and a backend that has them gets them.
 *
 * ## Two things about Kool's clip this class exists to get right
 *
 * - A clip drops any `play()` within `minIntervalMs` (150ms by default) of the last one on that
 *   clip, silently. `CueAudio` already caps voices per cue per frame, and a second cap underneath
 *   it would discard cues that cap had admitted, so [load] sets it to zero.
 * - A clip's `volume` is applied to the *most recently started* voice of its pool. Set before
 *   `play()`, it lands on the previous voice - which may still be sounding - and the new one keeps
 *   whatever the clip was last set to. So [play] starts the voice and then sets its volume.
 *
 * ## Thread affinity
 *
 * The frame thread, as `AudioDevice` states. Nothing here is synchronised.
 */
internal class KoolAudioDevice(private val clips: KoolClipLoader) : AudioDevice {

    /** Slot `i` is the clip [SoundHandle] `i` names. */
    private val loaded = ArrayList<AudioClip>()

    /** Paths already loaded, so two cues naming one file share one clip and one pool. */
    private val slotByPath = HashMap<String, Int>()

    private var closed = false

    /**
     * Loads [path] as a Kool clip.
     *
     * @throws AudioLoadException when the file is missing, when Kool cannot decode it, or when the
     *   platform has no audio output to open a clip on - each with a message saying which, and the
     *   platform's own exception as the cause.
     */
    override fun load(path: String): SoundHandle {
        check(!closed) { "load('$path') on a closed KoolAudioDevice" }
        slotByPath[path]?.let { return SoundHandle(it) }
        val clip = clips.load(path)
        clip.minIntervalMs = 0F
        loaded += clip
        val slot = loaded.size - 1
        slotByPath[path] = slot
        return SoundHandle(slot)
    }

    /** Starts one voice of [sound] at [volume]. [pitch] and [pan] are not expressible; see the class KDoc. */
    override fun play(sound: SoundHandle, volume: Float, pitch: Float, pan: Float) {
        check(!closed) { "play($sound) on a closed KoolAudioDevice" }
        val clip = loaded[sound.slot]
        clip.play()
        clip.volume = volume
    }

    /**
     * Stops every clip's most recent voice and forgets every clip. Idempotent.
     *
     * That is all Kool 0.19.0's `AudioClip` offers: `stop()` reaches only the most recently started
     * voice of a clip's pool, so an older overlapping voice plays on to its end, and there is no
     * `close` or `dispose` at all, so the platform lines behind a clip are not released by this
     * call. Nothing here can do better without reaching past Kool's API.
     */
    override fun close() {
        if (closed) return
        closed = true
        loaded.forEach { it.stop() }
        loaded.clear()
        slotByPath.clear()
    }

    /** The Kool clip behind [sound]. For a test that reads Kool's own playback state. */
    internal fun clipAt(sound: SoundHandle): AudioClip = loaded[sound.slot]

    override fun toString(): String = "KoolAudioDevice(${loaded.size} clip(s), $clips)"
}

/**
 * Makes a Kool clip from a bundle-relative path: the one step Kool does differently per platform.
 *
 * @throws AudioLoadException for every failure, so [KoolAudioDevice.load] states one contract.
 */
internal fun interface KoolClipLoader {
    fun load(path: String): AudioClip
}
