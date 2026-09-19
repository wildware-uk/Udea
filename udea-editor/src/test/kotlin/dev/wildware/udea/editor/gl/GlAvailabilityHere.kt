package dev.wildware.udea.editor.gl

import org.junit.jupiter.api.Assumptions

/**
 * Whether this JVM can create a Kool context, read from `$DISPLAY`. "Here" means *this module*: a copy
 * of `udea-render`'s `GlAvailability`, as `udea-agent-host` keeps one, because that one is `internal`
 * to its module's tests.
 *
 * It reads the environment rather than creating a throwaway context because Kool allows one context
 * per JVM for the life of the JVM: a probe that made one would spend the test's allowance on itself.
 * `-Dudea.render.requireGl=true` turns the skip into a failure, so a job with a display cannot have a
 * backend that stopped booting hide behind a skip.
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
