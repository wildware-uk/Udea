package dev.wildware.udea.agent.host.gl

import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.render.RenderRegistry
import dev.wildware.udea.render.backend.Lwjgl3Backend
import dev.wildware.udea.render.backend.WindowConfig
import org.junit.jupiter.api.Assumptions

/**
 * Whether this JVM can create an LWJGL3 context, decided once and cached. "Here" means *this
 * module*: `udea-render` has its own, and the two cannot be shared.
 *
 * A copy of `udea-render`'s `GlAvailability` and not a shared fixture, because that one is
 * `internal` to its module's test source set and a test fixture published across modules for four
 * lines is a dependency that will be paid for later. The reasoning is the same and is worth
 * restating rather than cross-referencing:
 *
 * A test that silently skips is normally worse than no test — it is green and it checked nothing.
 * The exception is a test whose subject *is* the display. An `Offscreen` host boots a real hidden
 * window with a real driver behind it, and a container or a CI agent with no GPU cannot provide
 * one; on such a box "this cannot be checked here" is the honest answer, not a red build claiming
 * the code is broken.
 *
 * `-Dudea.render.requireGl=true` turns the skip into a failure, which is what a CI job *with* a
 * display should set so that a render toolset which quietly stops working cannot hide behind a
 * skip forever. `udeaAgentGlTest` passes the property through.
 */
internal object GlAvailabilityHere {

    /** `-Dudea.render.requireGl=true` turns an unavailable context into a failure. */
    const val REQUIRE_PROPERTY: String = "udea.render.requireGl"

    /** Why the context could not be created, or `null` when one can be. */
    private val failure: String? by lazy { probe() }

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
            WindowConfig(title = "udea-agent-gl-probe", windowWidth = 1, windowHeight = 1),
            RenderRegistry(),
        ).use { null }
    } catch (failure: Throwable) {
        "${failure::class.java.name}: ${failure.message}"
    }
}
