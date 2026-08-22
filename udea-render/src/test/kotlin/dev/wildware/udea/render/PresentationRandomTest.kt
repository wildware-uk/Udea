package dev.wildware.udea.render

import dev.wildware.udea.core.GameContext
import dev.wildware.udea.core.RngService
import dev.wildware.udea.core.SimSystem
import dev.wildware.udea.core.fixtures.testGameContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Presentation randomness is a *type simulation cannot use*, not a rule about which
 * generator to call (spec 5, "Randomness").
 *
 * The half of that guarantee `udea-core` owns -- "this name does not resolve in the kernel"
 * -- is asserted there, by `PresentationRandomIsolationTest`. This is the half that lives
 * with the type: it is not an `RngService`, it is not injectable into a `SimSystem`, and it
 * is seeded from the wall clock so that nobody is tempted to rely on it reproducing.
 */
class PresentationRandomTest {

    @Test
    fun `PresentationRandom is not an RngService and so cannot stand in for one`() {
        // The interesting failure this prevents: `ctx.rng = PresentationRandom()` typechecking.
        assertTrue(
            !RngService::class.java.isAssignableFrom(PresentationRandom::class.java),
            "PresentationRandom implements RngService; simulation could then draw from the wall clock",
        )
    }

    @Test
    fun `a SimSystem cannot reach one through the only injectable`() {
        val ctx: GameContext = testGameContext(seed = 1L)

        // GameContext is the sole Fleks injectable, so this is the entire surface a SimSystem
        // has. Nothing on it is, or produces, a PresentationRandom.
        assertEquals(
            emptyList(),
            ctx.serviceKeys.filter { PresentationRandom::class.java.isAssignableFrom(it.type.java) },
        )
        assertTrue(
            GameContext::class.java.declaredFields.none {
                PresentationRandom::class.java.isAssignableFrom(it.type)
            },
        )
        // And the module arrow is the reason that cannot be fixed by adding one: SimSystem is
        // declared upstream of this module, so it cannot name this type at all.
        assertEquals("dev.wildware.udea.core", SimSystem::class.java.packageName)
        assertEquals("dev.wildware.udea.render", PresentationRandom::class.java.packageName)
    }

    @Test
    fun `two wall-seeded generators produce different sequences`() {
        val first = List(8) { PresentationRandom().nextLongish() }
        val second = List(8) { PresentationRandom().nextLongish() }

        // Not a determinism test in disguise: the claim is that presentation randomness is
        // *not* reproducible, which is what stops anyone building on it.
        assertNotEquals(first, second)
    }

    @Test
    fun `an explicitly seeded generator repeats exactly`() {
        val expected = List(64) { PresentationRandom.seeded(42L).nextInt(1_000) }
        val actual = List(64) { PresentationRandom.seeded(42L).nextInt(1_000) }

        assertEquals(expected, actual)
    }

    @Test
    fun `nextFloat stays inside the unit interval`() {
        val random = PresentationRandom.seeded(7L)

        repeat(10_000) {
            val value = random.nextFloat()
            assertTrue(value >= 0f && value < 1f, "nextFloat returned $value")
        }
    }

    @Test
    fun `a bounded range covers its bounds and nothing outside them`() {
        val random = PresentationRandom.seeded(9L)
        var sawLow = false
        var sawHigh = false

        repeat(10_000) {
            val value = random.nextInt(3, 6)
            assertTrue(value in 3..5, "nextInt(3, 6) returned $value")
            if (value == 3) sawLow = true
            if (value == 5) sawHigh = true
        }

        assertTrue(sawLow && sawHigh, "the range was not fully covered")
    }

    @Test
    fun `a float range stays inside its bounds`() {
        val random = PresentationRandom.seeded(11L)

        repeat(10_000) {
            val value = random.nextFloat(-2f, 3f)
            assertTrue(value >= -2f && value < 3f, "nextFloat(-2, 3) returned $value")
        }
    }

    @Test
    fun `an impossible range fails at the call rather than returning nonsense`() {
        val random = PresentationRandom.seeded(1L)

        assertFailsWith<IllegalArgumentException> { random.nextInt(0) }
        assertFailsWith<IllegalArgumentException> { random.nextInt(-1) }
        assertFailsWith<IllegalArgumentException> { random.nextInt(5, 5) }
        assertFailsWith<IllegalArgumentException> { random.nextFloat(1f, 1f) }
    }

    /** A draw wide enough that two wall-seeded generators colliding is not a flake. */
    private fun PresentationRandom.nextLongish(): Int = nextInt(Int.MAX_VALUE)
}
