package dev.wildware.udea.render.audio

import java.util.Collections
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.CopyOnWriteArrayList
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.Control
import javax.sound.sampled.DataLine
import javax.sound.sampled.FloatControl
import javax.sound.sampled.Line
import javax.sound.sampled.LineEvent
import javax.sound.sampled.LineListener
import javax.sound.sampled.LineUnavailableException
import javax.sound.sampled.Mixer
import javax.sound.sampled.spi.MixerProvider
import kotlin.math.abs

/**
 * A Java Sound mixer that stands where the sound card would, and writes down what it was given.
 *
 * ## Why it exists, and exactly what it replaces
 *
 * Kool 0.19.0 plays an `AudioClip` on the desktop through `javax.sound.sampled.Clip`:
 * `AudioClipImpl`'s pooled wrapper calls `AudioSystem.getClip()`, opens it on the PCM that
 * `STBVorbis` decoded, sets `FloatControl.Type.MASTER_GAIN`, and calls `start()`. On the box this
 * was written on, Java Sound can see no mixer at all - `/dev/snd` is `root:audio` and the build
 * user is not in `audio` - so `getClip()` throws before Kool gets a line (BRIEF-221 section 5
 * carries the executed transcript).
 *
 * So this replaces **the operating system's line and nothing above it**. Kool's clip class, Kool's
 * decoder, Kool's gain arithmetic, Kool's clip pool and Kool's stop listener all run for real; what
 * a test reads back is what reached the line - the decoded frames, the gain, the start - which is
 * the boundary a real sound card sits at. It does not make a noise and nothing in a test claims
 * one was heard.
 *
 * It is found by the JDK's own service lookup (`META-INF/services`) and selected by
 * [SELECT_PROPERTY], so on a machine that *does* have a sound card a test still gets this mixer
 * rather than playing through the speakers, and the assertions stay about one known subject.
 *
 * ## Position is wall-clock, and that is the device's business
 *
 * A running line's frame position advances with real time from `start()`, and the line fires
 * `LineEvent.Type.STOP` when it has played every frame - which is what a hardware line does, and
 * what Kool's `isEnded` listens for. This is presentation-side test code; nothing here is on a
 * simulation path.
 */
class CaptureMixerProvider : MixerProvider() {
    override fun getMixerInfo(): Array<Mixer.Info> = arrayOf(CaptureMixer.INFO)

    override fun getMixer(info: Mixer.Info?): Mixer {
        require(info == null || info == CaptureMixer.INFO) { "no mixer $info here" }
        return CaptureMixer
    }

    companion object {
        /**
         * The Java Sound property that names the default provider and mixer for `Clip` lines.
         *
         * `AudioSystem` reads it on every lookup, so setting it before the first `getClip()` is
         * enough; [select] does that.
         */
        const val SELECT_PROPERTY: String = "javax.sound.sampled.Clip"

        /** Makes this mixer the default for `Clip`, and forgets every line a previous test saw. */
        fun select() {
            System.setProperty(SELECT_PROPERTY, "${CaptureMixerProvider::class.java.name}#${CaptureMixer.NAME}")
            CaptureMixer.reset()
        }
    }
}

/** The one mixer [CaptureMixerProvider] offers. An object, so a test can read what it saw. */
object CaptureMixer : Mixer {

    const val NAME: String = "Udea capture mixer"

    val INFO: Mixer.Info = object : Mixer.Info(NAME, "Udea test", "records what reached the line", "1") {}

    /** Every line Kool opened, in order. */
    val lines: MutableList<CaptureClip> = CopyOnWriteArrayList()

    /**
     * When set, opening a line fails with this message, as `DirectAudioDevice` does when ALSA has
     * the device held by another process.
     */
    @Volatile
    var refuseOpen: String? = null

    fun reset() {
        lines.clear()
        refuseOpen = null
    }

    private val clipInfo = DataLine.Info(Clip::class.java, null as AudioFormat?)

    override fun getMixerInfo(): Mixer.Info = INFO
    override fun getSourceLineInfo(): Array<Line.Info> = arrayOf(clipInfo)
    override fun getTargetLineInfo(): Array<Line.Info> = emptyArray()
    override fun getSourceLineInfo(info: Line.Info): Array<Line.Info> =
        if (isLineSupported(info)) arrayOf(clipInfo) else emptyArray()
    override fun getTargetLineInfo(info: Line.Info): Array<Line.Info> = emptyArray()
    override fun isLineSupported(info: Line.Info): Boolean = info.lineClass.isAssignableFrom(Clip::class.java)

