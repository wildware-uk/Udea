package dev.wildware.udea.render.gl

import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.render.RenderRegistry
import dev.wildware.udea.render.backend.Lwjgl3Backend
import dev.wildware.udea.render.backend.WindowConfig
import org.junit.jupiter.api.Assumptions

/**
 * Whether this JVM can actually create an LWJGL3 context, decided once and cached.
 *
 * ## Why a skip is allowed here, and only here
 *
 * A test that silently skips is normally worse than no test: it is green and it checked
 * nothing. The exception is a test whose subject *is* the display — an `Offscreen` backend
 * boots a real hidden window with a real driver behind it, and a container, a headless CI
 * agent or a machine with no GPU cannot provide one. On such a box the honest answer is "this
 * cannot be checked here", not a red build that says the code is broken.
 *
 * So it is a skip **with a stated reason**, and it can be turned into a hard failure with
 * `-Dudea.render.requireGl=true` — which is what a CI job with a display should set, so that a
 * GL backend which quietly stops booting cannot hide behind a skip forever.
 */
internal object GlAvailability {

    /** `-Dudea.render.requireGl=true` turns an unavailable context into a failure. */
    const val REQUIRE_PROPERTY: String = "udea.render.requireGl"

    /** Why the context could not be created, or `null` when one can be. */
    val failure: String? by lazy { probe() }

    /** Skips the calling test when there is no context, unless [REQUIRE_PROPERTY] is set. */
    fun require() {
        val reason = failure ?: return
        check(System.getProperty(REQUIRE_PROPERTY) != "true") {
            "$REQUIRE_PROPERTY=true but no GL context could be created: $reason"
        }
        Assumptions.abort<Unit>("no LWJGL3 context on this machine: $reason")
    }

    private fun probe(): String? = try {
        // A 1x1 hidden window: the cheapest thing that still exercises GLFW, the driver and the
        // gdx natives, which are the three things that fail on a machine with no display.
        Lwjgl3Backend.start(
            RenderMode.Offscreen,
            WindowConfig(title = "udea-gl-probe", windowWidth = 1, windowHeight = 1),
            RenderRegistry(),
        ).use { null }
    } catch (failure: Throwable) {
        "${failure::class.java.name}: ${failure.message}"
    }
}
