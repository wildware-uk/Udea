package dev.wildware.udea.render.gl

import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.module.UdeaGameDef
import dev.wildware.udea.generated.CoreUdeaRegistry
import dev.wildware.udea.render.OffscreenTarget
import dev.wildware.udea.render.RenderPhase
import dev.wildware.udea.render.RenderRegistry
import dev.wildware.udea.render.RenderSystem
import dev.wildware.udea.render.backend.KoolBackend
import dev.wildware.udea.render.backend.WindowConfig
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * A second `create` on one backend is refused before it allocates anything.
 *
 * Split out of `OffscreenBackendTest` (see its KDoc): this claim needs a `KoolBackend` that
 * belongs to no other test in the same JVM, because Kool allows exactly one `KoolContext` per
 * process for the life of the process.
 *
 * The bug this pins: `create` allocated the batches and the pass and a whole pipeline inside
 * `kool.submit { ... }` and only then called `built.compareAndSet(null, pipeline)`. A second call
 * therefore made a second pipeline and leaked it, and ran `registry.build` again — which
 * re-invokes `onBind(world, ctx)` on the *same retained system instances*, replacing the live
 * pipeline's bound Families — on its way to throwing. An earlier version of this test appeared to
 * cover this and did not: it called `create` after `close()`, so it failed at
 * `KoolThread.submit`'s `check(isRunning)` and the guard itself was never reached.
 */
class OffscreenBackendSecondCreateTest {

    @Test
    fun `a second create is refused before it allocates anything`() {
        GlAvailability.require()
        val builds = AtomicInteger()
        val registry = RenderRegistry()
        registry.register(RenderPhase.World, { CountingBuildSystem(builds) })
        val backend = KoolBackend.start(
            RenderMode.Offscreen,
            WindowConfig(
                title = "udea-test",
                windowWidth = 320,
                windowHeight = 240,
                renderWidth = RENDER_WIDTH,
                renderHeight = RENDER_HEIGHT,
            ),
            registry,
        )
        try {
            val first = backend.create(definition().build())
            assertEquals(1, builds.get(), "the first create did not build the pipeline")

            assertFailsWith<IllegalStateException> { backend.create(definition().build()) }

            // The factory runs *after* the pass and the batches inside the same submitted
            // block, so one invocation is one pipeline: had the refused call reached the
            // submit, this would read 2 and two render objects would have leaked.
            assertEquals(1, builds.get(), "a second pipeline was built and then thrown away")
            assertSame<Any?>(
                first.presentation,
                backend.pipeline,
                "the live pipeline was replaced by the refused call",
            )
        } finally {
            backend.close()
        }
    }

    private fun definition() = UdeaGameDef(registry = CoreUdeaRegistry, modules = emptyList())

    /** Counts how many times the registry built it, which is how many times `create` allocated. */
    private class CountingBuildSystem(builds: AtomicInteger) : RenderSystem {
        init {
            builds.incrementAndGet()
        }

        override fun render(target: OffscreenTarget, alpha: Float): Unit = Unit
    }

    private companion object {
        const val RENDER_WIDTH = 128
        const val RENDER_HEIGHT = 64
    }
}
