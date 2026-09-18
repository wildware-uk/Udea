package dev.wildware.udea.agent.host.gl

import org.junit.jupiter.api.Assumptions

/**
 * Whether this JVM can create a Kool context, decided once and cached. "Here" means *this
 * module*: `udea-render` has its own, and the two cannot be shared.
 *
 * A copy of `udea-render`'s `GlAvailability` and not a shared fixture, because that one is
 * `internal` to its module's test source set and a test fixture published across modules for four
 * lines is a dependency that will be paid for later. The reasoning is the same and is worth
 * restating rather than cross-referencing:
 *
 * This used to create-and-close a throwaway `KoolBackend` to answer the question. Kool 0.19.0
 * allows **one `KoolContext` per JVM for the life of the JVM** (`KoolThread`'s KDoc) and refuses
 * a second `createContext` even after the first has closed - so the probe was spending the
 * process's one allowance on itself, and every real test's own context creation failed right
 * after with `GlContextException`, reported as "no context" when the true cause was "already
 * spent". It now reads `$DISPLAY`, which is the same signal `udea.render.requireGl` already
 * reasons about elsewhere: no X server means no context regardless of Kool's limit. A test that
 * goes on to create a context still fails loudly, under its own name, if the display turns out
 * not to work.
 *
 * `-Dudea.render.requireGl=true` turns the skip into a failure, which is what a CI job *with* a
 * display should set so that a render toolset which quietly stops working cannot hide behind a
 * skip forever. `udeaAgentGlTest` passes the property through.
 */
internal object GlAvailabilityHere {

    /** `-Dudea.render.requireGl=true` turns an unavailable display into a failure. */
    const val REQUIRE_PROPERTY: String = "udea.render.requireGl"

    /** Why there is no display, or `null` when one is advertised. */
    private val failure: String? by lazy {
        if (System.getenv("DISPLAY").orEmpty().isNotBlank()) null else "no DISPLAY is set"
    }

    /** Skips the calling test when there is no display, unless [REQUIRE_PROPERTY] is set. */
    fun require() {
        val reason = failure ?: return
        check(System.getProperty(REQUIRE_PROPERTY) != "true") {
            "$REQUIRE_PROPERTY=true but $reason"
        }
        Assumptions.abort<Unit>("no Kool context on this machine: $reason")
    }
}