    override fun getLine(info: Line.Info): Line {
        require(isLineSupported(info)) { "the capture mixer only offers Clip lines, not $info" }
        return CaptureClip(clipInfo)
    }

    override fun getMaxLines(info: Line.Info): Int = AudioSystem.NOT_SPECIFIED
    override fun getSourceLines(): Array<Line> = lines.filter { it.isOpen }.toTypedArray()
    override fun getTargetLines(): Array<Line> = emptyArray()
    override fun synchronize(lines: Array<out Line>?, maintainSync: Boolean): Unit =
        throw IllegalArgumentException("the capture mixer does not synchronise lines")
    override fun unsynchronize(lines: Array<out Line>?): Unit =
        throw IllegalArgumentException("the capture mixer does not synchronise lines")
    override fun isSynchronizationSupported(lines: Array<out Line>?, maintainSync: Boolean): Boolean = false

    override fun getLineInfo(): Line.Info = Line.Info(Mixer::class.java)
    override fun open() = Unit
    override fun close() = Unit
    override fun isOpen(): Boolean = true
    override fun getControls(): Array<Control> = emptyArray()
    override fun isControlSupported(control: Control.Type?): Boolean = false
    override fun getControl(control: Control.Type?): Control =
        throw IllegalArgumentException("the capture mixer has no $control control")
    override fun addLineListener(listener: LineListener?) = Unit
    override fun removeLineListener(listener: LineListener?) = Unit
}

/** One line Kool opened: what it was given, and what it was told to do. */
class CaptureClip(private val info: DataLine.Info) : Clip {

    private val listeners: MutableList<LineListener> = CopyOnWriteArrayList()

    /** Kool sets its volume here, in decibels. */
    val gain: FloatControl = object : FloatControl(Type.MASTER_GAIN, -80F, 6.0206F, 0.01F, -1, 0F, "dB") {}

    @Volatile private var opened = false
    @Volatile private var running = false
    private var audioFormat: AudioFormat? = null
    private var data: ByteArray = ByteArray(0)
    private var frames: Long = 0L
    private var positionAtStart: Long = 0L
    private var startedAtNanos: Long = 0L
    private var stoppedAt: Long = 0L
    private var endTimer: Timer? = null

    /** How many times `start()` was called. */
    @Volatile var starts: Int = 0
        private set

    /** How many times this line reported `STOP`, whether it ran out or was stopped. */
    @Volatile var stops: Int = 0
        private set

    /** Whether `close()` has been called. */
    @Volatile var closed: Boolean = false
        private set

    /** The largest absolute 16-bit sample Kool handed over. Zero means it opened on silence. */
    var peak: Int = 0
        private set

    /** Every event fired, in order - `Open`, `Start`, `Stop`, `Close`. */
    val events: MutableList<LineEvent.Type> = Collections.synchronizedList(ArrayList())

    override fun open(stream: AudioInputStream) {
        val bytes = stream.readAllBytes()
        open(stream.format, bytes, 0, bytes.size)
    }

    override fun open(format: AudioFormat, data: ByteArray, offset: Int, bufferSize: Int) {
        CaptureMixer.refuseOpen?.let { throw LineUnavailableException(it) }
        check(!opened) { "this line is already open" }
        audioFormat = format
        this.data = data.copyOfRange(offset, offset + bufferSize)
        frames = (bufferSize / format.frameSize).toLong()
        peak = peakOf(format, this.data)
        opened = true
        CaptureMixer.lines += this
        fire(LineEvent.Type.OPEN)
    }

    override fun open() {
        throw IllegalStateException("a Clip is opened on its data; Kool passes an AudioInputStream")
    }

    override fun start() {
        check(opened) { "start() on a line that is not open" }
        if (running) return
        starts++
        positionAtStart = stoppedAt
        startedAtNanos = System.nanoTime()
        running = true
        fire(LineEvent.Type.START)
        val remainingMs = ((frames - positionAtStart) * 1000L / frameRate()).coerceAtLeast(0L)
        endTimer = Timer("capture-clip-end", true).also { timer ->
            timer.schedule(
                object : TimerTask() {
                    override fun run() = finish(frames)
                },
                remainingMs,
            )
        }
    }

