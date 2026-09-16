package dev.wildware.udea.annotations

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * The half of the annotation vocabulary that needs no reflection, so it runs on every target
 * this module is built for rather than only on the JVM (issue #201).
 *
 * `AnnotationVocabularyTest` in `jvmTest` pins what only the JVM can read back - retention,
 * `@Target` and parameter defaults through `java.lang.Class` - and scans the source tree. What is
 * here is what each platform's compiler has to agree on: the authority enums' constants, in order,
 * and that every annotation is accepted at the site the spec gives it.
 */
class AuthorityVocabularyTest {

    @Test
    fun `the authority vocabulary frozen by spec 5 has exactly these constants`() {
        assertContentEquals(
            arrayOf(Authority.Server, Authority.OwnerPredicted, Authority.OwnerWritable),
            Authority.entries.toTypedArray(),
        )
        assertContentEquals(
            arrayOf(Lifetime.OnCreate, Lifetime.Always),
            Lifetime.entries.toTypedArray(),
        )
        assertContentEquals(
            arrayOf(Visibility.All, Visibility.OwnerOnly),
            Visibility.entries.toTypedArray(),
        )
    }

    /**
     * Applying every annotation at a legal site. This does not run: it compiling at all is
     * the assertion, because a wrong `@Target` makes the fixture below fail to compile - on
     * whichever target's compiler is compiling it.
     */
    @Suppress("unused")
    @Replicated
    private class TargetFixture {
        @Net(authority = Authority.OwnerPredicted, lifetime = Lifetime.OnCreate, visibility = Visibility.OwnerOnly)
        @Q(bits = 12, min = -1f, max = 1f)
        var rotation: Float = 0f

        @Sim
        var lastGroundedTick: Long = 0L

        @AgentState(name = "match_state")
        val phase: String = "warmup"

        @AgentTool(name = "nudge", description = "fixture")
        fun nudge(@Arg(description = "how far", required = false) distance: Float): Float = distance
    }

    @Test
    fun `the target fixture applies every annotation at a legal site`() {
        // The fixture above proves targeting at compile time; this keeps it reachable so it
        // is compiled and cannot be dropped as dead code by a future cleanup.
        assertEquals(1.5f, TargetFixture().nudge(1.5f))
        assertEquals("warmup", TargetFixture().phase)
    }
}
