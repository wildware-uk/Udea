package dev.wildware.udea.replay.equality

import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.identity.NetId
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The acceptance criterion that no failure path can print a bare hash mismatch.
 *
 * ## What "bare" means, and why it is a real risk rather than a style rule
 *
 * `WorldHasher.hash(WorldSnapshot)` folds four things that are not fields of anything: the clock,
 * the random streams, the id allocator, and the roster's own shape. A gate that compared only
 * hashes, or that compared only *component field* values, would meet a world that diverged in one
 * of those four and have nothing to say beyond two 64-bit numbers - which is exactly what spec 7
 * asks this issue to stop happening.
 *
 * Each of the four gets a test here, by hand, because none of them can be reached by simulating:
 * an extra random draw moves a field on the very next tick, so a run that differs *only* in its
 * random state does not exist in nature. It has to be assembled.
 *
 * The fifth test is the one path that could still print a bare mismatch - a hash that disagrees
 * with its own cells - and it asserts that this is refused as a corrupt input rather than rendered
 * as a divergence.
 */
class DivergenceReportFormatTest {

    private val lead = NetId.of(index = 0, generation = 0)

    /** A three-cell world: a roster of one, a clock, and one random word. */
    private fun world(
        label: String,
        x: Float = 1f,
        tick: Long = 7L,
        rngWord: Long = 0xABCDL,
        nextFresh: Long = 3L,
        presence: Long = 0b1L,
    ) = DigestBuilder(label)
        .tick(
            DigestBuilder.rowCount(1),
            DigestBuilder.rosterNetId(lead),
            DigestBuilder.presence(lead, 0, presence),
            DigestBuilder.componentType(),
            DigestBuilder.componentSlots(1),
            DigestBuilder.float(lead, 0, x),
            DigestBuilder.float(lead, 1, 2f),
            DigestBuilder.clock(tick),
            DigestBuilder.rng(0, rngWord),
            DigestBuilder.handle(ReplayDigestCells.HANDLE_NEXT_FRESH, nextFresh),
        )
        .build()

    @Test
    fun `two runs that differ only in a random stream word name the word, not a hash`() {
        val result = ReplayEquality.replayEquals(world("A"), world("B", rngWord = 0xABCEL))

        assertFalse(result.isEqual, "the two runs hold different random state")
        assertEquals(1, result.divergingCells)
        val rendered = result.describe()
        assertContains(rendered, "<rng>.word[0]")
        assertContains(rendered, "43981")
        assertContains(rendered, "43982")
        assertNamesSomething(rendered)
    }

    @Test
    fun `two runs that differ only in the id allocator name the allocator field`() {
        val result = ReplayEquality.replayEquals(world("A"), world("B", nextFresh = 4L))

        assertFalse(result.isEqual)
        val rendered = result.describe()
        assertContains(rendered, "<handles>.nextFresh")
        assertNamesSomething(rendered)
    }

    @Test
    fun `two runs that differ only in the clock name the clock`() {
        val result = ReplayEquality.replayEquals(world("A"), world("B", tick = 8L))

        assertFalse(result.isEqual)
        val rendered = result.describe()
        assertContains(rendered, "<clock>.tick")
        assertNamesSomething(rendered)
    }

    @Test
    fun `two runs that differ only in which entity carries a component name the presence word`() {
        val result = ReplayEquality.replayEquals(world("A"), world("B", presence = 0b0L))

        assertFalse(result.isEqual)
        val rendered = result.describe()
        assertContains(rendered, "<roster>.presence[0]")
        assertNamesSomething(rendered)
    }

    @Test
    fun `a component field divergence names the entity, the component FQN and the field`() {
        val result = ReplayEquality.replayEquals(world("A"), world("B", x = 1.0000001f))

        assertFalse(result.isEqual)
        val rendered = result.describe()
        assertContains(rendered, "NetId(")
        assertContains(rendered, "dev.wildware.udea.replay.equality.fixture.Drifter.x")
        assertNamesSomething(rendered)
    }

    @Test
    fun `a stream whose hash disagrees with its own cells is refused, not reported as a divergence`() {
        // The one shape that could still produce a bare hash mismatch, and no ReplayDigestWriter
        // can write it: the writer refolds its cells and refuses the tick. So it is forged.
        val honest = world("A")
        val forged = DigestBuilder("B")
            .corruptTick(
                hash = 999L,
                DigestBuilder.rowCount(1),
                DigestBuilder.rosterNetId(lead),
                DigestBuilder.presence(lead, 0, 0b1L),
                DigestBuilder.componentType(),
                DigestBuilder.componentSlots(1),
                DigestBuilder.float(lead, 0, 1f),
                DigestBuilder.float(lead, 1, 2f),
                DigestBuilder.clock(7L),
                DigestBuilder.rng(0, 0xABCDL),
                DigestBuilder.handle(ReplayDigestCells.HANDLE_NEXT_FRESH, 3L),
            )
            .build()

        val failure = assertFailsWith<IllegalStateException> {
            ReplayEquality.replayEquals(honest, forged)
        }
        val message = failure.message.orEmpty()
        assertContains(message, "every cell matches")
        assertContains(message, "corrupt")
    }

    @Test
    fun `a result that carries a tick but no cell refuses to render rather than printing a hash`() {
        // The format guarantee stated directly against the renderer, with no digest in the way:
        // if a future caller ever assembles a non-equal verdict with an empty cell list, it must
        // fail loudly instead of emitting "hash mismatch at tick N" and nothing else.
        val bare = ReplayEqualityResult(
            expected = world("A").header,
            actual = world("B").header,
            ticksCompared = 1,
            tick = Tick(7L),
            expectedHash = 1L,
            actualHash = 2L,
            divergingCells = 0,
            divergences = emptyList(),
        )

        val failure = assertFailsWith<IllegalStateException> { bare.describe() }
        assertContains(failure.message.orEmpty(), "named no cell")
    }

    @Test
    fun `the control - two identical runs render an equality, and nothing that reads as a failure`() {
        // A fence that fires on everything is as useless as one that fires on nothing. Without
        // this, every assertion above would still pass if `describe()` printed the word FAILED
        // unconditionally.
        val result = ReplayEquality.replayEquals(world("A"), world("B"))

        assertTrue(result.isEqual)
        val rendered = result.describe()
        assertContains(rendered, "replay equality holds")
        assertFalse(rendered.contains("FAILED"), "an equal run must not read as a failure:\n$rendered")
        assertFalse(rendered.contains("differing cell"), "there is nothing differing to report")
    }

    /**
     * Every non-equal report names at least one cell and prints both sides of it.
     *
     * Deliberately not "contains the word field": the point is that the rendering has a labelled
     * cell with an A and a B value under it, which is what makes it actionable.
     */
    private fun assertNamesSomething(rendered: String) {
        assertContains(rendered, "differing cell(s):")
        assertContains(rendered, "\n      A = ")
        assertContains(rendered, "\n      B = ")
        val hashLine = rendered.lines().single { it.trim().startsWith("world hash:") }
        val afterHash = rendered.substringAfter(hashLine)
        assertTrue(
            afterHash.contains("differing cell(s):"),
            "the hash line must never be the last thing a failure says:\n$rendered",
        )
    }
}
