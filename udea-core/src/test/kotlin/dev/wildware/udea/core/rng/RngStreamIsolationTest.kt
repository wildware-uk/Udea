package dev.wildware.udea.core.rng

import dev.wildware.udea.core.RngStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The property named streams exist for: **adding a consumer must not perturb an existing
 * sequence.**
 *
 * With one shared generator every draw is positional — the third combat roll of the match is
 * the third value of the sequence — so adding a loot roll in Phase 5 consumes a value, shifts
 * every combat roll after it, breaks every recorded replay fixture, and produces a diff that
 * blames the combat code. These tests assert the property from both directions: drawing from
 * a sibling stream cannot shift `Combat`, and *appending a stream to the enum* cannot either.
 */
class RngStreamIsolationTest {

    @Test
    fun `drawing from other streams does not shift Combat`() {
        val undisturbed = DefaultRngService(ROOT_SEED)
        val combatAlone = LongArray(1000) { undisturbed.stream(RngStream.Combat).nextLong() }

        val busy = DefaultRngService(ROOT_SEED)
        val combatAmongOthers = LongArray(1000) { index ->
            // Every other stream is being hammered between the combat draws, at a rate that
            // is itself uneven — exactly the shape of a gameplay change landing mid-project.
            repeat(index % 5) {
                busy.stream(RngStream.Loot).nextLong()
                busy.stream(RngStream.AI).nextInt(10)
                busy.stream(RngStream.Spawn).nextFloat()
                busy.stream(RngStream.Wave).nextBoolean()
            }
            busy.stream(RngStream.Combat).nextLong()
        }

        assertEquals(combatAlone.toList(), combatAmongOthers.toList())
    }

    @Test
    fun `appending a stream to the enum cannot perturb an existing one`() {
        // A test cannot add an enum constant, so the property is asserted structurally on the
        // thing that would change: a stream's seed is a pure function of the root seed and
        // its own ordinal, so ordinals 0..n are unchanged by the existence of ordinal n + 1.
        val current = RngStream.entries.size
        val seedsToday = LongArray(current) { DefaultRngService.streamSeed(ROOT_SEED, it) }
        val seedsAfterThreeMoreStreams = LongArray(current + 3) {
            DefaultRngService.streamSeed(ROOT_SEED, it)
        }

        assertEquals(
            seedsToday.toList(),
            seedsAfterThreeMoreStreams.take(current),
            "an appended stream must not change the seed of any existing one",
        )

        // And the derived state, not just the seed, is unchanged — which is what the draws
        // actually depend on.
        val combatState = LongArray(SimRandom.STATE_WORDS)
        DefaultRngService(ROOT_SEED).stream(RngStream.Combat).save(combatState)
        val standaloneCombat = LongArray(SimRandom.STATE_WORDS)
        SimRandom(DefaultRngService.streamSeed(ROOT_SEED, RngStream.Combat.ordinal))
            .save(standaloneCombat)

        assertEquals(combatState.toList(), standaloneCombat.toList())
    }

    @Test
    fun `two streams of one service do not share a sequence`() {
        val service = DefaultRngService(ROOT_SEED)

        val perStream = RngStream.entries.associateWith { stream ->
            LongArray(200) { service.stream(stream).nextLong() }.toList()
        }

        for (a in RngStream.entries) {
            for (b in RngStream.entries) {
                if (a.ordinal >= b.ordinal) continue
                assertNotEquals(perStream.getValue(a), perStream.getValue(b), "$a and $b agree")
                assertTrue(
                    perStream.getValue(a).intersect(perStream.getValue(b).toSet()).isEmpty(),
                    "$a and $b overlap, so their seeds share a SplitMix64 window",
                )
            }
        }
    }

    @Test
    fun `save then draw then restore then draw yields identical values`() {
        val service = DefaultRngService(ROOT_SEED)

        // Get every stream off its starting state first, so the restore has real work to do.
        repeat(50) { RngStream.entries.forEach { service.stream(it).nextLong() } }

        val captured = service.saveState()
        val expected = drawAcrossEveryStream(service, 100)

        val diverged = drawAcrossEveryStream(service, 100)
        assertNotEquals(expected, diverged, "the generator must actually have moved on")

        service.restore(captured)
        assertEquals(expected, drawAcrossEveryStream(service, 100))
    }

    @Test
    fun `a restored service continues a captured one exactly`() {
        val captureFrom = DefaultRngService(ROOT_SEED)
        repeat(313) { captureFrom.stream(RngStream.Loot).nextInt(1000) }

        val restored = DefaultRngService(ROOT_SEED)
        restored.restore(captureFrom.saveState())

        assertEquals(
            LongArray(100) { captureFrom.stream(RngStream.Loot).nextLong() }.toList(),
            LongArray(100) { restored.stream(RngStream.Loot).nextLong() }.toList(),
        )
    }

    @Test
    fun `two services with the same root seed replay and two with different seeds diverge`() {
        val a = DefaultRngService(ROOT_SEED)
        val b = DefaultRngService(ROOT_SEED)
        val c = DefaultRngService(ROOT_SEED + 1)

        val fromA = drawAcrossEveryStream(a, 100)
        assertEquals(fromA, drawAcrossEveryStream(b, 100))
        assertNotEquals(fromA, drawAcrossEveryStream(c, 100))
    }

    @Test
    fun `a stream instance is stable for the life of the service`() {
        val service = DefaultRngService(ROOT_SEED)

        // Stability is what makes `stream()` free to call in a hot loop: it is an array read,
        // not a construction, so a system may take it per-draw rather than caching it.
        assertSame(service.stream(RngStream.Combat), service.stream(RngStream.Combat))
        assertSame(service.stream(RngStream.Wave), service.stream(RngStream.Wave))
    }

    @Test
    fun `the RngService interface draws from the same streams as stream()`() {
        val viaInterface = DefaultRngService(ROOT_SEED)
        val viaStream = DefaultRngService(ROOT_SEED)

        assertEquals(ROOT_SEED, viaInterface.seed)
        repeat(100) {
            assertEquals(
                viaStream.stream(RngStream.Spawn).nextLong(),
                viaInterface.nextLong(RngStream.Spawn),
            )
        }
        assertEquals(
            viaStream.stream(RngStream.AI).nextFloat(),
            viaInterface.nextFloat(RngStream.AI),
        )
        assertEquals(
            viaStream.stream(RngStream.AI).nextInt(97),
            viaInterface.nextInt(RngStream.AI, 97),
        )
        assertEquals(
            viaStream.stream(RngStream.AI).nextBoolean(),
            viaInterface.nextBoolean(RngStream.AI),
        )
    }

    /** One draw from each stream, [rounds] times, flattened — a fingerprint of the service. */
    private fun drawAcrossEveryStream(service: DefaultRngService, rounds: Int): List<Long> {
        val out = ArrayList<Long>(rounds * RngStream.entries.size)
        repeat(rounds) {
            for (stream in RngStream.entries) out += service.stream(stream).nextLong()
        }
        return out
    }

    private companion object {
        const val ROOT_SEED = 2026L
    }
}
