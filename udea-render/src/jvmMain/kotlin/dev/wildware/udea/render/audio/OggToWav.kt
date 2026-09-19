package dev.wildware.udea.render.audio

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteOrder
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import org.lwjgl.stb.STBVorbis
import org.lwjgl.stb.STBVorbisInfo
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil

/**
 * Decodes an Ogg Vorbis file to a 16-bit PCM WAV, so that Kool's desktop clip never decodes Vorbis.
 *
 * ## Why this exists: Kool 0.19.0 crashes the JVM on an `.ogg`
 *
 * `AudioClipImpl$ClipWrapper.loadVorbis` calls `STBVorbis.stb_vorbis_decode_memory` and hands the
 * buffer it returns to `MemoryUtil.memFree`. That buffer is not the LWJGL allocator's: `memFree`
 * is only safe on it when LWJGL's allocator is the C library's, and Kool declares
 * `lwjgl-jemalloc`, which makes jemalloc the default. The result is a `SIGSEGV` in
 * `libjemalloc.so` - on LWJGL 3.4.3, which is what `udea-render` resolves, inside `memFree` itself;
 * on 3.3.6, which Kool declares, a little later, once the heap is already corrupt. Run with
 * `-Dorg.lwjgl.system.allocator=system` it does not crash. BRIEF-221 carries both runs. Every sound
 * `moba` ships is an `.ogg`.
 *
 * Switching the process to the system allocator was rejected: it is a global that has to be set
 * before LWJGL's first allocation - which the renderer makes long before audio loads - and it would
 * change the allocator under all of Kool's rendering to route around one audio call.
 *
 * ## How this avoids the same mistake
 *
 * It never frees anything it did not allocate. `stb_vorbis_open_memory` and `stb_vorbis_close`
 * pair inside stb, the sample buffer comes from and returns to `MemoryUtil`, and the file's bytes
 * are copied into a `MemoryUtil` buffer that is freed by the same. Kool is then handed a WAV, which
 * its clip opens through Java Sound with no LWJGL call at all.
 */
internal object OggToWav {

    /**
     * The WAV for [ogg].
     *
     * @throws IllegalStateException when stb cannot open or read the stream; the message carries
     *   stb's error code, as Kool's own decoder does.
     */
    fun convert(ogg: ByteArray): ByteArray {
        val encoded = MemoryUtil.memAlloc(ogg.size)
        try {
            encoded.put(ogg).flip()
            return MemoryStack.stackPush().use { stack ->
                val error = stack.mallocInt(1)
                val decoder = STBVorbis.stb_vorbis_open_memory(encoded, error, null)
                check(decoder != MemoryUtil.NULL) { "stb_vorbis could not open the stream (error ${error.get(0)})" }
                try {
                    wavOf(decoder)
                } finally {
                    STBVorbis.stb_vorbis_close(decoder)
                }
            }
        } finally {
            MemoryUtil.memFree(encoded)
        }
    }

    private fun wavOf(decoder: Long): ByteArray {
        val (channels, sampleRate) = MemoryStack.stackPush().use { stack ->
            val info = STBVorbisInfo.malloc(stack)
            STBVorbis.stb_vorbis_get_info(decoder, info)
            info.channels() to info.sample_rate()
        }
        val frames = STBVorbis.stb_vorbis_stream_length_in_samples(decoder)
        check(channels > 0 && sampleRate > 0 && frames > 0) {
            "stb_vorbis read no audio: $channels channel(s) at $sampleRate Hz, $frames frame(s)"
        }
        val samples = MemoryUtil.memAllocShort(frames * channels)
        try {
            val read = STBVorbis.stb_vorbis_get_samples_short_interleaved(decoder, channels, samples)
            val pcm = ByteArray(read * channels * Short.SIZE_BYTES)
            // Raw bytes in the platform's order, so the format says that order rather than assuming one.
            MemoryUtil.memByteBuffer(samples).get(pcm)
            val bigEndian = ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN
            val format = AudioFormat(sampleRate.toFloat(), Short.SIZE_BITS, channels, true, bigEndian)
            val wav = ByteArrayOutputStream(pcm.size + WAV_HEADER_BYTES)
            AudioSystem.write(
                AudioInputStream(ByteArrayInputStream(pcm), format, read.toLong()),
                AudioFileFormat.Type.WAVE,
                wav,
            )
            return wav.toByteArray()
        } finally {
            MemoryUtil.memFree(samples)
        }
    }

    /** A canonical RIFF/WAVE header, so the output buffer is sized once. */
    private const val WAV_HEADER_BYTES = 44
}
