package dev.wildware.udea.render.gl

import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.module.UdeaGameDef
import dev.wildware.udea.generated.CoreUdeaRegistry
import dev.wildware.udea.render.RenderRegistry
import dev.wildware.udea.render.backend.KoolBackend
import dev.wildware.udea.render.backend.WindowConfig
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * Closing the backend stops the render thread, and it stays stopped.
 *
 * Split out of `OffscreenBackendTest` (see its KDoc): this claim needs a `KoolBackend` that
 * belongs to no other test in the same JVM, because Kool allows exactly one `KoolContext` per
 * process for the life of the process.
 */
class OffscreenBackendShutdownTest {

    @Test
    fun `closing the backend stops the render thread`() {
        GlAvailability.require()
        val backend = KoolBackend.start(
            RenderMode.Offscreen,
            WindowConfig(
                title = "udea-test",
                windowWidth = 320,
                windowHeight = 240,
                renderWidth = 128,
                renderHeight = 64,
            ),
            RenderRegistry(),
        )
        backend.close()

        // `close` does not return until the loop has signalled that it exited or its shutdown
        // budget has run out, so this asks about something that has already happened either
        // way. There is no deadline in this test to lose a race against -- which is the whole
        // of issue #178: an earlier version of this test used to be the only assertion here, and
        // it was reading `Thread.isAlive` through `KoolThread.submit`, so on a loaded runner it
        // saw a live thread running a loop that was over and got a `GlContextException` instead.
        assertFalse(backend.renderLoopRunning, "the render loop was still running after close()")

        // And having exited it stays exited: `awaitExit` returns instead of parking, and a
        // `create` is refused for the stated reason rather than by whatever the queue does next.
        backend.awaitExit()
        assertFailsWith<IllegalStateException> {
            backend.create(UdeaGameDef(registry = CoreUdeaRegistry, modules = emptyList()).build())
        }
    }
}
