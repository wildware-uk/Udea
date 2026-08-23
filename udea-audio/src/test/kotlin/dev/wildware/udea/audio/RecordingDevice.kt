package dev.wildware.udea.audio

/**
 * An [AudioDevice] that writes down what it was asked to play instead of playing it.
 *
 * The mixer's whole observable behaviour is the argument list it hands a device, so this is what
 * every assertion in this module reads. It is not a mock of an interface with one implementation:
 * [AudioDevice.Silent] is the second, it ships, and it is what a headless process actually uses.
 */
internal class RecordingDevice : AudioDevice {

    /** Every path handed to [load], in call order. */
    val loaded = mutableListOf<String>()

    /** Every [play] call, in order. */
    val plays = mutableListOf<Play>()

    var closed: Boolean = false
        private set

    /** One playback request, as the mixer computed it. */
    data class Play(val sound: SoundHandle, val volume: Float, val pitch: Float, val pan: Float)

    override fun load(path: String): SoundHandle {
        loaded += path
        return SoundHandle(loaded.size - 1)
    }

    override fun play(sound: SoundHandle, volume: Float, pitch: Float, pan: Float) {
        plays += Play(sound, volume, pitch, pan)
    }

    override fun close() {
        closed = true
    }
}
