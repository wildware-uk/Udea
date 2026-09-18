package dev.wildware.udea.render.gl

import org.junit.jupiter.api.Assumptions

/**
 * Whether this JVM can actually create a Kool context, decided once and cached.
 *
 * ## Why this reads an environment variable rather than actually creating a context
 *
 * It used to: a throwaway 1x1 `KoolBackend` created and immediately closed, on the reasoning
 * that a probe should exercise the exact path it is answering for. That reasoning turned out to
 * be a defect rather than a strength. Kool 0.19.0 allows **one `KoolContext` per JVM for the
 * life of the JVM** and refuses a second `createContext` even after the first has closed (see
 * `KoolThread`'s KDoc) — so a probe that creates and closes one has *spent the process's one
 * allowance on the probe itself*, and the real backend a test goes on to create is the one that
 * fails. Every GL test in this suite called `GlAvailability.require()` as its first line, so
 * every single one of them was failing this way — `udeaGlTest` under xvfb reported
 * `GlContextException` from `KoolSystem`'s own "context already created" guard on every test,
 * which is a different failure from "no display" and was being reported as one.
 *
 * `$DISPLAY` is the signal this file's own build script already reasons about (see the "GL
 * trap" note wherever `udea.render.requireGl` is discussed): a machine with no display has no
 * X server and cannot create a context regardless of Kool's per-process limit, and that is
 * exactly the case this existed to detect. It is weaker than a real probe — a display could be
 * present and still fail to give up a context, for a driver reason this cannot see - but a real
 * probe is not available at this cost, and the tests that actually create a context still fail
 * loudly, under their own name, if the display turns out not to work.
 */
internal object GlAvailability {

    /** `-Dudea.render.requireGl=true` turns an unavailable display into a failure. */
    const val REQUIRE_PROPERTY: String = "udea.render.requireGl"

    /** Why there is no display, or `null` when one is advertised. */
    val failure: String? by lazy {
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
