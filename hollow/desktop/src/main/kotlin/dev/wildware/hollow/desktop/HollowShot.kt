package dev.wildware.hollow.desktop

import dev.wildware.hollow.HollowGame
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.render.capture.CaptureRequest
import dev.wildware.udea.render.capture.capture
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * `sh gradlew :hollow:desktop:runShot`: boots Offscreen on the clearing, waits until the picture is
 * whole, and writes it as a PNG to `-Dudea.shot.out` (issue #249).
 *
 * "Whole" is two consecutive frames that are byte-identical after at least [MIN_FRAMES]: Kool decodes
 * a glTF texture on its loader threads and draws a mesh only once its texture has pixels, so the
 * first frames can lack the ground. The clearing does not move, so a frame that stops changing is
 * the finished one.
 *
 * Exits non-zero when nothing settles within [FRAME_BUDGET] frames, so a shot that captured a
 * half-drawn frame cannot look like a green run.
 */
public object HollowShot {

    private const val OUTPUT_PROPERTY: String = "udea.shot.out"

    @JvmStatic
    public fun main(args: Array<String>) {
        val out = Path.of(System.getProperty(OUTPUT_PROPERTY) ?: "build/reports/udea/clearing.png")
        val started = HollowLaunch.start(RenderMode.Offscreen)
        val written = try {
            HollowGame.seed(started.host)
            started.backend.drive(started.host)
            val slot = checkNotNull(started.backend.pipeline?.capture) { "the pipeline has no capture slot" }
            var previous = slot.capture(CaptureRequest())
            var frames = 1
            var settled: ByteArray? = null
            while (settled == null && frames < FRAME_BUDGET) {
                val current = slot.capture(CaptureRequest())
                frames++
                if (frames >= MIN_FRAMES && current.bytes.contentEquals(previous.bytes)) settled = current.bytes
                previous = current
            }
            settled?.also {
                Files.createDirectories(out.toAbsolutePath().parent)
                Files.write(out, it)
                println(
                    "[hollow.shot] ${out.toAbsolutePath()} ${previous.width}x${previous.height} after $frames frames, " +
                        "${started.host.world.numEntities} entities",
                )
            }
        } finally {
            started.close()
        }
        if (written == null) {
            System.err.println("[hollow.shot] the frame did not settle in $FRAME_BUDGET frames")
            exitProcess(1)
        }
    }

    /** Frames to draw before a repeat counts as settled: past the first texture upload. */
    private const val MIN_FRAMES = 10

    private const val FRAME_BUDGET = 600
}
