package dev.wildware.udea.replay.equality

import dev.wildware.udea.replay.InputSample
import dev.wildware.udea.replay.PeerId
import dev.wildware.udea.replay.equality.fixture.DriftFixture
import dev.wildware.udea.replay.equality.fixture.DriftFixtureRecorder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The ceiling on how long a fixture of this world can be, which nothing named until issue #165.
 *
 * `InputSample.setPressCount` takes `0..255`, because a press count is one byte on the wire. The
 * fixture pilot kept a lifetime total instead of a rolling one, which fitted only by accident:
 * the 3600-tick fixture presses roughly `3600 / PULSE_ODDS` = 150 times and never reached the
 * limit. Recording the 36000-tick nightly fixture threw
 * `a press count must be in 0..255, was 256 for action 'drift/pulse'` partway through - so the
 * length of every fixture this world could ever have was capped by a rule in another class.
 *
 * This is the pin. It records past the wrap rather than reading the checked-in bytes, because
 * the checked-in bytes are the *output* of the thing under test: a fixture recorded before the
 * fix would still be sitting there, in range, saying nothing about whether a longer one can be
 * made now.
 */
class DriftPilotTest {

    @Test
    fun `the pilot's press counter rolls over, so a fixture is not capped at 255 presses`() {
        // Comfortably past the wrap - at one press in `PULSE_ODDS` draws, 256 presses take about
        // `256 * PULSE_ODDS` = 6144 ticks - and comfortably short of the 36000 the nightly
        // fixture holds, because this runs on every push.
        val recording = DriftFixtureRecorder.record(TICKS_PAST_THE_WRAP)

        val sample = InputSample(DriftFixture.SCHEMA)
        val counts = IntArray(recording.tickCount)
        for (index in 0 until recording.tickCount) {
            recording.sampleInto(recording.firstTick + index.toLong(), PeerId(0), sample)
            counts[index] = sample.pressCount(DriftFixture.ACTION_PULSE)
        }

        assertEquals(TICKS_PAST_THE_WRAP, recording.tickCount)
        assertTrue(
            counts.all { it in 0..DriftFixtureRecorder.PULSE_COUNT_MASK },
            "a press count outside 0..255 cannot be recorded at all, so this recording could " +
                "not exist; the assertion is here so a reader does not have to know that",
        )
        assertTrue(
            counts.asSequence().zipWithNext().any { (before, after) -> after < before },
            "the counter never rolled over in $TICKS_PAST_THE_WRAP tick(s), so this test ran " +
                "entirely below the limit it exists to cross and would pass against the lifetime " +
                "counter that could not record the nightly fixture at all",
        )
    }

    private companion object {
        const val TICKS_PAST_THE_WRAP: Int = 7_000
    }
}
