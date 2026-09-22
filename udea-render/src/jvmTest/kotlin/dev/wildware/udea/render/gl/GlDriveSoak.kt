package dev.wildware.udea.render.gl

import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.module.UdeaGameDef
import dev.wildware.udea.generated.CoreUdeaRegistry
import dev.wildware.udea.render.OffscreenTarget
import dev.wildware.udea.render.RenderPhase
import dev.wildware.udea.render.RenderRegistry
import dev.wildware.udea.render.RenderSystem
import dev.wildware.udea.render.backend.KoolBackend
import dev.wildware.udea.render.backend.WindowConfig
import dev.wildware.udea.render.capture.CaptureRequest
import dev.wildware.udea.render.capture.capture
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A game left running through [KoolBackend.drive] for a quarter of a minute of real frames, and
 * still drawing at the end of it.
 *
 * ## Why a soak, and why this length
 *
 * Issue #275 shipped a render hook whose only test drew two frames and took one capture. It
 * passed, and every game that used the hook shut its own window about **eight seconds** in, exit
 * 0, nothing logged. A test measured in frames could not see it; only a test measured in seconds
 * could. [SOAK_SECONDS] is twice that eight, so a pipeline that ends itself on any timer of that
 * order is inside the window this test watches.
 *
 * This branch changes both public `drive` overloads - the frame callback is wrapped in a counter,
 * and the environment is read for a launch probe that deliberately *closes the window* - so it is
 * exactly the shape #275 was: a change to the call every launcher makes, whose failure is a
 * pipeline that quietly stops. Here the environment names no probe, which is every ordinary run,
 * and the window must therefore still be open and still drawing after [SOAK_SECONDS].
 *
 * A failure *inside* a frame is loud rather than quiet, and that half is pinned elsewhere:
 * `OffscreenBackendExplodingCaptureTest` (a renderer that throws releases every waiting capture
 * with its cause) and `KoolThreadShutdownTest` (what the thread says about itself as it dies).
 *
 * @param mode the render mode to soak. Called once per mode, each from a class of its own,
 *   because Kool allows one context per JVM and `udeaGlTest` forks one JVM per class.
 */
internal fun soakDrive(mode: RenderMode) {
    GlAvailability.require()
    val drawn = AtomicLong()
    val registry = RenderRegistry()
    registry.register(RenderPhase.World, { SoakRenderSystem(drawn) })
    val backend = KoolBackend.start(
        mode,
        WindowConfig(
            title = "udea-soak-$mode",
            windowWidth = 320,
            windowHeight = 240,
            renderWidth = SOAK_RENDER_WIDTH,
            renderHeight = SOAK_RENDER_HEIGHT,
        ),
        registry,
    )
    try {
        val host = GameHost(mode, UdeaGameDef(registry = CoreUdeaRegistry, modules = emptyList()), backend)

        // The call a launcher makes. `MobaLaunch` and `HollowShot` both reach the backend this way.
        backend.drive(host)

        var previous = 0L
        for (second in 1..SOAK_SECONDS) {
            Thread.sleep(1000)
            val now = drawn.get()
            assertTrue(
                backend.renderLoopRunning,
                "the render loop stopped ${second}s into the soak, after $now frames, with no probe asked for",
            )
            assertTrue(
                now > previous,
                "no frame was drawn during second $second of the soak: the loop is alive but has stopped drawing (total $now)",
            )
            previous = now
        }

        // Still drawing into a capturable pass at the end, not merely still ticking over: this is
        // the half #275's Offscreen capture failure would have been caught by.
        val slot = checkNotNull(backend.pipeline?.capture) { "the pipeline lost its capture during the soak" }
        val shot = slot.capture(CaptureRequest())
        assertEquals(SOAK_RENDER_WIDTH, shot.width)
        assertEquals(SOAK_RENDER_HEIGHT, shot.height)
        assertTrue(
            drawn.get() >= SOAK_SECONDS,
            "only ${drawn.get()} frames in ${SOAK_SECONDS}s: fewer than one a second is not a game running",
        )
    } finally {
        backend.close()
    }
}

/** Real seconds each soak runs for: twice the eight #275's games survived. */
internal const val SOAK_SECONDS: Int = 16

internal const val SOAK_RENDER_WIDTH: Int = 128
internal const val SOAK_RENDER_HEIGHT: Int = 64

/** Counts the frames the pipeline actually drew, which is what "still drawing" is read from. */
private class SoakRenderSystem(private val frames: AtomicLong) : RenderSystem {
    override fun render(target: OffscreenTarget, alpha: Float) {
        frames.incrementAndGet()
    }
}
