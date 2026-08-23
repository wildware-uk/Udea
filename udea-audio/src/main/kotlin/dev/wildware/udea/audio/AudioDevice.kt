package dev.wildware.udea.audio

/**
 * The one thing this module cannot do for itself: turn a file into a noise.
 *
 * ## Why the backend is an interface and not LibGDX
 *
 * The old `SoundSystem` (`common/.../ecs/system/SoundSystem.kt`) was a Fleks `IntervalSystem`
 * with an empty `onTick`, whose real entry point was a `playSoundAtPosition` that read
 * `gameScreen.camera` off a file-level global and called `sound.soundAssets.random().play(...)`
 * on a live LibGDX handle held by an *asset value*. Three properties fell out of that and all
 * three are the reason this type exists:
 *
 * - a headless server ran the audio path, because nothing in the call chain could tell it was
 *   headless - it just happened to fail differently;
 * - `SoundCue` could not be read without a `Sound`, which is why `UDEA-MG-006` now forbids the
 *   asset model from resolving anything but plain data;
 * - and there was exactly one implementation, so "silent" was not a state the code had.
 *
 * Here it is: [Silent] is a complete implementation, it is what `RenderMode.Headless` gets, and
 * `CueAudio` cannot tell the difference. Nothing in this module names a GL type, a gdx backend or
 * `Gdx` itself, which is what keeps `udea-audio` a designated headless module (`UDEA-MG-002`).
 *
 * ## Thread affinity
 *
 * A device is touched from the thread that drives frames, which on a GL backend is the render
 * thread. [load] is called once during start-up and [play] once per audible cue; neither is
 * synchronised, for the same reason `CueQueue` is not.
 */
public interface AudioDevice {

    /**
     * Loads the sound file at [path] and returns the handle [play] takes.
     *
     * @param path a bundle-relative resource path, as authored on `SoundCue.sounds` - for
     *   instance `sounds/effects/melee_hit_1.ogg`.
     * @throws AudioLoadException when the file cannot be found or decoded. Loud rather than a
     *   silent substitution: a game that plays nothing because its files moved and a game that is
     *   deliberately silent are different states, and the old tree could not tell them apart.
     */
    public fun load(path: String): SoundHandle

    /**
     * Plays [sound] once.
     *
     * @param volume linear gain, already attenuated for distance. Always in `0f..1f`.
     * @param pitch playback rate multiplier, `1f` being the file as recorded.
     * @param pan `-1f` hard left, `0f` centre, `1f` hard right.
     */
    public fun play(sound: SoundHandle, volume: Float, pitch: Float, pan: Float)

    /** Releases every loaded sound. Calling [play] afterwards is the caller's mistake. */
    public fun close()

    /**
     * Loads nothing, plays nothing, and is what a process with no audio output uses.
     *
     * Not a fallback that a failure degrades into - it is selected at the composition root, by a
     * caller that knows it is headless. `CueAudio` still drains the cue queue against it, which is
     * the whole point: the queue stays bounded in CI and in an agent session, and the drain path
     * is exercised by every headless test rather than only by a run with speakers attached.
     */
    public object Silent : AudioDevice {

        /**
         * The handle every path loads to.
         *
         * One shared value rather than an ascending counter, so that a silent device allocates
         * nothing and holds nothing however many sounds a game declares.
         */
        public val HANDLE: SoundHandle = SoundHandle(0)

        override fun load(path: String): SoundHandle = HANDLE

        override fun play(sound: SoundHandle, volume: Float, pitch: Float, pan: Float): Unit = Unit

        override fun close(): Unit = Unit

        override fun toString(): String = "AudioDevice.Silent"
    }
}

/**
 * What an [AudioDevice] handed back for a loaded file.
 *
 * A value class over the device's own slot rather than the `Sound` itself, so that the routing
 * table `CueAudio` walks per frame is an `IntArray` and not an array of references - and so that
 * this module can hold the table without holding a LibGDX type.
 */
@JvmInline
public value class SoundHandle(public val slot: Int) {
    init {
        require(slot >= 0) { "a SoundHandle is a device slot, so it cannot be $slot" }
    }

    override fun toString(): String = "SoundHandle($slot)"
}

/** A sound file an [AudioDevice] could not load. Named, so the message says which file. */
public class AudioLoadException(
    /** The path that failed, exactly as it was authored. */
    public val path: String,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