    override fun stop() {
        if (running) finish(currentFrame())
    }

    @Synchronized
    private fun finish(at: Long) {
        if (!running) return
        endTimer?.cancel()
        endTimer = null
        stoppedAt = at
        running = false
        stops++
        fire(LineEvent.Type.STOP)
    }

    override fun close() {
        stop()
        if (opened) {
            opened = false
            closed = true
            fire(LineEvent.Type.CLOSE)
        }
    }

    private fun frameRate(): Long = (audioFormat?.frameRate ?: 1F).toLong().coerceAtLeast(1L)

    private fun currentFrame(): Long =
        if (!running) {
            stoppedAt
        } else {
            val elapsed = (System.nanoTime() - startedAtNanos) * frameRate() / 1_000_000_000L
            (positionAtStart + elapsed).coerceAtMost(frames)
        }

    override fun getFrameLength(): Int = frames.toInt()
    override fun getMicrosecondLength(): Long = frames * 1_000_000L / frameRate()
    override fun getFramePosition(): Int = currentFrame().toInt()
    override fun getLongFramePosition(): Long = currentFrame()
    override fun getMicrosecondPosition(): Long = currentFrame() * 1_000_000L / frameRate()

    override fun setFramePosition(frames: Int) {
        check(!running) { "the capture clip repositions only while stopped" }
        stoppedAt = frames.toLong().coerceIn(0L, this.frames)
    }

    override fun setMicrosecondPosition(microseconds: Long) =
        setFramePosition((microseconds * frameRate() / 1_000_000L).toInt())

    override fun setLoopPoints(start: Int, end: Int): Unit =
        throw IllegalArgumentException("the capture clip does not loop; KoolAudioDevice never asks it to")

    override fun loop(count: Int): Unit =
        throw IllegalArgumentException("the capture clip does not loop; KoolAudioDevice never asks it to")

    override fun drain() = Unit
    override fun flush() = Unit
    override fun isRunning(): Boolean = running
    override fun isActive(): Boolean = running
    override fun getFormat(): AudioFormat = checkNotNull(audioFormat) { "the line has no format until it is opened" }
    override fun getBufferSize(): Int = data.size
    override fun available(): Int = 0
    override fun getLevel(): Float = AudioSystem.NOT_SPECIFIED.toFloat()

    override fun getLineInfo(): Line.Info = info
    override fun isOpen(): Boolean = opened
    override fun getControls(): Array<Control> = arrayOf(gain)
    override fun isControlSupported(control: Control.Type?): Boolean = control == FloatControl.Type.MASTER_GAIN
    override fun getControl(control: Control.Type?): Control {
        require(control == FloatControl.Type.MASTER_GAIN) { "the capture clip has no $control control" }
        return gain
    }

    override fun addLineListener(listener: LineListener) {
        listeners += listener
    }

    override fun removeLineListener(listener: LineListener) {
        listeners -= listener
    }

    private fun fire(type: LineEvent.Type) {
        events += type
        val event = LineEvent(this, type, currentFrame())
        listeners.forEach { it.update(event) }
    }

    override fun toString(): String =
        "CaptureClip(${audioFormat ?: "unopened"}, frames=$frames, peak=$peak, gain=${gain.value}dB, " +
            "starts=$starts, stops=$stops)"

    private companion object {
        /** The loudest sample in 16-bit PCM, either byte order. Other encodings report zero. */
        fun peakOf(format: AudioFormat, bytes: ByteArray): Int {
            if (format.encoding != AudioFormat.Encoding.PCM_SIGNED || format.sampleSizeInBits != 16) return 0
            var peak = 0
            var i = 0
            while (i + 1 < bytes.size) {
                val lo = if (format.isBigEndian) bytes[i + 1] else bytes[i]
                val hi = if (format.isBigEndian) bytes[i] else bytes[i + 1]
                val sample = ((hi.toInt() shl 8) or (lo.toInt() and 0xFF)).toShort().toInt()
                peak = maxOf(peak, abs(sample))
                i += 2
            }
            return peak
        }
    }
}
